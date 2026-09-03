[CmdletBinding()]
param(
    [switch]$WindowsProfiles,
    [switch]$WindowsOlcRtc,
    [switch]$AndroidTunnel,
    [string]$EmulatorSerial = "emulator-5554",
    [string]$AdbPath = "",
    [string]$VlessProfile = $env:UNIFIEDVPN_PRIVATE_VLESS_PROFILE,
    [string]$AwgProfile = $env:UNIFIEDVPN_PRIVATE_AWG_PROFILE,
    [string]$OlcRtcLocations = $env:UNIFIEDVPN_PRIVATE_OLCRTC_LOCATIONS,
    [string]$OlcRtcProfile = $env:UNIFIEDVPN_OLCRTC_TEST_PROFILE,
    [string]$OlcRtcRepo = $env:OLCRTC_REPO
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
$remoteProbe = "/data/local/tmp/unifiedvpn-network-probe.jar"

if (-not $AdbPath) {
    $AdbPath = @(
        "$env:USERPROFILE\Android\Sdk\platform-tools\adb.exe",
        "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
    ) | Where-Object { Test-Path -LiteralPath $_ -PathType Leaf } | Select-Object -First 1
}

function Get-ProxyFingerprint {
    $settings = Get-ItemProperty -Path "HKCU:\Software\Microsoft\Windows\CurrentVersion\Internet Settings" -ErrorAction SilentlyContinue
    $winHttp = (& netsh.exe winhttp show proxy 2>$null | Out-String).Trim()
    $payload = @(
        "ProxyEnable=$($settings.ProxyEnable)",
        "ProxyServer=$($settings.ProxyServer)",
        "AutoConfigURL=$($settings.AutoConfigURL)",
        "WinHttp=$winHttp"
    ) -join "`n"
    $bytes = [Text.Encoding]::UTF8.GetBytes($payload)
    $hash = [Security.Cryptography.SHA256]::Create()
    try {
        return ([BitConverter]::ToString($hash.ComputeHash($bytes))).Replace("-", "")
    } finally {
        $hash.Dispose()
    }
}

function Invoke-Adb {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Arguments)

    $previousErrorAction = $ErrorActionPreference
    $previousAndroidUserHome = $env:ANDROID_USER_HOME
    $previousHome = $env:HOME
    $ErrorActionPreference = "Continue"
    try {
        $env:ANDROID_USER_HOME = Join-Path $env:USERPROFILE ".android"
        $env:HOME = $env:USERPROFILE
        $output = & $AdbPath -s $EmulatorSerial @Arguments 2>&1
        $exitCode = $LASTEXITCODE
    } finally {
        $env:ANDROID_USER_HOME = $previousAndroidUserHome
        $env:HOME = $previousHome
        $ErrorActionPreference = $previousErrorAction
    }
    if ($exitCode -ne 0) {
        throw "ADB failed without changing the Windows network: $($output -join ' ')"
    }
    return $output
}

function Assert-IsolatedEmulator {
    if ($EmulatorSerial -notmatch '^emulator-\d+$') {
        throw "Refusing Android VPN test: only emulator-* serials are allowed"
    }
    if (-not (Test-Path -LiteralPath $AdbPath -PathType Leaf)) {
        throw "ADB was not found"
    }
    if ((Invoke-Adb get-state | Select-Object -First 1).Trim() -ne "device") {
        throw "Android emulator is not ready"
    }
    if ((Invoke-Adb shell getprop ro.kernel.qemu | Select-Object -First 1).Trim() -ne "1") {
        throw "Refusing Android VPN test: target is not a QEMU emulator"
    }
}

function Build-AndroidProbe {
    $source = Join-Path $repoRoot "tools\android-network-probe\NetworkProbe.java"
    $outputRoot = Join-Path $repoRoot ".downloads\isolated-network-probe"
    $classes = Join-Path $outputRoot "classes"
    $jarFile = Join-Path $outputRoot "unifiedvpn-network-probe.jar"
    New-Item -ItemType Directory -Force -Path $classes | Out-Null

    & javac.exe --release 8 -Xlint:-options -d $classes $source
    if ($LASTEXITCODE -ne 0) {
        throw "Failed to compile the Android network probe"
    }

    $sdkRoot = Split-Path -Parent (Split-Path -Parent $AdbPath)
    $d8Path = Get-ChildItem -Path (Join-Path $sdkRoot "build-tools\*\d8.bat") -File | Sort-Object FullName -Descending | Select-Object -First 1 -ExpandProperty FullName
    if (-not $d8Path) {
        throw "Android d8 was not found"
    }
    if (Test-Path -LiteralPath $jarFile) {
        Remove-Item -LiteralPath $jarFile -Force
    }
    & $d8Path --output $jarFile (Join-Path $classes "NetworkProbe.class")
    if ($LASTEXITCODE -ne 0) {
        throw "Failed to create the Android DEX probe"
    }
    return $jarFile
}

function Test-AndroidTunnel {
    Assert-IsolatedEmulator
    $pidText = (Invoke-Adb shell pidof app.unifiedvpn.local | Select-Object -First 1).Trim()
    if ($pidText -notmatch '^\d+$') {
        throw "Unified VPN is not running in the emulator"
    }

    $connectivity = Invoke-Adb shell dumpsys connectivity
    if (($connectivity -join "`n") -notmatch 'ni\{VPN CONNECTED extra: VPN:app\.unifiedvpn\.local\}') {
        throw "No active Android VPN transport was found in the emulator"
    }

    $jarFile = Build-AndroidProbe
    try {
        Invoke-Adb push $jarFile $remoteProbe | Out-Null
        $result = Invoke-Adb shell env "CLASSPATH=$remoteProbe" app_process /data/local/tmp NetworkProbe https://www.instagram.com/ https://www.wikipedia.org/
        $lines = @($result | Where-Object { $_ -match '^(www\.instagram\.com|www\.wikipedia\.org)_http=[1-5]\d{2}$' })
        if ($lines.Count -ne 2) {
            throw "Android tunnel did not return valid HTTPS responses for both targets"
        }
        $pidAfter = (Invoke-Adb shell pidof app.unifiedvpn.local | Select-Object -First 1).Trim()
        if ($pidAfter -ne $pidText) {
            throw "Unified VPN restarted during the Android tunnel test"
        }
        $lines | ForEach-Object { Write-Host $_ }
    } finally {
        try { Invoke-Adb shell rm -f $remoteProbe | Out-Null } catch { }
    }
}

function Test-WindowsProfiles {
    if (-not $VlessProfile -or -not (Test-Path -LiteralPath $VlessProfile -PathType Leaf)) {
        throw "Set UNIFIEDVPN_PRIVATE_VLESS_PROFILE to a private local profile file"
    }
    if (-not $AwgProfile -or -not (Test-Path -LiteralPath $AwgProfile -PathType Leaf)) {
        throw "Set UNIFIEDVPN_PRIVATE_AWG_PROFILE to a private local profile file"
    }

    $env:UNIFIEDVPN_PRIVATE_VLESS_PROFILE = (Resolve-Path -LiteralPath $VlessProfile).Path
    $env:UNIFIEDVPN_PRIVATE_AWG_PROFILE = (Resolve-Path -LiteralPath $AwgProfile).Path
    if ($OlcRtcRepo) {
        $env:OLCRTC_REPO = (Resolve-Path -LiteralPath $OlcRtcRepo).Path
        $env:GIT_CONFIG_COUNT = "1"
        $env:GIT_CONFIG_KEY_0 = "safe.directory"
        $env:GIT_CONFIG_VALUE_0 = $env:OLCRTC_REPO.Replace('\', '/')
    }
    if (-not $env:GRADLE_USER_HOME) {
        $env:GRADLE_USER_HOME = Join-Path $env:USERPROFILE ".gradle"
    }

    Push-Location $repoRoot
    try {
        $gradleArguments = @(
            "-Duser.home=$env:USERPROFILE",
            ":sharedUI:jvmTest",
            "--tests",
            "org.olcbox.app.vpn.DesktopNativeProfileIntegrationTest",
            "--rerun-tasks",
            "--no-configuration-cache",
            "--no-daemon"
        )
        & .\gradlew.bat @gradleArguments
        if ($LASTEXITCODE -ne 0) {
            throw "Isolated Windows LocalSocks profile tests failed"
        }
    } finally {
        Pop-Location
    }
}

function Test-WindowsOlcRtc {
    if (-not $OlcRtcLocations -or -not (Test-Path -LiteralPath $OlcRtcLocations -PathType Leaf)) {
        throw "Set UNIFIEDVPN_PRIVATE_OLCRTC_LOCATIONS to a private locations_v4.json file"
    }
    if (-not $OlcRtcProfile) {
        throw "Set UNIFIEDVPN_OLCRTC_TEST_PROFILE or pass -OlcRtcProfile"
    }
    if (-not $OlcRtcRepo -or -not (Test-Path -LiteralPath $OlcRtcRepo -PathType Container)) {
        throw "Set OLCRTC_REPO to the pinned standalone olcRTC source clone"
    }

    $testRoot = Join-Path $repoRoot ".downloads"
    New-Item -ItemType Directory -Force -Path $testRoot | Out-Null
    $testDataDir = Join-Path $testRoot ("windows-olcrtc-test-" + [Guid]::NewGuid().ToString("N"))
    New-Item -ItemType Directory -Path $testDataDir | Out-Null
    Copy-Item -LiteralPath $OlcRtcLocations -Destination (Join-Path $testDataDir "locations_v4.json")

    $previousAppData = $env:APPDATA
    $previousTestDataDir = $env:UNIFIEDVPN_OLCRTC_TEST_DATA_DIR
    $previousTestProfile = $env:UNIFIEDVPN_OLCRTC_TEST_PROFILE
    $previousNativeExe = $env:OLCBOX_OLCRTC_EXE
    try {
        $env:APPDATA = Join-Path $testDataDir "appdata"
        New-Item -ItemType Directory -Force -Path $env:APPDATA | Out-Null
        $env:UNIFIEDVPN_OLCRTC_TEST_DATA_DIR = $testDataDir
        $env:UNIFIEDVPN_OLCRTC_TEST_PROFILE = $OlcRtcProfile
        $env:OLCRTC_REPO = (Resolve-Path -LiteralPath $OlcRtcRepo).Path
        $env:GIT_CONFIG_COUNT = "1"
        $env:GIT_CONFIG_KEY_0 = "safe.directory"
        $env:GIT_CONFIG_VALUE_0 = $env:OLCRTC_REPO.Replace('\', '/')
        $env:OLCBOX_OLCRTC_EXE = Join-Path $repoRoot "desktopApp\build\generated\desktopNativeResources\native\olcrtc-windows-amd64.exe"
        if (-not $env:GRADLE_USER_HOME) {
            $env:GRADLE_USER_HOME = Join-Path $env:USERPROFILE ".gradle"
        }

        Push-Location $repoRoot
        try {
            & .\gradlew.bat `
                "-Duser.home=$env:USERPROFILE" `
                :desktopApp:buildOlcRtcWindowsAmd64 `
                :desktopApp:copyOlcRtcDataAssets `
                :sharedUI:jvmTest `
                --tests org.olcbox.app.vpn.DesktopOlcRtcIntegrationTest `
                --rerun-tasks `
                --no-configuration-cache `
                --no-daemon
            if ($LASTEXITCODE -ne 0) {
                throw "Isolated Windows olcRTC LocalSocks test failed"
            }
        } finally {
            Pop-Location
        }
    } finally {
        $env:APPDATA = $previousAppData
        $env:UNIFIEDVPN_OLCRTC_TEST_DATA_DIR = $previousTestDataDir
        $env:UNIFIEDVPN_OLCRTC_TEST_PROFILE = $previousTestProfile
        $env:OLCBOX_OLCRTC_EXE = $previousNativeExe

        $testRootFull = [IO.Path]::GetFullPath($testRoot).TrimEnd('\') + '\'
        $testDataFull = [IO.Path]::GetFullPath($testDataDir)
        if (-not $testDataFull.StartsWith($testRootFull, [StringComparison]::OrdinalIgnoreCase)) {
            throw "Refusing to clean an olcRTC test directory outside .downloads"
        }
        Remove-Item -LiteralPath $testDataFull -Recurse -Force
    }
}

if (-not $WindowsProfiles -and -not $WindowsOlcRtc -and -not $AndroidTunnel) {
    throw "Choose -WindowsProfiles, -WindowsOlcRtc, -AndroidTunnel, or a combination"
}

$proxyBefore = Get-ProxyFingerprint
try {
    if ($WindowsProfiles) {
        Test-WindowsProfiles
    }
    if ($WindowsOlcRtc) {
        Test-WindowsOlcRtc
    }
    if ($AndroidTunnel) {
        Test-AndroidTunnel
    }
} finally {
    $proxyAfter = Get-ProxyFingerprint
    if ($proxyAfter -ne $proxyBefore) {
        throw "Windows proxy settings changed during an isolated test"
    }
}

Write-Host "Isolated VPN checks passed; Windows proxy settings are unchanged."
