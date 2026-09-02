# Unified VPN

Android-first VPN client based on the original olcbox application by Alan Anisimov:
https://github.com/alananisimov/olcbox. It keeps the existing `olcrtc`
engine and adds a unified profile layer for `olcrtc`, VLESS, and Amnezia
profiles.

<p align="center">
  <img src="docs/screenshots/unified-vpn-home.png" alt="Unified VPN home screen" width="260">
  <img src="docs/screenshots/unified-vpn-settings.png" alt="Unified VPN application settings" width="260">
  <img src="docs/screenshots/unified-vpn-sharing.png" alt="Unified VPN secure sharing screen" width="260">
</p>

## Status
Alpha version.
Not intended for production use.

Published and supported release targets are Android and Windows (x86-64) only.
iOS, macOS, and Linux packages are not currently supported or published.

Release history is maintained in [CHANGELOG.md](CHANGELOG.md).

## Legal Notice
This software is intended for development, testing and research purposes only.

The author does not provide any guarantees regarding:
- availability of network access
- compatibility with specific services
- compliance with any external restrictions

Users are solely responsible for how they use this software and must comply with applicable laws.

## Usage Restrictions
This software is not intended to be used for bypassing access restrictions or violating applicable laws.
The author does not support or encourage such use.

## Features
- One Android `VpnService` with selectable profile types.
- Imports `olcrtc://`, VLESS `vless://`, VLESS base64 subscriptions, AmneziaWG `.conf`, `awg://`, and Amnezia `vpn://`.
- VLESS runtime through packaged `sing-box`, with XHTTP/SplitHTTP profiles handled by the packaged Xray core; see `docs/ENGINES.md`.
- Self-hosted Amnezia WireGuard runtime through the packaged `sing-box` path; AmneziaWG obfuscation requires an AWG-capable core.
- One-tap tunnel start/stop with live relay status.
- Saved connection profiles with active location selection.
- Providers: Jazz, Telemost, WB Stream, Jitsi.
- Transports: DataChannel, VP8, SEI.
- VP8 tuning: FPS and batch size.
- Connectivity checks for individual locations.
- Config import from clipboard/file and export to clipboard.
- Diagnostics log view with log export.
- Android TUN/proxy modes, SOCKS5 credentials, separate split tunneling for olcRTC and VLESS/AmneziaWG.
- Network migration, reconnect handling, and watchdog recovery.
- Password-protected friend packages for Android and Windows: shared olcRTC profiles, an explicitly selected VLESS link, and one-action creation of a separate AmneziaWG peer.

## Updates

Unified VPN checks stable application releases at:
https://github.com/JosephTagirov/UnifiedVpn/releases.

Updates of the original [olcbox](https://github.com/alananisimov/olcbox) and
[Amnezia VPN](https://github.com/amnezia-vpn/amnezia-client) are shown as
upstream notices only. Unified VPN never installs upstream application assets
over itself.

## Secure Friend Packages

The friend-package creator includes only olcRTC profiles, the VLESS URI entered
for that friend, and verified SSH provisioning data. Existing VLESS and
AmneziaWG profiles are deliberately excluded, so an existing AWG private key is
never reused.

Packages are encrypted with AES-256-GCM. Send the encrypted package and its
password through different private channels. Do not publish either item. The
receiver creates a separate AWG peer and client private key; the SSH password is
not stored after provisioning.

To create one combined link, open `Settings -> Subscriptions & Sharing ->
Package for a friend`, enter the VLESS URI, SSH server details, and a package
password. Unified VPN produces one encrypted `unifiedvpn-friend-v1:` string.
The receiver copies that complete string and selects `+ -> Paste link, URI, or
friend package`, then enters the package password. The app imports olcRTC and
VLESS, provisions a separate AWG peer, and adds all three profiles.

## Platform Matrix

| Platform | Modes | Runtime |
| --- | --- | --- |
| Android | TUN, proxy | `VpnService`, `hev-socks5-tunnel`, local `olcrtc`, `sing-box`, and Xray SOCKS5 runtimes |
| Windows x86-64 | system proxy, local SOCKS, optional TUN | bundled `olcrtc`, `sing-box`, Xray, `tun2socks`, and Wintun runtimes |

## Requirements

| Area | Requirement |
| --- | --- |
| JVM | JDK 17+ |
| Android | Android Studio, Android SDK, Android NDK |
| Android olcRTC | Go, `gomobile`, and local `olcrtc` fork |
| Android VLESS | Packaged `sing-box` and Xray executables as `jniLibs/<abi>/libsing-box.so` and `libxray.so` |
| Windows native assets | Go and local `olcrtc` fork |
| Windows TUN | UAC elevation when TUN starts and bundled Wintun runtime |

Default local `olcrtc` path:

```text
../olcrtc
```

Override it with `OLCRTC_REPO`:

```powershell
$env:OLCRTC_REPO="C:\path\to\olcrtc"
.\gradlew.bat :desktopApp:run
```

The checkout must be clean and match the full `olcbox.olcrtcSha` pinned in
`gradle.properties`; Gradle rejects a different or unversioned source tree.

## Commands

| Task | Command |
| --- | --- |
| Android debug APK | `.\gradlew.bat :androidApp:assembleDebug` |
| Install Android debug APK | `.\gradlew.bat :androidApp:installDebug` |
| Run Windows desktop app | `.\gradlew.bat :desktopApp:run` |
| Build Windows native assets | `.\gradlew.bat :desktopApp:buildDesktopNativeAssets` |
| Windows EXE installer | `.\gradlew.bat :desktopApp:packageReleaseExe --no-daemon` |
| Windows portable ZIP | `.\gradlew.bat :desktopApp:packageReleasePortableZip` |
| Main checks | `.\gradlew.bat :sharedUI:jvmTest :desktopApp:compileKotlin :androidApp:assembleDebug` |
| JVM tests | `.\gradlew.bat :sharedUI:jvmTest` |

## Build Outputs

| Target | Command | Output |
| --- | --- | --- |
| Android debug | `.\gradlew.bat :androidApp:assembleDebug` | `androidApp\build\outputs\apk\debug\androidApp-debug.apk` |
| Android release | `.\gradlew.bat :androidApp:assembleRelease` | `androidApp\build\outputs\apk\release\androidApp-release.apk` |
| Windows EXE | `.\gradlew.bat :desktopApp:packageReleaseExe --no-daemon` | `desktopApp\build\compose\binaries\main-release\exe\UnifiedVPN-<version>.exe` |
| Windows MSI | `.\gradlew.bat :desktopApp:packageReleaseMsi --no-daemon` | `desktopApp\build\compose\binaries\main-release\msi\UnifiedVPN-<version>.msi` |
| Windows portable ZIP | `.\gradlew.bat :desktopApp:packageReleasePortableZip` | `desktopApp\build\compose\binaries\main-release\portable\UnifiedVPN-<version>-windows-amd64-portable.zip` |

## Native Assets

| Asset | Path |
| --- | --- |
| Windows `olcrtc` | `desktopApp/build/generated/desktopNativeResources/native/olcrtc-windows-amd64.exe` |
| Windows `tun2socks` | `desktopApp/build/generated/desktopNativeResources/native/tun2socks-windows-amd64.exe` |
| Windows Wintun runtime | `desktopApp/build/generated/desktopNativeResources/native/wintun.dll` |
| Windows AWG `sing-box` | `desktopApp/build/generated/desktopNativeResources/native/sing-box-awg-windows-amd64.exe` |
| Windows Xray | `desktopApp/build/generated/desktopNativeResources/native/xray-windows-amd64.exe` |

## Notes

- Android native ABIs: `armeabi-v7a`, `arm64-v8a`, `x86_64`.
- Android release builds require `keystore.properties` in the repository root.
- Windows installers must be built on Windows with `jpackage` and WiX available.

`keystore.properties`:

```properties
storeFile=/absolute/path/to/release.keystore
storePassword=...
keyAlias=...
keyPassword=...
```
