[CmdletBinding()]
param([Parameter(Mandatory=$true)][string]$OutputPath)
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
$root=Split-Path -Parent $PSScriptRoot
$source=Join-Path $root 'desktopApp/src/windowsInstaller'
$output=[IO.Path]::GetFullPath($OutputPath)
$allowed=[IO.Path]::GetFullPath((Join-Path $root 'desktopApp/build'))+[IO.Path]::DirectorySeparatorChar
if(-not $output.StartsWith($allowed,[StringComparison]::OrdinalIgnoreCase)){throw 'MSI guard output must be inside desktopApp/build'}
$wix=Split-Path -Parent (Get-Command candle.exe -ErrorAction Stop).Source
$sdk=Join-Path $wix 'sdk'
$dtf=Join-Path $sdk 'Microsoft.Deployment.WindowsInstaller.dll'
$wrapper=Join-Path $sdk 'x64/sfxca.dll'
$license=Join-Path $source 'WIX-DTF-LICENSE.txt'
foreach($path in @($dtf,$wrapper,(Join-Path $sdk 'MakeSfxCA.exe'))){if(-not (Test-Path -LiteralPath $path -PathType Leaf)){throw 'WiX DTF SDK is required for the scoped MSI preflight'}}
$work=[IO.Path]::GetFullPath((Join-Path $root ('desktopApp/build/tmp/msi-guard-'+[guid]::NewGuid().ToString('N'))))
$null=New-Item -ItemType Directory -Path $work
try {
 $managed=Join-Path $work 'UnifiedVpnGuard.dll'
 $config=Join-Path $work 'CustomAction.config'
 [IO.File]::WriteAllText($config,'<?xml version="1.0" encoding="utf-8"?><configuration><startup useLegacyV2RuntimeActivationPolicy="true"><supportedRuntime version="v4.0" sku=".NETFramework,Version=v4.0"/></startup></configuration>')
 $compiler=Join-Path $env:SystemRoot 'Microsoft.NET/Framework64/v4.0.30319/csc.exe'
 & $compiler /nologo /target:library /platform:x64 /optimize+ /warnaserror+ "/out:$managed" "/reference:$dtf" /reference:System.Core.dll "$source\InstallationScope.cs" "$source\MsiProcessGuard.cs"
 if($LASTEXITCODE -ne 0){throw 'Scoped MSI guard compilation failed'}
 $null=New-Item -ItemType Directory -Path ([IO.Path]::GetDirectoryName($output)) -Force
 & "$sdk/MakeSfxCA.exe" $output $wrapper $managed $config $dtf $license
 if($LASTEXITCODE -ne 0){throw 'Scoped MSI guard packaging failed'}
 Write-Output 'Built path-scoped Windows MSI preflight'
} finally {
 $parent=[IO.Path]::GetFullPath((Join-Path $root 'desktopApp/build/tmp'))
 if([IO.Path]::GetDirectoryName($work) -ne $parent -or -not [IO.Path]::GetFileName($work).StartsWith('msi-guard-')){throw 'Unsafe MSI guard temporary path'}
 Remove-Item -LiteralPath $work -Recurse -Force
}
