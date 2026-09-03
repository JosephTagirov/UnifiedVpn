# Local release testing

The release checks must not replace or reconfigure the VPN used by the host.

## Isolation rules

- Android VPN tests run only on a serial named `emulator-*` and additionally verify `ro.kernel.qemu=1`.
- Windows native profile tests use `DesktopRoutingMode.LocalSocks`. They do not enable the Windows system proxy or TUN mode.
- Real connectivity is accepted only after HTTPS responses from both Instagram and Wikipedia.
- An `f616f57` client must be tested against an `f616f57` server peer. During a staged server migration, select the profile whose peer has already moved to the `OLC2` record layer.
- Private profiles stay under `.downloads/private-test-profiles` or another ignored local directory.
- GitHub publishing is a separate manual action and must not be part of these tests.

## Commands

Run the active Android tunnel check:

```powershell
.\scripts\Test-IsolatedVpn.ps1 -AndroidTunnel -EmulatorSerial emulator-5554
```

After building the JNI libraries in an isolated Linux environment with the pinned NDK, prove that Gradle consumed those exact unstripped bytes and that its stripped outputs are the exact bytes packaged in the APK:

```powershell
.\scripts\Import-AndroidNativeOutput.ps1 `
    -NativeOutput "$env:USERPROFILE\SharedFolder\uvpn-native-out-a0180d5" `
    -BuildType debug

.\scripts\Verify-AndroidNativeProvenance.ps1 `
    -NativeOutput "$env:USERPROFILE\SharedFolder\uvpn-native-out-a0180d5" `
    -BuildType debug
```

The native output must come from Android NDK `28.2.13676358`. The verifier checks both tun libraries for `armeabi-v7a`, `arm64-v8a`, and `x86_64`; a cached Gradle task alone is not accepted as provenance.

For the Android switching regression, keep the same APK installed and test this exact sequence through the emulator UI:

```text
private olcRTC profile -> real AWG -> real VLESS -> private olcRTC profile
```

At every step, require UI state `Connected`, an unchanged application PID, successful HTTPS responses from Instagram and Wikipedia, and an empty Android crash buffer. For olcRTC and VLESS, also verify that a managed `tun-bridge-<port>.json` sing-box process exists while connected and disappears after stop. VLESS XHTTP has both an Xray upstream process and the bridge process. AWG must use its WireGuard core directly without the bridge.

Run private Windows VLESS and AWG checks through local SOCKS only:

```powershell
$env:UNIFIEDVPN_PRIVATE_VLESS_PROFILE = ".downloads\private-test-profiles\vless.txt"
$env:UNIFIEDVPN_PRIVATE_AWG_PROFILE = ".downloads\private-test-profiles\awg.conf"
$env:OLCRTC_REPO = ".downloads\olcrtc-f616-source"
.\scripts\Test-IsolatedVpn.ps1 -WindowsProfiles
```

Run the Windows olcRTC manager, authenticated SOCKS5, Instagram, Wikipedia, and stop checks against a private profile store:

```powershell
$env:UNIFIEDVPN_PRIVATE_OLCRTC_LOCATIONS = "$env:APPDATA\Olcbox\locations_v4.json"
$env:OLCRTC_REPO = ".downloads\olcrtc-f616-source"
.\scripts\Test-IsolatedVpn.ps1 -WindowsOlcRtc -OlcRtcProfile "<migrated-olcrtc-profile>"
```

The script hashes the relevant Windows proxy settings before and after the run and fails if they change. It never prints profile contents or proxy values, and it removes the isolated olcRTC profile copy after the test. Network integration tasks always run uncached; the olcRTC check makes up to six bounded connection attempts and accepts only application-level Connected followed by authenticated SOCKS5 HTTPS responses from both Instagram and Wikipedia.

Before local packaging, also run the uncached shared tests and Android lint:

```powershell
$env:OLCRTC_REPO = ".downloads\olcrtc-f616-source"
.\gradlew.bat :sharedUI:jvmTest :androidApp:lintDebug `
    --rerun-tasks --no-configuration-cache
```

Build the checked Windows installer and portable archive from the same verified app image:

```powershell
$env:OLCRTC_REPO = ".downloads\olcrtc-f616-source"
.\gradlew.bat :desktopApp:packageReleaseExe `
    :desktopApp:packageReleasePortableZip `
    --no-daemon --no-configuration-cache
```

Use `--no-daemon` for the Windows installer task so `jpackage` and WiX do not inherit a previously started Gradle process that cannot access Windows Installer ICE validation. The task verifies the embedded version, bundled JVM, launcher, and native assets before producing the EXE.

Passing automated checks does not authorize a GitHub release. Keep installer, portable ZIP, and APK local until both Windows and Android builds have been checked manually by the owner.
