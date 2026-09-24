[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$SourcePath,
    [Parameter(Mandatory = $true)][string]$OutputDirectory,
    [string]$ExpectedCommit = '',
    [string]$AndroidNdk = '',
    [switch]$SkipAndroid
)

$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
if (-not $ExpectedCommit) {
    $properties = ConvertFrom-StringData (Get-Content -LiteralPath (Join-Path $repo 'gradle.properties') -Raw)
    $ExpectedCommit = $properties['olcbox.awgCoreSha']
}
if ($ExpectedCommit -notmatch '^[0-9a-f]{40}$') { throw 'ExpectedCommit must be a full lowercase commit SHA.' }
$SourcePath = (Resolve-Path -LiteralPath $SourcePath).Path
$OutputDirectory = [IO.Path]::GetFullPath($OutputDirectory)
if (Test-Path -LiteralPath $OutputDirectory) { throw 'Use a new output directory; existing artifacts are never replaced.' }
if ($OutputDirectory.StartsWith($SourcePath + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Build output must be outside the pinned source checkout.'
}

function Invoke-Checked {
    param([string]$Program, [string[]]$Arguments)
    & $Program @Arguments
    if ($LASTEXITCODE -ne 0) { throw "Build step failed: $Program (exit $LASTEXITCODE)." }
}

function Assert-CleanSource {
    $head = Invoke-Checked git @('-C', $SourcePath, 'rev-parse', 'HEAD')
    if ($head.Trim() -ne $ExpectedCommit) { throw 'AWG source does not match the expected pin.' }
    $changes = Invoke-Checked git @('-C', $SourcePath, 'status', '--porcelain', '--untracked-files=all')
    if ($changes) { throw 'AWG source has local changes; refusing an unverified build.' }
}

function Assert-AndroidElf {
    param([string]$Binary, [hashtable]$Target)
    $readelf = Join-Path $AndroidNdk 'toolchains\llvm\prebuilt\windows-x86_64\bin\llvm-readelf.exe'
    $records = @(Invoke-Checked $readelf @('--elf-output-style=JSON', '--file-header', '--program-headers', '--notes', $Binary) |
        Out-String | ConvertFrom-Json)
    if ($records.Count -ne 1) { throw 'Expected exactly one ELF metadata record.' }
    $elf = $records[0]
    $machine = @{ arm64 = 183; arm = 40; amd64 = 62 }[$Target.Arch]
    $class = if ($Target.Arch -eq 'arm') { 1 } else { 2 }
    $loads = @($elf.ProgramHeaders | ForEach-Object { $_.ProgramHeader } | Where-Object { $_.Type.Value -eq 1 })
    $interpreters = @($elf.ProgramHeaders | Where-Object { $_.ProgramHeader.Type.Value -eq 3 })
    $notes = @($elf.Notes | ForEach-Object { $_.NoteSection.Note } | Where-Object { $_.Owner -eq 'Android' })
    if ($elf.ElfHeader.Type -ne 'SharedObject (0x3)' -or $elf.ElfHeader.Machine.Value -ne $machine -or
        $elf.ElfHeader.Ident.Class.Value -ne $class -or $elf.ElfHeader.Ident.DataEncoding.Value -ne 1 -or
        $elf.ElfHeader.Entry -eq 0 -or $loads.Count -eq 0 -or $interpreters.Count -ne 1 -or $notes.Count -ne 1) {
        throw 'Android ELF type, architecture, class or executable metadata is invalid.'
    }
    $description = [byte[]]$notes[0].'Description data'.Bytes
    if ($description.Length -lt 4 -or [BitConverter]::ToUInt32($description, 0) -ne 23) {
        throw 'Android AWG binary must target API23.'
    }
    foreach ($segment in $loads) {
        if ($segment.Alignment -lt 16384 -or $segment.Offset % 16384 -ne $segment.VirtualAddress % 16384) {
            throw 'Android AWG binary is not compatible with 16KiB memory pages.'
        }
    }
}

Assert-CleanSource
$goVersion = Invoke-Checked go @('version')
if ($goVersion -notmatch '\bgo1\.26\.4\b') { throw 'The reproducible AWG build requires Go 1.26.4.' }
$tags = 'with_wireguard,with_gvisor,with_quic,with_utls'
$versionText = 'unified-awg/' + $ExpectedCommit.Substring(0, 12)
$savedEnvironment = @{}
foreach ($name in @('GOOS', 'GOARCH', 'GOARM', 'GOARM64', 'GOAMD64', 'GOEXPERIMENT', 'CGO_ENABLED', 'CC', 'CGO_CFLAGS', 'CGO_LDFLAGS', 'GOFLAGS', 'GOWORK', 'GOTOOLCHAIN')) {
    $savedEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
}
New-Item -ItemType Directory -Path $OutputDirectory | Out-Null
Push-Location $SourcePath
try {
    $env:GOWORK = 'off'
    $env:GOFLAGS = ''
    $env:GOTOOLCHAIN = 'local'
    $env:GOAMD64 = 'v1'
    $env:GOARM64 = 'v8.0'
    $env:GOEXPERIMENT = ''
    $env:GOOS = 'windows'
    $env:GOARCH = 'amd64'
    $env:GOARM = ''
    $env:CGO_ENABLED = '0'
    $module = (Invoke-Checked go @('mod', 'edit', '-json') | Out-String | ConvertFrom-Json)
    $replacement = @($module.Replace | Where-Object { $_.Old.Path -eq 'github.com/sagernet/wireguard-go' })
    if ($replacement.Count -ne 1 -or $replacement[0].New.Path -ne 'github.com/throneproj/wireguard-go' -or
        $replacement[0].New.Version -notmatch '^v0\.0\.0-[0-9]{14}-[0-9a-f]{12}$') {
        throw 'Expected a versioned Throne AWG module replacement, not a local or unpinned module.'
    }
    $wireGuardVersion = $replacement[0].New.Version
    Invoke-Checked go @('test', '-mod=readonly', '-p=2', '-count=1', '-tags', $tags, './option', './protocol/wireguard', './transport/wireguard')

    $targets = @(@{ Name = 'sing-box-windows-amd64.exe'; Os = 'windows'; Arch = 'amd64'; Abi = ''; Triple = '' })
    if (-not $SkipAndroid) {
        if (-not $AndroidNdk) { $AndroidNdk = Join-Path $env:USERPROFILE 'Android\Sdk\ndk\28.2.13676358' }
        $AndroidNdk = (Resolve-Path -LiteralPath $AndroidNdk).Path
        $ndkProperties = ConvertFrom-StringData (Get-Content -LiteralPath (Join-Path $AndroidNdk 'source.properties') -Raw)
        if ($ndkProperties['Pkg.Revision'].Trim() -ne '28.2.13676358') { throw 'The reproducible AWG build requires NDK 28.2.13676358.' }
        $targets += @(
            @{ Name = 'android/arm64-v8a/libsing-box.so'; Os = 'android'; Arch = 'arm64'; Abi = 'arm64-v8a'; Triple = 'aarch64-linux-android' },
            @{ Name = 'android/armeabi-v7a/libsing-box.so'; Os = 'android'; Arch = 'arm'; Abi = 'armeabi-v7a'; Triple = 'armv7a-linux-androideabi' },
            @{ Name = 'android/x86_64/libsing-box.so'; Os = 'android'; Arch = 'amd64'; Abi = 'x86_64'; Triple = 'x86_64-linux-android' }
        )
    }
    $files = [ordered]@{}
    $moduleSum = $null
    foreach ($target in $targets) {
        $env:GOOS = $target.Os
        $env:GOARCH = $target.Arch
        $env:GOARM = if ($target.Arch -eq 'arm') { '7' } else { '' }
        $env:CGO_ENABLED = if ($target.Os -eq 'android') { '1' } else { '0' }
        $ldflags = '-s -w -checklinkname=0 -X github.com/sagernet/sing-box/constant.Version=' + $versionText
        if ($target.Os -eq 'android') {
            $env:CC = Join-Path $AndroidNdk ('toolchains\llvm\prebuilt\windows-x86_64\bin\' + $target.Triple + '23-clang.cmd')
            if (-not (Test-Path -LiteralPath $env:CC -PathType Leaf)) { throw 'The required API23 NDK compiler is missing.' }
            $env:CGO_CFLAGS = '-O2'
            $env:CGO_LDFLAGS = '-Wl,-z,max-page-size=16384'
            $ldflags += ' -extldflags=-Wl,-z,max-page-size=16384'
        }
        $output = Join-Path $OutputDirectory $target.Name
        New-Item -ItemType Directory -Force -Path (Split-Path -Parent $output) | Out-Null
        Invoke-Checked go @('build', '-mod=readonly', '-p=2', '-trimpath', '-buildvcs=true', '-tags', $tags,
            '-ldflags', $ldflags, '-o', $output, './cmd/sing-box')
        $info = (Invoke-Checked go @('version', '-m', '-json', $output) | Out-String | ConvertFrom-Json)
        $settings = @{}
        foreach ($setting in $info.Settings) { $settings[$setting.Key] = $setting.Value }
        $backend = @($info.Deps | Where-Object { $_.Path -eq 'github.com/sagernet/wireguard-go' })
        if ($info.GoVersion -ne 'go1.26.4' -or $info.Path -ne 'github.com/sagernet/sing-box/cmd/sing-box' -or
            $settings['vcs.revision'] -ne $ExpectedCommit -or $settings['vcs.modified'] -ne 'false' -or
            $settings['GOOS'] -ne $target.Os -or $settings['GOARCH'] -ne $target.Arch -or
            $settings['CGO_ENABLED'] -ne $env:CGO_ENABLED -or $settings['-tags'] -ne $tags -or
            $backend.Count -ne 1 -or $backend[0].Replace.Path -ne 'github.com/throneproj/wireguard-go' -or
            $backend[0].Replace.Version -ne $wireGuardVersion -or -not $backend[0].Replace.Sum) {
            throw 'Built AWG binary failed source, toolchain, target or module provenance checks.'
        }
        if ($moduleSum -and $moduleSum -ne $backend[0].Replace.Sum) { throw 'AWG module checksum differs across targets.' }
        $moduleSum = $backend[0].Replace.Sum
        if ($target.Os -eq 'android') { Assert-AndroidElf -Binary $output -Target $target }
        $files[$target.Name] = [ordered]@{
            size = (Get-Item -LiteralPath $output).Length
            sha256 = (Get-FileHash -LiteralPath $output -Algorithm SHA256).Hash.ToLowerInvariant()
            os = $target.Os; arch = $target.Arch; abi = $target.Abi
        }
        Write-Output "Verified AWG binary: $($target.Name)"
    }
    Assert-CleanSource
    $archive = Join-Path $OutputDirectory 'sing-box-source.tar.gz'
    Invoke-Checked git @('archive', '--format=tar.gz', '--output', $archive, $ExpectedCommit)
    $files['sing-box-source.tar.gz'] = [ordered]@{
        size = (Get-Item -LiteralPath $archive).Length
        sha256 = (Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash.ToLowerInvariant()
    }
    $manifest = [ordered]@{
        schema = 1; repository = 'https://github.com/Throneproj/sing-box'; commit = $ExpectedCommit
        go_version = 'go1.26.4'; tags = $tags; version_text = $versionText
        wireguard_module = 'github.com/throneproj/wireguard-go'; wireguard_version = $wireGuardVersion; wireguard_sum = $moduleSum
        android_api = 23; android_ndk = '28.2.13676358'; files = $files
    }
    [IO.File]::WriteAllText((Join-Path $OutputDirectory 'manifest.json'), ($manifest | ConvertTo-Json -Depth 8) + "`n", [Text.UTF8Encoding]::new($false))
    Write-Output 'Pinned AWG candidates built and verified. Installed or packaged engines were not changed.'
} finally {
    Pop-Location
    foreach ($name in $savedEnvironment.Keys) { [Environment]::SetEnvironmentVariable($name, $savedEnvironment[$name], 'Process') }
}
