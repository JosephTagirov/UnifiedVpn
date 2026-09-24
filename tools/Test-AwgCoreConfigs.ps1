[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$Binary,
    [Parameter(Mandatory = $true)][string]$OutputDirectory
)

$ErrorActionPreference = 'Stop'
$Binary = (Resolve-Path -LiteralPath $Binary).Path
$OutputDirectory = [IO.Path]::GetFullPath($OutputDirectory)
if (Test-Path -LiteralPath $OutputDirectory) { throw 'Use a new synthetic-fixture output directory.' }
New-Item -ItemType Directory -Path $OutputDirectory | Out-Null
$utf8 = [Text.UTF8Encoding]::new($false)
$results = @()

function New-SocksInbound {
    return @{ type = 'socks'; tag = 'local-socks'; listen = '127.0.0.1'; listen_port = 19481
        users = @(@{ username = 'synthetic-user'; password = 'synthetic-password' }) }
}

function New-WireGuardConfig {
    param([hashtable]$Amnezia = @{}, [object]$Keepalive = 25, [switch]$Ipv6, [switch]$PrivateDns)
    $peer = @{ address = '203.0.113.42'; port = 55424; public_key = 'vgKFDTXvNqWd2VsJWBBqqJHN1o420gQisA9067eKFxs='
        allowed_ips = @('0.0.0.0/0'); persistent_keepalive_interval = $Keepalive }
    $endpoint = @{ type = 'wireguard'; tag = 'awg'; address = @('10.8.1.2/32')
        private_key = 'AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA='; peers = @($peer); domain_resolver = 'bootstrap' }
    if ($Amnezia.Count) { $endpoint.amnezia_wg = $Amnezia }
    if ($Ipv6) { $peer.address = '2001:db8::42'; $peer.allowed_ips = @('::/0'); $endpoint.address = @('fd00::2/128') }
    $tunnelDns = @{ type = 'tcp'; tag = 'tunnel-dns'; server = '9.9.9.9'; server_port = 53; detour = 'awg' }
    if ($PrivateDns) { $tunnelDns.server = '10.8.1.1'; $tunnelDns.server_port = 5353 }
    return @{ log = @{ level = 'warn' }; inbounds = @((New-SocksInbound)); endpoints = @($endpoint)
        outbounds = @(@{ type = 'direct'; tag = 'direct' })
        dns = @{ servers = @(@{ type = 'udp'; tag = 'bootstrap'; server = '1.1.1.1' }, $tunnelDns); final = 'tunnel-dns'; strategy = 'ipv4_only' }
        route = @{ final = 'awg'; default_domain_resolver = 'tunnel-dns'; rules = @(
            @{ inbound = 'local-socks'; port = 53; action = 'hijack-dns' }, @{ inbound = 'local-socks'; action = 'sniff' }) } }
}

function Invoke-ConfigCheck {
    param([string]$Name, [hashtable]$Config)
    $path = Join-Path $OutputDirectory ($Name + '.json')
    [IO.File]::WriteAllText($path, ($Config | ConvertTo-Json -Depth 20) + "`n", $utf8)
    $start = [Diagnostics.ProcessStartInfo]::new()
    $start.FileName = $Binary
    $start.Arguments = 'check -c "' + $path + '"'
    $start.UseShellExecute = $false
    $start.CreateNoWindow = $true
    $start.RedirectStandardOutput = $true
    $start.RedirectStandardError = $true
    $process = [Diagnostics.Process]::Start($start)
    try {
        $stdout = $process.StandardOutput.ReadToEndAsync()
        $stderr = $process.StandardError.ReadToEndAsync()
        if (-not $process.WaitForExit(20000)) { $process.Kill(); $process.WaitForExit(); throw "Synthetic config check timed out: $Name" }
        $output = $stdout.GetAwaiter().GetResult() + $stderr.GetAwaiter().GetResult()
        if ($process.ExitCode -ne 0) { throw "Synthetic config rejected: $Name`n$output" }
        $script:results += @{ name = $Name; exit_code = 0; sha256 = (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant() }
        Write-Output "Accepted synthetic config: $Name"
    } finally { $process.Dispose() }
}

Invoke-ConfigCheck 'wireguard' (New-WireGuardConfig)
Invoke-ConfigCheck 'wireguard-keepalive-zero' (New-WireGuardConfig -Keepalive 0)
Invoke-ConfigCheck 'wireguard-keepalive-range' (New-WireGuardConfig -Keepalive '22-30')
Invoke-ConfigCheck 'wireguard-ipv6' (New-WireGuardConfig -Ipv6)
Invoke-ConfigCheck 'wireguard-private-dns-port' (New-WireGuardConfig -PrivateDns)
Invoke-ConfigCheck 'awg1' (New-WireGuardConfig -Amnezia @{ jc = 4; jmin = 40; jmax = 70; s1 = 16; s2 = 32; h1 = '11'; h2 = '12'; h3 = '13'; h4 = '14' })
Invoke-ConfigCheck 'awg2' (New-WireGuardConfig -Amnezia @{ jc = 4; jmin = 40; jmax = 70; s1 = 16; s2 = 32; s3 = 16; s4 = 16; h1 = '11-20'; h2 = '21-30'; h3 = '31-40'; h4 = '41-50'; i1 = '<b 0x01>' })
Invoke-ConfigCheck 'awg3' (New-WireGuardConfig -Amnezia @{ header_protection_key = 'AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE='; s1 = 32; s2 = 32; s3 = 32; s4 = 32; rekey_after_time = '22-30' })
Invoke-ConfigCheck 'awg31' (New-WireGuardConfig -Amnezia @{ random_trailers = $true; disable_cookies = $true })
Invoke-ConfigCheck 'awg31-explicit-false' (New-WireGuardConfig -Amnezia @{ random_trailers = $false; disable_cookies = $false })

foreach ($transport in @('tcp', 'ws', 'grpc', 'http', 'httpupgrade', 'quic')) {
    $outbound = @{ type = 'vless'; tag = 'vless'; server = '203.0.113.42'; server_port = 443
        uuid = '00000000-0000-4000-8000-000000000000'; tls = @{ enabled = $true; server_name = 'vpn.example.com' } }
    if ($transport -ne 'tcp') { $outbound.transport = @{ type = $transport } }
    if ($transport -in @('ws', 'http', 'httpupgrade')) { $outbound.transport.path = '/synthetic' }
    if ($transport -eq 'grpc') { $outbound.transport.service_name = 'synthetic' }
    Invoke-ConfigCheck ('vless-' + $transport) @{ inbounds = @((New-SocksInbound)); outbounds = @($outbound); route = @{ final = 'vless' } }
}

Invoke-ConfigCheck 'tcp-only-socks-doh-bridge' @{
    inbounds = @((New-SocksInbound))
    outbounds = @(@{ type = 'socks'; tag = 'upstream'; server = '127.0.0.1'; server_port = 19482; version = '5'; username = 'synthetic-user'; password = 'synthetic-password' })
    dns = @{ servers = @(@{ type = 'https'; tag = 'tunnel-dns'; server = '8.8.8.8'; server_port = 443; path = '/dns-query'; tls = @{ enabled = $true; server_name = 'dns.google' }; detour = 'upstream' }); final = 'tunnel-dns'; strategy = 'ipv4_only' }
    route = @{ final = 'upstream'; rules = @(@{ inbound = 'local-socks'; port = 53; action = 'hijack-dns' }) }
}

[IO.File]::WriteAllText((Join-Path $OutputDirectory 'result.json'), (@{ binary_sha256 = (Get-FileHash -LiteralPath $Binary -Algorithm SHA256).Hash.ToLowerInvariant(); checks = $results } | ConvertTo-Json -Depth 6) + "`n", $utf8)
Write-Output "Passed $($results.Count) synthetic checks. No tunnel, server connection or profile was started."
