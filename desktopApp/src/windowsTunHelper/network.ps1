param([switch]$SelfTest)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$script:maxPinnedEndpoints = 2

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
    if ($ip.AddressFamily -ne $hop.AddressFamily) { return $false }
    return $Route.prefix -in (Get-CaptureRoutes) -or $Route.prefix -eq (Get-HostPrefix $ip.ToString())
}

function Resolve-EndpointRoutes([string[]]$Endpoints) {
    if ($Endpoints.Count -lt 1 -or $Endpoints.Count -gt $script:maxPinnedEndpoints) { throw 'Invalid VPN endpoint count' }
    $prefixes = [System.Collections.Generic.HashSet[string]]::new()
    foreach ($address in $Endpoints) {
        $endpoint = [System.Net.IPAddress]::Parse($address).ToString()
        $prefix = Get-HostPrefix $endpoint
        if (-not $prefixes.Add($prefix)) { throw 'Duplicate VPN endpoint' }
        $routes = @(Find-NetRoute -RemoteIPAddress $endpoint |
            Where-Object { $_.PSObject.Properties.Name -contains 'NextHop' })
        if ($routes.Count -ne 1) { throw 'No unique route to a pinned VPN server' }
        $route = [pscustomobject]@{
            prefix = $prefix; interfaceIndex = [int]$routes[0].InterfaceIndex
            nextHop = [string]$routes[0].NextHop; metric = 7
        }
        if (-not (Test-RouteRecord $route)) { throw 'Invalid route to a pinned VPN server' }
        $route
    }
}

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
    # Windows PowerShell emits the decoded array as one pipeline object.
    $decoded = ConvertFrom-Json -InputObject ([System.IO.File]::ReadAllText($Path))
    $entries = @($decoded)
    $captureRoutes = @(Get-CaptureRoutes)
    if ($entries.Count -gt ($captureRoutes.Count + $script:maxPinnedEndpoints)) { throw 'Invalid route journal' }
    $prefixes = [System.Collections.Generic.HashSet[string]]::new()
    $hostRoutes = 0
    foreach ($entry in $entries) {
        if (-not (Test-RouteRecord $entry) -or -not $prefixes.Add($entry.prefix)) { throw 'Invalid route journal entry' }
        if ($entry.prefix -notin $captureRoutes) { $hostRoutes++ }
    }
    if ($hostRoutes -gt $script:maxPinnedEndpoints) { throw 'Invalid VPN endpoint count in route journal' }
    # Release the capture ranges first. The physical endpoint routes stay pinned until then.
    for ($i = $entries.Count - 1; $i -ge 0; $i--) {
        $entry = $entries[$i]
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

function Install-SessionRoutes([object[]]$EndpointRoutes, [int]$TunIndex) {
    foreach ($route in $EndpointRoutes) {
        Add-OwnedRoute $route.prefix $route.interfaceIndex $route.nextHop
    }
    foreach ($prefix in Get-CaptureRoutes) {
        $hop = if ($prefix.Contains(':')) { '::' } else { '0.0.0.0' }
        Add-OwnedRoute $prefix $TunIndex $hop
    }
}

if ($SelfTest) {
    if ((Get-HostPrefix '203.0.113.9') -ne '203.0.113.9/32') { throw 'IPv4 host route' }
    if ((Get-HostPrefix '2001:db8::9') -ne '2001:db8::9/128') { throw 'IPv6 host route' }
    if (@(Get-CaptureRoutes).Count -ne 4) { throw 'Capture routes' }
    $record = [pscustomobject]@{ prefix='203.0.113.9/32'; interfaceIndex=4; nextHop='192.0.2.1'; metric=7 }
    if (-not (Test-RouteRecord $record)) { throw 'Valid journal rejected' }
    $record.prefix = '0.0.0.0/0'
    if (Test-RouteRecord $record) { throw 'Unowned default route accepted' }
    $record.prefix = '203.0.113.9/32'; $record.metric = 1
    if (Test-RouteRecord $record) { throw 'Unowned metric accepted' }

    # Shadow all route cmdlets before exercising the production route functions.
    function Find-NetRoute([string]$RemoteIPAddress) {
        switch ($RemoteIPAddress) {
            '203.0.113.9' { [pscustomobject]@{ InterfaceIndex=6; NextHop='192.0.2.1' } }
            '2001:db8::9' { [pscustomobject]@{ InterfaceIndex=11; NextHop='fe80::1' } }
        }
    }
    function Get-NetRoute([string]$PolicyStore, [int]$InterfaceIndex, [string]$DestinationPrefix, [string]$ErrorAction) {
        $script:mockRoutes | Where-Object {
            $_.InterfaceIndex -eq $InterfaceIndex -and $_.DestinationPrefix -eq $DestinationPrefix
        }
    }
    function New-NetRoute([string]$PolicyStore, [string]$DestinationPrefix, [int]$InterfaceIndex,
                          [string]$NextHop, [int]$RouteMetric) {
        if ($DestinationPrefix -eq $script:mockFailurePrefix) { throw 'Simulated route creation failure' }
        $script:createdPrefixes += $DestinationPrefix
        $script:mockRoutes += [pscustomobject]@{
            DestinationPrefix=$DestinationPrefix; InterfaceIndex=$InterfaceIndex; NextHop=$NextHop; RouteMetric=$RouteMetric
        }
    }
    function Remove-NetRoute {
        [CmdletBinding(SupportsShouldProcess)]
        param([Parameter(ValueFromPipeline)]$Route)
        process {
            $script:removedPrefixes += $Route.DestinationPrefix
            $script:mockRoutes = @($script:mockRoutes | Where-Object { $_ -ne $Route })
        }
    }

    $fixtureDirectory = Join-Path ([System.IO.Path]::GetTempPath()) ('unifiedvpn-tun-test-' + [Guid]::NewGuid().ToString('N'))
    $null = New-Item -ItemType Directory -Path $fixtureDirectory
    $journal = Join-Path $fixtureDirectory 'state.json'
    try {
        $script:mockRoutes = @()
        $script:createdPrefixes = @()
        $script:removedPrefixes = @()
        $script:mockFailurePrefix = ''
        $pins = @(Resolve-EndpointRoutes @('203.0.113.9', '2001:db8::9'))
        if ($pins.Count -ne 2 -or $pins[0].interfaceIndex -ne 6 -or $pins[1].interfaceIndex -ne 11 -or
            $pins[0].nextHop -ne '192.0.2.1' -or $pins[1].nextHop -ne 'fe80::1') { throw 'Physical endpoint plan' }
        $expectedOrder = @('203.0.113.9/32', '2001:db8::9/128') + @(Get-CaptureRoutes)
        Install-SessionRoutes $pins 42
        if (($script:createdPrefixes -join ',') -ne ($expectedOrder -join ',')) { throw 'Endpoint routes must precede capture' }
        if ($script:ownedRoutes.Count -ne 6) { throw 'All route intentions must be journaled' }
        Remove-JournalRoutes $journal
        if (($script:removedPrefixes -join ',') -ne ($expectedOrder[5..0] -join ',') -or
            $script:mockRoutes.Count -ne 0 -or (Test-Path -LiteralPath $journal)) { throw 'Complete cleanup order or ownership' }

        $preexisting = [pscustomobject]@{ DestinationPrefix='203.0.113.9/32'; InterfaceIndex=6; NextHop='192.0.2.1'; RouteMetric=12 }
        $script:ownedRoutes = @()
        $script:mockRoutes = @($preexisting)
        $script:createdPrefixes = @()
        $script:removedPrefixes = @()
        $script:mockFailurePrefix = '128.0.0.0/1'
        $rejected = $false
        try { Install-SessionRoutes $pins 42 } catch {
            if ($_.Exception.Message -ne 'Simulated route creation failure') { throw }
            $rejected = $true
        }
        if (-not $rejected -or $script:ownedRoutes.Count -ne 3) { throw 'Partial setup was not journaled' }
        Remove-JournalRoutes $journal
        if (($script:removedPrefixes -join ',') -ne '0.0.0.0/1,2001:db8::9/128' -or
            $script:mockRoutes.Count -ne 1 -or $script:mockRoutes[0] -ne $preexisting) { throw 'Rollback removed a preexisting route' }

        $script:removedPrefixes = @()
        $script:mockRoutes = @([pscustomobject]@{
            DestinationPrefix='203.0.113.9/32'; InterfaceIndex=6; NextHop='192.0.2.1'; RouteMetric=7
        })
        $script:ownedRoutes = @($pins[0], [pscustomobject]@{ prefix='0.0.0.0/0'; interfaceIndex=6; nextHop='192.0.2.1'; metric=7 })
        Save-Journal
        $rejected = $false
        try { Remove-JournalRoutes $journal } catch { $rejected = $true }
        if (-not $rejected -or $script:removedPrefixes.Count -ne 0 -or
            -not (Test-Path -LiteralPath $journal)) { throw 'Corrupt journal modified the route table' }
    } finally {
        if (Test-Path -LiteralPath $journal) { Remove-Item -LiteralPath $journal }
        [System.IO.Directory]::Delete($fixtureDirectory)
    }
    'PASS: route validation, dual-stack endpoint precedence, complete cleanup, partial rollback and corrupt-journal guard; mock routes only'
    exit 0
}

$identity = [System.Security.Principal.WindowsIdentity]::GetCurrent()
if (-not ([System.Security.Principal.WindowsPrincipal]::new($identity)).IsInRole(
    [System.Security.Principal.WindowsBuiltInRole]::Administrator)) { throw 'Elevation required' }

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
    $stage = 'endpoint route'
    if (Get-NetAdapter -Name 'Olcbox' -ErrorAction SilentlyContinue) { throw 'Adapter Olcbox already exists' }
    # Resolve both physical routes while the capture adapter is still absent.
    $endpointRoutes = @(Resolve-EndpointRoutes $config.endpoints)

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
    Install-SessionRoutes $endpointRoutes $index
    # Discard cached physical answers before the scoped FakeDNS policy starts serving this session.
    Clear-DnsClientCache -ErrorAction Stop
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
        # Fake addresses belong to the retiring core and must not survive in the Windows resolver cache.
        try { Clear-DnsClientCache -ErrorAction Stop } catch {
            [Console]::Out.WriteLine('ERROR TUN DNS cache cleanup failed')
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
