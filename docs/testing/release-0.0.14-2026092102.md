# 0.0.14 Preview Publication Checks

Historical preparation record: this build was superseded before publication
by [2026092103](release-0.0.14-2026092103.md), following the owner's upstream
update request. The files below remain local rollback artifacts.

Date: 2026-09-21. Both Android and Windows use build `2026092102`.
The owner authorized GitHub publication. This is a prerelease; stable stays
on `0.0.12`. No working host VPN, private profile or server service was changed.

## Verification

| Check | Result |
| --- | --- |
| JVM tests | 280, zero failures/errors; live private gates unset |
| Android host tests | 165, zero failures/errors |
| OpenFlux server tools | 112 offline tests passed |
| Private notifier | 31 offline tests passed |
| Xray / AWG config-only checks | 12 Xray configurations and AWG TUN DNS accepted |
| Windows helper | 28 input, 6 launch-plan and 6 route-plan checks passed |
| Windows package | JVM startup, 8 native assets, installer build/upgrade policy verified |
| Windows portable | All 251 files match the packaged app image; 7 bundled native binaries/libraries match generated files |
| Android build | Debug and signed/minified release, release lint and native/binding verification passed |
| Android APK | `app.unifiedvpn.local`, version `0.0.14`, versionCode `2026092102`, minimum API 23, target API 35 |
| Android signature / ABIs | v1 + v2 verified; arm64-v8a, armeabi-v7a, x86_64 |
| Fresh API 35 emulator | Seven synthetic notification, profile, ping-state and release-upgrade scenarios passed |
| Publication contents | Reviewed source allowlist; no private configs, raw logs, bot secrets, unrelated drafts or generated duplicate bind tree |

The final Android test used a new owned AVD with synthetic guest-loopback
profiles. It verified notification Next/Previous, chooser selection, opening
the main app through the icon, process survival, Stop cleanup, inactive OpenFlux
ping without starting a document client, and signed-release upgrade preserving
profiles. The test app was removed and both owned emulator processes exited.
Local receipt: `.downloads/android-notification-20260921-101713/result.json`.

Android debug SHA256 used only for tests:
`0dbaf4ffa383e3817b4b83e4f65745af646da00cd6399a77367425bfcfb8ea43`.
The release APK hash below was unchanged after testing. An initial sandboxed
NDK invocation reported compiler access warnings; the outside-sandbox offline
build completed without those warnings before emulator testing. No verification
was disabled to package the applications.

## Release Assets

| File | Bytes | SHA256 |
| --- | ---: | --- |
| `UnifiedVPN-0.0.14-build.2026092102-android-universal.apk` | 139446283 | `86638d8fd0a556003dfe608745c54db94d2444bffae9d4472627d6ce2c672772` |
| `UnifiedVPN-0.0.14-build.2026092102-windows-amd64-installer.exe` | 173841920 | `1b19457aaf5648b35cd5eee0e5af27f1b3a82b254537bfa148868e190f13c6bc` |
| `UnifiedVPN-0.0.14-build.2026092102-portable-manual.zip` | 171857820 | `51226667c968a3e74233141bf71e2a83489febd8632413e3d1486ccf6ebf06dc` |
| `UnifiedVPN-upstream-notifier-2026092102.zip` | 16961 | `e82b67b4d4ace04e5da9bcdf85d981b783083a8464e3f37f365c655d7fc3815b` |
| `openflux-source.tar.gz` | 11895734 | `632c9bd5cce1494fdb47d7156699fafd3b6eddc91cb01fe4274f951ee0e8a3cd` |

`SHA256SUMS` lists these five payloads. The notifier ZIP contains exactly its
seven public source/docs/test files, no environment or state. OpenFlux source
includes the pinned source, integration overlay and upstream notices. The
Windows installer is not Authenticode-signed. Source and artifacts are reviewed
for this release; this is not a comprehensive security audit.

## Remaining Limits

No real Windows TUN connection, actual UAC interaction, crash cleanup, DNS/IPv6
leak test, sleep or network transition was performed. There is no persistent
kill-switch. Windows olcRTC/OpenFlux TUN remains unavailable. Synthetic Android
tests do not prove connectivity on a physical phone or the user's current
servers. No new Linux package is attached. See
[release notes](../releases/0.0.14.md) and
[Windows TUN details](windows-tun-2026092102.md).

# Проверки Публикации 0.0.14 Preview

Историческая подготовка: до публикации эта сборка была заменена на
[2026092103](release-0.0.14-2026092103.md) по запросу обновления компонентов.
Перечисленные здесь файлы сохранены локально для отката.

21.09.2026. Android и Windows имеют сборку `2026092102`. Владелец разрешил
публикацию на GitHub. Это предварительный выпуск; стабильным остаётся `0.0.12`.
Рабочий VPN ноутбука, приватные профили и серверные службы не менялись.

Пройдены 280 JVM-тестов, 165 Android host-тестов, 112 офлайн-тестов серверных
инструментов и 31 тест бота. Xray принял 12 тестовых конфигураций, AWG принял
тестовую конфигурацию DNS для TUN. Windows-компонент прошёл 28 проверок входных
данных, 6 проверок плана запуска и 6 проверок плана маршрутов. Проверены запуск
JVM, 8 нативных компонентов и политика замены сборок установщиком. Все 251 файл
portable совпали с образом приложения, 7 нативных бинарников/библиотек совпали
со сгенерированными файлами.

Android собран в debug и подписанном minified release, пройдены release lint и
проверки native/binding-состава. APK: `app.unifiedvpn.local`, `0.0.14`,
`2026092102`, минимальный API 23, целевой API 35; подписи v1/v2 проверены,
ABI arm64-v8a / armeabi-v7a / x86_64. Предупреждения доступа к NDK внутри
песочницы устранены повторной офлайн-сборкой вне неё до проверки на эмуляторе;
проверки упаковки не отключались.

На новом изолированном эмуляторе API 35 прошли семь сценариев с вымышленными
профилями: Next/Previous из шторки, выбор в списке, переход по значку в приложение,
сохранение процесса при переключении, остановка native-процесса, пинг неактивного
OpenFlux без нового клиента документа и установка release с сохранением профилей.
Хеши APK не изменились; тестовое приложение удалено, оба собственных процесса
эмулятора завершены. Путь квитанции и SHA debug-файла приведены выше.

Имена, размеры и контрольные суммы пяти файлов находятся в таблице выше и
`SHA256SUMS`. ZIP бота содержит только семь публичных файлов без токена и
состояния. Архив OpenFlux содержит соответствующие исходники, обёртку и
исходные уведомления авторства. Windows EXE не подписан Authenticode.
В публикацию не включены приватные конфиги, сырые логи, секреты бота,
несвязанные черновики и дублирующее сгенерированное дерево bind.
Это проверка состава выпуска, не полный аудит безопасности.

Реальное Windows TUN-подключение, UAC, очистка после сбоя, утечки DNS/IPv6,
сон и смена сети не проверялись. Постоянного kill-switch нет; Windows TUN
olcRTC/OpenFlux недоступен. Синтетические Android-тесты не доказывают работу на
физическом телефоне или текущих серверах пользователя. Нового Linux-пакета нет.
См. [описание выпуска](../releases/0.0.14.md) и
[подробности Windows TUN](windows-tun-2026092102.md).
