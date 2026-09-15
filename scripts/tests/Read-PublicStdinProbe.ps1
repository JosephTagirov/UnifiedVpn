#requires -Version 7.0
param([ValidateSet('Hash', 'Wait')][string]$Mode = 'Hash')
$ErrorActionPreference = 'Stop'
if ($Mode -eq 'Wait') {
    Start-Sleep -Seconds 60
    exit 0
}
$stdin = [Console]::OpenStandardInput()
$buffer = [byte[]]::new(4096)
$received = [IO.MemoryStream]::new()
$sha = [Security.Cryptography.SHA256]::Create()
try {
    while (($count = $stdin.Read($buffer, 0, $buffer.Length)) -gt 0) {
        if ($received.Length + $count -gt 262144) { throw 'Public stdin probe payload exceeded its limit' }
        $received.Write($buffer, 0, $count)
    }
    $bytes = $received.ToArray()
    [ordered]@{
        length = $bytes.Length
        sha256 = ([BitConverter]::ToString($sha.ComputeHash($bytes))).Replace('-', '').ToLowerInvariant()
    } | ConvertTo-Json -Compress
} finally {
    $sha.Dispose()
    $received.Dispose()
    $stdin.Dispose()
}
