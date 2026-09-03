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

Supported release targets are Android, Windows (x86-64), and Linux (x86-64/arm64).
Linux packages are built as portable AppImage files.

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

Application identity uses both `olcbox.version` and the monotonic
`olcbox.build` from `gradle.properties`. A higher semantic version is always an
update. When semantic versions are equal, an asset is offered only when its
`build.<number>` is greater than the installed build. Release APK, EXE, and ZIP
asset names must therefore contain that build token.

Checks for updates of the original [olcbox](https://github.com/alananisimov/olcbox)
and [Amnezia VPN](https://github.com/amnezia-vpn/amnezia-client) are not run by
the application. They can be monitored separately by the owner's private
server-side notifier.

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
| Linux x86-64/arm64 | local SOCKS, optional TUN | bundled `olcrtc`, AWG `sing-box`, Xray, and `hev-socks5-tunnel` runtimes |

## Requirements

| Area | Requirement |
| --- | --- |
| JVM | JDK 17+ |
| Android | Android Studio, Android SDK, Android NDK |
| Android olcRTC | Go, `gomobile`, and local `olcrtc` fork |
| Android VLESS | Packaged `sing-box` and Xray executables as `jniLibs/<abi>/libsing-box.so` and `libxray.so` |
| Windows native assets | Go and local `olcrtc` fork |
| Windows TUN | UAC elevation when TUN starts and bundled Wintun runtime |
| Linux native assets | JDK 17+, Go, C toolchain, `make`, FUSE/AppImage tools, AWG `sing-box`, and Xray |

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
| Android local test APK | `.\gradlew.bat :androidApp:packageDebugUpdateApk` |
| Android signed GitHub update APK | `.\gradlew.bat :androidApp:packageReleaseUpdateApk` |
| Install Android debug APK | `.\gradlew.bat :androidApp:installDebug` |
| Run Windows desktop app | `.\gradlew.bat :desktopApp:run` |
| Build Windows native assets | `.\gradlew.bat :desktopApp:buildDesktopNativeAssets` |
| Windows EXE installer | `.\gradlew.bat :desktopApp:packageReleaseExe --no-daemon` |
| Windows portable ZIP | `.\gradlew.bat :desktopApp:packageReleasePortableZip` |
| Windows GitHub update bundle | `.\gradlew.bat :desktopApp:packageReleaseUpdateBundle` |
| Linux AppImage (run on Linux) | `./gradlew :desktopApp:packageReleaseLinuxAppImage --no-daemon` |
| Reproducible Linux build and smoke-test | `sh ./scripts/Build-LinuxRelease.sh` |
| Main checks | `.\gradlew.bat :sharedUI:jvmTest :desktopApp:compileKotlin :androidApp:assembleDebug` |
| JVM tests | `.\gradlew.bat :sharedUI:jvmTest` |

## Build Outputs

| Target | Command | Output |
| --- | --- | --- |
| Android debug | `.\gradlew.bat :androidApp:assembleDebug` | `androidApp\build\outputs\apk\debug\androidApp-debug.apk` |
| Android release | `.\gradlew.bat :androidApp:assembleRelease` | `androidApp\build\outputs\apk\release\androidApp-release.apk` |
| Android local test APK | `.\gradlew.bat :androidApp:packageDebugUpdateApk` | `androidApp\build\outputs\update\UnifiedVPN-<version>-build.<build>-android-universal-debug.apk` |
| Android signed update APK | `.\gradlew.bat :androidApp:packageReleaseUpdateApk` | `androidApp\build\outputs\update\UnifiedVPN-<version>-build.<build>-android-universal.apk` |
| Windows EXE | `.\gradlew.bat :desktopApp:packageReleaseExe --no-daemon` | `desktopApp\build\compose\binaries\main-release\exe\UnifiedVPN-<version>.exe` |
| Windows MSI | `.\gradlew.bat :desktopApp:packageReleaseMsi --no-daemon` | `desktopApp\build\compose\binaries\main-release\msi\UnifiedVPN-<version>.msi` |
| Windows portable ZIP | `.\gradlew.bat :desktopApp:packageReleasePortableZip` | `desktopApp\build\compose\binaries\main-release\portable\UnifiedVPN-<version>-build.<build>-windows-amd64-portable.zip` |
| Windows update bundle | `.\gradlew.bat :desktopApp:packageReleaseUpdateBundle` | `desktopApp\build\compose\binaries\main-release\update\UnifiedVPN-<version>-build.<build>-windows-amd64-*` |
| Linux AppImage | `./gradlew :desktopApp:packageReleaseLinuxAppImage --no-daemon` | `desktopApp/build/compose/binaries/main-release/appimage/UnifiedVPN-<version>-build.<build>-linux-<arch>.AppImage` |

## Native Assets

| Asset | Path |
| --- | --- |
| Windows `olcrtc` | `desktopApp/build/generated/desktopNativeResources/native/olcrtc-windows-amd64.exe` |
| Windows `tun2socks` | `desktopApp/build/generated/desktopNativeResources/native/tun2socks-windows-amd64.exe` |
| Windows Wintun runtime | `desktopApp/build/generated/desktopNativeResources/native/wintun.dll` |
| Windows AWG `sing-box` | `desktopApp/build/generated/desktopNativeResources/native/sing-box-awg-windows-amd64.exe` |
| Windows Xray | `desktopApp/build/generated/desktopNativeResources/native/xray-windows-amd64.exe` |
| Linux `olcrtc` | `desktopApp/build/generated/desktopNativeResources/native/olcrtc-linux-<arch>` |
| Linux TUN bridge | `desktopApp/build/generated/desktopNativeResources/native/hev-socks5-tunnel-linux-<arch>` |
| Linux AWG `sing-box` | `desktopApp/build/generated/desktopNativeResources/native/sing-box-awg-linux-<arch>` |
| Linux Xray | `desktopApp/build/generated/desktopNativeResources/native/xray-linux-<arch>` |

## Notes

- Android native ABIs: `armeabi-v7a`, `arm64-v8a`, `x86_64`.
- Android release builds require `keystore.properties` in the repository root.
- Windows installers must be built on Windows with `jpackage` and WiX available.
- Linux AppImage must be built and smoke-tested on Linux; cross-packaging from Windows is not supported.
- `scripts/Build-LinuxRelease.sh` does not install packages, use `sudo`, restart
  services, or publish files. It checks out only the commits pinned in
  `gradle.properties` under `.downloads` and requires an explicit
  `APPIMAGETOOL` executable when `appimagetool` is not on `PATH`.

`keystore.properties`:

```properties
storeFile=/absolute/path/to/release.keystore
storePassword=...
keyAlias=...
keyPassword=...
```

---

# Unified VPN на русском

VPN-клиент в первую очередь для Android, основанный на оригинальном приложении
[olcbox Алана Анисимова](https://github.com/alananisimov/olcbox). Приложение
сохраняет существующий движок `olcrtc` и добавляет единый слой профилей для
`olcrtc`, VLESS и Amnezia.

<p align="center">
  <img src="docs/screenshots/unified-vpn-home.png" alt="Главный экран Unified VPN" width="260">
  <img src="docs/screenshots/unified-vpn-settings.png" alt="Настройки приложения Unified VPN" width="260">
  <img src="docs/screenshots/unified-vpn-sharing.png" alt="Экран безопасного обмена Unified VPN" width="260">
</p>

## Состояние

Альфа-версия. Не предназначена для использования в рабочей среде.

Поддерживаемые платформы: Android, Windows x86-64 и Linux x86-64/arm64.
Linux поставляется как переносимый файл AppImage.

История выпусков ведётся в [CHANGELOG.md](CHANGELOG.md).

## Юридическое уведомление

Это программное обеспечение предназначено только для разработки, тестирования
и исследований.

Автор не предоставляет гарантий относительно:

- доступности сетевого подключения;
- совместимости с конкретными сервисами;
- соответствия внешним ограничениям.

Пользователи самостоятельно отвечают за использование программы и обязаны
соблюдать применимое законодательство.

## Ограничения использования

Программа не предназначена для обхода ограничений доступа или нарушения
применимого законодательства. Автор не поддерживает и не поощряет такое
использование.

## Возможности

- Один Android `VpnService` с выбором типа профиля.
- Импорт `olcrtc://`, VLESS `vless://`, VLESS-подписок Base64, AmneziaWG
  `.conf`, `awg://` и Amnezia `vpn://`.
- VLESS работает через встроенный `sing-box`; профили XHTTP/SplitHTTP
  обрабатываются встроенным Xray. Подробности: `docs/ENGINES.md`.
- Собственный Amnezia WireGuard работает через встроенный `sing-box`;
  обфускация AmneziaWG требует AWG-совместимый core.
- Запуск и остановка туннеля одним нажатием с отображением текущего состояния.
- Сохранённые профили и выбор активного подключения.
- Сервисы: Jazz, Telemost, WB Stream и Jitsi.
- Транспорты: DataChannel, VP8 и SEI.
- Настройка VP8: FPS и размер пакета.
- Проверка доступности отдельных профилей.
- Импорт конфигурации из буфера обмена или файла и экспорт в буфер обмена.
- Просмотр и экспорт диагностического журнала.
- Режимы Android TUN/прокси, учётные данные SOCKS5 и раздельные настройки split
  tunneling для olcRTC и VLESS/AmneziaWG.
- Перенос подключения между сетями, переподключение и восстановление watchdog.
- Защищённые паролем пакеты для друзей на Android и Windows: общие профили
  olcRTC, явно выбранная VLESS-ссылка и создание отдельного peer AmneziaWG
  одним действием.

## Обновления

Unified VPN проверяет стабильные выпуски приложения здесь:
https://github.com/JosephTagirov/UnifiedVpn/releases.

Идентификатор приложения включает `olcbox.version` и монотонный `olcbox.build`
из `gradle.properties`. Более высокая семантическая версия всегда считается
обновлением. При одинаковой версии файл предлагается только тогда, когда его
`build.<number>` больше установленного build. Поэтому имена релизных APK, EXE и
ZIP обязаны содержать этот build-токен.

Само приложение не проверяет обновления оригинальных
[olcbox](https://github.com/alananisimov/olcbox) и
[Amnezia VPN](https://github.com/amnezia-vpn/amnezia-client). Владелец может
отслеживать их отдельным приватным уведомителем на своём сервере.

## Безопасные пакеты для друзей

Создатель пакета включает только профили olcRTC, введённый специально для друга
VLESS URI и проверенные данные для настройки по SSH. Существующие профили VLESS
и AmneziaWG намеренно исключаются, поэтому уже используемый закрытый ключ AWG
никогда не передаётся повторно.

Пакеты шифруются с помощью AES-256-GCM. Передавайте зашифрованный пакет и пароль
по разным закрытым каналам. Не публикуйте ни один из них. На устройстве
получателя создаются отдельный AWG peer и закрытый ключ клиента; пароль SSH
после настройки не хранится.

Чтобы создать одну объединённую ссылку, откройте `Настройки -> Подписки и общий
доступ -> Пакет для друга`, введите VLESS URI, данные SSH-сервера и пароль
пакета. Unified VPN создаст одну зашифрованную строку
`unifiedvpn-friend-v1:`. Получатель копирует строку целиком, выбирает
`+ -> Вставить ссылку, URI или пакет для друга` и вводит пароль пакета.
Приложение импортирует olcRTC и VLESS, создаёт отдельный AWG peer и добавляет все
три профиля.

## Поддерживаемые платформы

| Платформа | Режимы | Среда выполнения |
| --- | --- | --- |
| Android | TUN, прокси | `VpnService`, `hev-socks5-tunnel`, локальные среды SOCKS5 `olcrtc`, `sing-box` и Xray |
| Windows x86-64 | системный прокси, локальный SOCKS, необязательный TUN | встроенные `olcrtc`, `sing-box`, Xray, `tun2socks` и Wintun |
| Linux x86-64/arm64 | локальный SOCKS, необязательный TUN | встроенные `olcrtc`, AWG `sing-box`, Xray и `hev-socks5-tunnel` |

## Требования

| Область | Требование |
| --- | --- |
| JVM | JDK 17+ |
| Android | Android Studio, Android SDK, Android NDK |
| olcRTC для Android | Go, `gomobile` и локальная копия olcrtc |
| VLESS для Android | Встроенные `sing-box` и Xray в `jniLibs/<abi>/libsing-box.so` и `libxray.so` |
| Нативные файлы Windows | Go и локальная копия olcrtc |
| TUN для Windows | Запрос UAC при запуске TUN и встроенная среда Wintun |
| Нативные файлы Linux | JDK 17+, Go, C toolchain, `make`, FUSE/AppImage tools, AWG `sing-box` и Xray |

Путь к локальному `olcrtc` по умолчанию:

```text
../olcrtc
```

Его можно переопределить через `OLCRTC_REPO`:

```powershell
$env:OLCRTC_REPO="C:\path\to\olcrtc"
.\gradlew.bat :desktopApp:run
```

Рабочая копия должна быть чистой и совпадать с полным `olcbox.olcrtcSha`,
закреплённым в `gradle.properties`. Gradle отклоняет другой commit или каталог
без истории Git.

## Команды

| Задача | Команда |
| --- | --- |
| Debug APK для Android | `.\gradlew.bat :androidApp:assembleDebug` |
| Локальный тестовый APK Android | `.\gradlew.bat :androidApp:packageDebugUpdateApk` |
| Подписанный APK обновления Android для GitHub | `.\gradlew.bat :androidApp:packageReleaseUpdateApk` |
| Установить debug APK Android | `.\gradlew.bat :androidApp:installDebug` |
| Запустить Windows-приложение | `.\gradlew.bat :desktopApp:run` |
| Собрать нативные файлы Windows | `.\gradlew.bat :desktopApp:buildDesktopNativeAssets` |
| EXE-установщик Windows | `.\gradlew.bat :desktopApp:packageReleaseExe --no-daemon` |
| Portable ZIP Windows | `.\gradlew.bat :desktopApp:packageReleasePortableZip` |
| Набор файлов обновления Windows для GitHub | `.\gradlew.bat :desktopApp:packageReleaseUpdateBundle` |
| AppImage для Linux (запускать в Linux) | `./gradlew :desktopApp:packageReleaseLinuxAppImage --no-daemon` |
| Воспроизводимая Linux-сборка и smoke-test | `sh ./scripts/Build-LinuxRelease.sh` |
| Основные проверки | `.\gradlew.bat :sharedUI:jvmTest :desktopApp:compileKotlin :androidApp:assembleDebug` |
| JVM-тесты | `.\gradlew.bat :sharedUI:jvmTest` |

## Результаты сборки

| Цель | Команда | Результат |
| --- | --- | --- |
| Android debug | `.\gradlew.bat :androidApp:assembleDebug` | `androidApp\build\outputs\apk\debug\androidApp-debug.apk` |
| Android release | `.\gradlew.bat :androidApp:assembleRelease` | `androidApp\build\outputs\apk\release\androidApp-release.apk` |
| Локальный тестовый APK Android | `.\gradlew.bat :androidApp:packageDebugUpdateApk` | `androidApp\build\outputs\update\UnifiedVPN-<version>-build.<build>-android-universal-debug.apk` |
| Подписанный APK обновления Android | `.\gradlew.bat :androidApp:packageReleaseUpdateApk` | `androidApp\build\outputs\update\UnifiedVPN-<version>-build.<build>-android-universal.apk` |
| Windows EXE | `.\gradlew.bat :desktopApp:packageReleaseExe --no-daemon` | `desktopApp\build\compose\binaries\main-release\exe\UnifiedVPN-<version>.exe` |
| Windows MSI | `.\gradlew.bat :desktopApp:packageReleaseMsi --no-daemon` | `desktopApp\build\compose\binaries\main-release\msi\UnifiedVPN-<version>.msi` |
| Portable ZIP Windows | `.\gradlew.bat :desktopApp:packageReleasePortableZip` | `desktopApp\build\compose\binaries\main-release\portable\UnifiedVPN-<version>-build.<build>-windows-amd64-portable.zip` |
| Набор обновления Windows | `.\gradlew.bat :desktopApp:packageReleaseUpdateBundle` | `desktopApp\build\compose\binaries\main-release\update\UnifiedVPN-<version>-build.<build>-windows-amd64-*` |
| AppImage Linux | `./gradlew :desktopApp:packageReleaseLinuxAppImage --no-daemon` | `desktopApp/build/compose/binaries/main-release/appimage/UnifiedVPN-<version>-build.<build>-linux-<arch>.AppImage` |

## Нативные файлы

| Файл | Путь |
| --- | --- |
| Windows `olcrtc` | `desktopApp/build/generated/desktopNativeResources/native/olcrtc-windows-amd64.exe` |
| Windows `tun2socks` | `desktopApp/build/generated/desktopNativeResources/native/tun2socks-windows-amd64.exe` |
| Среда Wintun для Windows | `desktopApp/build/generated/desktopNativeResources/native/wintun.dll` |
| AWG `sing-box` для Windows | `desktopApp/build/generated/desktopNativeResources/native/sing-box-awg-windows-amd64.exe` |
| Xray для Windows | `desktopApp/build/generated/desktopNativeResources/native/xray-windows-amd64.exe` |
| `olcrtc` для Linux | `desktopApp/build/generated/desktopNativeResources/native/olcrtc-linux-<arch>` |
| TUN-мост для Linux | `desktopApp/build/generated/desktopNativeResources/native/hev-socks5-tunnel-linux-<arch>` |
| AWG `sing-box` для Linux | `desktopApp/build/generated/desktopNativeResources/native/sing-box-awg-linux-<arch>` |
| Xray для Linux | `desktopApp/build/generated/desktopNativeResources/native/xray-linux-<arch>` |

## Примечания

- Нативные ABI Android: `armeabi-v7a`, `arm64-v8a`, `x86_64`.
- Для release-сборки Android требуется `keystore.properties` в корне
  репозитория.
- Установщики Windows необходимо собирать в Windows при наличии `jpackage` и
  WiX.
- AppImage необходимо собирать и проверять в Linux; кросс-упаковка из Windows
  не поддерживается.
- `scripts/Build-LinuxRelease.sh` не устанавливает пакеты, не использует `sudo`,
  не перезапускает сервисы и ничего не публикует. Скрипт получает в `.downloads`
  только commit'ы, закреплённые в `gradle.properties`, и требует явный исполняемый
  `APPIMAGETOOL`, если `appimagetool` отсутствует в `PATH`.

`keystore.properties`:

```properties
storeFile=/absolute/path/to/release.keystore
storePassword=...
keyAlias=...
keyPassword=...
```
