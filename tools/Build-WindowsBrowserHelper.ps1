param(
    [Parameter(Mandatory=$true)][string]$OutputDirectory,
    [switch]$Offline
)
$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSHOME 'Modules\Microsoft.PowerShell.Utility\Microsoft.PowerShell.Utility.psd1') -ErrorAction Stop
Import-Module (Join-Path $PSHOME 'Modules\Microsoft.PowerShell.Management\Microsoft.PowerShell.Management.psd1') -ErrorAction Stop
$projectRoot = Split-Path -Parent $PSScriptRoot
$source = Join-Path $projectRoot 'desktopApp\src\windowsBrowserHelper'
$compiler = Join-Path ([Environment]::GetFolderPath('Windows')) 'Microsoft.NET\Framework64\v4.0.30319\csc.exe'
if (-not (Test-Path -LiteralPath $compiler)) { throw 'Windows .NET Framework compiler is required; nothing was installed' }
$output = [IO.Path]::GetFullPath($OutputDirectory)
$buildRoot = [IO.Path]::GetFullPath((Join-Path $projectRoot 'desktopApp\build')) + [IO.Path]::DirectorySeparatorChar
if (-not $output.StartsWith($buildRoot, [StringComparison]::OrdinalIgnoreCase)) { throw 'Output must be in desktopApp/build' }

$version = '1.0.3405.78'
$sha256 = 'D035807B2AABA871E8C014759626F566E96934E6CE6F0587056EE81D5228C373'
$cache = Join-Path $buildRoot "tmp\webview2\$version"
$package = Join-Path $cache "microsoft.web.webview2.$version.nupkg"
$null = New-Item -ItemType Directory -Path $cache -Force
if (-not (Test-Path -LiteralPath $package)) {
    if ($Offline) { throw 'Pinned WebView2 SDK is not cached; offline build cannot download it' }
    $partial = $package + '.partial'
    try {
        Invoke-WebRequest -UseBasicParsing -Uri "https://api.nuget.org/v3-flatcontainer/microsoft.web.webview2/$version/microsoft.web.webview2.$version.nupkg" -OutFile $partial -TimeoutSec 60
        if ((Get-FileHash -LiteralPath $partial -Algorithm SHA256).Hash -ne $sha256) { throw 'WebView2 SDK checksum mismatch' }
        Move-Item -LiteralPath $partial -Destination $package
    } finally {
        if (Test-Path -LiteralPath $partial) { Remove-Item -LiteralPath $partial -Force }
    }
}
if ((Get-FileHash -LiteralPath $package -Algorithm SHA256).Hash -ne $sha256) { throw 'WebView2 SDK checksum mismatch' }

$null = New-Item -ItemType Directory -Path $output -Force
Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive = [IO.Compression.ZipFile]::OpenRead($package)
try {
    $entries = @{
        'lib/net462/Microsoft.Web.WebView2.Core.dll' = 'Microsoft.Web.WebView2.Core.dll'
        'lib/net462/Microsoft.Web.WebView2.WinForms.dll' = 'Microsoft.Web.WebView2.WinForms.dll'
        'runtimes/win-x64/native/WebView2Loader.dll' = 'WebView2Loader.dll'
        'LICENSE.txt' = 'WebView2-LICENSE.txt'
        'NOTICE.txt' = 'WebView2-NOTICE.txt'
    }
    foreach ($name in $entries.Keys) {
        $entry = $archive.GetEntry($name)
        if (-not $entry) { throw 'Pinned WebView2 SDK layout changed' }
        [IO.Compression.ZipFileExtensions]::ExtractToFile($entry, (Join-Path $output $entries[$name]), $true)
    }
} finally { $archive.Dispose() }

$executable = Join-Path $output 'unifiedvpn-browser-helper.exe'
& $compiler /nologo /target:exe /platform:x64 /optimize+ /warnaserror+ "/out:$executable" `
    "/win32manifest:$source\helper.manifest" /reference:System.Web.Extensions.dll `
    /reference:System.Windows.Forms.dll /reference:System.Drawing.dll `
    "/reference:$output\Microsoft.Web.WebView2.Core.dll" "/reference:$output\Microsoft.Web.WebView2.WinForms.dll" `
    "$source\BrowserHelper.cs"
if ($LASTEXITCODE -ne 0) { throw 'Browser helper compilation failed' }
& $executable --self-test
if ($LASTEXITCODE -ne 0) { throw 'Browser helper policy validation failed' }
