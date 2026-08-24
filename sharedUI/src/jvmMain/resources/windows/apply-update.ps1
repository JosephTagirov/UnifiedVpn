param(
    [Parameter(Mandatory = $true)][long]$ParentPid,
    [Parameter(Mandatory = $true)][string]$StagedRoot,
    [Parameter(Mandatory = $true)][string]$TargetRoot,
    [Parameter(Mandatory = $true)][string]$LauncherName,
    [Parameter(Mandatory = $true)][string]$LogFile
)

$ErrorActionPreference = 'Stop'

function Write-UpdateLog([string]$Message) {
    $line = "$([DateTime]::UtcNow.ToString('o')) $Message"
    Add-Content -LiteralPath $LogFile -Value $line -Encoding UTF8
}

function Restore-PreviousVersion(
    [string]$Target,
    [string]$Backup,
    [string[]]$ManagedNames
) {
    foreach ($name in $ManagedNames) {
        $current = Join-Path $Target $name
        if (Test-Path -LiteralPath $current) {
            Remove-Item -LiteralPath $current -Recurse -Force
        }
        $previous = Join-Path $Backup $name
        if (Test-Path -LiteralPath $previous) {
            Move-Item -LiteralPath $previous -Destination $current
        }
    }
}

$staged = [IO.Path]::GetFullPath($StagedRoot)
$target = [IO.Path]::GetFullPath($TargetRoot)
if ($staged -eq $target -or $target -eq [IO.Path]::GetPathRoot($target)) {
    throw 'Unsafe Unified VPN update target'
}

$required = @(
    $LauncherName,
    'app\UnifiedVPN.cfg',
    'runtime\bin\server\jvm.dll',
    'runtime\lib\modules'
)
foreach ($relative in $required) {
    if (-not (Test-Path -LiteralPath (Join-Path $staged $relative) -PathType Leaf)) {
        throw "Staged update is missing $relative"
    }
}
if (-not (Test-Path -LiteralPath (Join-Path $target $LauncherName) -PathType Leaf)) {
    throw 'Current Unified VPN launcher is missing'
}

Write-UpdateLog 'Waiting for Unified VPN to close'
$deadline = [DateTime]::UtcNow.AddSeconds(120)
while ((Get-Process -Id $ParentPid -ErrorAction SilentlyContinue) -and [DateTime]::UtcNow -lt $deadline) {
    Start-Sleep -Milliseconds 250
}
if (Get-Process -Id $ParentPid -ErrorAction SilentlyContinue) {
    throw 'Unified VPN did not close before the update timeout'
}

$managed = @('app', 'runtime', $LauncherName)
$backup = Join-Path $target ('.update-backup-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $backup | Out-Null

foreach ($name in $managed) {
    $current = Join-Path $target $name
    if (Test-Path -LiteralPath $current) {
        Move-Item -LiteralPath $current -Destination (Join-Path $backup $name)
    }
}

try {
    Copy-Item -LiteralPath (Join-Path $staged 'app') -Destination (Join-Path $target 'app') -Recurse -Force
    Copy-Item -LiteralPath (Join-Path $staged 'runtime') -Destination (Join-Path $target 'runtime') -Recurse -Force
    Copy-Item -LiteralPath (Join-Path $staged $LauncherName) -Destination (Join-Path $target $LauncherName) -Force

    $launcher = Join-Path $target $LauncherName
    $verification = Start-Process -FilePath $launcher -ArgumentList '--verify-native-assets' -WorkingDirectory $target -PassThru -Wait
    if ($verification.ExitCode -ne 0) {
        throw "Updated launcher verification failed with exit code $($verification.ExitCode)"
    }

    $layers = 'HKCU:\Software\Microsoft\Windows NT\CurrentVersion\AppCompatFlags\Layers'
    if (Test-Path -LiteralPath $layers) {
        Remove-ItemProperty -LiteralPath $layers -Name $launcher -ErrorAction SilentlyContinue
    }

    Remove-Item -LiteralPath $backup -Recurse -Force
    Remove-Item -LiteralPath $staged -Recurse -Force
    Write-UpdateLog 'Unified VPN update applied successfully'
    Start-Process -FilePath (Join-Path $env:WINDIR 'explorer.exe') -ArgumentList ('"' + $launcher + '"') -WindowStyle Hidden
} catch {
    $message = $_.Exception.Message
    Write-UpdateLog ("Update failed: " + $message)
    Restore-PreviousVersion -Target $target -Backup $backup -ManagedNames $managed
    if (Test-Path -LiteralPath $backup) {
        Remove-Item -LiteralPath $backup -Recurse -Force
    }
    $launcher = Join-Path $target $LauncherName
    if (Test-Path -LiteralPath $launcher -PathType Leaf) {
        Start-Process -FilePath (Join-Path $env:WINDIR 'explorer.exe') -ArgumentList ('"' + $launcher + '"') -WindowStyle Hidden
    }
    exit 1
}
