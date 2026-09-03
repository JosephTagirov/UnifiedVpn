param(
    [Parameter(Mandatory = $true)]
    [string]$Executable,
    [int]$TimeoutSeconds = 30
)

$ErrorActionPreference = "Stop"
trap {
    Write-Error ("Startup measurement failed at line {0}: {1}" -f $_.InvocationInfo.ScriptLineNumber, $_.Exception.Message)
    break
}
$executablePath = (Resolve-Path -LiteralPath $Executable).Path
$tempRoot = [System.IO.Path]::GetFullPath([System.IO.Path]::GetTempPath())
$isolatedRoot = Join-Path $tempRoot ("unified-vpn-startup-" + [Guid]::NewGuid().ToString("N"))
$roaming = Join-Path $isolatedRoot "Roaming"
$local = Join-Path $isolatedRoot "Local"
New-Item -ItemType Directory -Path $roaming, $local -Force | Out-Null

$startInfo = [System.Diagnostics.ProcessStartInfo]::new()
$startInfo.FileName = $executablePath
$startInfo.WorkingDirectory = Split-Path -Parent $executablePath
$startInfo.UseShellExecute = $false
$startInfo.RedirectStandardOutput = $true
$startInfo.RedirectStandardError = $true

$stopwatch = [System.Diagnostics.Stopwatch]::StartNew()
$originalAppData = [Environment]::GetEnvironmentVariable("APPDATA", "Process")
$originalLocalAppData = [Environment]::GetEnvironmentVariable("LOCALAPPDATA", "Process")
try {
    [Environment]::SetEnvironmentVariable("APPDATA", $roaming, "Process")
    [Environment]::SetEnvironmentVariable("LOCALAPPDATA", $local, "Process")
    $process = [System.Diagnostics.Process]::Start($startInfo)
}
finally {
    [Environment]::SetEnvironmentVariable("APPDATA", $originalAppData, "Process")
    [Environment]::SetEnvironmentVariable("LOCALAPPDATA", $originalLocalAppData, "Process")
}
$windowReadyMs = $null
$windowProcess = $null
$deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)

function Get-DescendantProcessIds([int]$RootProcessId) {
    $processes = Get-CimInstance Win32_Process | Select-Object ProcessId, ParentProcessId
    $ids = [System.Collections.Generic.HashSet[int]]::new()
    $null = $ids.Add($RootProcessId)
    do {
        $added = $false
        foreach ($candidate in $processes) {
            if ($ids.Contains([int]$candidate.ParentProcessId) -and
                $ids.Add([int]$candidate.ProcessId)) {
                $added = $true
            }
        }
    } while ($added)
    return $ids
}

try {
    while ([DateTime]::UtcNow -lt $deadline -and -not $process.HasExited) {
        foreach ($processId in (Get-DescendantProcessIds -RootProcessId $process.Id)) {
            $candidate = Get-Process -Id $processId -ErrorAction SilentlyContinue
            if ($null -ne $candidate -and $candidate.MainWindowHandle -ne [IntPtr]::Zero) {
                $windowProcess = $candidate
                $windowReadyMs = $stopwatch.ElapsedMilliseconds
                break
            }
        }
        if ($null -ne $windowReadyMs) { break }
        Start-Sleep -Milliseconds 50
    }

    if ($null -eq $windowReadyMs) {
        throw "Unified VPN window did not appear within $TimeoutSeconds seconds"
    }

    [PSCustomObject]@{
        executable = $executablePath
        processId = $process.Id
        windowProcessId = $windowProcess.Id
        windowReadyMs = $windowReadyMs
    } | ConvertTo-Json -Compress
}
finally {
    if ($null -ne $windowProcess -and -not $windowProcess.HasExited) {
        $null = $windowProcess.CloseMainWindow()
    }
    if (-not $process.HasExited) {
        if (-not $process.WaitForExit(5000)) {
            & taskkill.exe /PID $process.Id /T /F | Out-Null
        }
    }

    $resolvedIsolatedRoot = [System.IO.Path]::GetFullPath($isolatedRoot)
    if ($resolvedIsolatedRoot.StartsWith($tempRoot, [StringComparison]::OrdinalIgnoreCase)) {
        Remove-Item -LiteralPath $resolvedIsolatedRoot -Recurse -Force -ErrorAction SilentlyContinue
    }
}
