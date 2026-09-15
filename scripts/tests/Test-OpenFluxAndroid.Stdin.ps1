#requires -Version 7.0
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot '..\Test-OpenFluxAndroid.ps1')
$testRoot = $PSScriptRoot
$failures = [Collections.Generic.List[string]]::new()
$testCount = 0

function Invoke-IsolatedAdb { throw 'ADB is forbidden in the public stdin-pipe tests' }
function Assert-True {
    param([bool]$Condition, [string]$Message)
    if (-not $Condition) { throw $Message }
}
function Get-ByteHash {
    param([byte[]]$Bytes)
    $sha = [Security.Cryptography.SHA256]::Create()
    try { return ([BitConverter]::ToString($sha.ComputeHash($Bytes))).Replace('-', '').ToLowerInvariant() } finally { $sha.Dispose() }
}
function New-PublicProbeStartInfo {
    param([string]$Mode = 'Hash')
    $start = [Diagnostics.ProcessStartInfo]::new()
    $start.FileName = Join-Path $PSHOME 'pwsh.exe'
    foreach ($argument in @('-NoLogo', '-NoProfile', '-NonInteractive', '-File',
            (Join-Path $testRoot 'Read-PublicStdinProbe.ps1'), '-Mode', $Mode)) { $start.ArgumentList.Add($argument) }
    return $start
}
function Assert-ByteExactTransfer {
    param([byte[]]$Payload, [switch]$ConfiguredWithBom)
    $start = New-PublicProbeStartInfo
    if ($ConfiguredWithBom) { $start.StandardInputEncoding = [Text.UTF8Encoding]::new($true) }
    $result = Invoke-RedirectedChildProcess -StartInfo $start -InputBytes $Payload -TimeoutSeconds 10
    Assert-True ($result.ExitCode -eq 0) 'Public helper subprocess failed'
    $received = $result.Output | ConvertFrom-Json
    Assert-True ($received.length -eq $Payload.Length) 'Redirected stdin byte length changed'
    Assert-True ($received.sha256 -eq (Get-ByteHash -Bytes $Payload)) 'Redirected stdin bytes changed'
    Assert-True ($start.StandardInputEncoding.GetPreamble().Length -eq 0) 'Child stdin retained a BOM-producing encoding'
}
function Invoke-LegacyPublicTransfer {
    param([byte[]]$Payload, [switch]$ForceBom)
    $start = New-PublicProbeStartInfo
    $start.UseShellExecute = $false
    $start.CreateNoWindow = $true
    $start.RedirectStandardInput = $true
    $start.RedirectStandardOutput = $true
    $start.RedirectStandardError = $true
    if ($ForceBom) { $start.StandardInputEncoding = [Text.UTF8Encoding]::new($true) }
    $process = [Diagnostics.Process]::new()
    $process.StartInfo = $start
    $launched = $false
    try {
        $launched = $process.Start()
        Assert-True $launched 'Legacy reproducer could not start public helper'
        $stdout = $process.StandardOutput.ReadToEndAsync()
        $stderr = $process.StandardError.ReadToEndAsync()
        [byte[]]$preamble = $process.StandardInput.Encoding.GetPreamble()
        $write = $process.StandardInput.BaseStream.WriteAsync($Payload, 0, $Payload.Length)
        Assert-True ($write.Wait(10000)) 'Legacy public write timed out'
        $process.StandardInput.Close()
        Assert-True ($process.WaitForExit(10000)) 'Legacy public helper did not stop'
        Assert-True ($process.ExitCode -eq 0) 'Legacy public helper failed'
        $received = ($stdout.Result + $stderr.Result) | ConvertFrom-Json
        return [pscustomobject]@{ Received = $received; Preamble = $preamble }
    } finally {
        try {
            if ($launched -and -not $process.HasExited) {
                $process.Kill()
                Assert-True ($process.WaitForExit(5000)) 'Legacy public helper cleanup timed out'
            }
        } finally { $process.Dispose() }
    }
}
function Invoke-PipeCase {
    param([string]$Name, [scriptblock]$Test)
    $script:testCount++
    try { & $Test; Write-Output ('PASS ' + $Name) } catch {
        $script:failures.Add($Name + ': ' + $_.Exception.Message)
        Write-Output ('FAIL ' + $Name)
    }
}

$publicPayload = [Text.UTF8Encoding]::new($false).GetBytes('{"public_fixture":true,"version":1}')
Invoke-PipeCase 'legacy text-writer path adds a BOM with UTF-8 BOM encoding' {
    $legacy = Invoke-LegacyPublicTransfer -Payload $publicPayload -ForceBom
    Assert-True ($legacy.Preamble.Length -eq 3) 'Legacy reproducer did not use UTF-8 BOM encoding'
    Assert-True ($legacy.Received.length -eq $publicPayload.Length + 3) 'Legacy path did not add its BOM'
    $prefix = $legacy.Received.sha256 -eq (Get-ByteHash -Bytes ([byte[]]($legacy.Preamble + $publicPayload)))
    $suffix = $legacy.Received.sha256 -eq (Get-ByteHash -Bytes ([byte[]]($publicPayload + $legacy.Preamble)))
    Assert-True ($prefix -or $suffix) 'Legacy extra bytes were not its encoding preamble'
    Write-Output ('LEGACY_FORCED_BOM_PLACEMENT=' + $(if ($prefix) { 'prefix' } else { 'suffix' }))
}
Invoke-PipeCase 'legacy default encoding behavior is measured without changing console encoding' {
    $legacy = Invoke-LegacyPublicTransfer -Payload $publicPayload
    $prefix = $legacy.Received.sha256 -eq (Get-ByteHash -Bytes ([byte[]]($legacy.Preamble + $publicPayload)))
    $suffix = $legacy.Received.sha256 -eq (Get-ByteHash -Bytes ([byte[]]($publicPayload + $legacy.Preamble)))
    Assert-True ($prefix -or $suffix) 'Legacy default pipe behavior was not explained by its preamble'
    Write-Output ('LEGACY_DEFAULT_STDIN_PREAMBLE_BYTES=' + $legacy.Preamble.Length)
    Write-Output ('LEGACY_DEFAULT_STDIN_BYTE_EXACT=' + ($legacy.Received.sha256 -eq (Get-ByteHash -Bytes $publicPayload)).ToString().ToLowerInvariant())
}
Invoke-PipeCase 'public JSON transfers byte-exactly' { Assert-ByteExactTransfer -Payload $publicPayload }
Invoke-PipeCase 'BOM-producing start configuration is overridden for raw stdin' { Assert-ByteExactTransfer -Payload $publicPayload -ConfiguredWithBom }
Invoke-PipeCase 'empty stdin stays empty at EOF' { Assert-ByteExactTransfer -Payload ([byte[]]::new(0)) }
Invoke-PipeCase 'all byte values and a large pipe write remain unchanged' {
    $bytes = [byte[]]::new(65536)
    for ($index = 0; $index -lt $bytes.Length; $index++) { $bytes[$index] = [byte]($index % 256) }
    Assert-ByteExactTransfer -Payload $bytes
}
Invoke-PipeCase 'an intentional payload BOM is preserved exactly once' {
    Assert-ByteExactTransfer -Payload ([byte[]](@(239, 187, 191) + $publicPayload))
}
Invoke-PipeCase 'non-ASCII UTF-8 is not decoded or rewritten by stdin' {
    $text = 'public:' + [char]0x0416 + [char]0x6f22 + [char]0x2603
    Assert-ByteExactTransfer -Payload ([Text.UTF8Encoding]::new($false).GetBytes($text))
}
Invoke-PipeCase 'timed-out local helper is reaped within the cleanup budget' {
    $timer = [Diagnostics.Stopwatch]::StartNew()
    $failure = $null
    try { Invoke-RedirectedChildProcess -StartInfo (New-PublicProbeStartInfo -Mode 'Wait') -TimeoutSeconds 1 | Out-Null } catch { $failure = $_ }
    Assert-True ($null -ne $failure -and $timer.Elapsed.TotalSeconds -lt 8) 'Owned local helper did not time out and stop promptly'
}

$hash = Get-ByteHash -Bytes $publicPayload
foreach ($format in @("$hash  files/locations_v4.json", "$hash *files/locations_v4.json`r`n", "$($hash.ToUpperInvariant())`tfiles/locations_v4.json")) {
    Invoke-PipeCase 'single matching Android checksum record is accepted' {
        Assert-TransferredProfileChecksum -Response $format -ExpectedHash $hash
    }
}
foreach ($malformed in @("diagnostic-prefix`n$hash  files/locations_v4.json", "$hash  other.json", "$hash  files/locations_v4.json`n$hash  files/locations_v4.json")) {
    Invoke-PipeCase 'malformed or ambiguous checksum output is rejected separately' {
        $failure = $null
        try { Assert-TransferredProfileChecksum -Response $malformed -ExpectedHash $hash } catch { $failure = $_ }
        Assert-True ($null -ne $failure -and $failure.Exception.Message -eq 'Android checksum response was not a single expected file record') 'Malformed checksum was not classified correctly'
    }
}
Invoke-PipeCase 'valid checksum output with changed payload still fails' {
    $failure = $null
    try { Assert-TransferredProfileChecksum -Response (('0' * 64) + '  files/locations_v4.json') -ExpectedHash $hash } catch { $failure = $_ }
    Assert-True ($null -ne $failure -and $failure.Exception.Message -eq 'Private profile stdin transfer was incomplete') 'Actual payload mismatch was not classified correctly'
}
Invoke-PipeCase 'public profile transfer uses acknowledged non-PTY stdin and verifies received bytes' {
    $fixtureRoot = Join-Path $repo ('.downloads\openflux\android-app-test\public-stdin-' + [Guid]::NewGuid().ToString('N'))
    $PrivateProfilePath = Join-Path $fixtureRoot 'public-profile.json'
    $transferState = @{ Hash = ''; Length = 0; Calls = [Collections.Generic.List[string]]::new() }
    function Invoke-IsolatedAdb {
        param([string[]]$AdbArguments, [int]$TimeoutSeconds = 30, [byte[]]$InputBytes = $null)
        $command = $AdbArguments -join ' '
        $transferState.Calls.Add($command)
        if ($null -ne $InputBytes) {
            Assert-True ($command -eq "shell -T run-as $package dd of=files/locations_v4.json") 'Profile transfer did not use acknowledged non-PTY stdin'
            $result = Invoke-RedirectedChildProcess -StartInfo (New-PublicProbeStartInfo) -InputBytes $InputBytes -TimeoutSeconds 10
            Assert-True ($result.ExitCode -eq 0) 'Public transfer helper failed'
            $received = $result.Output | ConvertFrom-Json
            $transferState.Hash = $received.sha256
            $transferState.Length = $received.length
            return [pscustomobject]@{ ExitCode = 0; Output = 'public synthetic dd completion' }
        }
        if ($command -eq "shell run-as $package sha256sum files/locations_v4.json") {
            return [pscustomobject]@{ ExitCode = 0; Output = ($transferState.Hash + '  files/locations_v4.json') }
        }
        if ($command -notin @("shell run-as $package mkdir -p files", "shell run-as $package chmod 600 files/locations_v4.json")) {
            throw 'Unexpected external operation in public profile transfer test'
        }
        return [pscustomobject]@{ ExitCode = 0; Output = '' }
    }
    New-Item -ItemType Directory -Path $fixtureRoot -Force | Out-Null
    try {
        [ordered]@{ version = 1; transport = 'yandex'; document_url = 'https://docs.yandex.ru/i/public-stdin-fixture'; encryption_key = ('01' * 32) } |
            ConvertTo-Json -Compress | Set-Content -LiteralPath $PrivateProfilePath -Encoding UTF8
        Send-PrivateProfileBundle
        Assert-True ($transferState.Length -gt 0 -and $transferState.Calls.Count -eq 4) 'Public profile transfer did not complete and verify its payload'
    } finally {
        Remove-Item -LiteralPath $PrivateProfilePath -Force
        Remove-Item -LiteralPath $fixtureRoot
    }
}

if ($failures.Count -gt 0) {
    $failures | Write-Output
    throw "$($failures.Count) of $testCount public stdin-pipe tests failed"
}
Write-Output ("PUBLIC_STDIN_PIPE_TESTS_PASSED=$testCount")
