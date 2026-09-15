#requires -Version 7.0
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot '..\Test-OpenFluxAndroid.ps1')
. (Join-Path $PSScriptRoot '..\Write-OpenFluxAndroidOwnership.ps1') -RunRoot 'synthetic-definitions-only' `
    -LauncherProcessId 810001 -QemuProcessId 810002 -FreshRunConfirmed
$realReadOwnership = ${function:Read-EmulatorOwnership}
$realWriteSummary = ${function:Write-AndroidFailureSummary}
$realGetEmulatorHostProcess = ${function:Get-EmulatorHostProcess}
$Connect = $true
$ConnectionSlotGranted = $true
$failures = [Collections.Generic.List[string]]::new()
$testCount = 0

function Assert-True {
    param([bool]$Condition, [string]$Message)
    if (-not $Condition) { throw $Message }
}

function Invoke-Captured {
    param([scriptblock]$Action)
    $output = [Collections.Generic.List[string]]::new()
    $failure = $null
    try { & $Action | ForEach-Object { $output.Add([string]$_) } } catch { $failure = $_ }
    return [pscustomobject]@{ Output = ($output -join "`n"); Failure = $failure }
}

function New-SyntheticState {
    $runRoot = Join-Path $repo ('.downloads\openflux\emulator-smoke\run-' + ('a' * 32))
    $avdDirectory = Join-Path $runRoot "avd\$ExpectedAvdName.avd"
    $processes = @(
        [pscustomobject]@{ role = 'launcher'; process_id = 810001; start_time_utc_ticks = '1000'
            executable = Join-Path $SdkRoot 'emulator\emulator.exe' },
        [pscustomobject]@{ role = 'qemu'; process_id = 810002; start_time_utc_ticks = '2000'
            executable = Join-Path $SdkRoot 'emulator\qemu\windows-x86_64\qemu-system-x86_64.exe' }
    )
    return @{
        Calls = [Collections.Generic.List[string]]::new()
        Builds = 0; PrivateTransfers = 0; OwnershipReads = 0; HealthChecks = 0
        BootPolls = 0; BootErrors = 0; BootStates = [Collections.Generic.Queue[string]]::new()
        Summary = $null; SummaryThrows = $false; ReadyLogs = $true
        Hash = $ExpectedApkSha256; OwnershipRejected = $false
        Properties = @{ 'ro.kernel.qemu' = '1'; 'ro.boot.qemu.avd_name' = $ExpectedAvdName
            'ro.product.cpu.abi' = 'x86_64'; 'ro.build.version.sdk' = '35'; 'sys.boot_completed' = '1' }
        ExistingApp = ''; ExistingAppExit = 1; ExistingCore = ''; ExistingCoreExit = 1
        AppPid = '3201'; NativePid = '3202'; BridgePid = '3203'; Uid = '2000'
        Connected = $false; PreexistingVpn = $false; StopFails = $false
        ForceStopThrows = $false; ClearThrows = $false
        KillExit = 0; ExitOnKill = $true; HostLive = $true; HostReadThrows = $false; ReusedPid = $false
        QemuParent = 810001; CommandSuffix = ''
        ExtraLogs = ''; DuringProbe = $null; AfterProbe = $null
        StartResult = 'Broadcast completed: result=-1, data="VPN_PREPARED"'
        Responses = "www.instagram.com_http=200`nwww.wikipedia.org_http=200"
        Ownership = [pscustomobject]@{ version = 1; fresh_userdata = $true; run_root = $runRoot
            avd_directory = $avdDirectory; avd_name = $ExpectedAvdName; emulator_serial = $EmulatorSerial
            processes = $processes }
    }
}

# All external boundaries are replaced before any test can invoke orchestration.
function Build-NetworkProbe { $state.Builds++; return 'synthetic-public-probe.jar' }
function Get-FileHash { return [pscustomobject]@{ Hash = $state.Hash } }
function Send-PrivateProfileBundle { $state.PrivateTransfers++ }
function Write-AndroidFailureSummary {
    param([string]$Stage, [System.Collections.IDictionary]$Flags, [string]$Logs, [string]$FailureMessage,
        [string]$AppProcessId, [Collections.Generic.HashSet[string]]$WarningCodes = $null)
    $state.Calls.Add('public summary saved')
    if ($state.SummaryThrows) { throw 'Synthetic diagnostic filesystem failure' }
    $state.Summary = New-AndroidPublicFailureSummary -Stage $Stage -Flags $Flags -Logs $Logs `
        -FailureMessage $FailureMessage -RetainedWarningCodes @($WarningCodes)
    Write-Output ('ANDROID_PUBLIC_FAILURE_SUMMARY=' + ($state.Summary | ConvertTo-Json -Depth 5 -Compress))
}
function Read-EmulatorOwnership {
    $state.OwnershipReads++
    if ($state.OwnershipRejected) { throw 'Synthetic ownership rejection' }
    return $state.Ownership
}
function Get-EmulatorHostProcess {
    param([int]$ProcessId)
    if ($state.HostReadThrows) { throw 'Synthetic host process lookup failure' }
    if (-not $state.HostLive) { return $null }
    $entry = $state.Ownership.processes | Where-Object { $_.process_id -eq $ProcessId }
    if ($null -eq $entry) { throw 'Unexpected process lookup in synthetic test' }
    return [pscustomobject]@{
        process_id = $ProcessId
        parent_process_id = $(if ($entry.role -eq 'qemu') { $state.QemuParent } else { 810000 })
        start_time_utc_ticks = $(if ($state.ReusedPid) { '9000' } else { $entry.start_time_utc_ticks })
        executable = $entry.executable
        command_line = ('"' + $entry.executable + '" -avd ' + $ExpectedAvdName + ' -port 5580 -datadir "' + $state.Ownership.avd_directory + '"' + $state.CommandSuffix)
    }
}
function Wait-TransportStopped {
    if ($state.StopFails) { throw 'Synthetic graceful stop timeout' }
    $state.Connected = $false
}
function Invoke-IsolatedAdb {
    param([string[]]$AdbArguments, [int]$TimeoutSeconds = 30, [byte[]]$InputBytes = $null,
        [scriptblock]$HealthCheck = $null)
    $command = $AdbArguments -join ' '
    $state.Calls.Add($command)
    $exitCode = 0
    $output = ''
    switch -Regex ($command) {
        '^shell getprop (.+)$' {
            $propertyName = $Matches[1]
            if ($propertyName -eq 'sys.boot_completed') {
                $state.BootPolls++
                if ($state.BootErrors -gt 0) { $state.BootErrors--; $exitCode = 1; $output = 'offline'; break }
                if ($state.BootStates.Count -gt 0) { $output = $state.BootStates.Dequeue(); break }
            }
            $output = $state.Properties[$propertyName]
            break
        }
        '^shell pm path ' { $output = $state.ExistingApp; $exitCode = $state.ExistingAppExit; break }
        '^shell pidof libopenflux.so libsing-box.so libxray.so$' {
            $output = $state.ExistingCore; $exitCode = $state.ExistingCoreExit; break
        }
        '^shell pidof app.unifiedvpn.local$' { $output = $state.AppPid; break }
        '^shell pidof libopenflux.so$' { $output = $state.NativePid; if (-not $output) { $exitCode = 1 }; break }
        '^shell pidof libsing-box.so$' { $output = $state.BridgePid; break }
        '^shell dumpsys connectivity$' {
            if ($state.Connected -or $state.PreexistingVpn) { $output = 'ni{VPN CONNECTED extra: VPN:app.unifiedvpn.local}' }
            break
        }
        '^shell id -u$' { $output = $state.Uid; break }
        '^logcat -d (?:-t 200 )?--pid ' {
            if ($state.ReadyLogs) { $output = "OpenFlux encrypted transport ready on 127.0.0.1:17000`nOpenFlux VPN tunnel established`n" }
            $output += $state.ExtraLogs
            break
        }
        '^shell am broadcast .*DEBUG_START_VPN$' { $state.Connected = $true; $output = $state.StartResult; break }
        '^shell am broadcast .*DEBUG_STOP_VPN$' { break }
        '^shell am force-stop ' { if ($state.ForceStopThrows) { throw 'Synthetic force-stop timeout' }; break }
        '^shell pm clear ' { if ($state.ClearThrows) { throw 'Synthetic pm-clear timeout' }; break }
        '^emu kill$' {
            $exitCode = $state.KillExit
            if ($state.ExitOnKill) { $state.HostLive = $false }
            break
        }
        '^get-state$' { $exitCode = 1; $output = 'offline'; break }
        '^shell env CLASSPATH=.* NetworkProbe ' {
            Assert-True ($null -ne $HealthCheck) 'HTTPS command has no continuous health callback'
            & $HealthCheck
            $state.HealthChecks++
            if ($null -ne $state.DuringProbe) { & $state.DuringProbe }
            & $HealthCheck
            $state.HealthChecks++
            if ($null -ne $state.AfterProbe) { & $state.AfterProbe }
            $output = $state.Responses
            break
        }
        '^(install |push |shell pm grant |shell appops set |shell cmd connectivity |shell svc wifi |shell input |shell am start |logcat -c$|logcat -b crash )' { break }
        default { throw 'Unexpected external command in synthetic test' }
    }
    return [pscustomobject]@{ ExitCode = $exitCode; Output = [string]$output }
}

function Assert-NoGuestMutation {
    Assert-True (-not ($state.Calls -match '^(install |push |emu kill$|shell (am|pm clear|pm grant|appops|cmd|svc|input) )')) 'Rejected guest was mutated'
    Assert-True ($state.PrivateTransfers -eq 0) 'Rejected guest received a private profile'
}

function Invoke-SyntheticCase {
    param([string]$Name, [scriptblock]$Test)
    $script:testCount++
    $state = New-SyntheticState
    try { & $Test; Write-Output ('PASS ' + $Name) } catch {
        $script:failures.Add($Name + ': ' + $_.Exception.Message)
        Write-Output ('FAIL ' + $Name)
    }
}

function Invoke-PublicOwnershipFixture {
    param([scriptblock]$Test)
    $syntheticRun = Join-Path $repo ('.downloads\openflux\emulator-smoke\run-' + [Guid]::NewGuid().ToString('N'))
    $OwnershipRecordPath = Join-Path $syntheticRun 'ownership.json'
    $state.Ownership.run_root = $syntheticRun
    $state.Ownership.avd_directory = Join-Path $syntheticRun "avd\$ExpectedAvdName.avd"
    New-Item -ItemType Directory -Path $state.Ownership.avd_directory -Force | Out-Null
    $created = [DateTime]::UtcNow.Ticks
    $state.Ownership.processes[0].start_time_utc_ticks = $created.ToString()
    $state.Ownership.processes[1].start_time_utc_ticks = ($created + 1).ToString()
    try { & $Test } finally {
        if (Test-Path -LiteralPath $OwnershipRecordPath) { Remove-Item -LiteralPath $OwnershipRecordPath -Force }
        Remove-Item -LiteralPath $state.Ownership.avd_directory
        Remove-Item -LiteralPath (Join-Path $syntheticRun 'avd')
        Remove-Item -LiteralPath $syntheticRun
    }
}

function Invoke-HostIdentityFixture {
    param([scriptblock]$Test)
    $lookup = @{ MissingField = 'ExecutablePath'; MissingOnlyAfterExit = $false; CimAbsent = $false
        CimThrows = $false; ProcessState = 'Absent'; ProcessChecks = 0; Disposals = 0 }
    function Get-CimInstance {
        param([string]$ClassName, [string]$Filter)
        if ($lookup.CimThrows) { throw 'Synthetic CIM lookup failure' }
        if ($lookup.CimAbsent) { return $null }
        Assert-True ($Filter -match '\AProcessId = (?<id>[1-9]\d*)\z') 'Unexpected synthetic CIM filter'
        $entry = $state.Ownership.processes | Where-Object { $_.process_id -eq [int]$Matches['id'] }
        Assert-True ($null -ne $entry) 'Unexpected synthetic CIM PID'
        $record = [pscustomobject]@{ ProcessId = $entry.process_id; ParentProcessId = 810001
            CreationDate = [DateTime]::new([long]$entry.start_time_utc_ticks, [DateTimeKind]::Utc)
            ExecutablePath = $entry.executable; CommandLine = 'synthetic-public-command' }
        if ($lookup.MissingField -and (-not $lookup.MissingOnlyAfterExit -or -not $state.HostLive)) {
            $record.($lookup.MissingField) = $null
        }
        return $record
    }
    function Get-Process {
        param([int]$Id)
        $lookup.ProcessChecks++
        switch ($lookup.ProcessState) {
            'Absent' {
                throw [Management.Automation.ErrorRecord]::new([ArgumentException]::new('Synthetic absent process'),
                    'NoProcessFoundForGivenId,Microsoft.PowerShell.Commands.GetProcessCommand',
                    [Management.Automation.ErrorCategory]::ObjectNotFound, $Id)
            }
            'Denied' { throw [UnauthorizedAccessException]::new('Synthetic denied process lookup') }
            'OtherNotFound' {
                throw [Management.Automation.ErrorRecord]::new([ArgumentException]::new('Synthetic unrelated lookup error'),
                    'SyntheticOtherObjectNotFound', [Management.Automation.ErrorCategory]::ObjectNotFound, $Id)
            }
            'Null' { return $null }
        }
        $process = [pscustomobject]@{
            Id = $(if ($lookup.ProcessState -eq 'WrongPid') { $Id + 1 } else { $Id })
            HasExited = $lookup.ProcessState -in @('Exited', 'WrongPid')
        }
        $process | Add-Member ScriptMethod Dispose { $lookup.Disposals++ }
        return $process
    }
    function Get-EmulatorHostProcess {
        param([int]$ProcessId)
        & $realGetEmulatorHostProcess -ProcessId $ProcessId
    }
    & $Test
}

Invoke-SyntheticCase 'prepare has no ADB, ownership, or private-data access' {
    Invoke-OpenFluxAndroidTest -Prepare | Out-Null
    Assert-True ($state.Builds -eq 1 -and $state.Calls.Count -eq 0 -and $state.OwnershipReads -eq 0 -and $state.PrivateTransfers -eq 0) 'Prepare crossed a live boundary'
}
Invoke-SyntheticCase 'missing connection slot rejects before external work' {
    $ConnectionSlotGranted = $false
    $result = Invoke-Captured { Invoke-OpenFluxAndroidTest }
    Assert-True ($null -ne $result.Failure -and $state.Builds -eq 0) 'Missing slot was accepted'
    Assert-NoGuestMutation
}
Invoke-SyntheticCase 'wrong APK cannot shut down or clear a guest' {
    $state.Hash = '0' * 64
    $result = Invoke-Captured { Invoke-OpenFluxAndroidTest }
    Assert-True ($null -ne $result.Failure -and $state.OwnershipReads -eq 0) 'Wrong APK was accepted'
    Assert-NoGuestMutation
}
Invoke-SyntheticCase 'ownership rejection cannot shut down or clear a guest' {
    $state.OwnershipRejected = $true
    $result = Invoke-Captured { Invoke-OpenFluxAndroidTest }
    Assert-True ($null -ne $result.Failure) 'Ownership rejection was ignored'
    Assert-NoGuestMutation
}
Invoke-SyntheticCase 'unexpected emulator identity cannot trigger cleanup' {
    $state.Properties['ro.boot.qemu.avd_name'] = 'Normal_User_AVD'
    $result = Invoke-Captured { Invoke-OpenFluxAndroidTest }
    Assert-True ($null -ne $result.Failure) 'Unexpected AVD was accepted'
    Assert-NoGuestMutation
}
Invoke-SyntheticCase 'existing installation cannot trigger cleanup' {
    $state.ExistingApp = 'package:/public/example.apk'
    $state.ExistingAppExit = 0
    $result = Invoke-Captured { Invoke-OpenFluxAndroidTest }
    Assert-True ($null -ne $result.Failure) 'Existing installation was accepted'
    Assert-NoGuestMutation
}
Invoke-SyntheticCase 'ADB installation lookup failure is not a fresh guest' {
    $state.ExistingApp = 'error: device offline'
    $result = Invoke-Captured { Invoke-OpenFluxAndroidTest }
    Assert-True ($null -ne $result.Failure) 'ADB failure was treated as freshness'
    Assert-NoGuestMutation
}
Invoke-SyntheticCase 'existing transport cannot trigger cleanup' {
    $state.ExistingCore = '7777'; $state.ExistingCoreExit = 0
    $result = Invoke-Captured { Invoke-OpenFluxAndroidTest }
    Assert-True ($null -ne $result.Failure) 'Existing transport was accepted'
    Assert-NoGuestMutation
}
Invoke-SyntheticCase 'existing VPN cannot trigger cleanup' {
    $state.PreexistingVpn = $true
    $result = Invoke-Captured { Invoke-OpenFluxAndroidTest }
    Assert-True ($null -ne $result.Failure) 'Existing VPN was accepted'
    Assert-NoGuestMutation
}
Invoke-SyntheticCase 'synthetic uninterrupted session completes stop and owned exit' {
    $result = Invoke-Captured { Invoke-OpenFluxAndroidTest }
    Assert-True ($null -eq $result.Failure) ('Synthetic session failed: ' + $result.Failure)
    Assert-True ($result.Output -match 'ANDROID_APP_VPN_HTTPS_AND_STOP_PASSED=true' -and $result.Output -match 'OWNED_TEST_EMULATOR_STOPPED=true') 'Successful lifecycle evidence missing'
    Assert-True ($state.HealthChecks -eq 2 -and $state.PrivateTransfers -eq 1) 'Synthetic probe boundary not exercised'
}
foreach ($unpreparedResult in @('', 'Broadcast completed: result=0',
        'Broadcast completed: result=0, data="VPN_PERMISSION_REQUIRED"',
        'Broadcast completed: result=-1, data="VPN_PREPARED_OTHER"')) {
    Invoke-SyntheticCase ("unprepared VPN response is rejected: $unpreparedResult") {
        $state.StartResult = $unpreparedResult
        $result = Invoke-Captured { Invoke-OpenFluxAndroidTest }
        Assert-True ($null -ne $result.Failure) 'Unprepared VPN was accepted'
        Assert-True ($state.Summary.error_codes -contains 'VPN_NOT_PREPARED') 'Missing preparation was not diagnosed'
        Assert-True ($state.HealthChecks -eq 0) 'Unprepared VPN attempted a traffic probe'
        Assert-True ($state.Calls -contains "shell pm clear $package") 'Private-data cleanup was skipped'
        Assert-True ($state.Calls -contains 'emu kill') 'Owned shutdown was skipped'
    }
}
foreach ($failureField in @('ForceStopThrows', 'ClearThrows', 'StopFails')) {
    Invoke-SyntheticCase ("$failureField still attempts remaining cleanup and owned shutdown") {
        $state[$failureField] = $true
        $result = Invoke-Captured { Invoke-OpenFluxAndroidTest }
        Assert-True ($null -ne $result.Failure) 'Cleanup failure was hidden'
        Assert-True ($state.Calls -contains "shell pm clear $package") 'Private-data cleanup was skipped'
        Assert-True ($state.Calls -contains 'emu kill') 'Owned shutdown was skipped'
        Assert-True ($result.Output -notmatch 'ANDROID_APP_VPN_HTTPS_AND_STOP_PASSED=true') 'Cleanup failure reported pass'
    }
}
foreach ($killExit in @(0, 1)) {
    Invoke-SyntheticCase ("ADB shutdown exit $killExit cannot prove a live host process stopped") {
        $state.KillExit = $killExit; $state.ExitOnKill = $false
        $result = Invoke-Captured { Stop-OwnedEmulator -Ownership $state.Ownership -TimeoutSeconds 0 }
        Assert-True ($null -ne $result.Failure -and $result.Output -notmatch 'STOPPED=true') 'Live host process falsely reported stopped'
        Assert-True (-not ($state.Calls -contains 'get-state')) 'Shutdown relied on ADB transport state'
    }
}
Invoke-SyntheticCase 'host lookup failure cannot report shutdown' {
    $state.HostReadThrows = $true
    $result = Invoke-Captured { Stop-OwnedEmulator -Ownership $state.Ownership -TimeoutSeconds 0 }
    Assert-True ($null -ne $result.Failure -and $result.Output -notmatch 'STOPPED=true') 'Unverifiable host exit was accepted'
}
Invoke-SyntheticCase 'complete CIM identity does not need an independent process lookup' {
    Invoke-HostIdentityFixture {
        $lookup.MissingField = ''
        $current = Get-EmulatorHostProcess -ProcessId 810002
        Assert-True ($current.start_time_utc_ticks -eq '2000' -and $lookup.ProcessChecks -eq 0) 'Complete CIM identity was altered'
    }
}
Invoke-SyntheticCase 'absent CIM process remains absent without a fallback lookup' {
    Invoke-HostIdentityFixture {
        $lookup.CimAbsent = $true
        Assert-True ($null -eq (Get-EmulatorHostProcess -ProcessId 810002) -and $lookup.ProcessChecks -eq 0) 'Absent CIM process was changed'
    }
}
foreach ($field in @('ExecutablePath', 'CreationDate')) {
    Invoke-SyntheticCase ("incomplete CIM $field accepts only independently absent PID") {
        Invoke-HostIdentityFixture {
            $lookup.MissingField = $field
            Assert-True ($null -eq (Get-EmulatorHostProcess -ProcessId 810002) -and $lookup.ProcessChecks -eq 1) 'Independent process absence was not accepted'
        }
    }
}
Invoke-SyntheticCase 'incomplete CIM identity accepts an independently exited process and disposes its handle' {
    Invoke-HostIdentityFixture {
        $lookup.ProcessState = 'Exited'
        Assert-True ($null -eq (Get-EmulatorHostProcess -ProcessId 810002) -and $lookup.Disposals -eq 1) 'Exited process proof or handle disposal failed'
    }
}
foreach ($processState in @('Live', 'Denied', 'OtherNotFound', 'Null', 'WrongPid')) {
    Invoke-SyntheticCase ("incomplete CIM identity stays fail closed for $processState independent lookup") {
        Invoke-HostIdentityFixture {
            $lookup.ProcessState = $processState
            $result = Invoke-Captured { Stop-OwnedEmulator -Ownership $state.Ownership -TimeoutSeconds 0 }
            Assert-True ($null -ne $result.Failure -and $result.Output -notmatch 'STOPPED=true' -and
                -not ($state.Calls -contains 'emu kill')) 'Unverified process identity authorized shutdown or exit success'
            Assert-True ($result.Failure.Exception.Message -eq 'Cannot verify the recorded emulator host process identity') 'Lookup failure escaped its fixed diagnostic'
            if ($processState -in @('Live', 'WrongPid')) { Assert-True ($lookup.Disposals -eq 1) 'Lookup process handle was not disposed' }
        }
    }
}
Invoke-SyntheticCase 'CIM query failure cannot be replaced with a secondary absence result' {
    Invoke-HostIdentityFixture {
        $lookup.CimThrows = $true
        $result = Invoke-Captured { Get-EmulatorHostProcess -ProcessId 810002 }
        Assert-True ($null -ne $result.Failure -and $lookup.ProcessChecks -eq 0) 'CIM failure bypassed identity checks'
    }
}
Invoke-SyntheticCase 'owned cleanup accepts incomplete CIM exit records only after independent exit proof' {
    Invoke-HostIdentityFixture {
        $lookup.MissingOnlyAfterExit = $true
        $result = Invoke-Captured { Stop-OwnedEmulator -Ownership $state.Ownership -TimeoutSeconds 0 }
        Assert-True ($null -eq $result.Failure -and $result.Output -match 'STOPPED=true' -and
            $state.Calls -contains 'emu kill' -and $lookup.ProcessChecks -eq 2) 'CIM exit race prevented verified owned shutdown'
    }
}
Invoke-SyntheticCase 'PID reuse cannot cause an ADB kill of an unrelated guest' {
    $state.ReusedPid = $true
    $result = Invoke-Captured { Stop-OwnedEmulator -Ownership $state.Ownership -TimeoutSeconds 0 }
    Assert-True ($null -eq $result.Failure -and $result.Output -match 'STOPPED=true') 'Original owned processes were not recognized as exited'
    Assert-True (-not ($state.Calls -contains 'emu kill')) 'Reused host PID triggered guest shutdown'
}
foreach ($damage in @(
    @{ Name = 'native PID replacement'; Action = { $state.NativePid = '4002' } },
    @{ Name = 'native death'; Action = { $state.NativePid = '' } },
    @{ Name = 'app restart'; Action = { $state.AppPid = '4001' } },
    @{ Name = 'DNS bridge replacement'; Action = { $state.BridgePid = '4003' } },
    @{ Name = 'TUN disappearance'; Action = { $state.Connected = $false } },
    @{ Name = 'recovery log'; Action = { $state.ExtraLogs = 'OpenFlux core stopped; reconnecting transport' } },
    @{ Name = 'failure log'; Action = { $state.ExtraLogs = 'VPN start failed safely' } },
    @{ Name = 'duplicate ready session'; Action = { $state.ExtraLogs = 'OpenFlux VPN tunnel established' } }
)) {
    Invoke-SyntheticCase ($damage.Name + ' during HTTPS cannot report a successful VPN probe') {
        $state.DuringProbe = $damage.Action
        $result = Invoke-Captured { Invoke-OpenFluxAndroidTest }
        Assert-True ($null -ne $result.Failure) 'Broken transport accepted synthetic HTTPS 200'
        Assert-True ($result.Output -notmatch 'ANDROID_APP_VPN_HTTPS_AND_STOP_PASSED=true|www.instagram.com_http=200') 'HTTPS evidence was emitted after loss'
        Assert-True ($state.Calls -contains 'emu kill') 'Failed probe skipped owned shutdown'
    }
}
Invoke-SyntheticCase 'post-probe native loss is checked before accepting HTTPS results' {
    $state.AfterProbe = { $state.NativePid = '' }
    $result = Invoke-Captured { Invoke-OpenFluxAndroidTest }
    Assert-True ($null -ne $result.Failure -and $result.Output -notmatch 'www.instagram.com_http=200') 'Post-probe loss accepted'
}
Invoke-SyntheticCase 'excluded app UID cannot be used for HTTPS evidence' {
    $state.Uid = '10123'
    $result = Invoke-Captured { Invoke-OpenFluxAndroidTest }
    Assert-True ($null -ne $result.Failure -and $state.HealthChecks -eq 0) 'Excluded app UID was accepted'
}
Invoke-SyntheticCase 'duplicate HTTPS target does not satisfy both targets' {
    $state.Responses = "www.instagram.com_http=200`nwww.instagram.com_http=200"
    $result = Invoke-Captured { Invoke-OpenFluxAndroidTest }
    Assert-True ($null -ne $result.Failure -and $result.Output -notmatch 'ANDROID_APP_VPN_HTTPS_AND_STOP_PASSED=true') 'Duplicate target accepted'
}
Invoke-SyntheticCase 'command monitor checks while pending and after completion' {
    $fake = [pscustomobject]@{ Waits = 0 }
    $fake | Add-Member ScriptMethod WaitForExit { param([int]$Milliseconds) $this.Waits++; return $this.Waits -ge 2 }
    $checks = [Collections.Generic.List[int]]::new()
    Wait-IsolatedCommand -Process $fake -TimeoutSeconds 3 -HealthCheck { $checks.Add($fake.Waits) }
    Assert-True (($checks -join ',') -eq '0,1,2') 'Monitoring missed pending or completed command state'
}
Invoke-SyntheticCase 'command monitor aborts immediately on health loss' {
    $fake = [pscustomobject]@{ Waits = 0 }
    $fake | Add-Member ScriptMethod WaitForExit { param([int]$Milliseconds) $this.Waits++; return $false }
    $result = Invoke-Captured { Wait-IsolatedCommand -Process $fake -TimeoutSeconds 3 -HealthCheck { throw 'Synthetic transport loss' } }
    Assert-True ($null -ne $result.Failure -and $fake.Waits -eq 0) 'Health loss did not interrupt command monitoring'
}
Invoke-SyntheticCase 'command monitor respects its total timeout' {
    $fake = [pscustomobject]@{ Waits = 0 }
    $fake | Add-Member ScriptMethod WaitForExit { param([int]$Milliseconds) $this.Waits++; return $false }
    $result = Invoke-Captured { Wait-IsolatedCommand -Process $fake -TimeoutSeconds 0 }
    Assert-True ($null -ne $result.Failure -and $fake.Waits -eq 0) 'Command timeout was ignored'
}
Invoke-SyntheticCase 'public ownership record checks process creation time and isolated paths' {
    $syntheticRun = Join-Path $repo ('.downloads\openflux\emulator-smoke\run-' + [Guid]::NewGuid().ToString('N'))
    $OwnershipRecordPath = Join-Path $syntheticRun 'ownership.json'
    $state.Ownership.run_root = $syntheticRun
    $state.Ownership.avd_directory = Join-Path $syntheticRun "avd\$ExpectedAvdName.avd"
    New-Item -ItemType Directory -Path $state.Ownership.avd_directory -Force | Out-Null
    try {
        $state.Ownership | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $OwnershipRecordPath -Encoding UTF8
        $record = & $realReadOwnership
        Assert-True ($record.processes.Count -eq 2) 'Valid public synthetic ownership rejected'
        $state.ReusedPid = $true
        $result = Invoke-Captured { & $realReadOwnership }
        Assert-True ($null -ne $result.Failure) 'Stale host identity was accepted'
        $state.ReusedPid = $false
        $state.Ownership.fresh_userdata = 'true'
        $state.Ownership | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $OwnershipRecordPath -Encoding UTF8
        $result = Invoke-Captured { & $realReadOwnership }
        Assert-True ($null -ne $result.Failure) 'Nonboolean freshness declaration was accepted'
        $OwnershipRecordPath = Join-Path $repo 'ownership.json'
        $result = Invoke-Captured { & $realReadOwnership }
        Assert-True ($null -ne $result.Failure) 'Nonisolated ownership path was accepted'
    } finally {
        # These are only the exact public fixture file and empty directory created above.
        Remove-Item -LiteralPath (Join-Path $syntheticRun 'ownership.json') -Force
        Remove-Item -LiteralPath $state.Ownership.avd_directory
        Remove-Item -LiteralPath (Join-Path $syntheticRun 'avd')
        Remove-Item -LiteralPath $syntheticRun
    }
}
foreach ($qemuName in @('qemu-system-x86_64.exe', 'qemu-system-x86_64-headless.exe')) {
    Invoke-SyntheticCase ("exact SDK $qemuName accepted for recovered ownership") {
        Invoke-PublicOwnershipFixture {
            $state.Ownership.processes[1].executable = Join-Path $SdkRoot "emulator\qemu\windows-x86_64\$qemuName"
            Write-OpenFluxEmulatorOwnership -IsolatedRunRoot $syntheticRun -LauncherId 810001 -QemuId 810002 -Confirmed | Out-Null
            $record = & $realReadOwnership
            Assert-True ($record.processes[1].executable -eq $state.Ownership.processes[1].executable) 'Exact SDK executable was not recorded'
            Assert-True ($state.Calls.Count -eq 0) 'Ownership recovery invoked ADB'
        }
    }
}
Invoke-SyntheticCase 'ownership recovery requires explicit fresh confirmation' {
    $result = Invoke-Captured { Write-OpenFluxEmulatorOwnership -IsolatedRunRoot $repo -LauncherId 810001 -QemuId 810002 }
    Assert-True ($null -ne $result.Failure) 'Ownership recovery accepted missing fresh confirmation'
}
Invoke-SyntheticCase 'ownership recovery and reader reject non-SDK QEMU path' {
    Invoke-PublicOwnershipFixture {
        $state.Ownership.processes[1].executable = Join-Path $syntheticRun 'qemu-system-x86_64-headless.exe'
        $result = Invoke-Captured { Write-OpenFluxEmulatorOwnership -IsolatedRunRoot $syntheticRun -LauncherId 810001 -QemuId 810002 -Confirmed }
        Assert-True ($null -ne $result.Failure -and -not (Test-Path -LiteralPath $OwnershipRecordPath)) 'Unapproved executable was recorded'
        $state.Ownership | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $OwnershipRecordPath -Encoding UTF8
        $result = Invoke-Captured { & $realReadOwnership }
        Assert-True ($null -ne $result.Failure) 'Reader accepted an arbitrary QEMU path'
    }
}
Invoke-SyntheticCase 'ownership recovery and reader reject an unrelated QEMU parent' {
    Invoke-PublicOwnershipFixture {
        $state.QemuParent = 810099
        $result = Invoke-Captured { Write-OpenFluxEmulatorOwnership -IsolatedRunRoot $syntheticRun -LauncherId 810001 -QemuId 810002 -Confirmed }
        Assert-True ($null -ne $result.Failure -and -not (Test-Path -LiteralPath $OwnershipRecordPath)) 'Unrelated QEMU child was recorded'
        $state.Ownership | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $OwnershipRecordPath -Encoding UTF8
        $result = Invoke-Captured { & $realReadOwnership }
        Assert-True ($null -ne $result.Failure) 'Reader accepted an unrelated QEMU parent'
    }
}
Invoke-SyntheticCase 'ownership recovery and reader reject duplicate launch arguments' {
    Invoke-PublicOwnershipFixture {
        $state.CommandSuffix = ' -datadir C:\public-unrelated-avd'
        $result = Invoke-Captured { Write-OpenFluxEmulatorOwnership -IsolatedRunRoot $syntheticRun -LauncherId 810001 -QemuId 810002 -Confirmed }
        Assert-True ($null -ne $result.Failure -and -not (Test-Path -LiteralPath $OwnershipRecordPath)) 'Ambiguous AVD arguments were recorded'
        $state.Ownership | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $OwnershipRecordPath -Encoding UTF8
        $result = Invoke-Captured { & $realReadOwnership }
        Assert-True ($null -ne $result.Failure) 'Reader accepted ambiguous AVD arguments'
    }
}
Invoke-SyntheticCase 'ownership recovery cannot replace an existing record' {
    Invoke-PublicOwnershipFixture {
        Write-OpenFluxEmulatorOwnership -IsolatedRunRoot $syntheticRun -LauncherId 810001 -QemuId 810002 -Confirmed | Out-Null
        $before = [IO.File]::ReadAllText($OwnershipRecordPath)
        $result = Invoke-Captured { Write-OpenFluxEmulatorOwnership -IsolatedRunRoot $syntheticRun -LauncherId 810001 -QemuId 810002 -Confirmed }
        Assert-True ($null -ne $result.Failure -and [IO.File]::ReadAllText($OwnershipRecordPath) -eq $before) 'Existing ownership was overwritten'
    }
}
Invoke-SyntheticCase 'ownership recovery rejects a process older than the fresh run' {
    Invoke-PublicOwnershipFixture {
        $state.Ownership.processes[0].start_time_utc_ticks = [DateTime]::UtcNow.AddHours(-3).Ticks.ToString()
        $result = Invoke-Captured { Write-OpenFluxEmulatorOwnership -IsolatedRunRoot $syntheticRun -LauncherId 810001 -QemuId 810002 -Confirmed }
        Assert-True ($null -ne $result.Failure -and -not (Test-Path -LiteralPath $OwnershipRecordPath)) 'Old process was recorded as fresh'
    }
}
Invoke-SyntheticCase 'boot readiness waits through early boot after ownership is accepted' {
    $state.BootStates.Enqueue('0')
    $state.BootStates.Enqueue('1')
    $flags = New-AndroidStageFlags
    Confirm-FreshOwnedEmulator -Flags $flags | Out-Null
    Assert-True ($state.BootPolls -eq 2 -and $flags.ownership_validated -and $flags.boot_completed -and $flags.fresh_guest_accepted) 'Early boot was not awaited after ownership'
    Assert-NoGuestMutation
}
Invoke-SyntheticCase 'boot wait tolerates one bounded ADB-not-ready response' {
    $state.BootErrors = 1
    Wait-OwnedGuestBoot -Ownership $state.Ownership -TimeoutSeconds 3
    Assert-True ($state.BootPolls -eq 2) 'Transient boot ADB failure was not retried'
    Assert-NoGuestMutation
}
Invoke-SyntheticCase 'boot timeout is bounded and cannot authorize guest cleanup' {
    $state.Properties['sys.boot_completed'] = '0'
    $BootTimeoutSeconds = 0
    $result = Invoke-Captured { Invoke-OpenFluxAndroidTest }
    Assert-True ($null -ne $result.Failure -and $state.BootPolls -eq 1) 'Boot timeout was not bounded'
    Assert-True ($state.Summary.error_codes -contains 'BOOT_TIMEOUT') 'Boot timeout diagnostic missing'
    Assert-NoGuestMutation
}
Invoke-SyntheticCase 'boot polling never starts when host ownership is rejected' {
    $state.OwnershipRejected = $true
    Invoke-Captured { Invoke-OpenFluxAndroidTest } | Out-Null
    Assert-True ($state.BootPolls -eq 0) 'Rejected host ownership still queried guest boot'
    Assert-NoGuestMutation
}
Invoke-SyntheticCase 'boot wait stops if recorded host processes exit' {
    $state.HostLive = $false
    $result = Invoke-Captured { Wait-OwnedGuestBoot -Ownership $state.Ownership -TimeoutSeconds 3 }
    Assert-True ($null -ne $result.Failure -and $state.BootPolls -eq 0) 'Boot wait queried an exited guest'
    Assert-NoGuestMutation
}
Invoke-SyntheticCase 'startup failure summary precedes stop and preserves fixed stage flags' {
    $state.ReadyLogs = $false
    $state.ExtraLogs = 'Starting OpenFlux profile; Bound OpenFlux transport to Wi-Fi; OpenFlux: OPENFLUX_FATAL: cannot initialize Yandex Docs transport; OpenFlux start failed: OpenFlux exited before the encrypted transport became ready'
    $result = Invoke-Captured { Invoke-OpenFluxAndroidTest }
    $summaryIndex = $state.Calls.IndexOf('public summary saved')
    $stopIndex = $state.Calls.IndexOf("shell am broadcast -n $receiver -a org.olcbox.app.DEBUG_STOP_VPN")
    Assert-True ($null -ne $result.Failure -and $summaryIndex -ge 0 -and $stopIndex -gt $summaryIndex) 'Startup reason was not captured before cleanup'
    Assert-True ($state.Summary.stage -eq 'vpn_start' -and $state.Summary.flags.profile_transferred -and $state.Summary.flags.vpn_start_requested -and -not $state.Summary.flags.authenticated_ready) 'Startup diagnostic stage flags were inaccurate'
    Assert-True ($state.Summary.error_codes -contains 'YANDEX_TRANSPORT_INIT_FAILED' -and $state.Summary.error_codes -contains 'NATIVE_EXIT_BEFORE_READY') 'Native startup reasons were dropped'
}
Invoke-SyntheticCase 'diagnostic persistence failure cannot skip owned cleanup' {
    $state.SummaryThrows = $true
    $state.ExtraLogs = 'OpenFlux start failed: synthetic public failure'
    $result = Invoke-Captured { Invoke-OpenFluxAndroidTest }
    Assert-True ($null -ne $result.Failure -and $state.Calls -contains 'emu kill' -and -not $state.HostLive) 'Diagnostic failure skipped guest shutdown'
}
foreach ($known in @(
    @{ Literal = 'cannot initialize OpenFlux network stack'; Code = 'NETWORK_STACK_INIT_FAILED' },
    @{ Literal = 'cannot initialize mandatory AES-256-GCM encryption'; Code = 'ENCRYPTION_INIT_FAILED' },
    @{ Literal = 'encrypted peer handshake timed out; check the server, document access, and matching key'; Code = 'PEER_HANDSHAKE_TIMEOUT' },
    @{ Literal = 'cannot bind the configured loopback SOCKS port'; Code = 'SOCKS_BIND_FAILED' },
    @{ Literal = 'OpenFlux executable is missing for this Android ABI'; Code = 'NATIVE_EXECUTABLE_MISSING' },
    @{ Literal = 'TUN DNS bridge start failed'; Code = 'TUN_BRIDGE_START_FAILED' }
)) {
    Invoke-SyntheticCase ('fixed diagnostic literal maps to ' + $known.Code) {
        $summary = New-AndroidPublicFailureSummary -Stage 'vpn_start' -Flags (New-AndroidStageFlags) -Logs $known.Literal
        Assert-True ($summary.error_codes -contains $known.Code) 'Known fixed diagnostic literal was not classified'
    }
}
foreach ($known in @(
    @{ Literal = 'TUN bridge log reader failed:'; Code = 'TUN_BRIDGE_LOG_READER_FAILED'; Level = 'D' },
    @{ Literal = 'VPN start failed safely:'; Code = 'VPN_START_FAILED_SAFELY'; Level = 'D' },
    @{ Literal = 'Failed to load native tun2socks libraries'; Code = 'TUN2SOCKS_NATIVE_LOAD_FAILED'; Level = 'E' }
)) {
    Invoke-SyntheticCase ('fixed startup diagnostic remains fatal without exposing its suffix: ' + $known.Code) {
        $sentinels = @('https://docs.yandex.ru/i/SENTINEL_PRIVATE_DIAGNOSTIC_586', ('c7e14602' * 8),
            'SENTINEL_PRIVATE_READER_ERROR_587')
        $state.ReadyLogs = $false
        $state.ExtraLogs = "$($known.Level)/OlcboxVpnService(3201): $($known.Literal) " + ($sentinels -join ' ')
        $directSummary = New-AndroidPublicFailureSummary -Stage 'vpn_start' -Flags (New-AndroidStageFlags) -Logs $state.ExtraLogs
        Assert-True ($directSummary.error_codes.Count -eq 1 -and $directSummary.error_codes -contains $known.Code) 'Startup diagnostic was not classified from its log prefix'
        $result = Invoke-Captured { Invoke-OpenFluxAndroidTest }
        Assert-True ($null -ne $result.Failure -and $state.HealthChecks -eq 0) 'Classified startup failure reached the HTTPS probe'
        Assert-True ($state.Summary.error_codes -contains $known.Code -and
            $state.Summary.error_codes -contains 'TRANSPORT_HEALTH_REJECTED') 'Classification removed the fatal health rejection'
        Assert-True (-not $result.Output.Contains('ANDROID_APP_VPN_HTTPS_AND_STOP_PASSED=true')) 'Classified failure reported a successful session'
        $json = $directSummary | ConvertTo-Json -Depth 5 -Compress
        foreach ($secret in $sentinels) {
            Assert-True (-not $json.Contains($secret) -and -not $result.Output.Contains($secret) -and
                -not $result.Failure.Exception.Message.Contains($secret)) 'A private diagnostic suffix escaped'
        }
    }
}
foreach ($readerFailure in @(
    @{ Suffix = 'Stream closed'; Reason = 'STREAM_CLOSED' },
    @{ Suffix = 'Pipe is closed'; Reason = 'STREAM_CLOSED' },
    @{ Suffix = 'read failed: EINTR (Interrupted system call)'; Reason = 'INTERRUPTED' },
    @{ Suffix = 'read interrupted'; Reason = 'INTERRUPTED' },
    @{ Suffix = 'java.io.InterruptedIOException'; Reason = 'INTERRUPTED' },
    @{ Suffix = 'read failed: EAGAIN (Resource temporarily unavailable)'; Reason = 'WOULD_BLOCK' },
    @{ Suffix = 'EWOULDBLOCK'; Reason = 'WOULD_BLOCK' },
    @{ Suffix = 'operation would block'; Reason = 'WOULD_BLOCK' },
    @{ Suffix = 'read failed: EBADF (Bad file descriptor)'; Reason = 'BAD_FILE_DESCRIPTOR' },
    @{ Suffix = 'Bad file descriptor'; Reason = 'BAD_FILE_DESCRIPTOR' },
    @{ Suffix = 'Underlying input stream returned zero bytes'; Reason = 'ZERO_BYTE_READ' },
    @{ Suffix = 'java.io.IOException'; Reason = 'IO_FAILURE' },
    @{ Suffix = 'read failed: EIO (I/O error)'; Reason = 'IO_FAILURE' },
    @{ Suffix = 'Input/output error'; Reason = 'IO_FAILURE' },
    @{ Suffix = 'unknown reader condition'; Reason = 'UNCLASSIFIED' }
)) {
    Invoke-SyntheticCase ('TUN reader suffix stays private and fatal with fixed reason ' + $readerFailure.Reason) {
        $sentinels = @('https://docs.yandex.ru/i/SENTINEL_PRIVATE_READER_DOCUMENT_589',
            ('c7e14602' * 8), 'SENTINEL_PRIVATE_READER_SUFFIX_590')
        $state.ReadyLogs = $false
        $state.ExtraLogs = 'D/OlcboxVpnService( 3201): TUN bridge log reader failed: ' +
            $readerFailure.Suffix + ' ' + ($sentinels -join ' ')
        $result = Invoke-Captured { Invoke-OpenFluxAndroidTest }
        Assert-True ($null -ne $result.Failure -and $state.HealthChecks -eq 0 -and
            -not $result.Output.Contains('ANDROID_APP_VPN_HTTPS_AND_STOP_PASSED=true')) 'Reader reason exempted a fatal startup failure'
        Assert-True ($state.Summary.tun_bridge_log_reader_reason_codes.Count -eq 1 -and
            $state.Summary.tun_bridge_log_reader_reason_codes[0] -ceq $readerFailure.Reason) 'Reader suffix did not map to its fixed reason'
        Assert-True ($state.Summary.error_codes -contains 'TUN_BRIDGE_LOG_READER_FAILED' -and
            $state.Summary.error_codes -contains 'TRANSPORT_HEALTH_REJECTED') 'Reader reason removed fatal classification'
        foreach ($secret in $sentinels) {
            Assert-True (-not $result.Output.Contains($secret) -and
                -not $result.Failure.Exception.Message.Contains($secret)) 'A reader reason exposed its private suffix'
        }
    }
}
Invoke-SyntheticCase 'TUN reader reasons are deduplicated and cannot cross diagnostic lines' {
    $logs = "TUN bridge log reader failed: Stream closed`nTUN bridge log reader failed: Stream closed`n" +
        "TUN bridge log reader failed: unknown`nEINTR EAGAIN EBADF IOException"
    $summary = New-AndroidPublicFailureSummary -Stage 'vpn_start' -Flags (New-AndroidStageFlags) -Logs $logs
    Assert-True (($summary.tun_bridge_log_reader_reason_codes -join ',') -ceq 'STREAM_CLOSED,UNCLASSIFIED') 'Reader reason escaped its line boundary or was duplicated'
}
Invoke-SyntheticCase 'unrelated failure text cannot acquire a TUN reader reason' {
    $logs = "EINTR IOException`nD/OtherTag(3201): TUN bridge log reader failed: Stream closed`n" +
        'prefix TUN bridge log reader failed: Bad file descriptor'
    $summary = New-AndroidPublicFailureSummary -Stage 'vpn_start' -Flags (New-AndroidStageFlags) -Logs $logs
    Assert-True ($summary.tun_bridge_log_reader_reason_codes.Count -eq 0) 'Unrelated diagnostic text acquired a reader reason'
}
Invoke-SyntheticCase 'unknown failure remains rejected without exposing its diagnostic suffix' {
    $secret = 'SENTINEL_UNKNOWN_PRIVATE_FAILURE_588'
    $unknownLine = 'D/OlcboxVpnService(3201): Unrecognized subsystem failed: ' + $secret
    $directSummary = New-AndroidPublicFailureSummary -Stage 'vpn_start' -Flags (New-AndroidStageFlags) -Logs $unknownLine
    Assert-True ($directSummary.error_codes.Count -eq 1 -and
        $directSummary.error_codes -contains 'UNCLASSIFIED_FAILURE') 'Unknown condition was assigned a known diagnostic code'
    $state.ReadyLogs = $false
    $state.ExtraLogs = "OpenFlux: Process output reader stopped`n" + $unknownLine
    $result = Invoke-Captured { Invoke-OpenFluxAndroidTest }
    Assert-True ($null -ne $result.Failure -and $state.HealthChecks -eq 0) 'Unknown failure was exempted from health checks'
    Assert-True ($state.Summary.error_codes.Count -eq 1 -and
        $state.Summary.error_codes -contains 'TRANSPORT_HEALTH_REJECTED' -and
        $state.Summary.warning_codes -contains 'NATIVE_OUTPUT_READER_STOPPED') 'Unknown failure rejection or reader warning was lost'
    $json = $directSummary | ConvertTo-Json -Depth 5 -Compress
    Assert-True (-not $json.Contains($secret) -and -not $result.Output.Contains($secret) -and
        -not $result.Failure.Exception.Message.Contains($secret)) 'Unknown diagnostic suffix escaped'
    Assert-True (-not $result.Output.Contains('ANDROID_APP_VPN_HTTPS_AND_STOP_PASSED=true')) 'Unknown failure reported a successful session'
}
Invoke-SyntheticCase 'printed and persisted diagnostics exclude sentinel secrets and arbitrary fields' {
    $sentinels = @('https://docs.yandex.ru/i/SENTINEL_PRIVATE_DOCUMENT_581', ('a91bd045' * 8),
        'SENTINEL_SOCKS_USERNAME_582', 'SENTINEL_SOCKS_PASSWORD_583', 'SENTINEL_PRIVATE_EXCEPTION_584')
    $flags = New-AndroidStageFlags
    $flags['untrusted_extra_field'] = $sentinels[0]
    $flags.app_installed = $true
    $flags.profile_transferred = $sentinels[1]
    $secretText = $sentinels -join "`n"
    $state.ReadyLogs = $false
    $state.ExtraLogs = $secretText + "`nOPENFLUX_FATAL: cannot initialize Yandex Docs transport`nOpenFlux: Process output reader stopped`n" +
        'D/OlcboxVpnService(3201): TUN bridge log reader failed: read failed: EINTR ' + ($sentinels -join ' ')
    $warningCodes = [Collections.Generic.HashSet[string]]::new()
    $warningCodes.Add($sentinels[1]) | Out-Null
    $outputRoot = Join-Path $repo ('.downloads\openflux\android-app-test\public-diagnostic-' + [Guid]::NewGuid().ToString('N'))
    $recordPath = ''
    try {
        $result = Invoke-Captured {
            & $realWriteSummary -Stage $sentinels[0] -Flags $flags -Logs $secretText `
                -FailureMessage $sentinels[4] -AppProcessId '3201' -WarningCodes $warningCodes
        }
        Assert-True ($null -eq $result.Failure) 'Public diagnostic fixture could not be persisted'
        $recordLine = @($result.Output -split '\r?\n' | Where-Object { $_.StartsWith('ANDROID_PUBLIC_FAILURE_SUMMARY_FILE=') })
        Assert-True ($recordLine.Count -eq 1) 'Diagnostic record path was not emitted'
        $recordPath = $recordLine[0].Substring('ANDROID_PUBLIC_FAILURE_SUMMARY_FILE='.Length)
        $saved = [IO.File]::ReadAllText($recordPath)
        foreach ($secret in $sentinels) {
            Assert-True (-not $result.Output.Contains($secret) -and -not $saved.Contains($secret)) 'A sentinel secret escaped in diagnostics'
        }
        Assert-True (-not $saved.Contains('untrusted_extra_field')) 'Untrusted flag key escaped the fixed schema'
        $summary = $saved | ConvertFrom-Json
        Assert-True ($summary.stage -eq 'unknown' -and -not $summary.flags.profile_transferred -and
            $summary.observations.log_refresh_succeeded -and $summary.error_codes -contains 'YANDEX_TRANSPORT_INIT_FAILED') 'Fixed-schema sanitization lost safe evidence'
        Assert-True ($summary.warning_codes.Count -eq 1 -and $summary.warning_codes[0] -eq 'NATIVE_OUTPUT_READER_STOPPED') 'Warning retention lost its fixed allowlist'
        Assert-True ($summary.tun_bridge_log_reader_reason_codes.Count -eq 1 -and
            $summary.tun_bridge_log_reader_reason_codes[0] -ceq 'INTERRUPTED') 'Persisted summary lost its fixed reader reason'
        Assert-True ($saved.Length -lt 8192) 'Public diagnostic summary exceeded its size budget'
    } finally {
        if ($recordPath -and (Test-Path -LiteralPath $recordPath)) { Remove-Item -LiteralPath $recordPath -Force }
        if (Test-Path -LiteralPath $outputRoot) { Remove-Item -LiteralPath $outputRoot }
    }
}
Invoke-SyntheticCase 'oversized unknown log input remains bounded and reports no raw content' {
    $secret = 'SENTINEL_LARGE_PRIVATE_LOG_585'
    $summary = New-AndroidPublicFailureSummary -Stage 'vpn_start' -Flags (New-AndroidStageFlags) `
        -Logs (($secret * 4000) + ' cannot initialize OpenFlux network stack') -FailureMessage $secret
    $json = $summary | ConvertTo-Json -Depth 5 -Compress
    Assert-True ($summary.observations.logs_truncated -and $summary.error_codes -contains 'NETWORK_STACK_INIT_FAILED') 'Bounded log tail lost fixed evidence'
    Assert-True ($json.Length -lt 8192 -and -not $json.Contains($secret)) 'Oversized raw log content escaped'
}
foreach ($warningLine in @('OpenFlux: Process output reader stopped',
    'D/OlcboxVpnService( 3201): OpenFlux: Process output reader stopped',
    'D/OlcboxVpnService(3201): OpenFlux: Process output reader stopped')) {
    Invoke-SyntheticCase 'exact reader warning does not replace normal readiness proof' {
        $codes = [Collections.Generic.HashSet[string]]::new()
        $proof = "OpenFlux encrypted transport ready on 127.0.0.1:17000`nOpenFlux VPN tunnel established`n" + $warningLine
        Assert-TransportLogsHealthy -Logs $proof -RequireReady -WarningCodes $codes
        Assert-True ($codes.Count -eq 1 -and $codes.Contains('NATIVE_OUTPUT_READER_STOPPED')) 'Exact warning was not retained'
        $result = Invoke-Captured { Assert-TransportLogsHealthy -Logs $warningLine -RequireReady }
        Assert-True ($null -ne $result.Failure) 'Warning alone satisfied authenticated readiness'
    }
}
foreach ($lookalike in @('prefix OpenFlux: Process output reader stopped',
    'OpenFlux: Process output reader stopped suffix', 'OpenFlux: Process output reader stopped ',
    ' OpenFlux: Process output reader stopped', 'OpenFlux: process output reader stopped',
    'D/OtherTag(3201): OpenFlux: Process output reader stopped',
    'D/OlcboxVpnService(3201): prefix OpenFlux: Process output reader stopped',
    'OPENFLUX_FATAL: OpenFlux: Process output reader stopped')) {
    Invoke-SyntheticCase 'warning lookalike remains fatal and is not a fixed warning' {
        $proof = "OpenFlux encrypted transport ready on 127.0.0.1:17000`nOpenFlux VPN tunnel established`n" + $lookalike
        $result = Invoke-Captured { Assert-TransportLogsHealthy -Logs $proof -RequireReady }
        Assert-True ($null -ne $result.Failure) 'Warning lookalike was exempted from health checks'
        Assert-True (@(Get-AndroidTransportWarningCodes -Logs $lookalike).Count -eq 0) 'Lookalike was classified as an exact warning'
    }
}
foreach ($actualFailure in @('OpenFlux: OPENFLUX_FATAL: cannot initialize Yandex Docs transport',
    'OpenFlux core stopped', 'OpenFlux core stopped; reconnecting transport')) {
    Invoke-SyntheticCase 'exact reader warning cannot hide fatal or actual transport stop' {
        $proof = "OpenFlux encrypted transport ready on 127.0.0.1:17000`nOpenFlux VPN tunnel established`nOpenFlux: Process output reader stopped`n" + $actualFailure
        $result = Invoke-Captured { Assert-TransportLogsHealthy -Logs $proof -RequireReady }
        Assert-True ($null -ne $result.Failure) 'Reader warning hid an actual fatal or transport stop'
    }
}
Invoke-SyntheticCase 'full synthetic HTTPS and stop pass retains exact reader warning' {
    $state.ExtraLogs = 'D/OlcboxVpnService( 3201): OpenFlux: Process output reader stopped'
    $result = Invoke-Captured { Invoke-OpenFluxAndroidTest }
    Assert-True ($null -eq $result.Failure -and $state.HealthChecks -eq 2) 'Exact warning bypassed or broke normal HTTPS proof'
    Assert-True ($result.Output.Contains('ANDROID_PUBLIC_WARNING_CODES=["NATIVE_OUTPUT_READER_STOPPED"]') -and
        $result.Output.Contains('ANDROID_APP_VPN_HTTPS_AND_STOP_PASSED=true')) 'Successful lifecycle did not report its reader warning'
}
Invoke-SyntheticCase 'whitespace warning lookalike is not normalized by the ADB log wrapper' {
    $state.ExtraLogs = 'OpenFlux: Process output reader stopped '
    $result = Invoke-Captured { Invoke-OpenFluxAndroidTest }
    Assert-True ($null -ne $result.Failure -and $state.HealthChecks -eq 0) 'ADB whitespace trimming created an allowed warning'
}
Invoke-SyntheticCase 'reader warning observed during HTTPS survives later log rotation on success' {
    $state.DuringProbe = { $state.ExtraLogs = 'OpenFlux: Process output reader stopped' }
    $state.AfterProbe = { $state.ExtraLogs = '' }
    $result = Invoke-Captured { Invoke-OpenFluxAndroidTest }
    Assert-True ($null -eq $result.Failure -and $result.Output.Contains('ANDROID_PUBLIC_WARNING_CODES=["NATIVE_OUTPUT_READER_STOPPED"]')) 'Successful probe lost an earlier warning'
}
Invoke-SyntheticCase 'lost native process still fails and retains an earlier reader warning' {
    $state.DuringProbe = { $state.ExtraLogs = 'OpenFlux: Process output reader stopped' }
    $state.AfterProbe = { $state.ExtraLogs = ''; $state.NativePid = '' }
    $result = Invoke-Captured { Invoke-OpenFluxAndroidTest }
    Assert-True ($null -ne $result.Failure -and -not $result.Output.Contains('ANDROID_APP_VPN_HTTPS_AND_STOP_PASSED=true')) 'Reader warning hid native death'
    Assert-True ($state.Summary.warning_codes -contains 'NATIVE_OUTPUT_READER_STOPPED' -and
        $state.Summary.error_codes -notcontains 'NATIVE_OUTPUT_READER_STOPPED') 'Failure report did not retain the reader event solely as a warning'
    Assert-True ($result.Output.Contains('ANDROID_PUBLIC_WARNING_CODES=["NATIVE_OUTPUT_READER_STOPPED"]')) 'Failed lifecycle lost warning output'
}
Invoke-SyntheticCase 'exact reader warning does not allow a lost TUN' {
    $state.ExtraLogs = 'OpenFlux: Process output reader stopped'
    $state.DuringProbe = { $state.Connected = $false }
    $result = Invoke-Captured { Invoke-OpenFluxAndroidTest }
    Assert-True ($null -ne $result.Failure -and -not $result.Output.Contains('ANDROID_APP_VPN_HTTPS_AND_STOP_PASSED=true')) 'Reader warning hid TUN loss'
}

if ($failures.Count -gt 0) {
    $failures | Write-Output
    throw "$($failures.Count) of $testCount synthetic Android harness tests failed"
}
Write-Output ("SYNTHETIC_ANDROID_HARNESS_TESTS_PASSED=$testCount")
