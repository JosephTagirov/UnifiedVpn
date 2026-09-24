param(
    [Parameter(Mandatory=$true)][string]$NativeDirectory,
    [Parameter(Mandatory=$true)][string]$OutputFile
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$source = Join-Path $projectRoot 'desktopApp\src\windowsTunHelper'
$compiler = Join-Path ([Environment]::GetFolderPath('Windows')) 'Microsoft.NET\Framework64\v4.0.30319\csc.exe'
if (-not (Test-Path -LiteralPath $compiler)) { throw 'Windows .NET Framework compiler is required; nothing was installed' }
$native = (Resolve-Path -LiteralPath $NativeDirectory).Path
$output = [IO.Path]::GetFullPath($OutputFile)
$expectedRoot = [IO.Path]::GetFullPath((Join-Path $projectRoot 'desktopApp\build')) + [IO.Path]::DirectorySeparatorChar
if (-not $output.StartsWith($expectedRoot, [StringComparison]::OrdinalIgnoreCase)) { throw 'Output must be in desktopApp/build' }
$null = New-Item -ItemType Directory -Path (Split-Path -Parent $output) -Force
& $compiler /nologo /target:exe /platform:x64 /optimize+ /warnaserror+ "/out:$output" `
    "/win32manifest:$source\helper.manifest" /reference:System.Web.Extensions.dll `
    "/resource:$native\tun2socks-windows-amd64.exe,tun2socks.exe" `
    "/resource:$native\wintun.dll,wintun.dll" "/resource:$source\network.ps1,network.ps1" "$source\TunHelper.cs"
if ($LASTEXITCODE -ne 0) { throw 'TUN helper compilation failed' }
& $output --self-test
if ($LASTEXITCODE -ne 0) { throw 'TUN helper validation failed' }
& (Join-Path ([Environment]::GetFolderPath('System')) 'WindowsPowerShell\v1.0\powershell.exe') -NoProfile -NonInteractive -ExecutionPolicy Bypass -File "$source\network.ps1" -SelfTest
if ($LASTEXITCODE -ne 0) { throw 'TUN route-plan validation failed' }
