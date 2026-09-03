param(
    [Parameter(Mandatory = $true)]
    [string]$Executable
)

$ErrorActionPreference = "Stop"
$target = (Resolve-Path -LiteralPath $Executable).Path
$repositoryRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot ".."))
$desktopBuildRoot = [System.IO.Path]::GetFullPath((Join-Path $repositoryRoot "desktopApp\build"))

if (-not $target.StartsWith($desktopBuildRoot, [StringComparison]::OrdinalIgnoreCase)) {
    throw "Refusing to stop a process outside desktopApp/build: $target"
}

$matching = Get-CimInstance Win32_Process | Where-Object {
    $_.ExecutablePath -and $_.ExecutablePath.Equals($target, [StringComparison]::OrdinalIgnoreCase)
}

foreach ($item in $matching) {
    & taskkill.exe /PID $item.ProcessId /T /F | Out-Null
    if ($LASTEXITCODE -ne 0 -and
        $null -ne (Get-Process -Id $item.ProcessId -ErrorAction SilentlyContinue)) {
        throw "Could not stop local desktop build process tree $($item.ProcessId)"
    }
    Write-Output "Stopped local desktop build process tree $($item.ProcessId)"
}
