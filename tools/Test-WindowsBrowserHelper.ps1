param([Parameter(Mandatory=$true)][string]$HelperFile)
$ErrorActionPreference = 'Stop'
$helper = (Resolve-Path -LiteralPath $HelperFile).Path
$tempRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd([IO.Path]::DirectorySeparatorChar)
$leaf = 'unifiedvpn-browser-' + [Guid]::NewGuid().ToString('N')
$directory = Join-Path $tempRoot $leaf
$start = [Diagnostics.ProcessStartInfo]::new($helper, '--self-test-browser')
$start.UseShellExecute = $false
$start.CreateNoWindow = $true
$start.RedirectStandardInput = $true
$start.RedirectStandardOutput = $true
$start.RedirectStandardError = $true
$process = [Diagnostics.Process]::new()
$process.StartInfo = $start
$result = [ordered]@{
    started_utc = [DateTimeOffset]::UtcNow.ToString('o')
    exit_code = -1
    synthetic_cookie_matches = $false
}
$started = $false
try {
    $started = $process.Start()
    $stdout = $process.StandardOutput.ReadToEndAsync()
    $stderr = $process.StandardError.ReadToEndAsync()
    $request = @{
        id = ('a' * 32)
        document_url = 'https://docs.yandex.ru/docs/view?offline=synthetic'
        user_data_dir = $directory
    } | ConvertTo-Json -Compress
    $process.StandardInput.WriteLine($request)
    $process.StandardInput.Flush()
    if (-not $process.WaitForExit(20000)) { throw 'Owned offline browser smoke exceeded its deadline' }
    $result.exit_code = $process.ExitCode
    $result.diagnostic = @($stderr.Result -split '[\r\n]+' | Where-Object {
        $_ -match '^OFFLINE_WEBVIEW2_SMOKE [a-zA-Z0-9_= .-]+$'
    })
    $reply = $null
    try { $reply = $stdout.Result | ConvertFrom-Json -ErrorAction Stop }
    catch { $result.reply_invalid = $true }
    $cookies = @($reply.cookies | Where-Object { $null -ne $_ })
    $result.request_id_matches = $reply.id -eq ('a' * 32)
    $result.cookie_count = $cookies.Count
    if ($cookies.Count -eq 1) {
        $result.synthetic_cookie_matches = $cookies[0].name -eq 'anonymous' -and
            $cookies[0].value -eq 'synthetic' -and $cookies[0].secure -eq $true
    }
    if (Test-Path -LiteralPath $directory) {
        $rules = @((Get-Acl -LiteralPath $directory).GetAccessRules($true, $true, [Security.Principal.SecurityIdentifier]))
        $broad = @('S-1-1-0', 'S-1-5-11', 'S-1-5-32-545', 'S-1-15-2-1')
        $result.broad_read_allow_count = @($rules | Where-Object {
            $_.AccessControlType -eq 'Allow' -and $broad -contains $_.IdentityReference.Value -and
                ($_.FileSystemRights -band [Security.AccessControl.FileSystemRights]::ReadData) -ne 0
        }).Count
    }
} finally {
    if ($started -and -not $process.HasExited) {
        $process.Kill()
        $null = $process.WaitForExit(5000)
    }
    if ($started) { $process.StandardInput.Dispose() }
    $process.Dispose()
    Start-Sleep -Milliseconds 500
    $remaining = @(Get-CimInstance -Query "SELECT ProcessId FROM Win32_Process WHERE Name='msedgewebview2.exe' AND CommandLine LIKE '%$leaf%'").Count
    if ($remaining -ne 0) { throw 'Owned browser process still exists; directory retained' }
    $full = [IO.Path]::GetFullPath($directory)
    if ([IO.Path]::GetDirectoryName($full) -ne $tempRoot -or [IO.Path]::GetFileName($full) -ne $leaf -or
        $leaf -notmatch '^unifiedvpn-browser-[0-9a-f]{32}$') { throw 'Owned directory guard rejected cleanup' }
    if (Test-Path -LiteralPath $full) { Remove-Item -LiteralPath $full -Recurse -Force }
    $result.owned_browser_processes_remaining = $remaining
    $result.owned_directory_removed = -not (Test-Path -LiteralPath $full)
    $result.finished_utc = [DateTimeOffset]::UtcNow.ToString('o')
    $result | ConvertTo-Json -Compress
}
if ($result.exit_code -ne 0 -or -not $result.synthetic_cookie_matches -or
    $result.broad_read_allow_count -ne 0) { exit 2 }
