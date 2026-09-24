param(
    [switch]$PrepareOnly,
    [switch]$SkipAndroid,
    [string]$AndroidNdk = '',
    [string]$SourcePath = '',
    [string]$ArtifactsPath = ''
)

$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$upstream = 'd34dc8caa70ca059cd80d8f5753499361052dabc'
$protocol = 'unified-openflux-aesgcm-v1'
$versionText = "unified-openflux 5 upstream=$upstream protocol=$protocol"
if (-not $SourcePath) { $SourcePath = Join-Path $repo ('.downloads\openflux\source-' + $upstream.Substring(0, 12)) }
$SourcePath = [IO.Path]::GetFullPath($SourcePath)
if (-not $ArtifactsPath) { $ArtifactsPath = Join-Path $repo '.downloads\openflux\artifacts' }
$artifacts = [IO.Path]::GetFullPath($ArtifactsPath)

function Invoke-Checked {
    param([string]$Program, [string[]]$Arguments)
    & $Program @Arguments
    if ($LASTEXITCODE -ne 0) { throw "Build step failed: $Program (exit $LASTEXITCODE)." }
}

if (-not (Test-Path -LiteralPath $SourcePath)) {
    Invoke-Checked git @('clone', '--no-checkout', 'https://github.com/p1neappleXpress/OpenFlux.git', $SourcePath)
    Invoke-Checked git @('-C', $SourcePath, 'checkout', '--detach', $upstream)
}
$head = & git -C $SourcePath rev-parse HEAD
if ($LASTEXITCODE -ne 0 -or $head.Trim() -ne $upstream) {
    throw 'Source is not at the reviewed pin; refusing to reset an existing checkout.'
}
$goVersion = & go version
if ($LASTEXITCODE -ne 0 -or $goVersion -notmatch '\bgo1\.26\.4\b') { throw 'This pinned build requires Go 1.26.4.' }

$overlay = (Resolve-Path (Join-Path $PSScriptRoot 'overlay')).Path
foreach ($file in Get-ChildItem -LiteralPath $overlay -File -Recurse) {
    $destination = Join-Path $SourcePath $file.FullName.Substring($overlay.Length + 1)
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $destination) | Out-Null
    Copy-Item -LiteralPath $file.FullName -Destination $destination -Force
}
New-Item -ItemType Directory -Force -Path $artifacts | Out-Null
$savedEnvironment = @{}
foreach ($name in @('GOOS', 'GOARCH', 'GOARM', 'CGO_ENABLED', 'CC', 'CGO_CFLAGS', 'CGO_LDFLAGS')) {
    $savedEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
}
Push-Location $SourcePath
try {
    Invoke-Checked go @('get', 'github.com/things-go/go-socks5@v0.1.3')
    Invoke-Checked go @('mod', 'tidy')
    if ($PrepareOnly) { Write-Output 'Pinned source and overlay prepared.'; return }
    $env:GOOS = 'windows'
    $env:GOARCH = 'amd64'
    $env:CGO_ENABLED = '0'
    Invoke-Checked go @('test', '-p=2', '-count=1', './transport', './transport/yandex', './tunnel', './cmd/unified-openflux')
    $manifestFiles = [ordered]@{}
    foreach ($os in @('windows', 'linux')) {
        $env:GOOS = $os
        $name = if ($os -eq 'windows') { 'openflux-windows-amd64.exe' } else { 'openflux-linux-amd64' }
        $output = Join-Path $artifacts $name
        Invoke-Checked go @('build', '-p=2', '-trimpath', '-buildvcs=false', '-ldflags=-s -w', '-o', $output, './cmd/unified-openflux')
        $manifestFiles[$name] = (Get-FileHash -LiteralPath $output -Algorithm SHA256).Hash.ToLowerInvariant()
    }
    $actualVersion = & (Join-Path $artifacts 'openflux-windows-amd64.exe') --version
    if ($LASTEXITCODE -ne 0 -or $actualVersion.Trim() -ne $versionText) { throw 'Native wrapper provenance mismatch.' }
    if (-not $SkipAndroid) {
        if (-not $AndroidNdk) {
            $candidates = @(Get-ChildItem -LiteralPath (Join-Path $env:USERPROFILE 'Android\Sdk\ndk') -Directory | Sort-Object Name -Descending)
            if (-not $candidates.Count) { throw 'Android NDK missing; provide -AndroidNdk.' }
            $AndroidNdk = $candidates[0].FullName
        }
        $clangRoot = Join-Path $AndroidNdk 'toolchains\llvm\prebuilt\windows-x86_64\bin'
        $targets = @(
            @{ Abi = 'arm64-v8a'; Arch = 'arm64'; Triple = 'aarch64-linux-android' },
            @{ Abi = 'armeabi-v7a'; Arch = 'arm'; Triple = 'armv7a-linux-androideabi' },
            @{ Abi = 'x86_64'; Arch = 'amd64'; Triple = 'x86_64-linux-android' }
        )
        foreach ($target in $targets) {
            $env:GOOS = 'android'
            $env:GOARCH = $target.Arch
            $env:GOARM = if ($target.Arch -eq 'arm') { '7' } else { '' }
            $env:CGO_ENABLED = '1'
            $env:CC = Join-Path $clangRoot ($target.Triple + '24-clang.cmd')
            if (-not (Test-Path -LiteralPath $env:CC)) { throw 'Required Android NDK compiler missing.' }
            $env:CGO_CFLAGS = '-O2'
            $env:CGO_LDFLAGS = '-Wl,-z,max-page-size=16384'
            $relative = 'android/' + $target.Abi + '/libopenflux.so'
            $output = Join-Path $artifacts $relative
            New-Item -ItemType Directory -Force -Path (Split-Path -Parent $output) | Out-Null
            Invoke-Checked go @('build', '-p=2', '-trimpath', '-buildvcs=false', '-buildmode=pie', '-ldflags=-s -w -checklinkname=0 -extldflags=-Wl,-z,max-page-size=16384', '-o', $output, './cmd/unified-openflux')
            $manifestFiles[$relative] = (Get-FileHash -LiteralPath $output -Algorithm SHA256).Hash.ToLowerInvariant()
        }
    }
    Invoke-Checked go @('mod', 'vendor')
    $archive = Join-Path $artifacts 'openflux-source.tar.gz'
    Invoke-Checked tar @('-czf', $archive, '--exclude=.git', '-C', $SourcePath, '.')
    $manifestFiles['openflux-source.tar.gz'] = (Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash.ToLowerInvariant()
    $licenses = Join-Path $artifacts 'licenses'
    New-Item -ItemType Directory -Force -Path $licenses | Out-Null
    foreach ($name in @('LICENSE', 'NOTICE', 'COPYRIGHT')) {
        Copy-Item -LiteralPath (Join-Path $SourcePath $name) -Destination (Join-Path $licenses $name) -Force
    }
    $manifest = [ordered]@{
        schema = 1; upstream = $upstream; protocol = $protocol; version_text = $versionText
        server_backend = 'l4'; default_codec = 'legacy'; tcp_buffers = @(65536, 262144, 1048576)
        go_version = 'go1.26.4'; socks5_module = 'github.com/things-go/go-socks5@v0.1.3'; files = $manifestFiles
    }
    $utf8 = New-Object Text.UTF8Encoding($false)
    [IO.File]::WriteAllText((Join-Path $artifacts 'manifest.json'), ($manifest | ConvertTo-Json -Depth 8) + "`n", $utf8)
    Write-Output 'OpenFlux native builds and source archive verified. No release was published.'
} finally {
    Pop-Location
    foreach ($name in $savedEnvironment.Keys) {
        [Environment]::SetEnvironmentVariable($name, $savedEnvironment[$name], 'Process')
    }
}
