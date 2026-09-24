#requires -Version 7.0
[CmdletBinding()]
param(
    [Parameter(Mandatory)] [string]$LatestSource,
    [Parameter(Mandatory)] [string]$LegacySource,
    [Parameter(Mandatory)] [string]$CompatibleSource,
    [ValidateRange(30, 600)] [int]$TimeoutSeconds = 300,
    [string]$GoBinary = 'go'
)

$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
$fixtures = Join-Path $PSScriptRoot 'tests\olcrtc-compatibility'
$output = Join-Path $repo ('.downloads\olcrtc-compatibility\run-' + [Guid]::NewGuid().ToString('N'))
$pins = [ordered]@{
    latest = @{ path = $LatestSource; sha = '08843d6accd7f43a1d04aa0ac7a3d5ea90e27efa'; modern = $true }
    legacy = @{ path = $LegacySource; sha = 'f616f57bb3a90740f1755922ffeaa7acc5cfe4ed'; modern = $false }
    compatible = @{ path = $CompatibleSource; sha = 'd7a00da5242f72a48b505ffc4b6aa8246376bf25'; modern = $false }
}
$go = (Get-Command $GoBinary -CommandType Application -ErrorAction Stop | Select-Object -First 1).Source
$git = (Get-Command git -CommandType Application -ErrorAction Stop | Select-Object -First 1).Source

function Assert-CleanSource {
    param($Pin)
    $path = (Resolve-Path -LiteralPath $Pin.path).Path
    if (-not (Test-Path -LiteralPath (Join-Path $path '.git') -PathType Container)) {
        throw 'A standalone, already downloaded olcRTC Git clone is required'
    }
    $head = & $git --no-optional-locks -C $path rev-parse HEAD
    if ($LASTEXITCODE -ne 0 -or $head.Trim() -ne $Pin.sha) { throw 'Source does not match the expected public commit' }
    $dirty = & $git --no-optional-locks -C $path status --porcelain --untracked-files=all
    if ($LASTEXITCODE -ne 0 -or $dirty) { throw 'Compatibility checks require an unchanged clean source tree' }
    $Pin.path = $path
}

function Invoke-EnvelopeTests {
    param([string]$Label, $Pin, [switch]$Emit)
    $replacement = [ordered]@{}
    foreach ($name in @('receiver_test.go') + $(if ($Emit) { @('sender_test.go') } else { @() })) {
        $target = Join-Path $Pin.path ('internal\engine\jitsi\unifiedvpn_envelope_' + $name)
        if (Test-Path -LiteralPath $target) { throw 'Refusing to shadow an existing core source file' }
        $replacement[$target] = Join-Path $fixtures $name
    }
    $overlay = Join-Path $output ($Label + '.overlay.json')
    [IO.File]::WriteAllText($overlay, (@{ Replace = $replacement } | ConvertTo-Json -Depth 4), [Text.UTF8Encoding]::new($false))
    $start = [Diagnostics.ProcessStartInfo]::new()
    $start.FileName = $go
    $start.WorkingDirectory = $Pin.path
    $start.UseShellExecute = $false
    $start.CreateNoWindow = $true
    $start.RedirectStandardOutput = $true
    $start.RedirectStandardError = $true
    foreach ($name in @($start.Environment.Keys)) {
        if ($name -match '^(OLCRTC_|UNIFIEDVPN_PRIVATE_|UNIFIEDVPN_WIRE_)') {
            $start.Environment.Remove($name) | Out-Null
        }
    }
    $start.Environment['GOWORK'] = 'off'
    $start.Environment['GOFLAGS'] = ''
    $start.Environment['GOPROXY'] = 'off'
    $start.Environment['GOTOOLCHAIN'] = 'local'
    $start.Environment['GOVCS'] = '*:off'
    $start.Environment['GOMAXPROCS'] = '4'
    $start.Environment['UNIFIEDVPN_WIRE_FIXTURE'] = Join-Path $output 'latest-sender-wire.json'
    $start.Environment['UNIFIEDVPN_WIRE_ACCEPT_MODERN'] = $Pin.modern.ToString().ToLowerInvariant()
    $pattern = if ($Emit) { '^TestUnifiedVPNEnvelopeSender$' } else { '^TestUnifiedVPNEnvelopeReceiver' }
    foreach ($argument in @('test', '-mod=readonly', '-count=1', '-json', '-p=2',
        "-timeout=${TimeoutSeconds}s", ('-overlay=' + $overlay), '-run', $pattern, './internal/engine/jitsi')) {
        $start.ArgumentList.Add($argument)
    }
    $process = [Diagnostics.Process]::new()
    $process.StartInfo = $start
    $started = $false
    try {
        $started = $process.Start()
        if (-not $started) { throw 'Could not start the isolated compatibility tests' }
        $stdout = $process.StandardOutput.ReadToEndAsync()
        $stderr = $process.StandardError.ReadToEndAsync()
        if (-not $process.WaitForExit($TimeoutSeconds * 1000)) { throw 'Compatibility tests exceeded their timeout' }
        [IO.File]::WriteAllText((Join-Path $output ($Label + '.jsonl')), $stdout.Result, [Text.UTF8Encoding]::new($false))
        [IO.File]::WriteAllText((Join-Path $output ($Label + '.stderr.txt')), $stderr.Result, [Text.UTF8Encoding]::new($false))
        $events = @($stdout.Result.Split([char]10) | Where-Object { $_.Trim() } | ForEach-Object { $_ | ConvertFrom-Json })
        $passed = @($events | Where-Object { $_.Action -eq 'pass' -and $_.Test }).Count
        $expectedCount = if ($Emit) { 1 } else { 2 }
        if ($process.ExitCode -ne 0 -or $passed -ne $expectedCount -or
            @($events | Where-Object { $_.Action -in @('fail', 'skip') }).Count -ne 0) {
            throw "Unexpected compatibility result in $Label; see isolated JSON/stderr logs (dependencies must already be cached)"
        }
        Write-Host "PASS $Label ($passed tests)"
        return [ordered]@{ stage = $Label; commit = $Pin.sha; tests_passed = $passed }
    } finally {
        if ($started -and -not $process.HasExited) {
            $process.Kill($true)
            $process.WaitForExit()
        }
        $process.Dispose()
    }
}

foreach ($pin in $pins.Values) { Assert-CleanSource $pin }
New-Item -ItemType Directory -Path $output | Out-Null
$stages = [Collections.Generic.List[object]]::new()
$failure = $null
try {
    $stages.Add((Invoke-EnvelopeTests -Label 'latest-sender' -Pin $pins.latest -Emit))
    foreach ($name in $pins.Keys) {
        $stages.Add((Invoke-EnvelopeTests -Label ($name + '-receiver') -Pin $pins[$name]))
    }
} catch {
    $failure = $_
} finally {
    try { foreach ($pin in $pins.Values) { Assert-CleanSource $pin } } catch { if ($null -eq $failure) { $failure = $_ } }
    [ordered]@{
        passed = ($null -eq $failure)
        synthetic_only = $true
        latest_sender_fixture_sha256 = if (Test-Path -LiteralPath (Join-Path $output 'latest-sender-wire.json')) {
            (Get-FileHash -LiteralPath (Join-Path $output 'latest-sender-wire.json') -Algorithm SHA256).Hash.ToLowerInvariant()
        } else { $null }
        matrix = @($pins.Keys | ForEach-Object {
            [ordered]@{ receiver = $_; commit = $pins[$_].sha; accepts_legacy = $true; accepts_modern = $pins[$_].modern }
        })
        stages = @($stages)
        error = if ($failure) { $failure.Exception.Message } else { $null }
    } | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $output 'result.json') -Encoding utf8
    Write-Host "OLCRTC_COMPATIBILITY_REPORT=$output"
}
if ($failure) { throw $failure }
