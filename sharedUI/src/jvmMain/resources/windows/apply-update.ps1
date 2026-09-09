$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$PSModuleAutoLoadingPreference = 'None'
$env:PSModulePath = ''
Set-StrictMode -Version Latest

$script:LogFile = $null
$work = $null
$backup = $null
$lockStream = $null
$lockPath = $null
$movedNames = [System.Collections.Generic.List[string]]::new()
$committed = $false

function Test-PathExists([string]$Path) {
    return [IO.File]::Exists($Path) -or [IO.Directory]::Exists($Path)
}

function Test-CurrentProcessElevated {
    $identity = [Security.Principal.WindowsIdentity]::GetCurrent()
    try {
        $principal = [Security.Principal.WindowsPrincipal]::new($identity)
        return $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
    } finally {
        $identity.Dispose()
    }
}

function Write-UpdateLog([string]$Message) {
    if ([string]::IsNullOrWhiteSpace($script:LogFile)) {
        return
    }
    try {
        $line = "$([DateTime]::UtcNow.ToString('o')) $Message$([Environment]::NewLine)"
        [IO.File]::AppendAllText($script:LogFile, $line, [Text.Encoding]::UTF8)
    } catch {
        # Logging must never alter the update transaction.
    }
}

function Remove-Path([string]$Path) {
    if ([IO.Directory]::Exists($Path)) {
        [IO.Directory]::Delete($Path, $true)
    } elseif ([IO.File]::Exists($Path)) {
        [IO.File]::Delete($Path)
    }
}

function Remove-UpdateDirectory(
    [string]$Path,
    [string]$ExpectedParent,
    [string]$RequiredPrefix
) {
    if ([string]::IsNullOrWhiteSpace($Path) -or -not [IO.Directory]::Exists($Path)) {
        return
    }
    $full = [IO.Path]::GetFullPath($Path)
    $parent = [IO.Directory]::GetParent($full)
    if ($null -eq $parent -or
        -not $parent.FullName.Equals([IO.Path]::GetFullPath($ExpectedParent), [StringComparison]::OrdinalIgnoreCase) -or
        -not ([IO.Path]::GetFileName($full).StartsWith($RequiredPrefix, [StringComparison]::Ordinal))) {
        throw 'Refusing to remove an unsafe updater directory'
    }
    [IO.Directory]::Delete($full, $true)
}

function Move-Path([string]$Source, [string]$Destination) {
    if ([IO.Directory]::Exists($Source)) {
        [IO.Directory]::Move($Source, $Destination)
    } elseif ([IO.File]::Exists($Source)) {
        [IO.File]::Move($Source, $Destination)
    } else {
        throw "Update path is missing: $Source"
    }
}

function Restore-PreviousVersion(
    [string]$Target,
    [string]$Backup,
    [System.Collections.Generic.List[string]]$MovedNames
) {
    $restored = $true
    for ($index = $MovedNames.Count - 1; $index -ge 0; $index--) {
        $name = $MovedNames[$index]
        $previous = [IO.Path]::Combine($Backup, $name)
        if (-not (Test-PathExists $previous)) {
            Write-UpdateLog "Rollback copy is missing: $name"
            $restored = $false
            continue
        }
        try {
            $current = [IO.Path]::Combine($Target, $name)
            Remove-Path $current
            Move-Path -Source $previous -Destination $current
        } catch {
            Write-UpdateLog "Rollback failed for $name`: $($_.Exception.Message)"
            $restored = $false
        }
    }
    return $restored
}

function Assert-RequiredFiles([string]$Root, [string[]]$Required) {
    foreach ($relative in $Required) {
        $file = [IO.Path]::Combine($Root, $relative)
        if (-not [IO.File]::Exists($file) -or ([IO.FileInfo]::new($file)).Length -le 0) {
            throw "Unified VPN update is missing $relative"
        }
    }
}

function Get-Sha256([string]$Path) {
    $stream = [IO.File]::Open($Path, [IO.FileMode]::Open, [IO.FileAccess]::Read, [IO.FileShare]::Read)
    $sha256 = [Security.Cryptography.SHA256]::Create()
    try {
        $bytes = $sha256.ComputeHash($stream)
        return ([BitConverter]::ToString($bytes)).Replace('-', '').ToLowerInvariant()
    } finally {
        $sha256.Dispose()
        $stream.Dispose()
    }
}

function Expand-VerifiedArchive([string]$Archive, [string]$Destination) {
    $null = [Reflection.Assembly]::Load('System.IO.Compression')
    $null = [Reflection.Assembly]::Load('System.IO.Compression.FileSystem')
    [IO.Directory]::CreateDirectory($Destination) | Out-Null
    $destinationFull = [IO.Path]::GetFullPath($Destination)
    $boundary = $destinationFull.TrimEnd([IO.Path]::DirectorySeparatorChar) + [IO.Path]::DirectorySeparatorChar
    $totalBytes = 0L
    $zip = [IO.Compression.ZipFile]::OpenRead($Archive)
    try {
        foreach ($entry in $zip.Entries) {
            $name = $entry.FullName.Replace('\', '/').TrimEnd('/')
            if ([string]::IsNullOrWhiteSpace($name)) {
                continue
            }
            $hasUnsafeSegment = $false
            foreach ($segment in $name.Split('/')) {
                if ($segment -eq '..' -or $segment -eq '.' -or $segment -eq '') {
                    $hasUnsafeSegment = $true
                    break
                }
            }
            if ($name.StartsWith('/') -or $name -match '^[A-Za-z]:' -or $hasUnsafeSegment) {
                throw 'Unified VPN update contains an unsafe archive path'
            }
            $relative = $name.Replace('/', [IO.Path]::DirectorySeparatorChar)
            $outputPath = [IO.Path]::GetFullPath([IO.Path]::Combine($destinationFull, $relative))
            if (-not $outputPath.StartsWith($boundary, [StringComparison]::OrdinalIgnoreCase)) {
                throw 'Unified VPN update archive escapes its work directory'
            }
            if ([string]::IsNullOrEmpty($entry.Name)) {
                [IO.Directory]::CreateDirectory($outputPath) | Out-Null
                continue
            }
            $parent = [IO.Directory]::GetParent($outputPath)
            if ($null -eq $parent) {
                throw 'Unified VPN update contains an invalid archive path'
            }
            [IO.Directory]::CreateDirectory($parent.FullName) | Out-Null
            $input = $entry.Open()
            $output = [IO.File]::Open(
                $outputPath,
                [IO.FileMode]::CreateNew,
                [IO.FileAccess]::Write,
                [IO.FileShare]::None
            )
            try {
                $buffer = [byte[]]::new(81920)
                while (($read = $input.Read($buffer, 0, $buffer.Length)) -gt 0) {
                    $totalBytes += $read
                    if ($totalBytes -gt 1000000000L) {
                        throw 'Unified VPN update is larger than the allowed limit'
                    }
                    $output.Write($buffer, 0, $read)
                }
            } finally {
                $output.Dispose()
                $input.Dispose()
            }
        }
    } finally {
        $zip.Dispose()
    }
}

function Copy-Directory([string]$Source, [string]$Destination) {
    if (-not [IO.Directory]::Exists($Source)) {
        throw "Update directory is missing: $Source"
    }
    [IO.Directory]::CreateDirectory($Destination) | Out-Null
    foreach ($file in [IO.Directory]::EnumerateFiles($Source)) {
        [IO.File]::Copy($file, [IO.Path]::Combine($Destination, [IO.Path]::GetFileName($file)), $false)
    }
    foreach ($directory in [IO.Directory]::EnumerateDirectories($Source)) {
        Copy-Directory -Source $directory -Destination ([IO.Path]::Combine($Destination, [IO.Path]::GetFileName($directory)))
    }
}

function Test-ProcessRunning([long]$ProcessId) {
    try {
        $process = [Diagnostics.Process]::GetProcessById([int]$ProcessId)
        try {
            return -not $process.HasExited
        } finally {
            $process.Dispose()
        }
    } catch [ArgumentException] {
        return $false
    }
}

function Start-ExplorerLauncher([string]$Launcher) {
    $systemDirectory = [Environment]::SystemDirectory
    $windowsDirectory = [IO.Directory]::GetParent($systemDirectory)
    if ($null -eq $windowsDirectory) {
        throw 'Trusted Windows directory could not be resolved'
    }
    $explorer = [IO.Path]::Combine($windowsDirectory.FullName, 'explorer.exe')
    if (-not [IO.File]::Exists($explorer)) {
        throw 'Trusted Windows Explorer executable is missing'
    }
    $info = [Diagnostics.ProcessStartInfo]::new()
    $info.FileName = $explorer
    $info.Arguments = '"' + $Launcher + '"'
    $info.UseShellExecute = $false
    $info.WindowStyle = [Diagnostics.ProcessWindowStyle]::Hidden
    $process = [Diagnostics.Process]::Start($info)
    if ($null -eq $process) {
        throw 'Unified VPN restart request failed'
    }
    $process.Dispose()
}

if (Test-CurrentProcessElevated) {
    [Console]::Error.WriteLine(
        'Automatic Unified VPN replacement is disabled for an administrator process. Restart normally.'
    )
    exit 1
}

$target = [IO.Path]::GetFullPath($TargetRoot)
$archive = [IO.Path]::GetFullPath($ArchivePath)
$launcher = [IO.Path]::Combine($target, $LauncherName)
$managed = @('app', 'runtime', $LauncherName)
$required = @(
    $LauncherName,
    'app\UnifiedVPN.cfg',
    'runtime\bin\server\jvm.dll',
    'runtime\lib\modules'
)

try {
    $targetRootPath = [IO.Path]::GetPathRoot($target)
    if ($ParentPid -le 0 -or
        $target.Equals($targetRootPath, [StringComparison]::OrdinalIgnoreCase) -or
        -not [IO.Directory]::Exists($target)) {
        throw 'Unsafe Unified VPN update target'
    }
    if (([IO.File]::GetAttributes($target) -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
        throw 'Unified VPN update target must not be a reparse point'
    }
    if (-not [IO.File]::Exists($archive)) {
        throw 'Downloaded Unified VPN update is missing'
    }
    if ($ExpectedSha256 -notmatch '^[a-fA-F0-9]{64}$') {
        throw 'Unified VPN update SHA-256 is invalid'
    }
    if ($ArchivePrefix -match '(^[\\/])|(^|[\\/])\.\.?(?:[\\/]|$)') {
        throw 'Unified VPN archive prefix is unsafe'
    }
    foreach ($name in $managed) {
        $managedPath = [IO.Path]::Combine($target, $name)
        if ((Test-PathExists $managedPath) -and
            (([IO.File]::GetAttributes($managedPath) -band [IO.FileAttributes]::ReparsePoint) -ne 0)) {
            throw "Unified VPN managed path must not be a reparse point: $name"
        }
    }

    $lockPath = [IO.Path]::Combine($target, '.unifiedvpn-update.lock')
    if ([IO.File]::Exists($lockPath) -and
        (([IO.File]::GetAttributes($lockPath) -band [IO.FileAttributes]::ReparsePoint) -ne 0)) {
        throw 'Unified VPN updater lock must not be a reparse point'
    }
    try {
        $lockStream = [IO.File]::Open(
            $lockPath,
            [IO.FileMode]::OpenOrCreate,
            [IO.FileAccess]::ReadWrite,
            [IO.FileShare]::None
        )
    } catch [IO.IOException] {
        throw 'Another Unified VPN update is already running'
    }

    $script:LogFile = [IO.Path]::Combine(
        $target,
        '.unifiedvpn-update-' + [guid]::NewGuid().ToString('N') + '.log'
    )
    Write-UpdateLog 'Preparing verified Unified VPN update'

    $work = [IO.Path]::Combine($target, '.update-work-' + [guid]::NewGuid().ToString('N'))
    if (Test-PathExists $work) {
        throw 'Updater work directory already exists'
    }
    [IO.Directory]::CreateDirectory($work) | Out-Null
    $trustedArchive = [IO.Path]::Combine($work, 'update.zip')
    [IO.File]::Copy($archive, $trustedArchive, $false)
    $actualSha256 = Get-Sha256 $trustedArchive
    if ($actualSha256 -ne $ExpectedSha256.ToLowerInvariant()) {
        throw 'Unified VPN update changed after download verification'
    }

    $extractRoot = [IO.Path]::Combine($work, 'payload')
    Expand-VerifiedArchive -Archive $trustedArchive -Destination $extractRoot
    if ([string]::IsNullOrEmpty($ArchivePrefix)) {
        $staged = $extractRoot
    } else {
        $staged = [IO.Path]::Combine($extractRoot, $ArchivePrefix.Replace('/', '\'))
    }
    $staged = [IO.Path]::GetFullPath($staged)
    $extractBoundary = [IO.Path]::GetFullPath($extractRoot).TrimEnd('\') + '\'
    if (-not $staged.Equals([IO.Path]::GetFullPath($extractRoot), [StringComparison]::OrdinalIgnoreCase) -and
        -not $staged.StartsWith($extractBoundary, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'Unified VPN staged directory is outside the updater work directory'
    }
    Assert-RequiredFiles -Root $staged -Required $required

    $expectedVersionLine = "java-options=-Djpackage.app-version=$ExpectedVersion"
    $configLines = [IO.File]::ReadAllLines([IO.Path]::Combine($staged, 'app\UnifiedVPN.cfg'))
    $versionMatches = $false
    foreach ($line in $configLines) {
        if ($line.Trim() -eq $expectedVersionLine) {
            $versionMatches = $true
            break
        }
    }
    if (-not $versionMatches) {
        throw "Portable update version does not match $ExpectedVersion"
    }

    Write-UpdateLog 'Waiting for Unified VPN to close'
    $deadline = [DateTime]::UtcNow.AddSeconds(120)
    while ((Test-ProcessRunning $ParentPid) -and [DateTime]::UtcNow -lt $deadline) {
        [Threading.Thread]::Sleep(250)
    }
    if (Test-ProcessRunning $ParentPid) {
        throw 'Unified VPN did not close before the update timeout'
    }

    Assert-RequiredFiles -Root $target -Required $required
    $backup = [IO.Path]::Combine($target, '.update-backup-' + [guid]::NewGuid().ToString('N'))
    if (Test-PathExists $backup) {
        throw 'Updater backup directory already exists'
    }
    [IO.Directory]::CreateDirectory($backup) | Out-Null
    foreach ($name in $managed) {
        $current = [IO.Path]::Combine($target, $name)
        Move-Path -Source $current -Destination ([IO.Path]::Combine($backup, $name))
        $movedNames.Add($name)
    }

    Copy-Directory -Source ([IO.Path]::Combine($staged, 'app')) -Destination ([IO.Path]::Combine($target, 'app'))
    Copy-Directory -Source ([IO.Path]::Combine($staged, 'runtime')) -Destination ([IO.Path]::Combine($target, 'runtime'))
    [IO.File]::Copy([IO.Path]::Combine($staged, $LauncherName), $launcher, $false)
    Assert-RequiredFiles -Root $target -Required $required

    $verifyInfo = [Diagnostics.ProcessStartInfo]::new()
    $verifyInfo.FileName = $launcher
    $verifyInfo.Arguments = '--verify-native-assets'
    $verifyInfo.WorkingDirectory = $target
    $verifyInfo.UseShellExecute = $false
    $verifyInfo.CreateNoWindow = $true
    $verificationMarker = [IO.Path]::Combine(
        $work,
        'unifiedvpn-native-assets-' + [guid]::NewGuid().ToString('N') + '.ok'
    )
    $verifyInfo.EnvironmentVariables['UNIFIEDVPN_NATIVE_VERIFY_MARKER'] = $verificationMarker
    $verification = [Diagnostics.Process]::Start($verifyInfo)
    if ($null -eq $verification) {
        throw 'Updated launcher verification could not start'
    }
    try {
        if (-not $verification.WaitForExit(60000)) {
            $verification.Kill()
            throw 'Updated launcher verification timed out'
        }
        if ($verification.ExitCode -ne 0) {
            throw "Updated launcher verification failed with exit code $($verification.ExitCode)"
        }
        if (-not [IO.File]::Exists($verificationMarker)) {
            throw 'Updated launcher did not start its JVM verification code'
        }
        $verificationMessage = [IO.File]::ReadAllText($verificationMarker).Trim()
        if ($verificationMessage -notmatch '^Verified [1-9][0-9]* desktop native assets$') {
            throw 'Updated launcher returned an invalid native-assets verification marker'
        }
    } finally {
        $verification.Dispose()
    }

    try {
        $key = [Microsoft.Win32.Registry]::CurrentUser.OpenSubKey(
            'Software\Microsoft\Windows NT\CurrentVersion\AppCompatFlags\Layers',
            $true
        )
        if ($null -ne $key) {
            try {
                $key.DeleteValue($launcher, $false)
            } finally {
                $key.Dispose()
            }
        }
    } catch {
        Write-UpdateLog "Compatibility flag cleanup failed: $($_.Exception.Message)"
    }

    Start-ExplorerLauncher $launcher
    $committed = $true
    Write-UpdateLog 'Unified VPN update applied and restart requested successfully'

    try {
        Remove-UpdateDirectory -Path $backup -ExpectedParent $target -RequiredPrefix '.update-backup-'
        $backup = $null
    } catch {
        Write-UpdateLog "Verified backup was preserved: $($_.Exception.Message)"
    }
    try {
        Remove-UpdateDirectory -Path $work -ExpectedParent $target -RequiredPrefix '.update-work-'
        $work = $null
    } catch {
        Write-UpdateLog "Updater work directory cleanup failed: $($_.Exception.Message)"
    }
    $lockStream.Dispose()
    $lockStream = $null
    try {
        [IO.File]::Delete($lockPath)
    } catch {
        Write-UpdateLog "Updater lock cleanup failed: $($_.Exception.Message)"
    }
    exit 0
} catch {
    $message = $_.Exception.Message
    Write-UpdateLog "Update failed: $message"
    if (-not $committed -and $movedNames.Count -gt 0 -and
        -not [string]::IsNullOrWhiteSpace($backup)) {
        $rollbackComplete = Restore-PreviousVersion -Target $target -Backup $backup -MovedNames $movedNames
        if ($rollbackComplete) {
            Write-UpdateLog "Previous Unified VPN version restored; backup retained at $backup"
        } else {
            Write-UpdateLog "Rollback was incomplete; recovery files retained at $backup"
        }
    }
    if (-not [string]::IsNullOrWhiteSpace($work)) {
        try {
            Remove-UpdateDirectory -Path $work -ExpectedParent $target -RequiredPrefix '.update-work-'
        } catch {
            Write-UpdateLog "Failed updater work directory was preserved: $($_.Exception.Message)"
        }
    }
    if ($null -ne $lockStream) {
        $lockStream.Dispose()
        $lockStream = $null
    }
    if (-not [string]::IsNullOrWhiteSpace($lockPath)) {
        try {
            [IO.File]::Delete($lockPath)
        } catch {
            Write-UpdateLog "Updater lock cleanup failed: $($_.Exception.Message)"
        }
    }
    if ([IO.File]::Exists($launcher)) {
        try {
            Start-ExplorerLauncher $launcher
        } catch {
            Write-UpdateLog "Restored launcher could not be restarted: $($_.Exception.Message)"
        }
    }
    exit 1
}
