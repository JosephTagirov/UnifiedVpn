#requires -Version 7.0
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$RunRoot,
    [Parameter(Mandatory = $true)]
    [ValidateRange(1, 2147483647)]
    [int]$LauncherProcessId,
    [Parameter(Mandatory = $true)]
    [ValidateRange(1, 2147483647)]
    [int]$QemuProcessId,
    [Parameter(Mandatory = $true)]
    [switch]$FreshRunConfirmed,
    [string]$SdkRoot = (Join-Path $env:USERPROFILE 'Android\Sdk'),
    [ValidatePattern('^emulator-\d+$')]
    [string]$EmulatorSerial = 'emulator-5580',
    [ValidatePattern('^UnifiedVPN_OpenFlux_[A-Za-z0-9_]+$')]
    [string]$ExpectedAvdName = 'UnifiedVPN_OpenFlux_Smoke'
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'Test-OpenFluxAndroid.ps1') -SdkRoot $SdkRoot `
    -EmulatorSerial $EmulatorSerial -ExpectedAvdName $ExpectedAvdName

function Write-OpenFluxEmulatorOwnership {
    param([string]$IsolatedRunRoot, [int]$LauncherId, [int]$QemuId, [switch]$Confirmed)
    if (-not $Confirmed -or $LauncherId -le 0 -or $QemuId -le 0 -or $LauncherId -eq $QemuId) {
        throw 'Explicit fresh-run confirmation and distinct launcher/child IDs are required'
    }
    $smokeRoot = [IO.Path]::GetFullPath((Join-Path $repo '.downloads\openflux\emulator-smoke'))
    $absoluteRun = [IO.Path]::GetFullPath($IsolatedRunRoot)
    if ((Split-Path -Parent $absoluteRun) -ne $smokeRoot -or
        (Split-Path -Leaf $absoluteRun) -notmatch '^run-[0-9a-f]{32}$') {
        throw 'Ownership recovery is restricted to the isolated OpenFlux run directory'
    }
    $avdDirectory = Join-Path $absoluteRun "avd\$ExpectedAvdName.avd"
    for ($path = $avdDirectory; $path -ne $repo; $path = Split-Path -Parent $path) {
        $item = Get-Item -LiteralPath $path
        if (-not $item.PSIsContainer -or ($item.Attributes -band [IO.FileAttributes]::ReparsePoint)) {
            throw 'Ownership recovery requires real directories inside the isolated run'
        }
    }
    $ownershipPath = Join-Path $absoluteRun 'ownership.json'
    if (Test-Path -LiteralPath $ownershipPath) { throw 'Ownership recovery must not replace an existing record' }

    # Inspect only the explicitly supplied host IDs; never discover, start, or stop a guest.
    $launcher = Get-EmulatorHostProcess -ProcessId $LauncherId
    $qemu = Get-EmulatorHostProcess -ProcessId $QemuId
    if ($null -eq $launcher -or $null -eq $qemu -or
        $launcher.executable -ne (Join-Path $SdkRoot 'emulator\emulator.exe') -or
        $qemu.executable -notin @(Get-AllowedQemuExecutablePaths) -or
        $qemu.parent_process_id -ne $LauncherId) {
        throw 'Supplied host IDs are not the exact SDK launcher and its approved QEMU child'
    }
    $runCreated = (Get-Item -LiteralPath $absoluteRun).CreationTimeUtc
    $now = [DateTime]::UtcNow
    $launcherCreated = [DateTime]::new([long]$launcher.start_time_utc_ticks, [DateTimeKind]::Utc)
    $qemuCreated = [DateTime]::new([long]$qemu.start_time_utc_ticks, [DateTimeKind]::Utc)
    if ($runCreated -lt $now.AddHours(-2) -or $runCreated -gt $now.AddSeconds(1) -or
        $launcherCreated -lt $runCreated.AddSeconds(-1) -or $launcherCreated -gt $now -or
        $qemuCreated -lt $launcherCreated -or $qemuCreated -gt $now) {
        throw 'Ownership recovery is limited to a recent newly created run and its launch sequence'
    }
    foreach ($hostProcess in @($launcher, $qemu)) {
        Assert-EmulatorLaunchArguments -CommandLine $hostProcess.command_line -AvdDirectory $avdDirectory
    }
    $record = [ordered]@{
        version = 1
        fresh_userdata = $true
        run_root = $absoluteRun
        avd_directory = $avdDirectory
        avd_name = $ExpectedAvdName
        emulator_serial = $EmulatorSerial
        processes = @(
            [ordered]@{ role = 'launcher'; process_id = $LauncherId
                start_time_utc_ticks = $launcher.start_time_utc_ticks; executable = $launcher.executable },
            [ordered]@{ role = 'qemu'; process_id = $QemuId
                start_time_utc_ticks = $qemu.start_time_utc_ticks; executable = $qemu.executable }
        )
    }
    $bytes = [Text.UTF8Encoding]::new($false).GetBytes(($record | ConvertTo-Json -Depth 5))
    $file = [IO.File]::Open($ownershipPath, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write, [IO.FileShare]::None)
    try { $file.Write($bytes, 0, $bytes.Length) } finally { $file.Dispose() }
    Write-Output ('SMOKE_OWNERSHIP_RECORD=' + $ownershipPath)
}

if ($MyInvocation.InvocationName -ne '.') {
    Write-OpenFluxEmulatorOwnership -IsolatedRunRoot $RunRoot -LauncherId $LauncherProcessId `
        -QemuId $QemuProcessId -Confirmed:$FreshRunConfirmed
}
