[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$NativeOutput,
    [ValidateSet("debug", "release")]
    [string]$BuildType = "debug",
    [string]$Apk = "",
    [ValidateSet("armeabi-v7a", "arm64-v8a", "x86_64")]
    [string[]]$ExpectedAbis = @("armeabi-v7a", "arm64-v8a", "x86_64")
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
$libraries = @("libhev-socks5-tunnel.so", "libolcbox_tun2socks.so")
$variant = (Get-Culture).TextInfo.ToTitleCase($BuildType)

if (-not $Apk) {
    $Apk = Join-Path $repoRoot "androidApp\build\outputs\apk\$BuildType\androidApp-$BuildType.apk"
}

$nativeRoot = (Resolve-Path -LiteralPath $NativeOutput).Path
$apkPath = (Resolve-Path -LiteralPath $Apk).Path
$cxxVariantRoot = Join-Path $repoRoot "androidApp\build\intermediates\cxx\$variant"
$cxxCandidates = @(
    Get-ChildItem -LiteralPath $cxxVariantRoot -Directory -ErrorAction Stop |
        Where-Object { Test-Path -LiteralPath (Join-Path $_.FullName "obj\local") -PathType Container }
)
if ($cxxCandidates.Count -ne 1) {
    throw "Expected exactly one $variant CXX output under $cxxVariantRoot; found $($cxxCandidates.Count)"
}
$unstrippedRoot = Join-Path $cxxCandidates[0].FullName "obj\local"
$strippedRoot = Join-Path $repoRoot (
    "androidApp\build\intermediates\stripped_native_libs\{0}\strip{1}DebugSymbols\out\lib" -f
        $BuildType,
        $variant
)

function Get-FileSha256 {
    param([Parameter(Mandatory = $true)][string]$Path)
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}

function Get-StreamSha256 {
    param([Parameter(Mandatory = $true)][IO.Stream]$Stream)
    $hasher = [Security.Cryptography.SHA256]::Create()
    try {
        return ([BitConverter]::ToString($hasher.ComputeHash($Stream))).Replace("-", "").ToLowerInvariant()
    } finally {
        $hasher.Dispose()
    }
}

Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive = [IO.Compression.ZipFile]::OpenRead($apkPath)
try {
    foreach ($abi in ($ExpectedAbis | Select-Object -Unique)) {
        foreach ($library in $libraries) {
            $relativePath = Join-Path $abi $library
            $freshFile = Join-Path $nativeRoot $relativePath
            $unstrippedFile = Join-Path $unstrippedRoot $relativePath
            $strippedFile = Join-Path $strippedRoot $relativePath
            foreach ($requiredFile in @($freshFile, $unstrippedFile, $strippedFile)) {
                if (-not (Test-Path -LiteralPath $requiredFile -PathType Leaf)) {
                    throw "Required native file is missing: $requiredFile"
                }
            }

            $freshSha = Get-FileSha256 -Path $freshFile
            $unstrippedSha = Get-FileSha256 -Path $unstrippedFile
            if ($freshSha -ne $unstrippedSha) {
                throw "$relativePath differs between the fresh NDK output and Gradle CXX input"
            }

            $entryName = "lib/$abi/$library"
            $entry = $archive.GetEntry($entryName)
            if ($null -eq $entry -or $entry.Length -le 0) {
                throw "APK entry is missing or empty: $entryName"
            }
            $entryStream = $entry.Open()
            try {
                $apkSha = Get-StreamSha256 -Stream $entryStream
            } finally {
                $entryStream.Dispose()
            }
            $strippedSha = Get-FileSha256 -Path $strippedFile
            if ($apkSha -ne $strippedSha) {
                throw "$entryName differs between Gradle stripped output and the APK"
            }

            Write-Host "Verified $entryName fresh=$freshSha packaged=$apkSha"
        }
    }
} finally {
    $archive.Dispose()
}

Write-Host "Android native provenance verified for $BuildType APK: $apkPath"
