[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$NativeOutput,
    [ValidateSet("debug", "release")]
    [string]$BuildType = "debug"
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
$abis = @("armeabi-v7a", "arm64-v8a", "x86_64")
$libraries = @("libhev-socks5-tunnel.so", "libolcbox_tun2socks.so")
$variant = (Get-Culture).TextInfo.ToTitleCase($BuildType)
$nativeRoot = (Resolve-Path -LiteralPath $NativeOutput).Path
$cxxVariantRoot = Join-Path $repoRoot "androidApp\build\intermediates\cxx\$variant"
$cxxCandidates = @(
    Get-ChildItem -LiteralPath $cxxVariantRoot -Directory -ErrorAction Stop |
        Where-Object { Test-Path -LiteralPath (Join-Path $_.FullName "obj\local") -PathType Container }
)
if ($cxxCandidates.Count -ne 1) {
    throw "Expected exactly one $variant CXX output under $cxxVariantRoot; found $($cxxCandidates.Count)"
}

$targetRoot = Join-Path $cxxCandidates[0].FullName "obj\local"
$targetRootFull = [IO.Path]::GetFullPath($targetRoot)
$expectedRootFull = [IO.Path]::GetFullPath($cxxVariantRoot).TrimEnd('\') + '\'
if (-not $targetRootFull.StartsWith($expectedRootFull, [StringComparison]::OrdinalIgnoreCase)) {
    throw "Refusing to import native files outside the Android CXX build directory"
}

foreach ($abi in $abis) {
    foreach ($library in $libraries) {
        $source = Join-Path $nativeRoot (Join-Path $abi $library)
        if (-not (Test-Path -LiteralPath $source -PathType Leaf)) {
            throw "Fresh native output is missing: $source"
        }
        if ((Get-Item -LiteralPath $source).Length -le 0) {
            throw "Fresh native output is empty: $source"
        }

        $targetDirectory = Join-Path $targetRoot $abi
        New-Item -ItemType Directory -Force -Path $targetDirectory | Out-Null
        $target = Join-Path $targetDirectory $library
        Copy-Item -LiteralPath $source -Destination $target -Force

        $sourceSha = (Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash.ToLowerInvariant()
        $targetSha = (Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash.ToLowerInvariant()
        if ($sourceSha -ne $targetSha) {
            throw "Copied native output failed SHA-256 verification: $abi/$library"
        }
        Write-Host "Imported $abi/$library sha256=$targetSha"
    }
}

Write-Host "Fresh native output imported into $targetRootFull"
