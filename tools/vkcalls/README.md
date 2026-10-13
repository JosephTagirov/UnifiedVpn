# VK Calls Native Engine

## English

The experimental VK Calls provider uses the unmodified olcRTC fork at
`403edd409011dd371796260196ecd17630df0fdd` from
[ghostlane-project/olcrtc](https://github.com/ghostlane-project/olcrtc).
It is packaged separately from the existing `d7a00da5242f` Jitsi core and Android AAR.

- Provider: `vkcalls`; transport: `vp8channel`.
- DTLS profile: `chrome-linux-138-compat-v1`; default VP8: 60 FPS, batch 64.
- Platforms in this build: Windows amd64 and Android arm64-v8a, armeabi-v7a, x86_64.
- Android executes `libvkcalls.so` from the installed native library directory. It is a PIE executable, not a second JNI/gomobile runtime.
- The client binds authenticated SOCKS to loopback. Windows supports the existing olcRTC proxy modes; Windows VK TUN is not implemented. Android uses the existing olcRTC split-tunneling group and TUN/SOCKS bridge.
- Native readiness follows the authenticated olcRTC handshake; the application additionally checks SOCKS credentials. A port accepting TCP alone is insufficient.
- Existing olcRTC import/export supports VK through its provider field. Do not import a VK profile into an older Unified VPN build: it does not recognize this provider.
- Inactive-profile latency probing is not implemented on Android VK; it must not call the old Jitsi-only mobile binding. Sustained speed, multiple simultaneous clients and physical Android 13 remain to be verified.

Build from a clean standalone clone pinned to the revision above:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File tools/vkcalls/build.ps1 -SourcePath <pinned-clone>
```

The script requires Go 1.26.6 and an installed Android NDK. It tests four focused
native packages, uses upstream Mage for Windows, uses NDK/CGO PIE for Android,
checks native VCS provenance, and emits a SHA-256 manifest plus upstream LICENSE
and NOTICE. It neither changes the source checkout nor replaces existing Jitsi cores.
Gradle takes `-Pvkcalls.artifactDir=<directory>`; the default is
`.downloads/vkcalls/artifacts`. These generated binaries and all private profiles
remain outside Git. Public distribution includes a corresponding source archive
and the build-specific third-party notice bundle. Build 2026101301 supplies these
as separate release assets; physical-device acceptance remains outstanding.

## Русский

Экспериментальный провайдер VK Calls использует неизменённый форк olcRTC
`403edd409011dd371796260196ecd17630df0fdd` из
[ghostlane-project/olcrtc](https://github.com/ghostlane-project/olcrtc).
Он упакован отдельно от прежнего ядра Jitsi `d7a00da5242f` и Android AAR.

- Провайдер: `vkcalls`; транспорт: `vp8channel`.
- DTLS: `chrome-linux-138-compat-v1`; стандартный VP8: 60 FPS, batch 64.
- Платформы этой сборки: Windows amd64 и Android arm64-v8a, armeabi-v7a, x86_64.
- Android запускает PIE-файл `libvkcalls.so` из каталога библиотек установленного приложения. Это не второе JNI/gomobile-ядро.
- SOCKS с авторизацией слушает только loopback. В Windows работают существующие прокси-режимы olcRTC; Windows TUN для VK не реализован. Android использует группу раздельного туннелирования olcRTC и существующий TUN/SOCKS-мост.
- Готовность ядра наступает после аутентифицированного рукопожатия olcRTC; приложение дополнительно проверяет SOCKS-авторизацию. Одного открытого TCP-порта недостаточно.
- Обычные импорт и экспорт olcRTC сохраняют поле провайдера VK. Старые сборки Unified VPN этого провайдера не распознают: не импортируйте туда VK-профиль.
- Проверка пинга неактивного VK-профиля на Android не реализована и не должна обращаться к старым mobile bindings. Скорость, несколько одновременных клиентов и физический Android 13 ещё требуют проверки.

Сборка из чистого отдельного клона с указанным commit:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File tools/vkcalls/build.ps1 -SourcePath <pinned-clone>
```

Нужны Go 1.26.6 и установленный Android NDK. Скрипт запускает четыре набора
тестов, использует upstream Mage для Windows и NDK/CGO PIE для Android,
проверяет VCS-метаданные, создаёт SHA-256 manifest и включает исходные LICENSE
и NOTICE. Исходный checkout и прежние ядра Jitsi не заменяются. Для Gradle
можно задать `-Pvkcalls.artifactDir=<directory>`; стандартный каталог:
`.downloads/vkcalls/artifacts`. Ядра и приватные профили исключены из Git.
К публичному выпуску прилагаются соответствующие исходники и отдельный архив
лицензий зависимостей. В сборке 2026101301 это файлы релиза; проверка на
физическом устройстве ещё требуется.
