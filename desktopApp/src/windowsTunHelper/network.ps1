param([switch]$SelfTest)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

function Get-CaptureRoutes {
    return @('0.0.0.0/1', '128.0.0.0/1', '::/1', '8000::/1')
}

function Get-HostPrefix([string]$Address) {
    $ip = [System.Net.IPAddress]::Parse($Address)
    if ($ip.AddressFamily -eq [System.Net.Sockets.AddressFamily]::InterNetwork) { return "$Address/32" }
    return "$Address/128"
}

function Test-RouteRecord($Route) {
    if ($null -eq $Route -or $Route.interfaceIndex -lt 1 -or $Route.interfaceIndex -gt 2147483647) { return $false }
    if ($Route.metric -ne 7) { return $false }
    $parts = $Route.prefix.Split('/')
    if ($parts.Count -ne 2) { return $false }
    $ip = $null
    $hop = $null
    if (-not [System.Net.IPAddress]::TryParse($parts[0], [ref]$ip) -or
        -not [System.Net.IPAddress]::TryParse($Route.nextHop, [ref]$hop)) { return $false }
    return $Route.prefix -in (Get-CaptureRoutes) -or $Route.prefix -eq (Get-HostPrefix $ip.ToString())
}

if ($SelfTest) {
    if ((Get-HostPrefix '203.0.113.9') -ne '203.0.113.9/32') { throw 'IPv4 host route' }
    if ((Get-HostPrefix '2001:db8::9') -ne '2001:db8::9/128') { throw 'IPv6 host route' }
    if (@(Get-CaptureRoutes).Count -ne 4) { throw 'Capture routes' }
    $r = [pscustomobject]@{ prefix='203.0.113.9/32'; interfaceIndex=4; nextHop='192.0.2.1'; metric=7 }
    if (-not (Test-RouteRecord $r)) { throw 'Valid journal rejected' }
    $r.prefix='0.0.0.0/0'
    if (Test-RouteRecord $r) { throw 'Unowned default route accepted' }
    $r.prefix='203.0.113.9/32'; $r.metric=1
    if (Test-RouteRecord $r) { throw 'Unowned metric accepted' }
    'PASS: 6 route-plan checks; no adapter, DNS or routes changed'
    exit 0
}

$identity = [System.Security.Principal.WindowsIdentity]::GetCurrent()
if (-not ([System.Security.Principal.WindowsPrincipal]::new($identity)).IsInRole(
    [System.Security.Principal.WindowsBuiltInRole]::Administrator)) { throw 'Elevation required' }

$sessionRoot = [System.IO.Path]::GetFullPath($PSScriptRoot)
$protectedRoot = Split-Path -Parent $sessionRoot
$journal = Join-Path $sessionRoot 'state.json'
$script:ownedRoutes = @()
$nativeProcess = $null
$lease = $null
$leaseHeld = $false
$stage = 'initialization'
$exitCode = 0

function Save-Journal {
    # The journal is protected by the helper-created directory ACL.
    $json = ConvertTo-Json -Depth 4 -Compress -InputObject @($script:ownedRoutes)
    [System.IO.File]::WriteAllText($journal, $json, [System.Text.UTF8Encoding]::new($false))
}

function Remove-JournalRoutes([string]$Path) {
    if (-not (Test-Path -LiteralPath $Path)) { return }
    if ((Get-Item -LiteralPath $Path).Attributes -band [System.IO.FileAttributes]::ReparsePoint) { throw 'Reparse journal rejected' }
    $entries = @(ConvertFrom-Json -InputObject ([System.IO.File]::ReadAllText($Path)))
    if ($entries.Count -gt 5) { throw 'Invalid route journal' }
    foreach ($entry in $entries) {
        if (-not (Test-RouteRecord $entry)) { throw 'Invalid route journal entry' }
        Get-NetRoute -PolicyStore ActiveStore -InterfaceIndex $entry.interfaceIndex -DestinationPrefix $entry.prefix -ErrorAction SilentlyContinue |
            Where-Object { $_.NextHop -eq $entry.nextHop -and $_.RouteMetric -eq $entry.metric } |
            Remove-NetRoute -Confirm:$false -ErrorAction Stop
    }
    Remove-Item -LiteralPath $Path -Force
}

function Add-OwnedRoute([string]$Prefix, [int]$Index, [string]$NextHop) {
    $existing = @(Get-NetRoute -PolicyStore ActiveStore -InterfaceIndex $Index -DestinationPrefix $Prefix -ErrorAction SilentlyContinue |
        Where-Object { $_.NextHop -eq $NextHop })
    if ($existing.Count -gt 0) { return }
    # Record intent first, so a crash between creation and persistence is recoverable.
    $script:ownedRoutes += [pscustomobject]@{ prefix=$Prefix; interfaceIndex=$Index; nextHop=$NextHop; metric=7 }
    Save-Journal
    New-NetRoute -PolicyStore ActiveStore -DestinationPrefix $Prefix -InterfaceIndex $Index -NextHop $NextHop -RouteMetric 7 | Out-Null
}

try {
    # Only one elevated helper can own routes. A second launch never recovers
    # another live session's journal or takes ownership of its adapter.
    $lease = [System.Threading.Mutex]::new($false, 'Global\UnifiedVPN-Tun-Session')
    try { $leaseHeld = $lease.WaitOne(0) } catch [System.Threading.AbandonedMutexException] { $leaseHeld = $true }
    if (-not $leaseHeld) { throw 'Another TUN session is active' }
    $stage = 'recovery'
    foreach ($directory in @(Get-ChildItem -LiteralPath $protectedRoot -Directory -Filter 'session-*')) {
        if ($directory.Attributes -band [System.IO.FileAttributes]::ReparsePoint) { throw 'Reparse directory rejected' }
        if ($directory.FullName -ne $sessionRoot) { Remove-JournalRoutes (Join-Path $directory.FullName 'state.json') }
    }
    $line = [Console]::In.ReadLine()
    if ($null -eq $line -or $line.Length -gt 8192) { throw 'Invalid configuration' }
    $config = ConvertFrom-Json -InputObject $line
    # C# validates before forwarding. No path or command is accepted here.
    $endpoint = [System.Net.IPAddress]::Parse([string]$config.endpoints[0]).ToString()
    $stage = 'endpoint route'
    $endpointRoute = @(Find-NetRoute -RemoteIPAddress $endpoint | Where-Object { $_.PSObject.Properties.Name -contains 'NextHop' })
    if ($endpointRoute.Count -ne 1) { throw 'No unique route to the VPN server' }
    if (Get-NetAdapter -Name 'Olcbox' -ErrorAction SilentlyContinue) { throw 'Adapter Olcbox already exists' }

    $stage = 'native startup'
    $auth = ''
    if (-not [string]::IsNullOrEmpty($config.username)) {
        $auth = [Uri]::EscapeDataString($config.username) + ':' + [Uri]::EscapeDataString($config.password) + '@'
    }
    $start = [System.Diagnostics.ProcessStartInfo]::new()
    $start.FileName = Join-Path $sessionRoot 'tun2socks.exe'
    $start.Arguments = '--device Olcbox --proxy socks5://' + $auth + '127.0.0.1:' + [int]$config.port + ' --mtu 1500 --loglevel silent'
    $start.WorkingDirectory = $sessionRoot
    $start.UseShellExecute = $false
    $start.CreateNoWindow = $true
    $nativeProcess = [System.Diagnostics.Process]::Start($start)
    $deadline = [DateTime]::UtcNow.AddSeconds(15)
    do {
        if ($nativeProcess.HasExited) { throw 'Native tunnel exited' }
        $adapter = Get-NetAdapter -Name 'Olcbox' -ErrorAction SilentlyContinue
        if ($null -ne $adapter) { break }
        Start-Sleep -Milliseconds 200
    } while ([DateTime]::UtcNow -lt $deadline)
    if ($null -eq $adapter) { throw 'TUN adapter startup timeout' }
    $index = [int]$adapter.ifIndex

    $stage = 'adapter setup'
    # Only the new adapter is configured; physical adapters and their DNS stay intact.
    Set-NetIPInterface -InterfaceIndex $index -AddressFamily IPv4 -Dhcp Disabled -AutomaticMetric Disabled -InterfaceMetric 5
    Set-NetIPInterface -InterfaceIndex $index -AddressFamily IPv6 -AutomaticMetric Disabled -InterfaceMetric 5
    New-NetIPAddress -InterfaceIndex $index -IPAddress '198.18.0.1' -PrefixLength 30 -PolicyStore ActiveStore | Out-Null
    New-NetIPAddress -InterfaceIndex $index -IPAddress 'fdfe:dcba:9876::1' -PrefixLength 126 -PolicyStore ActiveStore | Out-Null
    Set-DnsClientServerAddress -InterfaceIndex $index -ServerAddresses @('1.1.1.1', '2606:4700:4700::1111')
    Set-DnsClient -InterfaceIndex $index -RegisterThisConnectionsAddress $false

    $stage = 'route setup'
    Add-OwnedRoute (Get-HostPrefix $endpoint) ([int]$endpointRoute[0].InterfaceIndex) ([string]$endpointRoute[0].NextHop)
    foreach ($prefix in Get-CaptureRoutes) {
        $hop = if ($prefix.Contains(':')) { '::' } else { '0.0.0.0' }
        Add-OwnedRoute $prefix $index $hop
    }
    [Console]::Out.WriteLine('READY')
    [Console]::Out.Flush()
    $stop = [Console]::In.ReadLineAsync()
    while (-not $stop.IsCompleted) {
        if ($nativeProcess.HasExited) { throw 'Native tunnel exited during session' }
        Start-Sleep -Milliseconds 200
    }
} catch {
    # No raw exception text: commands may include the local SOCKS password.
    [Console]::Out.WriteLine('ERROR Windows TUN failed during ' + $stage)
    [Console]::Out.Flush()
    $exitCode = 1
} finally {
    if ($leaseHeld) {
        try { Remove-JournalRoutes $journal } catch {
            [Console]::Out.WriteLine('ERROR TUN route cleanup incomplete; recovery will retry on next start')
            $exitCode = 1
        }
    }
    if ($null -ne $nativeProcess) {
        if (-not $nativeProcess.HasExited) { $nativeProcess.Kill(); $null = $nativeProcess.WaitForExit(5000) }
        $nativeProcess.Dispose()
    }
    if ($leaseHeld) { $lease.ReleaseMutex() }
    if ($null -ne $lease) { $lease.Dispose() }
}
exit $exitCode
