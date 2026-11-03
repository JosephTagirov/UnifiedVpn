[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)][string]$InstallerPath,
    [Parameter(Mandatory=$true)][ValidatePattern('^[0-9]+\.[0-9]+\.[0-9]+$')][string]$Version,
    [Parameter(Mandatory=$true)][long]$Build,
    [Parameter(Mandatory=$true)][guid]$ProductCode,
    [string]$Vendor='Unknown',
    [ValidateSet('Public edition','Owner edition')][string]$Edition='Public edition'
)
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
$root=Split-Path -Parent $PSScriptRoot
$source=[IO.Path]::GetFullPath((Join-Path $root 'desktopApp/src/windowsInstaller'))
$work=[IO.Path]::GetFullPath((Join-Path $root ('desktopApp/build/tmp/branded-installer-'+[guid]::NewGuid().ToString('N'))))
$null=New-Item -ItemType Directory -Path $work
$msi=Join-Path $work 'UnifiedVPN.msi'
$stub=Join-Path $work 'setup.exe'
$target=[IO.Path]::GetFullPath($InstallerPath)
$allowed=[IO.Path]::GetFullPath((Join-Path $root 'desktopApp/build'))+[IO.Path]::DirectorySeparatorChar
if(-not $target.StartsWith($allowed,[StringComparison]::OrdinalIgnoreCase)){throw 'Installer must be inside desktopApp/build'}
try {
    & "$PSScriptRoot/Verify-WindowsInstaller.ps1" -InstallerPath $target -ExpectedVersion $Version -ExpectedBuild $Build -ExpectedVendor $Vendor -ExportMsiPath $msi
    $metadata=@"
using System.Reflection;
[assembly: AssemblyTitle("UnifiedVPN Setup")]
[assembly: AssemblyProduct("UnifiedVPN")]
[assembly: AssemblyVersion("$Version.0")]
internal static class SetupProduct {
 public const string Version="$Version", Build="$Build", Edition="$Edition";
 public const string ProductCode="$($ProductCode.ToString('B'))", UpgradeCode="{6f0aaf78-dbed-4745-9d95-9e63f10a30de}";
}
"@
    [IO.File]::WriteAllText((Join-Path $work 'SetupProduct.cs'),$metadata,[Text.Encoding]::UTF8)
    $compiler=Join-Path ([Environment]::GetFolderPath('Windows')) 'Microsoft.NET/Framework64/v4.0.30319/csc.exe'
    & $compiler /nologo /target:winexe /platform:x64 /optimize+ /warnaserror+ "/out:$stub" "/win32manifest:$source\setup.manifest" "/win32icon:$root\desktopApp\appIcons\WindowsIcon.ico" /reference:System.Windows.Forms.dll /reference:System.Drawing.dll /reference:System.Core.dll "$source\Installer.cs" "$source\InstallerPolicy.cs" "$work\SetupProduct.cs"
    if($LASTEXITCODE -ne 0){throw 'Windows installer compilation failed'}
    $test=Start-Process -FilePath $stub -ArgumentList '--self-test' -WindowStyle Hidden -Wait -PassThru
    if($test.ExitCode -ne 0){throw 'Installer policy self-tests failed'}
    $hash=[Security.Cryptography.SHA256]::Create()
    $payloadStream=[IO.File]::OpenRead($msi)
    $output=[IO.File]::Open($stub,[IO.FileMode]::Append,[IO.FileAccess]::Write,[IO.FileShare]::None)
    try {
        $payloadStream.CopyTo($output)
        $payloadStream.Position=0
        $sha=$hash.ComputeHash($payloadStream)
        $marker=[Text.Encoding]::ASCII.GetBytes('UVPNMSIPAYLOAD01')
        $length=[BitConverter]::GetBytes([long]$payloadStream.Length)
        $output.Write($marker,0,$marker.Length);$output.Write($length,0,8);$output.Write($sha,0,32)
        $output.Flush($true)
    } finally {$output.Dispose();$payloadStream.Dispose();$hash.Dispose()}
    [IO.File]::Copy($stub,$target,$true)
    Write-Output 'Built UnifiedVPN branded setup: install/update/repair detection, 10 policy checks passed'
} finally {
    # Resolve and bound the generated temporary directory before deleting it.
    $resolved=[IO.Path]::GetFullPath($work)
    $parent=[IO.Path]::GetFullPath((Join-Path $root 'desktopApp/build/tmp'))
    if([IO.Path]::GetDirectoryName($resolved) -ne $parent -or -not [IO.Path]::GetFileName($resolved).StartsWith('branded-installer-')){throw 'Unsafe installer temporary path'}
    Remove-Item -LiteralPath $resolved -Recurse -Force
}
