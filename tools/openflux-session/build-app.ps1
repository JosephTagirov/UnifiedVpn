param(
    [string]$SourcePath = '',
    [string]$ArtifactsPath = '',
    [string]$AndroidNdk = '',
    [switch]$SkipAndroid
)
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
if (-not $SourcePath) { $SourcePath = Join-Path $repo '.downloads\openflux\upstream-v0.5.0-20261011' }
if (-not $ArtifactsPath) { $ArtifactsPath = Join-Path $repo '.downloads\openflux\session-app-2026101102' }
$SourcePath = (Resolve-Path -LiteralPath $SourcePath).Path
$ArtifactsPath = [IO.Path]::GetFullPath($ArtifactsPath)
& (Join-Path $PSScriptRoot 'build.ps1') -SourcePath $SourcePath -TestsOnly
function Invoke-Checked([string]$Program, [string[]]$Arguments) {
    & $Program @Arguments
    if ($LASTEXITCODE -ne 0) { throw "Build step failed: $Program" }
}
$saved = @{}
foreach ($name in @('GOTOOLCHAIN', 'GOMAXPROCS', 'GOOS', 'GOARCH', 'GOARM', 'CGO_ENABLED', 'CC', 'CGO_CFLAGS', 'CGO_LDFLAGS')) {
    $saved[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
}
Push-Location $SourcePath
try {
    $env:GOTOOLCHAIN = 'go1.26.6'
    $env:GOMAXPROCS = '2'
    $env:CGO_ENABLED = '0'
    $env:GOARCH = 'amd64'
    New-Item -ItemType Directory -Force -Path $ArtifactsPath | Out-Null
    $files = [ordered]@{}
    foreach ($os in @('windows', 'linux')) {
        $env:GOOS = $os
        $name = if ($os -eq 'windows') { 'openflux-session-windows-amd64.exe' } else { 'openflux-session-linux-amd64' }
        $output = Join-Path $ArtifactsPath $name
        Invoke-Checked go @('build', '-p=2', '-trimpath', '-buildvcs=false', '-ldflags=-s -w', '-o', $output, './cmd/unified-session')
        $files[$name] = (Get-FileHash -LiteralPath $output -Algorithm SHA256).Hash.ToLowerInvariant()
    }
    $version = & (Join-Path $ArtifactsPath 'openflux-session-windows-amd64.exe') --version
    $expectedVersion = 'unified-openflux-session 1 upstream=7336e1b9d940fc09d00bbf545d15b9437487aa10 protocol=upstream-openflux-session'
    if ($LASTEXITCODE -ne 0 -or $version.Trim() -ne $expectedVersion) { throw 'Session wrapper provenance mismatch.' }
    if (-not $SkipAndroid) {
        if (-not $AndroidNdk) {
            $AndroidNdk = (Get-ChildItem -LiteralPath (Join-Path $env:USERPROFILE 'Android\Sdk\ndk') -Directory | Sort-Object Name -Descending | Select-Object -First 1).FullName
        }
        $compilers = Join-Path $AndroidNdk 'toolchains\llvm\prebuilt\windows-x86_64\bin'
        foreach ($target in @(
            @{ Abi='arm64-v8a'; Arch='arm64'; Triple='aarch64-linux-android' },
            @{ Abi='armeabi-v7a'; Arch='arm'; Triple='armv7a-linux-androideabi' },
            @{ Abi='x86_64'; Arch='amd64'; Triple='x86_64-linux-android' }
        )) {
            $env:GOOS = 'android'; $env:GOARCH = $target.Arch
            $env:GOARM = if ($target.Arch -eq 'arm') { '7' } else { '' }
            $env:CGO_ENABLED = '1'
            $env:CC = Join-Path $compilers ($target.Triple + '24-clang.cmd')
            if (-not (Test-Path -LiteralPath $env:CC)) { throw 'Android NDK compiler missing.' }
            $env:CGO_CFLAGS = '-O2'
            $env:CGO_LDFLAGS = '-Wl,-z,max-page-size=16384'
            $name = 'android/' + $target.Abi + '/libopenflux_session.so'
            $output = Join-Path $ArtifactsPath $name
            New-Item -ItemType Directory -Force -Path (Split-Path -Parent $output) | Out-Null
            Invoke-Checked go @('build', '-p=2', '-trimpath', '-buildvcs=false', '-buildmode=pie', '-ldflags=-s -w -checklinkname=0 -extldflags=-Wl,-z,max-page-size=16384', '-o', $output, './cmd/unified-session')
            $files[$name] = (Get-FileHash -LiteralPath $output -Algorithm SHA256).Hash.ToLowerInvariant()
        }
    }
    $overlayHashes = [ordered]@{}
    foreach ($file in Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'overlay') -File -Recurse) {
        $overlayHashes[$file.FullName.Substring($PSScriptRoot.Length + 1).Replace('\', '/')] = (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
    }
    Invoke-Checked go @('mod', 'vendor')
    $archive = Join-Path $ArtifactsPath 'openflux-session-source.tar.gz'
    Invoke-Checked tar @('-czf', $archive, '--exclude=.git', '-C', $SourcePath, '.')
    $files['openflux-session-source.tar.gz'] = (Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash.ToLowerInvariant()
    $licenses = Join-Path $ArtifactsPath 'licenses'
    New-Item -ItemType Directory -Force -Path $licenses | Out-Null
    foreach ($name in @('LICENSE', 'NOTICE', 'COPYRIGHT')) {
        if (Test-Path -LiteralPath (Join-Path $SourcePath $name)) {
            Copy-Item -LiteralPath (Join-Path $SourcePath $name) -Destination (Join-Path $licenses $name) -Force
        }
    }
    $manifest = [ordered]@{
        schema=1; upstream='7336e1b9d940fc09d00bbf545d15b9437487aa10'; protocol='upstream-openflux-session'
        version_text=$expectedVersion; go_version='go1.26.6'; files=$files; overlay=$overlayHashes
    }
    [IO.File]::WriteAllText((Join-Path $ArtifactsPath 'manifest.json'), ($manifest | ConvertTo-Json -Depth 8) + "`n", (New-Object Text.UTF8Encoding($false)))
    Write-Output 'Session application cores verified. Existing classic cores and server binaries were not replaced.'
} finally {
    Pop-Location
    foreach ($name in $saved.Keys) { [Environment]::SetEnvironmentVariable($name, $saved[$name], 'Process') }
}
