#requires -Version 7.0
[CmdletBinding(DefaultParameterSetName = 'Prepare')]
param(
    [Parameter(ParameterSetName = 'Prepare')]
    [switch]$PrepareOnly,
    [Parameter(Mandatory = $true, ParameterSetName = 'Connect')]
    [switch]$Connect,
    [Parameter(Mandatory = $true, ParameterSetName = 'Connect')]
    [switch]$ConnectionSlotGranted,
    [Parameter(Mandatory = $true, ParameterSetName = 'Connect')]
    [string]$PrivateProfilePath,
    [Parameter(Mandatory = $true, ParameterSetName = 'Connect')]
    [string]$OwnershipRecordPath,
    [ValidatePattern('^emulator-\d+$')]
    [string]$EmulatorSerial = 'emulator-5580',
    [ValidatePattern('^UnifiedVPN_OpenFlux_[A-Za-z0-9_]+$')]
    [string]$ExpectedAvdName = 'UnifiedVPN_OpenFlux_Smoke',
    [string]$SdkRoot = (Join-Path $env:USERPROFILE 'Android\Sdk'),
    [string]$ApkPath = '',
    [ValidatePattern('^[0-9a-fA-F]{64}$')]
    [string]$ExpectedApkSha256 = 'cbe9cdf852821429eb321f1611959228d6f12a285207b8513da1497551f34c68',
    [ValidateRange(5, 180)]
    [int]$BootTimeoutSeconds = 90,
    [ValidateRange(30, 180)]
    [int]$ReadyTimeoutSeconds = 110,
    [ValidateRange(30, 240)]
    [int]$ProbeTimeoutSeconds = 200
)

$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$outputRoot = [IO.Path]::GetFullPath((Join-Path $repo '.downloads\openflux\android-app-test'))
if (-not $outputRoot.StartsWith($repo + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Test output must remain within the intended workspace'
}
$adb = Join-Path $SdkRoot 'platform-tools\adb.exe'
$package = 'app.unifiedvpn.local'
$receiver = "$package/org.olcbox.app.DebugVpnControlReceiver"
$remoteProbe = '/data/local/tmp/unifiedvpn-openflux-network-probe.jar'
if (-not $ApkPath) { $ApkPath = Join-Path $repo 'androidApp\build\outputs\apk\debug\androidApp-debug.apk' }

function Invoke-IsolatedAdb {
    param([string[]]$AdbArguments, [int]$TimeoutSeconds = 30, [byte[]]$InputBytes = $null,
        [scriptblock]$HealthCheck = $null)
    $start = [Diagnostics.ProcessStartInfo]::new()
    $start.FileName = $adb
    $start.Environment['ANDROID_USER_HOME'] = Join-Path $env:USERPROFILE '.android'
    $start.Environment['HOME'] = $env:USERPROFILE
    foreach ($argument in (@('-s', $EmulatorSerial) + $AdbArguments)) { $start.ArgumentList.Add($argument) }
    return Invoke-RedirectedChildProcess -StartInfo $start -TimeoutSeconds $TimeoutSeconds `
        -InputBytes $InputBytes -HealthCheck $HealthCheck
}

function Invoke-RedirectedChildProcess {
    param([Diagnostics.ProcessStartInfo]$StartInfo, [int]$TimeoutSeconds = 30,
        [byte[]]$InputBytes = $null, [scriptblock]$HealthCheck = $null)
    $StartInfo.UseShellExecute = $false
    $StartInfo.CreateNoWindow = $true
    $StartInfo.RedirectStandardOutput = $true
    $StartInfo.RedirectStandardError = $true
    $StartInfo.RedirectStandardInput = $null -ne $InputBytes
    if ($null -ne $InputBytes) { $StartInfo.StandardInputEncoding = [Text.UTF8Encoding]::new($false) }
    $process = [Diagnostics.Process]::new()
    $process.StartInfo = $StartInfo
    $launched = $false
    try {
        $launched = $process.Start()
        if (-not $launched) { throw 'Cannot start isolated child command' }
        $stdout = $process.StandardOutput.ReadToEndAsync()
        $stderr = $process.StandardError.ReadToEndAsync()
        if ($null -ne $InputBytes) {
            $stdin = $process.StandardInput.BaseStream
            $write = $stdin.WriteAsync($InputBytes, 0, $InputBytes.Length)
            if (-not $write.Wait(10000)) { throw 'Private stdin transfer exceeded its timeout' }
            $write.GetAwaiter().GetResult()
            # The raw stream owns bytes and EOF; the text writer must not emit a preamble.
            $stdin.Close()
        }
        Wait-IsolatedCommand -Process $process -TimeoutSeconds $TimeoutSeconds -HealthCheck $HealthCheck
        return [pscustomobject]@{ ExitCode = $process.ExitCode; Output = $stdout.Result + $stderr.Result }
    } finally {
        try {
            if ($launched -and -not $process.HasExited) {
                $process.Kill()
                if (-not $process.WaitForExit(5000)) { throw 'Owned child process did not stop within its cleanup timeout' }
            }
        } finally { $process.Dispose() }
    }
}

function Wait-IsolatedCommand {
    param($Process, [int]$TimeoutSeconds, [scriptblock]$HealthCheck = $null)
    $timer = [Diagnostics.Stopwatch]::StartNew()
    do {
        if ($null -ne $HealthCheck) { & $HealthCheck | Out-Null }
        $remaining = $TimeoutSeconds * 1000 - $timer.ElapsedMilliseconds
        if ($remaining -le 0) { throw 'Isolated adb command exceeded its timeout' }
        if ($Process.WaitForExit([int][Math]::Min(1000, $remaining))) {
            if ($null -ne $HealthCheck) { & $HealthCheck | Out-Null }
            return
        }
    } while ($true)
}

function Invoke-CheckedAdb {
    param([string[]]$AdbArguments, [int]$TimeoutSeconds = 30, [switch]$PreserveWhitespace)
    $result = Invoke-IsolatedAdb -AdbArguments $AdbArguments -TimeoutSeconds $TimeoutSeconds
    if ($result.ExitCode -ne 0) { throw 'Isolated adb operation failed; no private command output was emitted' }
    if ($PreserveWhitespace) { return $result.Output }
    return $result.Output.Trim()
}

function Get-EmulatorHostProcess {
    param([int]$ProcessId)
    $hostProcess = Get-CimInstance Win32_Process -Filter "ProcessId = $ProcessId" -OperationTimeoutSec 5 -ErrorAction Stop
    if ($null -eq $hostProcess) { return $null }
    if ($null -eq $hostProcess.CreationDate -or -not $hostProcess.ExecutablePath) {
        # CIM can retain an incomplete record during exit; only independent exit proof permits absence.
        $process = $null
        try {
            $process = Get-Process -Id $ProcessId -ErrorAction Stop
            if ($null -ne $process -and $process.Id -eq $ProcessId -and $process.HasExited) { return $null }
        } catch {
            if ($_.FullyQualifiedErrorId -ceq 'NoProcessFoundForGivenId,Microsoft.PowerShell.Commands.GetProcessCommand' -and
                $_.CategoryInfo.Category -eq [Management.Automation.ErrorCategory]::ObjectNotFound) { return $null }
        } finally {
            if ($null -ne $process) { $process.Dispose() }
        }
        throw 'Cannot verify the recorded emulator host process identity'
    }
    return [pscustomobject]@{
        process_id = [int]$hostProcess.ProcessId
        parent_process_id = [int]$hostProcess.ParentProcessId
        start_time_utc_ticks = $hostProcess.CreationDate.ToUniversalTime().Ticks.ToString()
        executable = [string]$hostProcess.ExecutablePath
        command_line = [string]$hostProcess.CommandLine
    }
}

function Get-AllowedQemuExecutablePaths {
    Join-Path $SdkRoot 'emulator\qemu\windows-x86_64\qemu-system-x86_64.exe'
    Join-Path $SdkRoot 'emulator\qemu\windows-x86_64\qemu-system-x86_64-headless.exe'
}

function Assert-EmulatorLaunchArguments {
    param([string]$CommandLine, [string]$AvdDirectory)
    foreach ($flag in @{
        avd = $ExpectedAvdName
        port = $EmulatorSerial.Substring('emulator-'.Length)
        datadir = $AvdDirectory
    }.GetEnumerator()) {
        $value = [regex]::Escape([string]$flag.Value)
        if ([regex]::Matches($CommandLine, ('(?i)(?:^|\s)-' + $flag.Key + '(?=\s|$)')).Count -ne 1 -or
            $CommandLine -notmatch ('(?:^|\s)-' + $flag.Key + '\s+(?:"' + $value + '"|' + $value + ')(?:\s|$)')) {
            throw 'Recorded host process does not use one unambiguous isolated AVD and port'
        }
    }
}

function Read-EmulatorOwnership {
    $smokeRoot = [IO.Path]::GetFullPath((Join-Path $repo '.downloads\openflux\emulator-smoke'))
    $recordPath = [IO.Path]::GetFullPath($OwnershipRecordPath)
    $runRoot = Split-Path -Parent $recordPath
    if ((Split-Path -Parent $runRoot) -ne $smokeRoot -or
        (Split-Path -Leaf $runRoot) -notmatch '^run-[0-9a-f]{32}$' -or
        (Split-Path -Leaf $recordPath) -ne 'ownership.json') {
        throw 'Ownership record must belong to a fresh isolated OpenFlux emulator run'
    }
    # Reject junctions/symlinks before trusting paths used by the isolated launcher.
    for ($path = $recordPath; $path -ne $repo; $path = Split-Path -Parent $path) {
        $item = Get-Item -LiteralPath $path
        if ($item.Attributes -band [IO.FileAttributes]::ReparsePoint) {
            throw 'Isolated emulator ownership paths must not contain reparse points'
        }
    }
    $file = Get-Item -LiteralPath $recordPath
    if ($file.PSIsContainer -or $file.Length -gt 8192 -or $file.Length -eq 0) { throw 'Invalid emulator ownership record' }
    try { $record = [IO.File]::ReadAllText($recordPath) | ConvertFrom-Json } catch { throw 'Invalid emulator ownership record' }
    $avdDirectory = Join-Path $runRoot "avd\$ExpectedAvdName.avd"
    if ($record.version -ne 1 -or $record.fresh_userdata -isnot [bool] -or $record.fresh_userdata -ne $true -or
        $record.run_root -ne $runRoot -or $record.avd_directory -ne $avdDirectory -or
        $record.emulator_serial -ne $EmulatorSerial -or $record.avd_name -ne $ExpectedAvdName -or
        @($record.processes).Count -ne 2) { throw 'Emulator ownership record does not match the dedicated fresh guest' }
    foreach ($directory in @((Split-Path -Parent $avdDirectory), $avdDirectory)) {
        $item = Get-Item -LiteralPath $directory
        if (-not $item.PSIsContainer -or ($item.Attributes -band [IO.FileAttributes]::ReparsePoint)) {
            throw 'Recorded AVD must be a real directory within its isolated run'
        }
    }
    $expectedExecutables = @{
        launcher = @(Join-Path $SdkRoot 'emulator\emulator.exe')
        qemu = @(Get-AllowedQemuExecutablePaths)
    }
    $roles = @()
    $ids = @()
    foreach ($entry in $record.processes) {
        if (-not $expectedExecutables.ContainsKey([string]$entry.role) -or $roles -contains $entry.role -or
            [string]$entry.process_id -notmatch '^[1-9]\d*$' -or $ids -contains $entry.process_id -or
            [string]$entry.start_time_utc_ticks -notmatch '^\d+$' -or
            $entry.executable -notin $expectedExecutables[[string]$entry.role]) { throw 'Invalid emulator process ownership entry' }
        $roles += $entry.role
        $ids += $entry.process_id
        $current = Get-EmulatorHostProcess -ProcessId $entry.process_id
        if ($null -eq $current -or $current.start_time_utc_ticks -ne $entry.start_time_utc_ticks -or
            $current.executable -ne $entry.executable) { throw 'Recorded emulator host process no longer matches this run' }
        if ($entry.role -eq 'qemu' -and
            $current.parent_process_id -ne ($record.processes | Where-Object { $_.role -eq 'launcher' }).process_id) {
            throw 'Recorded QEMU is not a child of the owned launcher'
        }
        Assert-EmulatorLaunchArguments -CommandLine $current.command_line -AvdDirectory $avdDirectory
    }
    return $record
}

function Wait-OwnedGuestBoot {
    param($Ownership, [int]$TimeoutSeconds = $BootTimeoutSeconds)
    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    do {
        if (@(Get-LiveOwnedEmulatorProcesses -Ownership $Ownership).Count -ne 2) {
            throw 'Owned emulator host process exited before Android boot completed'
        }
        try {
            $boot = Invoke-IsolatedAdb -AdbArguments @('shell', 'getprop', 'sys.boot_completed') -TimeoutSeconds 3
            if ($boot.ExitCode -eq 0 -and $boot.Output.Trim() -eq '1') { return }
        } catch { }
        if ([DateTime]::UtcNow -ge $deadline) { break }
        Start-Sleep -Milliseconds 500
    } while ($true)
    throw 'Owned Android guest did not complete boot within the bounded wait'
}

function Confirm-FreshOwnedEmulator {
    param([System.Collections.IDictionary]$Flags = $null)
    $hash = (Get-FileHash -LiteralPath $ApkPath -Algorithm SHA256).Hash
    if ($hash -ne $ExpectedApkSha256) { throw 'APK does not match the final artifact hash' }
    $ownership = Read-EmulatorOwnership
    if ($null -ne $Flags) { $Flags.ownership_validated = $true }
    Wait-OwnedGuestBoot -Ownership $ownership
    if ($null -ne $Flags) { $Flags.boot_completed = $true }
    foreach ($property in @{
        'ro.kernel.qemu' = '1'
        'ro.boot.qemu.avd_name' = $ExpectedAvdName
        'ro.product.cpu.abi' = 'x86_64'
        'ro.build.version.sdk' = '35'
    }.GetEnumerator()) {
        if ((Invoke-CheckedAdb -AdbArguments @('shell', 'getprop', $property.Key)) -ne $property.Value) {
            throw 'Refusing connection test outside the dedicated API 35 x86_64 test emulator'
        }
    }
    $existing = Invoke-IsolatedAdb -AdbArguments @('shell', 'pm', 'path', $package)
    if ($existing.ExitCode -notin @(0, 1) -or $existing.Output.Trim()) {
        throw 'Connection test requires a verified absent app installation'
    }
    $cores = Invoke-IsolatedAdb -AdbArguments @('shell', 'pidof', 'libopenflux.so', 'libsing-box.so', 'libxray.so')
    if ($cores.ExitCode -ne 1 -or $cores.Output.Trim()) { throw 'Guest transport processes are not verified absent' }
    $connectivity = Invoke-CheckedAdb -AdbArguments @('shell', 'dumpsys', 'connectivity')
    if ($connectivity -match 'ni\{VPN CONNECTED') { throw 'Fresh isolated guest already has a connected VPN' }
    if ($null -ne $Flags) { $Flags.fresh_guest_accepted = $true }
    return $ownership
}

function New-AndroidStageFlags {
    return [ordered]@{
        ownership_validated = $false; boot_completed = $false; fresh_guest_accepted = $false
        app_installed = $false; profile_transferred = $false; app_started = $false
        vpn_start_requested = $false; authenticated_ready = $false; tun_verified = $false
        https_completed = $false; stop_completed = $false
    }
}

function Test-ExactOpenFluxReaderWarning {
    param([string]$Line)
    $message = [regex]::Replace($Line, '\AD/OlcboxVpnService\([ ]*\d+\): ', '')
    return $message -ceq 'OpenFlux: Process output reader stopped'
}

function Get-AndroidTransportWarningCodes {
    param([string]$Logs = '', [string[]]$RetainedCodes = @())
    if ($RetainedCodes -ccontains 'NATIVE_OUTPUT_READER_STOPPED') { return 'NATIVE_OUTPUT_READER_STOPPED' }
    foreach ($line in ($Logs -split '\r?\n')) {
        if (Test-ExactOpenFluxReaderWarning -Line $line) { return 'NATIVE_OUTPUT_READER_STOPPED' }
    }
}

function Get-TunBridgeLogReaderReasonCodes {
    param([string]$InputText)
    $reasons = [Collections.Generic.List[string]]::new()
    $pattern = '(?im)^(?:[VDIWEF]/OlcboxVpnService\([ ]*\d+\): )?TUN bridge log reader failed:[ \t]*(?<suffix>[^\r\n]*)'
    foreach ($entry in [regex]::Matches($InputText, $pattern)) {
        $suffix = $entry.Groups['suffix'].Value
        $reason = if ($suffix -match '(?i)\b(?:(?:stream|pipe) (?:is )?closed|closed (?:stream|pipe))\b') {
            'STREAM_CLOSED'
        } elseif ($suffix -match '(?i)\b(?:EINTR|interrupted(?:ioexception)?)\b') {
            'INTERRUPTED'
        } elseif ($suffix -match '(?i)\b(?:EAGAIN|EWOULDBLOCK|would block|resource temporarily unavailable)\b') {
            'WOULD_BLOCK'
        } elseif ($suffix -match '(?i)\b(?:EBADF|bad file descriptor)\b') {
            'BAD_FILE_DESCRIPTOR'
        } elseif ($suffix -match '(?i)\bUnderlying input stream returned zero bytes\b') {
            'ZERO_BYTE_READ'
        } elseif ($suffix -match '(?i)\b(?:EIO|IOException|I/O error|input/output error)\b') {
            'IO_FAILURE'
        } else {
            'UNCLASSIFIED'
        }
        if (-not $reasons.Contains($reason)) { $reasons.Add($reason) }
    }
    return $reasons.ToArray()
}

function Get-AndroidBrowserCheckpoint {
    param([string]$Line)
    if ($Line -cmatch '^I/OpenFluxBrowser[ ]*\([ ]*\d+\): (OPENFLUX_BROWSER_CHECKPOINT view=(?:true|false) page=(?:true|false) polls=\d{1,3} state=(?:Unknown|Loading|Config|Verification|Other) blocked=\d{1,3})$') {
        return $Matches[1]
    }
    if ($Line -cmatch '^I/OpenFluxBrowser[ ]*\([ ]*\d+\): (OPENFLUX_BROWSER_STAGE (?:request_rejected|service_created|service_bound|request_received|network_bound|cookies_cleared|view_created|page_finished|response_success|response_failed))$') {
        return $Matches[1]
    }
}

function New-AndroidPublicFailureSummary {
    param([string]$Stage, [System.Collections.IDictionary]$Flags, [string]$Logs, [string]$FailureMessage,
        [bool]$LogRefreshSucceeded = $false, [string[]]$RetainedWarningCodes = @())
    $Logs = [string]$Logs
    $FailureMessage = [string]$FailureMessage
    $stages = @('guest_preflight', 'app_install', 'profile_transfer', 'app_launch', 'vpn_start',
        'authenticated_ready', 'https_probe', 'vpn_stop')
    if ($Stage -notin $stages) { $Stage = 'unknown' }
    $boundedLogs = if ($Logs.Length -gt 65536) { $Logs.Substring($Logs.Length - 65536) } else { $Logs }
    $boundedFailure = if ($FailureMessage.Length -gt 4096) { $FailureMessage.Substring(0, 4096) } else { $FailureMessage }
    $inputText = $boundedLogs + "`n" + $boundedFailure
    $literals = [ordered]@{
        ENCRYPTION_INIT_FAILED = @('cannot initialize mandatory AES-256-GCM encryption')
        NETWORK_STACK_INIT_FAILED = @('cannot initialize OpenFlux network stack')
        YANDEX_TRANSPORT_INIT_FAILED = @('cannot initialize Yandex Docs transport')
        YANDEX_DOCUMENT_UNAVAILABLE = @('Yandex document editor is unavailable or does not allow editing')
        YANDEX_HTTPS_FAILED = @('Yandex document HTTPS request failed')
        BROWSER_VERIFICATION_REQUIRED = @('Yandex browser verification is required')
        BROWSER_VERIFICATION_FAILED = @('Browser verification failed')
        BROWSER_VERIFICATION_TIMEOUT = @('Browser verification timed out')
        BROWSER_NETWORK_UNAVAILABLE = @('Browser verification upstream network is unavailable')
        BROWSER_ACCOUNT_REJECTED = @('Browser verification refused an authenticated account session')
        BROWSER_RETRY_LIMIT = @('Browser verification retry limit reached')
        BROWSER_ANDROID_UNSUPPORTED = @('Browser verification requires Android 9 or newer')
        BROWSER_PROVIDER_FAILED = @('Yandex browser verification did not complete (stage=provider)')
        BROWSER_PROVIDER_TIMEOUT = @('Yandex browser verification did not complete (stage=provider_timeout)')
        BROWSER_COOKIES_REJECTED = @('Yandex browser verification did not complete (stage=cookies)')
        BROWSER_HTTPS_RETRY_FAILED = @('Yandex browser verification did not complete (stage=retry_https)')
        BROWSER_CHALLENGE_REPEATED = @('Yandex browser verification did not complete (stage=verification_repeated)')
        BROWSER_RETRY_BACKOFF = @('Yandex browser verification did not complete (stage=backoff)')
        BROWSER_ATTEMPT_LIMIT = @('Yandex browser verification did not complete (stage=attempt_limit)')
        PEER_HANDSHAKE_TIMEOUT = @('encrypted peer handshake timed out; check the server, document access, and matching key')
        PEER_SESSION_STOPPED = @('encrypted peer session is stopped')
        PEER_NOT_READY = @('encrypted peer is not ready')
        PEER_CHALLENGE_FAILED = @('cannot generate peer challenge')
        SOCKS_BIND_FAILED = @('cannot bind the configured loopback SOCKS port')
        SOCKS_LISTENER_STOPPED = @('local SOCKS listener stopped')
        NATIVE_CONFIG_OPEN_FAILED = @('cannot open private configuration file')
        NATIVE_CONFIG_INVALID = @('invalid OpenFlux configuration JSON', 'unsupported OpenFlux version, mode, or transport')
        NATIVE_CONFIG_TRAILING_DATA = @('unexpected trailing configuration data')
        NATIVE_CONFIG_URL_INVALID = @('a valid HTTPS Yandex Docs document URL is required')
        NATIVE_CONFIG_KEY_INVALID = @('encryption_key must contain exactly 64 hexadecimal characters; plaintext is disabled')
        NATIVE_EXECUTABLE_MISSING = @('OpenFlux executable is missing for this Android ABI')
        NATIVE_LIBRARY_DIR_UNAVAILABLE = @('OpenFlux native library directory is unavailable')
        PRIVATE_WORK_DIR_UNAVAILABLE = @('OpenFlux private working directory is unavailable')
        PRIVATE_CONFIG_PERMISSION_FAILED = @('OpenFlux private configuration permissions could not be set')
        PROFILE_INVALID = @('OpenFlux profile is invalid')
        NATIVE_EXIT_BEFORE_READY = @('OpenFlux exited before the encrypted transport became ready')
        NATIVE_READY_TIMEOUT = @('OpenFlux encrypted transport did not become ready within 90 seconds', 'OpenFlux encrypted transport did not become ready within 120 seconds')
        NATIVE_STOP_TIMEOUT = @('OpenFlux process is still stopping after a forced stop')
        TUN_BRIDGE_START_FAILED = @('TUN DNS bridge start failed')
        TUN_BRIDGE_EXIT_BEFORE_READY = @('TUN DNS bridge exited before SOCKS became ready')
        TUN_BRIDGE_READY_TIMEOUT = @('TUN DNS bridge did not open SOCKS port')
        TUN_BRIDGE_LOG_READER_FAILED = @('TUN bridge log reader failed:')
        TUN_ESTABLISH_FAILED = @('VPN establish failed')
        TUN2SOCKS_START_FAILED = @('tun2socks start failed')
        TUN2SOCKS_NATIVE_LOAD_FAILED = @('Failed to load native tun2socks libraries')
        TUN2SOCKS_UNAVAILABLE = @('tun2socks native libraries are unavailable')
        TUN2SOCKS_EXITED = @('tun2socks exited with code')
        VPN_PERMISSION_REVOKED = @('VPN permission revoked')
        VPN_NOT_PREPARED = @('Debug VPN start did not confirm prepared permission', 'VPN permission is not prepared or was revoked')
        UPSTREAM_UNAVAILABLE = @('no upstream network', 'Waiting for upstream network')
        TRANSPORT_RECOVERY = @('reconnecting transport', 'Retrying transport', 'Watchdog:')
        NATIVE_FATAL_REPORTED = @('OPENFLUX_FATAL:')
        NATIVE_CONTEXT_DEADLINE = @('OPENFLUX_FATAL: context deadline exceeded')
        NATIVE_CONTEXT_CANCELED = @('OPENFLUX_FATAL: context canceled')
        OPENFLUX_START_FAILED = @('OpenFlux start failed:')
        VPN_START_FAILED_SAFELY = @('VPN start failed safely:')
        TRANSPORT_HEALTH_REJECTED = @('Android transport reported failure, shutdown, or recovery during the test')
        AUTHENTICATED_TUN_TIMEOUT = @('OpenFlux app did not establish an authenticated TUN within its bounded startup budget')
        APK_HASH_MISMATCH = @('APK does not match the final artifact hash')
        BOOT_TIMEOUT = @('Owned Android guest did not complete boot within the bounded wait')
        HOST_EXIT_DURING_BOOT = @('Owned emulator host process exited before Android boot completed')
        PROFILE_TRANSFER_SHA_MISMATCH = @('Private profile stdin transfer was incomplete')
        PROFILE_CHECKSUM_RESPONSE_INVALID = @('Android checksum response was not a single expected file record')
        PROFILE_STDIN_TRANSFER_FAILED = @('Private app-UID stdin transfer failed', 'Private stdin transfer exceeded its timeout')
        APP_PROCESS_NOT_SINGLE = @('Expected one stable app process for the VPN lifecycle test')
        APP_CRASH = @('App crash detected during VPN test')
        TRANSPORT_PROCESS_CHANGED = @('App, OpenFlux core, or DNS bridge process changed during the HTTPS probe')
        TUN_LOST = @('OpenFlux app TUN was lost during the HTTPS probe')
        HTTPS_PROBE_FAILED = @('Real HTTPS through the app VPN did not pass for both targets')
        UNKNOWN_ADB_FAILURE = @('Isolated adb operation failed; no private command output was emitted')
    }
    $codes = [Collections.Generic.List[string]]::new()
    foreach ($entry in $literals.GetEnumerator()) {
        foreach ($literal in $entry.Value) {
            if ($inputText.IndexOf($literal, [StringComparison]::OrdinalIgnoreCase) -ge 0) { $codes.Add($entry.Key); break }
        }
    }
    if ($codes.Count -eq 0) { $codes.Add('UNCLASSIFIED_FAILURE') }
    $safeFlags = New-AndroidStageFlags
    foreach ($key in @($safeFlags.Keys)) { $safeFlags[$key] = $null -ne $Flags -and $Flags[$key] -is [bool] -and $Flags[$key] }
    return [ordered]@{
        version = 1
        outcome = 'failed'
        stage = $Stage
        flags = $safeFlags
        observations = [ordered]@{
            logs_available = -not [string]::IsNullOrWhiteSpace($boundedLogs)
            log_refresh_succeeded = $LogRefreshSucceeded
            logs_truncated = $Logs.Length -gt 65536
            service_start_seen = $boundedLogs.Contains('Starting OpenFlux profile')
            transport_bound_seen = $boundedLogs.Contains('Bound OpenFlux transport to ')
            encryption_initialized_seen = $boundedLogs.Contains('OPENFLUX_ENCRYPTION AES-256-GCM')
            authenticated_ready_seen = $boundedLogs.Contains('OpenFlux encrypted transport ready on 127.0.0.1:')
            dns_bridge_ready_seen = $boundedLogs.Contains('TUN DNS bridge ready on ')
            tun_established_seen = $boundedLogs.Contains('OpenFlux VPN tunnel established')
            native_fatal_seen = $boundedLogs.Contains('OPENFLUX_FATAL:')
        }
        error_codes = @($codes.ToArray())
        tun_bridge_log_reader_reason_codes = @(Get-TunBridgeLogReaderReasonCodes -InputText $inputText)
        warning_codes = @(Get-AndroidTransportWarningCodes -Logs $Logs -RetainedCodes $RetainedWarningCodes)
    }
}

function Write-AndroidFailureSummary {
    param([string]$Stage, [System.Collections.IDictionary]$Flags, [string]$Logs, [string]$FailureMessage,
        [string]$AppProcessId, [Collections.Generic.HashSet[string]]$WarningCodes = $null)
    $refreshed = $false
    if ($Flags.app_installed -eq $true -and $AppProcessId -match '^\d+$') {
        try {
            $snapshot = Invoke-IsolatedAdb -AdbArguments @('logcat', '-d', '-t', '200', '--pid', $AppProcessId,
                '-v', 'brief', '-s', 'OlcboxVpnService:D', '*:S') -TimeoutSeconds 3
            if ($snapshot.ExitCode -eq 0) { $Logs += "`n" + $snapshot.Output; $refreshed = $true }
        } catch { }
    }
    foreach ($code in @(Get-AndroidTransportWarningCodes -Logs $Logs)) {
        if ($null -ne $WarningCodes) { $WarningCodes.Add($code) | Out-Null }
    }
    if ($Flags.app_installed -eq $true) {
        try {
            $checkpoints = Invoke-IsolatedAdb -AdbArguments @('logcat', '-d', '-v', 'brief', '-s', 'OpenFluxBrowser:I', '*:S') -TimeoutSeconds 3
            foreach ($line in ($checkpoints.Output -split '\r?\n')) {
                Get-AndroidBrowserCheckpoint -Line $line
            }
        } catch { }
    }
    $summary = New-AndroidPublicFailureSummary -Stage $Stage -Flags $Flags -Logs $Logs `
        -FailureMessage $FailureMessage -LogRefreshSucceeded $refreshed -RetainedWarningCodes @($WarningCodes)
    $json = $summary | ConvertTo-Json -Depth 5 -Compress
    Write-Output ('ANDROID_PUBLIC_FAILURE_SUMMARY=' + $json)

    $expectedRoot = [IO.Path]::GetFullPath((Join-Path $repo '.downloads\openflux\android-app-test'))
    $directory = [IO.Path]::GetFullPath($outputRoot)
    if ($directory -ne $expectedRoot -and -not $directory.StartsWith($expectedRoot + '\', [StringComparison]::OrdinalIgnoreCase)) {
        throw 'Public diagnostics must remain inside the Android test output directory'
    }
    for ($path = $directory; $path -ne $repo; $path = Split-Path -Parent $path) {
        if ((Test-Path -LiteralPath $path) -and ((Get-Item -LiteralPath $path).Attributes -band [IO.FileAttributes]::ReparsePoint)) {
            throw 'Public diagnostics paths must not contain reparse points'
        }
    }
    New-Item -ItemType Directory -Path $directory -Force | Out-Null
    $recordPath = Join-Path $directory ('failure-' + [Guid]::NewGuid().ToString('N') + '.json')
    $bytes = [Text.UTF8Encoding]::new($false).GetBytes($json)
    $file = [IO.File]::Open($recordPath, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write, [IO.FileShare]::None)
    try { $file.Write($bytes, 0, $bytes.Length) } finally { $file.Dispose() }
    Write-Output ('ANDROID_PUBLIC_FAILURE_SUMMARY_FILE=' + $recordPath)
}

function Assert-TransportLogsHealthy {
    param([string]$Logs, [switch]$RequireReady, [Collections.Generic.HashSet[string]]$WarningCodes = $null)
    foreach ($code in @(Get-AndroidTransportWarningCodes -Logs $Logs)) {
        if ($null -ne $WarningCodes) { $WarningCodes.Add($code) | Out-Null }
    }
    if ($Logs -match 'OPENFLUX_FATAL:') {
        throw 'Android transport reported failure, shutdown, or recovery during the test'
    }
    foreach ($line in ($Logs -split '\r?\n')) {
        if (Test-ExactOpenFluxReaderWarning -Line $line) { continue }
        if ($line -match '(?i)\b(failed|failure|revoked|exited|stopped|stopping|canceled|canceling|reconnecting|reconnected|restarting|retrying)\b|Watchdog:') {
            throw 'Android transport reported failure, shutdown, or recovery during the test'
        }
    }
    $authenticated = [regex]::Matches($Logs, 'OpenFlux encrypted transport ready on 127\.0\.0\.1:').Count
    $established = [regex]::Matches($Logs, 'OpenFlux VPN tunnel established').Count
    if ($authenticated -gt 1 -or $established -gt 1 -or ($RequireReady -and ($authenticated -ne 1 -or $established -ne 1))) {
        throw 'Android test requires one uninterrupted authenticated OpenFlux TUN session'
    }
}

function Assert-ActiveTransport {
    param([string]$AppProcessId, [string]$NativeProcessId, [string]$BridgeProcessId,
        [Collections.Generic.HashSet[string]]$WarningCodes = $null)
    foreach ($identity in @{
        $package = $AppProcessId
        'libopenflux.so' = $NativeProcessId
        'libsing-box.so' = $BridgeProcessId
    }.GetEnumerator()) {
        if ((Invoke-CheckedAdb -AdbArguments @('shell', 'pidof', $identity.Key) -TimeoutSeconds 3) -ne $identity.Value) {
            throw 'App, OpenFlux core, or DNS bridge process changed during the HTTPS probe'
        }
    }
    $connectivity = Invoke-CheckedAdb -AdbArguments @('shell', 'dumpsys', 'connectivity') -TimeoutSeconds 3
    if ($connectivity -notmatch 'ni\{VPN CONNECTED extra: VPN:app\.unifiedvpn\.local\}') {
        throw 'OpenFlux app TUN was lost during the HTTPS probe'
    }
    $logs = Invoke-CheckedAdb -AdbArguments @('logcat', '-d', '--pid', $AppProcessId, '-v', 'brief', '-s', 'OlcboxVpnService:D', '*:S') -TimeoutSeconds 3 -PreserveWhitespace
    Assert-TransportLogsHealthy -Logs $logs -RequireReady -WarningCodes $WarningCodes
}

function Get-LiveOwnedEmulatorProcesses {
    param($Ownership)
    foreach ($entry in $Ownership.processes) {
        $current = Get-EmulatorHostProcess -ProcessId $entry.process_id
        if ($null -ne $current -and $current.start_time_utc_ticks -eq $entry.start_time_utc_ticks) {
            if ($current.executable -ne $entry.executable) { throw 'Cannot verify the owned emulator host process' }
            $entry
        }
    }
}

function Stop-OwnedEmulator {
    param($Ownership, [int]$TimeoutSeconds = 20)
    $live = @(Get-LiveOwnedEmulatorProcesses -Ownership $Ownership)
    if ($live.role -contains 'qemu') {
        try {
            $request = Invoke-IsolatedAdb -AdbArguments @('emu', 'kill') -TimeoutSeconds 5
            if ($request.ExitCode -eq 0) { Write-Output ('OWNED_TEST_EMULATOR_SHUTDOWN_REQUESTED=' + $EmulatorSerial) }
        } catch { Write-Warning 'Owned emulator shutdown request failed; checking the recorded host processes' }
    }
    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    do {
        if (@(Get-LiveOwnedEmulatorProcesses -Ownership $Ownership).Count -eq 0) {
            Write-Output 'OWNED_TEST_EMULATOR_STOPPED=true'
            return
        }
        if ([DateTime]::UtcNow -ge $deadline) { break }
        Start-Sleep -Milliseconds 500
    } while ($true)
    throw 'Owned emulator host process is still active; keep the OpenFlux connection slot reserved'
}

function Build-NetworkProbe {
    $classes = Join-Path $outputRoot 'probe-classes'
    $jar = Join-Path $outputRoot 'unifiedvpn-openflux-network-probe.jar'
    New-Item -ItemType Directory -Path $classes -Force | Out-Null
    & javac.exe --release 8 -Xlint:-options -d $classes (Join-Path $repo 'tools\android-network-probe\NetworkProbe.java')
    if ($LASTEXITCODE -ne 0) { throw 'Cannot compile the existing Android HTTPS probe' }
    $d8 = Get-ChildItem -Path (Join-Path $SdkRoot 'build-tools\*\d8.bat') -File |
        Sort-Object FullName -Descending | Select-Object -First 1 -ExpandProperty FullName
    if (-not $d8) { throw 'Android d8 is unavailable' }
    & $d8 --output $jar (Join-Path $classes 'NetworkProbe.class')
    if ($LASTEXITCODE -ne 0) { throw 'Cannot package the existing Android HTTPS probe' }
    return $jar
}

function Send-PrivateProfileBundle {
    $file = Get-Item -LiteralPath $PrivateProfilePath
    if (-not $file.PSIsContainer -and $file.Length -gt 0 -and $file.Length -le 32768) {
        try { $profile = [IO.File]::ReadAllText($file.FullName) | ConvertFrom-Json } catch {
            throw 'Private OpenFlux profile is not valid JSON'
        }
    } else { throw 'Private OpenFlux profile must be a nonempty file of at most 32768 bytes' }
    $document = ([string]$profile.document_url).Trim()
    $key = ([string]$profile.encryption_key).Trim().ToLowerInvariant()
    if (-not $document.StartsWith('https://', [StringComparison]::OrdinalIgnoreCase) -or $key -notmatch '^[0-9a-f]{64}$') {
        throw 'Private OpenFlux profile requires an HTTPS document and a 64-character key'
    }
    $transport = if ($null -eq $profile.PSObject.Properties['transport']) {
        'yandex'
    } elseif ($profile.transport -is [string]) {
        $profile.transport.Trim().ToLowerInvariant()
    } else { '' }
    if (($null -ne $profile.version -and $profile.version -ne 1) -or
        $transport -notin @('yandex', 'vyandex')) {
        throw 'Private OpenFlux profile has an unsupported version or transport'
    }
    $canonical = [ordered]@{ version = 1; transport = $transport; document_url = $document; encryption_key = $key } |
        ConvertTo-Json -Compress
    $bundle = [ordered]@{
        version = 5
        active_location_id = 'isolated-openflux'
        locations = @([ordered]@{
            storage_id = 'isolated-openflux'
            name = 'OpenFlux isolated'
            vpn_profile = [ordered]@{ type = 'openflux'; name = 'OpenFlux isolated'; raw_config = $canonical }
        })
    } | ConvertTo-Json -Depth 8 -Compress
    $bytes = [Text.UTF8Encoding]::new($false).GetBytes($bundle)
    try {
        Invoke-CheckedAdb -AdbArguments @('shell', 'run-as', $package, 'mkdir', '-p', 'files') | Out-Null
        # Non-PTY shell keeps stdin byte-exact and waits for the remote dd exit before checking its file.
        $transfer = Invoke-IsolatedAdb -AdbArguments @('shell', '-T', 'run-as', $package, 'dd', 'of=files/locations_v4.json') -InputBytes $bytes
        if ($transfer.ExitCode -ne 0) { throw 'Private app-UID stdin transfer failed' }
        Invoke-CheckedAdb -AdbArguments @('shell', 'run-as', $package, 'chmod', '600', 'files/locations_v4.json') | Out-Null
        $sha = [Security.Cryptography.SHA256]::Create()
        try { $expected = ([BitConverter]::ToString($sha.ComputeHash($bytes))).Replace('-', '').ToLowerInvariant() } finally { $sha.Dispose() }
        $actual = Invoke-CheckedAdb -AdbArguments @('shell', 'run-as', $package, 'sha256sum', 'files/locations_v4.json')
        Assert-TransferredProfileChecksum -Response $actual -ExpectedHash $expected
    } finally {
        [Array]::Clear($bytes, 0, $bytes.Length)
        $profile = $null
        $canonical = $null
        $bundle = $null
        $key = $null
        $document = $null
    }
}

function Assert-TransferredProfileChecksum {
    param([string]$Response, [string]$ExpectedHash)
    $checksum = [regex]::Match($Response.Trim(), '\A([0-9a-fA-F]{64})[ \t]+\*?files/locations_v4\.json\z')
    if (-not $checksum.Success) { throw 'Android checksum response was not a single expected file record' }
    if ($checksum.Groups[1].Value -ne $ExpectedHash) { throw 'Private profile stdin transfer was incomplete' }
}

function Wait-TransportStopped {
    $deadline = [DateTime]::UtcNow.AddSeconds(25)
    do {
        $processes = Invoke-IsolatedAdb -AdbArguments @('shell', 'pidof', 'libopenflux.so', 'libsing-box.so', 'libxray.so')
        $connectivity = Invoke-CheckedAdb -AdbArguments @('shell', 'dumpsys', 'connectivity')
        if (-not $processes.Output.Trim() -and $connectivity -notmatch 'ni\{VPN CONNECTED extra: VPN:app\.unifiedvpn\.local\}') {
            $configs = Invoke-IsolatedAdb -AdbArguments @('shell', 'run-as', $package, 'ls', 'no_backup/engines/openflux')
            if ($configs.ExitCode -ne 0) { throw 'Cannot verify OpenFlux private configuration cleanup' }
            if ($configs.Output -match '(?m)^client-[^\s]+\.json\r?$') { throw 'OpenFlux transient configuration remained after stop' }
            return
        }
        Start-Sleep -Milliseconds 500
    } while ([DateTime]::UtcNow -lt $deadline)
    throw 'Android app transport or TUN did not stop within 25 seconds'
}

function Close-IsolatedTestSession {
    param($Ownership, [bool]$Installed, [bool]$Started)
    $cleanupFailed = $false
    try {
        if ($Installed) {
            try {
                if ($Started) {
                    Invoke-CheckedAdb -AdbArguments @('shell', 'am', 'broadcast', '-n', $receiver, '-a', 'org.olcbox.app.DEBUG_STOP_VPN') | Out-Null
                    Wait-TransportStopped
                }
            } catch { $cleanupFailed = $true } finally {
                try {
                    Invoke-CheckedAdb -AdbArguments @('shell', 'am', 'force-stop', $package) -TimeoutSeconds 5 | Out-Null
                } catch { $cleanupFailed = $true } finally {
                    try {
                        Invoke-CheckedAdb -AdbArguments @('shell', 'pm', 'clear', $package) -TimeoutSeconds 10 | Out-Null
                    } catch { $cleanupFailed = $true }
                }
            }
        }
    } finally {
        Stop-OwnedEmulator -Ownership $Ownership
    }
    if ($cleanupFailed) { throw 'Owned guest exited, but isolated app cleanup did not complete; test failed' }
}

function Invoke-OpenFluxAndroidTest {
    param([switch]$Prepare)
    if (-not $Prepare -and (-not $Connect -or -not $ConnectionSlotGranted)) {
        throw 'Root must explicitly grant the connection slot after server readiness and Windows client stop'
    }
    $probeJar = Build-NetworkProbe
    Write-Output 'ANDROID_HTTPS_PROBE_READY=true'
    if ($Prepare) { return }

    $flags = New-AndroidStageFlags
    $stage = 'guest_preflight'
    $ownership = $null
    $cleanupAuthorized = $false
    $installed = $false
    $started = $false
    $appPid = ''
    $logs = ''
    $warningCodes = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    try {
        # No cleanup may target the guest until every ownership and freshness guard has passed.
        $ownership = Confirm-FreshOwnedEmulator -Flags $flags
        $cleanupAuthorized = $true
        $stage = 'app_install'
        Invoke-CheckedAdb -AdbArguments @('install', '-t', $ApkPath) -TimeoutSeconds 120 | Out-Null
        $installed = $true
        $flags.app_installed = $true
        Invoke-CheckedAdb -AdbArguments @('shell', 'pm', 'grant', $package, 'android.permission.POST_NOTIFICATIONS') | Out-Null
        Invoke-CheckedAdb -AdbArguments @('shell', 'appops', 'set', $package, 'ACTIVATE_VPN', 'allow') | Out-Null
        $stage = 'profile_transfer'
        Send-PrivateProfileBundle
        $flags.profile_transferred = $true
        $stage = 'app_launch'
        Invoke-CheckedAdb -AdbArguments @('push', $probeJar, $remoteProbe) | Out-Null
        Invoke-CheckedAdb -AdbArguments @('shell', 'cmd', 'connectivity', 'airplane-mode', 'disable') | Out-Null
        Invoke-CheckedAdb -AdbArguments @('shell', 'svc', 'wifi', 'enable') | Out-Null
        Invoke-CheckedAdb -AdbArguments @('shell', 'input', 'keyevent', 'KEYCODE_MENU') | Out-Null
        Invoke-CheckedAdb -AdbArguments @('shell', 'am', 'start', '-W', '-n', "$package/org.olcbox.app.AppActivity") | Out-Null
        $appPid = Invoke-CheckedAdb -AdbArguments @('shell', 'pidof', $package)
        if ($appPid -notmatch '^\d+$') { throw 'Expected one stable app process for the VPN lifecycle test' }
        $flags.app_started = $true
        Invoke-CheckedAdb -AdbArguments @('logcat', '-c') | Out-Null
        $stage = 'vpn_start'
        $started = $true
        $flags.vpn_start_requested = $true
        $startResult = Invoke-CheckedAdb -AdbArguments @('shell', 'am', 'broadcast', '-n', $receiver, '-a', 'org.olcbox.app.DEBUG_START_VPN')
        if ($startResult -cnotmatch '(?m)^Broadcast completed: result=-1, data="VPN_PREPARED"\r?$') {
            throw 'Debug VPN start did not confirm prepared permission'
        }
        $deadline = [DateTime]::UtcNow.AddSeconds($ReadyTimeoutSeconds)
        $ready = $false
        do {
            $logs = Invoke-CheckedAdb -AdbArguments @('logcat', '-d', '--pid', $appPid, '-v', 'brief', '-s', 'OlcboxVpnService:D', '*:S') -PreserveWhitespace
            Assert-TransportLogsHealthy -Logs $logs -WarningCodes $warningCodes
            if ($logs -match 'OpenFlux encrypted transport ready on 127\.0\.0\.1:' -and
                $logs -match 'OpenFlux VPN tunnel established') { $ready = $true; break }
            Start-Sleep -Milliseconds 500
        } while ([DateTime]::UtcNow -lt $deadline)
        if (-not $ready) { throw 'OpenFlux app did not establish an authenticated TUN within its bounded startup budget' }
        $flags.authenticated_ready = $true
        $stage = 'authenticated_ready'
        $nativePid = Invoke-CheckedAdb -AdbArguments @('shell', 'pidof', 'libopenflux.so')
        $bridgePid = Invoke-CheckedAdb -AdbArguments @('shell', 'pidof', 'libsing-box.so')
        if ($nativePid -notmatch '^\d+$' -or $bridgePid -notmatch '^\d+$') {
            throw 'Expected one OpenFlux core and DNS bridge process'
        }
        $healthCheck = { Assert-ActiveTransport -AppProcessId $appPid -NativeProcessId $nativePid -BridgeProcessId $bridgePid -WarningCodes $warningCodes }
        & $healthCheck
        $flags.tun_verified = $true
        if ((Invoke-CheckedAdb -AdbArguments @('shell', 'id', '-u')) -ne '2000') {
            throw 'HTTPS probe must run as shell, outside the excluded app UID'
        }
        Write-Output 'ANDROID_AUTHENTICATED_OPENFLUX_TUN_READY=true'
        $stage = 'https_probe'
        $probe = Invoke-IsolatedAdb -AdbArguments @('shell', 'env', "CLASSPATH=$remoteProbe", 'app_process',
            '/data/local/tmp', 'NetworkProbe', 'https://www.instagram.com/', 'https://www.wikipedia.org/') `
            -TimeoutSeconds $ProbeTimeoutSeconds -HealthCheck $healthCheck
        & $healthCheck
        $responses = @($probe.Output -split '\r?\n' | Where-Object { $_ -match '^(www\.instagram\.com|www\.wikipedia\.org)_http=[1-5]\d{2}$' })
        if ($probe.ExitCode -ne 0 -or $responses.Count -ne 2 -or
            @($responses | ForEach-Object { ($_ -split '_http=')[0] } | Select-Object -Unique).Count -ne 2) {
            throw 'Real HTTPS through the app VPN did not pass for both targets'
        }
        $crashes = Invoke-CheckedAdb -AdbArguments @('logcat', '-b', 'crash', '-d', '-v', 'brief')
        if ($crashes -match 'app\.unifiedvpn\.local|org\.olcbox\.app') { throw 'App crash detected during VPN test' }
        $flags.https_completed = $true
        $responses | ForEach-Object { Write-Output $_ }
        $stage = 'vpn_stop'
        Invoke-CheckedAdb -AdbArguments @('shell', 'am', 'broadcast', '-n', $receiver, '-a', 'org.olcbox.app.DEBUG_STOP_VPN') | Out-Null
        Wait-TransportStopped
        $started = $false
        $flags.stop_completed = $true
    } catch {
        try {
            Write-AndroidFailureSummary -Stage $stage -Flags $flags -Logs $logs `
                -FailureMessage ([string]$_.Exception.Message) -AppProcessId $appPid -WarningCodes $warningCodes
        } catch { Write-Warning 'Public failure summary could not be persisted; continuing owned cleanup' }
        throw 'Android OpenFlux test failed; see the fixed-field public failure summary'
    } finally {
        try {
            if ($cleanupAuthorized) { Close-IsolatedTestSession -Ownership $ownership -Installed $installed -Started $started }
        } finally {
            [string[]]$safeWarningCodes = @(Get-AndroidTransportWarningCodes -RetainedCodes @($warningCodes))
            Write-Output ('ANDROID_PUBLIC_WARNING_CODES=' + (ConvertTo-Json -InputObject $safeWarningCodes -Compress))
        }
    }
    Write-Output 'ANDROID_APP_VPN_HTTPS_AND_STOP_PASSED=true'
}

# Dot-sourcing loads definitions for synthetic orchestration tests without ADB, builds, or private reads.
if ($MyInvocation.InvocationName -ne '.') {
    Invoke-OpenFluxAndroidTest -Prepare:($PSCmdlet.ParameterSetName -eq 'Prepare')
}
