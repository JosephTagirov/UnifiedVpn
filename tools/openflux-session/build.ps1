param(
    [string]$SourcePath = '',
    [string]$ArtifactsPath = '',
    [switch]$TestsOnly
)

$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$pin = '7336e1b9d940fc09d00bbf545d15b9437487aa10'
if (-not $SourcePath) { $SourcePath = Join-Path $repo '.downloads\openflux\upstream-v0.5.0-20261011' }
if (-not $ArtifactsPath) { $ArtifactsPath = Join-Path $repo '.downloads\openflux\session-2026101101' }
$SourcePath = (Resolve-Path -LiteralPath $SourcePath).Path
$ArtifactsPath = [IO.Path]::GetFullPath($ArtifactsPath)
if ((& git -C $SourcePath rev-parse HEAD).Trim() -ne $pin -or $LASTEXITCODE -ne 0) {
    throw 'Expected the reviewed OpenFlux 0.5.0 source pin; no checkout/reset is performed.'
}

function Invoke-Checked([string]$Program, [string[]]$Arguments) {
    & $Program @Arguments
    if ($LASTEXITCODE -ne 0) { throw "Build step failed: $Program" }
}

$saved = @{}
foreach ($name in @('GOTOOLCHAIN', 'GOMAXPROCS', 'GOOS', 'GOARCH', 'CGO_ENABLED')) {
    $saved[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
}
Push-Location $SourcePath
try {
    foreach ($name in @('tunneled-dns.patch', 'striped-retry.patch')) {
        $patch = Join-Path $PSScriptRoot $name
        & git apply --reverse --check $patch 2>$null
        if ($LASTEXITCODE -ne 0) {
            Invoke-Checked git @('apply', '--check', $patch)
            Invoke-Checked git @('apply', $patch)
        }
    }
    $overlay = (Resolve-Path (Join-Path $PSScriptRoot 'overlay')).Path
    foreach ($file in Get-ChildItem -LiteralPath $overlay -File -Recurse) {
        $target = Join-Path $SourcePath $file.FullName.Substring($overlay.Length + 1)
        New-Item -ItemType Directory -Force -Path (Split-Path -Parent $target) | Out-Null
        Copy-Item -LiteralPath $file.FullName -Destination $target -Force
    }
    $env:GOTOOLCHAIN = 'go1.26.6'
    $env:GOMAXPROCS = '2'
    $env:GOOS = 'windows'
    $env:GOARCH = 'amd64'
    $env:CGO_ENABLED = '0'
    Invoke-Checked go @('test', '-p=2', '-count=1', './transport', './tunnel', './socks5', './share', './cmd/unified-session')
    if ($TestsOnly) { return }
    New-Item -ItemType Directory -Path $ArtifactsPath -Force | Out-Null
    $files = [ordered]@{}
    foreach ($os in @('windows', 'linux')) {
        $env:GOOS = $os
        $name = if ($os -eq 'windows') { 'openflux-session-windows-amd64.exe' } else { 'openflux-session-linux-amd64' }
        $target = Join-Path $ArtifactsPath $name
        Invoke-Checked go @('build', '-p=2', '-trimpath', '-ldflags=-s -w', '-o', $target, '.')
        $files[$name] = (Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash.ToLowerInvariant()
    }
    $patches = [ordered]@{}
    foreach ($name in @('tunneled-dns.patch', 'striped-retry.patch')) {
        $patches[$name] = (Get-FileHash -LiteralPath (Join-Path $PSScriptRoot $name) -Algorithm SHA256).Hash.ToLowerInvariant()
    }
    $manifest = [ordered]@{
        schema = 1; upstream = $pin; version = '0.5.0'; build = '2026101101'
        protocol = 'upstream-openflux-session'; go = '1.26.6'; patches = $patches; files = $files
        packaged_in_unifiedvpn = $false; android_built = $false
    }
    [IO.File]::WriteAllText((Join-Path $ArtifactsPath 'manifest.json'),
        ($manifest | ConvertTo-Json -Depth 5) + "`n", (New-Object Text.UTF8Encoding($false)))
    Write-Output 'Isolated Session cores built. Existing UnifiedVPN cores were not replaced.'
} finally {
    Pop-Location
    foreach ($name in $saved.Keys) { [Environment]::SetEnvironmentVariable($name, $saved[$name], 'Process') }
}
