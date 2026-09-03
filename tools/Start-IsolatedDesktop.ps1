param(
    [Parameter(Mandatory = $true)]
    [string]$Executable,
    [string]$DataRoot = "",
    [string]$Language = "",
    [int]$TimeoutSeconds = 30
)

$ErrorActionPreference = "Stop"
$executablePath = (Resolve-Path -LiteralPath $Executable).Path
if ([string]::IsNullOrWhiteSpace($DataRoot)) {
    $DataRoot = Join-Path $env:TEMP ("unified-vpn-manual-" + [Guid]::NewGuid().ToString("N"))
}
$resolvedDataRoot = [System.IO.Path]::GetFullPath($DataRoot)
$roaming = Join-Path $resolvedDataRoot "Roaming"
$local = Join-Path $resolvedDataRoot "Local"
New-Item -ItemType Directory -Path $roaming, $local -Force | Out-Null

$startInfo = [System.Diagnostics.ProcessStartInfo]::new()
$startInfo.FileName = $executablePath
$startInfo.WorkingDirectory = Split-Path -Parent $executablePath
$startInfo.UseShellExecute = $false
$startInfo.EnvironmentVariables["APPDATA"] = $roaming
$startInfo.EnvironmentVariables["LOCALAPPDATA"] = $local
if (-not [string]::IsNullOrWhiteSpace($Language)) {
    $startInfo.EnvironmentVariables["JAVA_TOOL_OPTIONS"] = "-Duser.language=$Language"
}
$process = [System.Diagnostics.Process]::Start($startInfo)
$deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
$windowProcess = $null

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

while ([DateTime]::UtcNow -lt $deadline -and -not $process.HasExited) {
    foreach ($candidateId in (Get-DescendantProcessIds -RootProcessId $process.Id)) {
        $candidate = Get-Process -Id $candidateId -ErrorAction SilentlyContinue
        if ($null -ne $candidate -and $candidate.MainWindowHandle -ne [IntPtr]::Zero) {
            $windowProcess = $candidate
            break
        }
    }
    if ($null -ne $windowProcess) { break }
    Start-Sleep -Milliseconds 100
}

if ($process.HasExited) {
    throw "Unified VPN exited during isolated startup with code $($process.ExitCode)"
}
if ($null -eq $windowProcess) {
    & taskkill.exe /PID $process.Id /T /F | Out-Null
    throw "Unified VPN window did not appear within $TimeoutSeconds seconds"
}

[PSCustomObject]@{
    executable = $executablePath
    processId = $process.Id
    windowProcessId = $windowProcess.Id
    windowHandle = $windowProcess.MainWindowHandle.ToInt64()
    dataRoot = $resolvedDataRoot
} | ConvertTo-Json -Compress
