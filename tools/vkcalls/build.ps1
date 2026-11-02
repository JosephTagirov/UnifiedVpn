param(
    [Parameter(Mandatory = $true)][string]$SourcePath,
    [string]$ArtifactsPath = '',
    [string]$AndroidNdk = '',
    [switch]$SkipTests
)

$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$properties = ConvertFrom-StringData (Get-Content -LiteralPath (Join-Path $repo 'gradle.properties') -Raw)
$pin = $properties['olcbox.olcrtcSha']
if ($pin -notmatch '^[0-9a-f]{40}$') { throw 'olcbox.olcrtcSha must be a full Git commit.' }
$SourcePath = (Resolve-Path -LiteralPath $SourcePath).Path
if (-not $ArtifactsPath) { $ArtifactsPath = Join-Path $repo ('.downloads\vkcalls\artifacts-' + $pin.Substring(0, 7)) }
$ArtifactsPath = [IO.Path]::GetFullPath($ArtifactsPath)
if ((& git -C $SourcePath rev-parse HEAD).Trim() -ne $pin -or $LASTEXITCODE -ne 0) { throw 'VK Calls source revision mismatch.' }
if (& git -C $SourcePath status --porcelain --untracked-files=no) { throw 'Tracked VK Calls source modifications are not accepted.' }
if (-not $AndroidNdk) {
    $AndroidNdk = (Get-ChildItem -LiteralPath (Join-Path $env:USERPROFILE 'Android\Sdk\ndk') -Directory |
        Sort-Object Name -Descending | Select-Object -First 1).FullName
}
$compilers = Join-Path $AndroidNdk 'toolchains\llvm\prebuilt\windows-x86_64\bin'
function Invoke-Checked([string]$Program, [string[]]$Arguments) {
    & $Program @Arguments
    if ($LASTEXITCODE -ne 0) { throw "VK Calls build step failed: $Program" }
}
$saved = @{}
foreach ($name in @('GOTOOLCHAIN', 'GOMAXPROCS', 'GOOS', 'GOARCH', 'GOARM', 'CGO_ENABLED', 'CC', 'CGO_CFLAGS', 'CGO_LDFLAGS')) {
    $saved[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
}
Push-Location $SourcePath
try {
    $env:GOTOOLCHAIN = 'go1.26.6'
    $env:GOMAXPROCS = '2'
    $env:GOOS = 'windows'
    $env:GOARCH = 'amd64'
    $env:CGO_ENABLED = '0'
    if (-not $SkipTests) {
        Invoke-Checked go @('test', '-p=2', '-count=1', './internal/config', './internal/crypto', './internal/client', './internal/engine/vkcalls')
    }
    New-Item -ItemType Directory -Force -Path $ArtifactsPath | Out-Null
    # Use upstream Mage for the native Windows build; Android needs NDK/CGO PIE, which Mage does not expose.
    Invoke-Checked go @('run', 'github.com/magefile/mage', '-compile', (Join-Path $ArtifactsPath 'mage.exe'))
    Invoke-Checked (Join-Path $ArtifactsPath 'mage.exe') @('build')
    $windows = 'olcrtc-vkcalls-windows-amd64.exe'
    Copy-Item -LiteralPath (Join-Path $SourcePath 'build\olcrtc-windows-amd64.exe') -Destination (Join-Path $ArtifactsPath $windows)
    $files = [ordered]@{}
    $files[$windows] = (Get-FileHash -LiteralPath (Join-Path $ArtifactsPath $windows) -Algorithm SHA256).Hash.ToLowerInvariant()
    foreach ($target in @(
        @{ Abi='arm64-v8a'; Arch='arm64'; Triple='aarch64-linux-android' },
        @{ Abi='armeabi-v7a'; Arch='arm'; Triple='armv7a-linux-androideabi' },
        @{ Abi='x86_64'; Arch='amd64'; Triple='x86_64-linux-android' }
    )) {
        $env:GOOS = 'android'
        $env:GOARCH = $target.Arch
        $env:GOARM = if ($target.Arch -eq 'arm') { '7' } else { '' }
        $env:CGO_ENABLED = '1'
        $env:CC = Join-Path $compilers ($target.Triple + '24-clang.cmd')
        if (-not (Test-Path -LiteralPath $env:CC)) { throw 'Android NDK compiler missing.' }
        $env:CGO_CFLAGS = '-O2'
        $env:CGO_LDFLAGS = '-Wl,-z,max-page-size=16384'
        $name = 'android/' + $target.Abi + '/libvkcalls.so'
        $output = Join-Path $ArtifactsPath $name
        New-Item -ItemType Directory -Force -Path (Split-Path -Parent $output) | Out-Null
        Invoke-Checked go @('build', '-p=2', '-trimpath', '-buildvcs=true', '-buildmode=pie',
            '-ldflags=-s -w -checklinkname=0 -extldflags=-Wl,-z,max-page-size=16384', '-o', $output, './cmd/olcrtc')
        $files[$name] = (Get-FileHash -LiteralPath $output -Algorithm SHA256).Hash.ToLowerInvariant()
    }
    foreach ($name in $files.Keys) {
        $info = & go version -m -json (Join-Path $ArtifactsPath $name) | ConvertFrom-Json
        if ($LASTEXITCODE -ne 0) { throw 'Cannot inspect VK Calls build provenance.' }
        $settings = @{}
        foreach ($setting in $info.Settings) { $settings[$setting.Key] = $setting.Value }
        if ($settings['vcs.revision'] -ne $pin -or $settings['vcs.modified'] -ne 'false') {
            throw 'VK Calls binary provenance mismatch.'
        }
    }
    foreach ($name in @('LICENSE', 'NOTICE')) {
        Copy-Item -LiteralPath (Join-Path $SourcePath $name) -Destination (Join-Path $ArtifactsPath $name)
    }
    $manifest = [ordered]@{ schema=1; revision=$pin; go='1.26.6'; files=$files }
    [IO.File]::WriteAllText((Join-Path $ArtifactsPath 'manifest.json'), ($manifest | ConvertTo-Json -Depth 5) + "`n", [Text.UTF8Encoding]::new($false))
    Write-Output 'Pinned VK Calls cores built; existing Jitsi cores were not replaced.'
} finally {
    Pop-Location
    foreach ($name in $saved.Keys) { [Environment]::SetEnvironmentVariable($name, $saved[$name], 'Process') }
}
