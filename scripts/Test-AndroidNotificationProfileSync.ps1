#requires -Version 7.0
[CmdletBinding()]
param(
    [Parameter(Mandatory)] [string]$FreshOwnershipRecord,
    [Parameter(Mandatory)] [string]$TestApk,
    [string]$TestReleaseApk = ''
)

$ErrorActionPreference = 'Stop'
$testOwnership = [IO.Path]::GetFullPath($FreshOwnershipRecord)
$testApkPath = [IO.Path]::GetFullPath($TestApk)
$testApkHash = (Get-FileHash -LiteralPath $testApkPath -Algorithm SHA256).Hash
$releaseApkPath = if ($TestReleaseApk) { [IO.Path]::GetFullPath($TestReleaseApk) } else { $null }
$releaseApkHash = if ($releaseApkPath) { (Get-FileHash -LiteralPath $releaseApkPath -Algorithm SHA256).Hash } else { $null }
# Reuse the guarded ADB/process helpers, not the OpenFlux connection test.
. (Join-Path $PSScriptRoot 'Test-OpenFluxAndroid.ps1') -PrepareOnly `
    -ApkPath $testApkPath -ExpectedApkSha256 $testApkHash
$OwnershipRecordPath = $testOwnership
$outputRoot = Join-Path $repo ('.downloads\android-notification-' + [DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss'))
New-Item -ItemType Directory -Path $outputRoot -Force | Out-Null
$checks = [Collections.Generic.List[string]]::new()
$script:uiReadSequence = 0

function Read-TestUi {
    for ($attempt = 0; $attempt -lt 3; $attempt++) {
        $script:uiReadSequence++
        $guestPath = "/sdcard/notification-test-ui-$($script:uiReadSequence).xml"
        # A busy SystemUI can refuse a hierarchy dump; never reuse a stale XML file.
        $dump = Invoke-IsolatedAdb -AdbArguments @('shell', 'uiautomator', 'dump', $guestPath)
        if ($dump.ExitCode -eq 0 -and $dump.Output -notmatch 'ERROR:') {
            $text = Invoke-CheckedAdb -AdbArguments @('exec-out', 'cat', $guestPath)
            $ui = [xml]$text
            [IO.File]::WriteAllText((Join-Path $outputRoot 'last-ui.xml'), $text)
            return $ui
        }
        Start-Sleep -Milliseconds 500
    }
    throw 'Android UI hierarchy remained unavailable after three read-only attempts'
}

function Tap-TestUi {
    param([string]$Label, [ValidateSet('None', 'Up', 'Down')] [string]$Scroll = 'None')
    $node = Find-TestUiNode -Label $Label -Scroll $Scroll
    if ($null -eq $node) { throw "UI element not found: $Label" }
    Tap-TestNode $node
}

function Find-TestUiNode {
    param([string]$Label, [ValidateSet('None', 'Up', 'Down')] [string]$Scroll = 'None')
    for ($attempt = 0; $attempt -lt 7; $attempt++) {
        $ui = Read-TestUi
        $node = @($ui.SelectNodes('//node') | Where-Object {
            $_.GetAttribute('text') -eq $Label -or $_.GetAttribute('content-desc') -eq $Label
        }) | Select-Object -First 1
        if ($null -ne $node) { return $node }
        if ($Scroll -eq 'None') { return $null }
        $scrollNode = $ui.SelectSingleNode('//node[@scrollable="true"]')
        if ($null -eq $scrollNode) { return $null }
        $bounds = [regex]::Match($scrollNode.GetAttribute('bounds'), '^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$')
        if (-not $bounds.Success) { throw 'Scrollable fixture view has no bounds' }
        $x = [int](([int]$bounds.Groups[1].Value + [int]$bounds.Groups[3].Value) / 2)
        $height = [int]$bounds.Groups[4].Value - [int]$bounds.Groups[2].Value
        $top = [int]([int]$bounds.Groups[2].Value + $height * 0.2)
        $bottom = [int]([int]$bounds.Groups[2].Value + $height * 0.8)
        $from = if ($Scroll -eq 'Down') { $bottom } else { $top }
        $to = if ($Scroll -eq 'Down') { $top } else { $bottom }
        Invoke-CheckedAdb -AdbArguments @('shell', 'input', 'swipe', "$x", "$from", "$x", "$to", '300') | Out-Null
        Start-Sleep -Milliseconds 250
    }
    return $null
}

function Tap-TestNode {
    param([System.Xml.XmlElement]$Node)
    $bounds = [regex]::Match($Node.GetAttribute('bounds'), '^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$')
    if (-not $bounds.Success) { throw 'UI element has no valid bounds' }
    $x = [int]( ([int]$bounds.Groups[1].Value + [int]$bounds.Groups[3].Value) / 2 )
    $y = [int]( ([int]$bounds.Groups[2].Value + [int]$bounds.Groups[4].Value) / 2 )
    Invoke-CheckedAdb -AdbArguments @('shell', 'input', 'tap', "$x", "$y") | Out-Null
    Start-Sleep -Milliseconds 500
}

function Open-TestNotification {
    param([switch]$WithActions)
    Invoke-CheckedAdb -AdbArguments @('shell', 'cmd', 'statusbar', 'expand-notifications') | Out-Null
    $deadline = [DateTime]::UtcNow.AddSeconds(30)
    do {
        Start-Sleep -Milliseconds 500
        $ui = Read-TestUi
        $title = $ui.SelectSingleNode('//node[@text="Unified VPN"]')
        if ($null -eq $title) { continue }
        if (-not $WithActions) { return }
        if ($null -ne $ui.SelectSingleNode('//node[@text="Next" or @text="NEXT"]')) { return }
        for ($ancestor = $title.ParentNode; $null -ne $ancestor; $ancestor = $ancestor.ParentNode) {
            $expand = @($ancestor.SelectNodes('.//node[@content-desc="Expand"]'))
            if ($expand.Count -eq 1) { Tap-TestNode $expand[0]; break }
        }
    } while ([DateTime]::UtcNow -lt $deadline)
    throw 'Fixture notification or its expanded actions did not become visible'
}

function Open-TestChooser {
    param([switch]$Failed)
    Open-TestNotification
    $ui = Read-TestUi
    $pattern = if ($Failed) { '^Fixture Fail .*failed$' } else { '^Fixture [AB] .*Connected$' }
    $label = @($ui.SelectNodes('//node') | Where-Object {
        $_.GetAttribute('text') -match $pattern
    }) | Select-Object -First 1
    if ($null -eq $label) { throw 'Expected fixture notification was not found' }
    Tap-TestUi -Label $label.GetAttribute('text')
    Assert-TestChooser
}

function Assert-TestChooser {
    param([switch]$WithReconnect)
    $resumed = Invoke-CheckedAdb -AdbArguments @('shell', 'dumpsys', 'activity', 'activities')
    if ($resumed -notmatch '(?:mResumedActivity|topResumedActivity)[=:].*VpnProfileChooserActivity') {
        throw 'Notification chooser unexpectedly closed'
    }
    if ($WithReconnect) {
        if ($null -eq (Find-TestUiNode -Label 'Reconnect' -Scroll Down)) {
            throw 'Failed connection has no separate Reconnect action'
        }
    }
}

function Save-TestChooserScreenshot {
    param([ValidateSet('initial-failure', 'after-rollback')] [string]$State)
    $guestPath = "/sdcard/notification-reconnect-$State.png"
    Invoke-CheckedAdb -AdbArguments @('shell', 'screencap', '-p', $guestPath) | Out-Null
    Invoke-CheckedAdb -AdbArguments @('pull', $guestPath, (Join-Path $outputRoot "reconnect-$State.png")) | Out-Null
}

function Wait-TestLauncherReady {
    Invoke-CheckedAdb -AdbArguments @('shell', 'input', 'keyevent', 'KEYCODE_MENU') | Out-Null
    Invoke-CheckedAdb -AdbArguments @('shell', 'input', 'keyevent', 'KEYCODE_HOME') | Out-Null
    # A fresh API 35 guest still finishes launcher initialization after sys.boot_completed.
    Start-Sleep -Seconds 20
    $ui = Read-TestUi
    if (@($ui.SelectNodes('//node') | Where-Object {
        $_.GetAttribute('text') -eq "Pixel Launcher isn't responding"
    }).Count -ne 0) {
        Tap-TestUi 'Wait'
        Start-Sleep -Seconds 10
        $ui = Read-TestUi
    }
    if (@($ui.SelectNodes('//node') | Where-Object {
        $_.GetAttribute('text') -like "*isn't responding*"
    }).Count -ne 0) {
        throw 'Fresh guest has an ANR before the test app starts'
    }
}

function Wait-TestConnectionFailure {
    param([int]$MinimumStarts)
    $deadline = [DateTime]::UtcNow.AddSeconds(45)
    do {
        $logs = Invoke-CheckedAdb -AdbArguments @('logcat', '-d', '--pid', $script:appPid, '-v', 'brief', '-s', 'OlcboxVpnService:D', '*:S')
        $notifications = Invoke-CheckedAdb -AdbArguments @('shell', 'dumpsys', 'notification', '--noredact')
        if ([regex]::Matches($logs, 'Starting VLESS profile').Count -ge $MinimumStarts -and
            $notifications -match 'Fixture Fail .*failed') { return }
        Start-Sleep -Milliseconds 500
    } while ([DateTime]::UtcNow -lt $deadline)
    throw 'Synthetic connection did not finish a new failing attempt'
}

function Wait-TestSelection {
    param([string]$Id, [string]$Name, [int]$MinimumConnections)
    $deadline = [DateTime]::UtcNow.AddSeconds(40)
    do {
        $bundle = (Invoke-CheckedAdb -AdbArguments @('shell', 'run-as', $package, 'cat', 'files/locations_v4.json')) | ConvertFrom-Json
        $logs = Invoke-CheckedAdb -AdbArguments @('logcat', '-d', '--pid', $script:appPid, '-v', 'brief', '-s', 'OlcboxVpnService:D', '*:S')
        if ($bundle.active_location_id -eq $Id -and
            [regex]::Matches($logs, 'VLESS VPN tunnel established').Count -ge $MinimumConnections) {
            $notifications = Invoke-CheckedAdb -AdbArguments @('shell', 'dumpsys', 'notification', '--noredact')
            if ($notifications -match ([regex]::Escape($Name) + ' .*Connected')) { return }
        }
        Start-Sleep -Milliseconds 500
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "Fixture did not switch to $Name with a new established TUN"
}

function Assert-TestMainSelection {
    param([string]$Name)
    $resumed = Invoke-CheckedAdb -AdbArguments @('shell', 'dumpsys', 'activity', 'activities')
    if ($resumed -notmatch '(?:mResumedActivity|topResumedActivity)[=:].*org.olcbox.app.AppActivity') { throw 'Main app is not resumed' }
    $ui = Read-TestUi
    $selected = $ui.SelectSingleNode('//node[@content-desc="Selected location"]')
    if ($null -eq $selected) { throw 'Main app selected-profile marker is missing' }
    for ($ancestor = $selected.ParentNode; $null -ne $ancestor; $ancestor = $ancestor.ParentNode) {
        $names = @($ancestor.SelectNodes('.//node') | Where-Object { $_.GetAttribute('text') -match '^Fixture [AB]$' })
        if ($names.Count -gt 0) {
            if ($names.Count -ne 1 -or $names[0].GetAttribute('text') -ne $Name) {
                throw "Main app did not select $Name"
            }
            return
        }
    }
    throw 'Selected marker has no profile label'
}

function Test-OpenFluxShareUi {
    $ui = Read-TestUi
    $label = $ui.SelectSingleNode('//node[@text="Fixture OpenFlux"]')
    $settings = $null
    if ($null -eq $label) { throw 'OpenFlux fixture is missing from the profile list' }
    for ($ancestor = $label.ParentNode; $null -ne $ancestor; $ancestor = $ancestor.ParentNode) {
        $candidates = @($ancestor.SelectNodes('.//node[@content-desc="Settings"]'))
        if ($candidates.Count -eq 1) { $settings = $candidates[0]; break }
    }
    if ($null -eq $settings) { throw 'OpenFlux fixture settings action was not found' }
    Tap-TestNode $settings
    Tap-TestUi 'Share location'
    $ui = Read-TestUi
    if ($null -eq $ui.SelectSingleNode('//node[@text="Share OpenFlux profile"]') -or
        $null -ne $ui.SelectSingleNode('//node[@content-desc="QR code"]')) {
        throw 'OpenFlux export must show its warning before generating the share sheet'
    }
    Tap-TestUi 'Cancel'
    $ui = Read-TestUi
    if ($null -ne $ui.SelectSingleNode('//node[@content-desc="QR code"]') -or
        $null -eq $ui.SelectSingleNode('//node[@text="Location settings"]')) {
        throw 'Cancelling OpenFlux sharing must return to the editor without a share sheet'
    }
    Tap-TestUi 'Share location'
    Tap-TestUi 'Continue'
    $ui = Read-TestUi
    $payload = @($ui.SelectNodes('//node') | Where-Object {
        $_.GetAttribute('text').StartsWith('openflux://')
    }) | Select-Object -First 1
    if ($null -eq $ui.SelectSingleNode('//node[@text="OpenFlux profile"]') -or
        $null -eq $ui.SelectSingleNode('//node[@content-desc="QR code"]') -or $null -eq $payload) {
        throw 'Confirmed OpenFlux export must render its QR and importable URI'
    }
    Tap-TestUi 'Copy'
    $cores = Invoke-IsolatedAdb -AdbArguments @('shell', 'pidof', 'libopenflux.so', 'libsing-box.so', 'libxray.so')
    if ($cores.ExitCode -ne 1 -or $cores.Output.Trim()) { throw 'OpenFlux sharing unexpectedly started a native core' }
    Invoke-CheckedAdb -AdbArguments @('shell', 'input', 'keyevent', 'KEYCODE_BACK') | Out-Null
    Start-Sleep -Milliseconds 500
    Invoke-CheckedAdb -AdbArguments @('shell', 'input', 'keyevent', 'KEYCODE_BACK') | Out-Null
    Start-Sleep -Milliseconds 500
}

$ownership = $null
$installed = $false
$releaseInstalled = $false
$failure = $null
try {
    $ownership = Confirm-FreshOwnedEmulator
    Invoke-CheckedAdb -AdbArguments @('install', '-t', $ApkPath) -TimeoutSeconds 120 | Out-Null
    $installed = $true
    Invoke-CheckedAdb -AdbArguments @('shell', 'pm', 'grant', $package, 'android.permission.POST_NOTIFICATIONS') | Out-Null
    Invoke-CheckedAdb -AdbArguments @('shell', 'appops', 'set', $package, 'ACTIVATE_VPN', 'allow') | Out-Null
    Wait-TestLauncherReady
    # Both endpoints are guest loopback fixtures, not working accounts or internet-traffic tests.
    $locations = foreach ($suffix in @('A', 'B')) {
        $port = if ($suffix -eq 'A') { 9 } else { 10 }
        [ordered]@{
            storage_id = "fixture-$suffix"; name = "Fixture $suffix"
            vpn_profile = [ordered]@{
                type = 'vless'; name = "Fixture $suffix"
                uri = "vless://00000000-0000-4000-8000-000000000001@127.0.0.1:${port}?security=none&type=tcp#Fixture-$suffix"
            }
        }
    }
    $bundle = [ordered]@{ version = 5; active_location_id = 'fixture-A'; locations = @($locations) } | ConvertTo-Json -Depth 8 -Compress
    Invoke-CheckedAdb -AdbArguments @('shell', 'run-as', $package, 'mkdir', '-p', 'files') | Out-Null
    $transfer = Invoke-IsolatedAdb -AdbArguments @('shell', '-T', 'run-as', $package, 'dd', 'of=files/locations_v4.json') `
        -InputBytes ([Text.UTF8Encoding]::new($false).GetBytes($bundle))
    if ($transfer.ExitCode -ne 0) { throw 'Synthetic fixture transfer failed' }
    Invoke-CheckedAdb -AdbArguments @('shell', 'run-as', $package, 'chmod', '600', 'files/locations_v4.json') | Out-Null
    Invoke-CheckedAdb -AdbArguments @('shell', 'input', 'keyevent', 'KEYCODE_MENU') | Out-Null
    Invoke-CheckedAdb -AdbArguments @('shell', 'am', 'start', '-W', '-n', "$package/org.olcbox.app.AppActivity") | Out-Null
    $script:appPid = Invoke-CheckedAdb -AdbArguments @('shell', 'pidof', $package)
    Invoke-CheckedAdb -AdbArguments @('logcat', '-c') | Out-Null
    $start = Invoke-CheckedAdb -AdbArguments @('shell', 'am', 'broadcast', '-n', $receiver, '-a', 'org.olcbox.app.DEBUG_START_VPN')
    if ($start -notmatch 'result=-1, data="VPN_PREPARED"') { throw 'Debug VPN permission was not prepared' }
    Wait-TestSelection 'fixture-A' 'Fixture A' 1
    Assert-TestMainSelection 'Fixture A'
    $checks.Add('Initial profile in UI and established fixture TUN')

    Invoke-CheckedAdb -AdbArguments @('shell', 'input', 'keyevent', 'KEYCODE_HOME') | Out-Null
    Open-TestNotification -WithActions
    Tap-TestUi 'Next'
    Wait-TestSelection 'fixture-B' 'Fixture B' 2
    Open-TestChooser
    Tap-TestUi 'Open Unified VPN window'
    Assert-TestMainSelection 'Fixture B'
    $checks.Add('Notification Next synchronizes background main UI; icon resumes app')

    Open-TestChooser
    Tap-TestUi 'Fixture A'
    Wait-TestSelection 'fixture-A' 'Fixture A' 3
    Assert-TestChooser
    Invoke-CheckedAdb -AdbArguments @('shell', 'am', 'start', '-W', '-n', "$package/org.olcbox.app.AppActivity") | Out-Null
    Assert-TestMainSelection 'Fixture A'
    $checks.Add('Chooser stays open after successful selection and synchronizes the main UI')

    Invoke-CheckedAdb -AdbArguments @('shell', 'input', 'keyevent', 'KEYCODE_HOME') | Out-Null
    Open-TestNotification -WithActions
    Tap-TestUi 'Previous'
    Wait-TestSelection 'fixture-B' 'Fixture B' 4
    Open-TestChooser
    Tap-TestUi 'Open Unified VPN window'
    Assert-TestMainSelection 'Fixture B'
    if ((Invoke-CheckedAdb -AdbArguments @('shell', 'pidof', $package)) -ne $script:appPid) { throw 'App process restarted during switches' }
    $checks.Add('Notification Previous wraps selection; same app process survives')

    Open-TestChooser
    Tap-TestUi 'Stop'
    Start-Sleep -Seconds 3
    $cores = Invoke-IsolatedAdb -AdbArguments @('shell', 'pidof', 'libopenflux.so', 'libsing-box.so', 'libxray.so')
    if ($cores.ExitCode -ne 1 -or $cores.Output.Trim()) { throw 'Native core remains after Stop' }
    Assert-TestChooser
    $checks.Add('Chooser Stop releases the native core without closing the window')

    Invoke-CheckedAdb -AdbArguments @('shell', 'am', 'force-stop', $package) | Out-Null
    # This unsupported transport fails locally before any network traffic.
    $failedProfile = [ordered]@{
        storage_id = 'fixture-fail'; name = 'Fixture Fail'
        vpn_profile = [ordered]@{
            type = 'vless'; name = 'Fixture Fail'
            uri = 'vless://00000000-0000-4000-8000-000000000001@127.0.0.1:9?security=none&type=fixture-unsupported#Fixture-Fail'
        }
    }
    $failureBundle = [ordered]@{
        version = 5; active_location_id = 'fixture-fail'; locations = @($locations) + @($failedProfile)
    } | ConvertTo-Json -Depth 8 -Compress
    $transfer = Invoke-IsolatedAdb -AdbArguments @('shell', '-T', 'run-as', $package, 'dd', 'of=files/locations_v4.json') `
        -InputBytes ([Text.UTF8Encoding]::new($false).GetBytes($failureBundle))
    if ($transfer.ExitCode -ne 0) { throw 'Failed-connection fixture transfer failed' }
    Invoke-CheckedAdb -AdbArguments @('shell', 'am', 'start', '-W', '-n', "$package/org.olcbox.app.AppActivity") | Out-Null
    $script:appPid = Invoke-CheckedAdb -AdbArguments @('shell', 'pidof', $package)
    Invoke-CheckedAdb -AdbArguments @('logcat', '-c') | Out-Null
    $start = Invoke-CheckedAdb -AdbArguments @('shell', 'am', 'broadcast', '-n', $receiver, '-a', 'org.olcbox.app.DEBUG_START_VPN')
    if ($start -notmatch 'result=-1, data="VPN_PREPARED"') { throw 'Failed-connection test VPN permission was not prepared' }
    Wait-TestConnectionFailure 1
    Open-TestChooser -Failed
    Assert-TestChooser -WithReconnect
    Save-TestChooserScreenshot -State 'initial-failure'
    Tap-TestUi 'Reconnect' -Scroll Down
    Wait-TestConnectionFailure 2
    Assert-TestChooser -WithReconnect
    if ((Invoke-CheckedAdb -AdbArguments @('shell', 'pidof', $package)) -ne $script:appPid) {
        throw 'App restarted during retry of the failed active profile'
    }
    $checks.Add('Failed initial connection retries the same profile and keeps chooser open')

    Tap-TestUi 'Fixture A' -Scroll Up
    Wait-TestSelection 'fixture-A' 'Fixture A' 1
    Assert-TestChooser
    Tap-TestUi 'Fixture Fail' -Scroll Down
    Wait-TestSelection 'fixture-A' 'Fixture A' 2
    Assert-TestChooser -WithReconnect
    Save-TestChooserScreenshot -State 'after-rollback'
    $beforeRetry = Invoke-CheckedAdb -AdbArguments @('logcat', '-d', '--pid', $script:appPid, '-v', 'brief', '-s', 'OlcboxVpnService:D', '*:S')
    $priorFailures = [regex]::Matches($beforeRetry, 'fixture-unsupported').Count
    Tap-TestUi 'Reconnect' -Scroll Down
    Wait-TestSelection 'fixture-A' 'Fixture A' 3
    Assert-TestChooser -WithReconnect
    $afterRetry = Invoke-CheckedAdb -AdbArguments @('logcat', '-d', '--pid', $script:appPid, '-v', 'brief', '-s', 'OlcboxVpnService:D', '*:S')
    if ([regex]::Matches($afterRetry, 'fixture-unsupported').Count -le $priorFailures) {
        throw 'Reconnect restarted the restored profile instead of retrying the failed target'
    }
    if ((Invoke-CheckedAdb -AdbArguments @('shell', 'pidof', $package)) -ne $script:appPid) {
        throw 'App restarted during failed switch/rollback/retry'
    }
    $checks.Add('Failed switch retains its retry target after rollback and keeps chooser open')

    Tap-TestUi 'Stop' -Scroll Down
    Start-Sleep -Seconds 3
    Assert-TestChooser
    $cores = Invoke-IsolatedAdb -AdbArguments @('shell', 'pidof', 'libopenflux.so', 'libsing-box.so', 'libxray.so')
    if ($cores.ExitCode -ne 1 -or $cores.Output.Trim()) { throw 'Retry test left a native core after Stop' }

    Invoke-CheckedAdb -AdbArguments @('shell', 'am', 'force-stop', $package) | Out-Null
    $openFluxConfig = [ordered]@{
        version = 1; transport = 'yandex'
        document_url = 'https://docs.yandex.ru/docs/view?url=synthetic-notification-test'
        encryption_key = ('ab' * 32)
    } | ConvertTo-Json -Compress
    $inactiveBundle = [ordered]@{
        version = 5; active_location_id = 'fixture-openflux'
        locations = @([ordered]@{
            storage_id = 'fixture-openflux'; name = 'Fixture OpenFlux'
            vpn_profile = [ordered]@{ type = 'openflux'; name = 'Fixture OpenFlux'; raw_config = $openFluxConfig }
        })
    } | ConvertTo-Json -Depth 8 -Compress
    $transfer = Invoke-IsolatedAdb -AdbArguments @('shell', '-T', 'run-as', $package, 'dd', 'of=files/locations_v4.json') `
        -InputBytes ([Text.UTF8Encoding]::new($false).GetBytes($inactiveBundle))
    if ($transfer.ExitCode -ne 0) { throw 'Inactive OpenFlux fixture transfer failed' }
    Invoke-CheckedAdb -AdbArguments @('shell', 'am', 'start', '-W', '-n', "$package/org.olcbox.app.AppActivity") | Out-Null
    $script:appPid = Invoke-CheckedAdb -AdbArguments @('shell', 'pidof', $package)
    Tap-TestUi 'Ping'
    $ui = Read-TestUi
    if ($null -eq $ui.SelectSingleNode('//node[@text="Connect to check latency"]')) {
        throw 'Inactive OpenFlux must explain that connecting is required for latency checks'
    }
    if ($null -ne $ui.SelectSingleNode('//node[@text="Offline"]')) { throw 'Inactive OpenFlux was mislabeled as offline' }
    $cores = Invoke-IsolatedAdb -AdbArguments @('shell', 'pidof', 'libopenflux.so', 'libsing-box.so', 'libxray.so')
    if ($cores.ExitCode -ne 1 -or $cores.Output.Trim()) { throw 'Inactive ping unexpectedly started a native core' }
    $checks.Add('Inactive OpenFlux ping shows connect-first without starting a document client')

    Test-OpenFluxShareUi
    $checks.Add('OpenFlux export requires consent; Cancel, QR and Copy work without a document client')

    if ($releaseApkPath) {
        Invoke-CheckedAdb -AdbArguments @('install', '-r', $releaseApkPath) -TimeoutSeconds 120 | Out-Null
        $releaseInstalled = $true
        $appPackage = Invoke-CheckedAdb -AdbArguments @('shell', 'dumpsys', 'package', $package)
        if ($appPackage -match 'flags=\[[^\]]*DEBUGGABLE') { throw 'Release APK remains debuggable' }
        Invoke-CheckedAdb -AdbArguments @('shell', 'am', 'start', '-W', '-n', "$package/org.olcbox.app.AppActivity") | Out-Null
        $script:appPid = Invoke-CheckedAdb -AdbArguments @('shell', 'pidof', $package)
        Tap-TestUi 'Ping'
        $ui = Read-TestUi
        if ($null -eq $ui.SelectSingleNode('//node[@text="Connect to check latency"]')) {
            throw 'Signed/minified release did not preserve profiles or launch the new ping UI'
        }
        $checks.Add('Signed release upgrade preserves profiles, launches and renders inactive ping state')
        Test-OpenFluxShareUi
        $checks.Add('Signed/minified release supports the OpenFlux consent and share flow')
    }
} catch {
    $failure = $_
    if ($installed) {
        try {
            Read-TestUi | Out-Null
            Invoke-CheckedAdb -AdbArguments @('shell', 'dumpsys', 'activity', 'activities') |
                Set-Content -LiteralPath (Join-Path $outputRoot 'failure-activities.txt') -Encoding utf8
            Invoke-CheckedAdb -AdbArguments @('logcat', '-d', '--pid', $script:appPid, '-v', 'brief') |
                Set-Content -LiteralPath (Join-Path $outputRoot 'failure-logcat.txt') -Encoding utf8
        } catch { Write-Warning 'Could not capture isolated test diagnostics' }
    }
} finally {
    if ($installed) {
        try {
            if (-not $releaseInstalled) {
                Invoke-CheckedAdb -AdbArguments @('shell', 'am', 'broadcast', '-n', $receiver, '-a', 'org.olcbox.app.DEBUG_STOP_VPN') | Out-Null
            }
            Invoke-CheckedAdb -AdbArguments @('shell', 'am', 'force-stop', $package) | Out-Null
            Invoke-CheckedAdb -AdbArguments @('uninstall', $package) | Out-Null
        } catch { if ($null -eq $failure) { $failure = $_ } }
    }
    if ($null -ne $ownership) {
        try { Stop-OwnedEmulator -Ownership $ownership } catch { if ($null -eq $failure) { $failure = $_ } }
    }
    [ordered]@{
        apk_sha256 = $testApkHash; release_apk_sha256 = $releaseApkHash
        synthetic_only = $true; checks = @($checks)
        passed = ($null -eq $failure); error = if ($failure) { $failure.Exception.Message } else { $null }
        error_stack = if ($failure) { $failure.ScriptStackTrace } else { $null }
    } | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $outputRoot 'result.json') -Encoding utf8
    Write-Output "NOTIFICATION_TEST_REPORT=$outputRoot"
}
if ($failure) { throw $failure }
$checks | ForEach-Object { Write-Output "PASS: $_" }
