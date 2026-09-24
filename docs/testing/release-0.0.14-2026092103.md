# 0.0.14 Preview Publication Checks

Historical local candidate, superseded before publication by build
`2026092201` to add the requested Android notification reconnect action.

Date: 2026-09-21. Android and Windows build `2026092103` replaces the unpublished
`2026092102` after the requested upstream refresh. Publication is authorized
as a prerelease; stable remains `0.0.12`. The working OpenFlux session, host
proxy, DNS, routes and production server services were not changed.

## Selected Cores

- olcRTC `d7a00da5242f72a48b505ffc4b6aa8246376bf25`, retaining the existing Jitsi
  envelope and OLC2 record layer. The newest olcbox core was tested first and
  rejected for incompatibility with an existing `f616` server. This is not a
  claim of compatibility with pre-OLC2 servers or a full olcbox UI merge.
- AWG-capable Throne sing-box `7745e9afd0a1f7a5b6216fd114e1ad4a64b974c3`, with
  backend `b311c8ac53aed5637b0bd961b9c6ae1fc1eef093` from `wip/1.14.0`.
  This is a development branch, not a stable release.
- Xray `26.3.27` and OpenFlux `4f1bdb554c262f3ae9adbfe317a092c6b929ba7d` are
  unchanged. No server is upgraded by installing these clients.

See [upstream selection and reproducible checks](upstream-refresh-2026092103.md).

## Build And Offline Verification

| Check | Result |
| --- | --- |
| Full JVM suite | 296 tests, zero failures/errors; private live gates disabled |
| Android host suite | 172 tests, zero failures/errors |
| OpenFlux server tools | 112 offline tests passed |
| Private notifier | 37 offline tests passed |
| olcRTC selected candidate | 234 top-level tests plus 31 subtests, normal and race builds |
| olcRTC newest candidate | 238 top-level tests plus 31 subtests, normal and race builds |
| Actual Jitsi envelope serializers/receivers | Seven compatibility assertions passed |
| AWG configurations | 17 WG/AWG/VLESS/DNS-bridge checks passed |
| Xray configurations | 12 synthetic transport configurations accepted |
| AWG reproducibility | All four targets built twice with matching SHA256 |
| AWG provenance | Clean full VCS pin, backend module checksum, build tags and targets checked; wrong-pin negative test rejected |
| Android AWG ELF | NDK 28.2, minimum API 23, ET_DYN/PIE, architecture, interpreter and 16KiB LOAD alignment verified |
| Windows helper | 28 input, six launch-plan and six route-plan checks passed |
| Windows packaging | Isolated packaged JVM startup, eight required native assets and installer build-aware replacement policy verified |
| Windows portable | 251 files match the packaged image; all seven native binaries/libraries match staged/generated resources |
| Android packaging | Debug and signed/minified release, lint and native/binding checks passed |
| Android APK identity | `app.unifiedvpn.local`, `0.0.14`, versionCode `2026092103`, minimum API 23, target API 35 |
| Android signatures | v1/v2 verified; signing certificate unchanged from the preceding local build |
| Android native contents | All three ABIs; all 12 olcRTC/AWG debug/release provenance chains verified, including independently reproduced stripping |

The full Gradle run completed 174 tasks. Reproduction needs the pinned clean
source checkouts, local native prerequisites, signing material and cached
dependencies; none of the private inputs are included in the repository.

```powershell
./gradlew.bat '-Pkotlin.compiler.execution.strategy=in-process' `
  '-POLCRTC_REPO=.downloads/upstream-review-20260921/olcrtc-compatible-d7a00da' `
  :sharedUI:jvmTest :sharedUI:testAndroidHostTest :androidApp:assembleDebug `
  :androidApp:packageReleaseUpdateApk :desktopApp:packageReleaseUpdateBundle `
  :desktopApp:packageReleasePortableZip --offline --max-workers=2 `
  --no-parallel --no-daemon --no-configuration-cache --console=plain
```

## Real Traffic And Android UI

Windows live tests used temporary isolated app-data directories, private local
profile files, separate loopback ports and Local SOCKS mode. AWG, VLESS XHTTP
and olcRTC each reached application-level Connected, carried TLS-verified HTTPS
to Instagram and Wikipedia through authenticated SOCKS5, and stopped cleanly.
The SOCKS probe has no direct fallback. The host system-proxy state was identical
before and after each run. No OpenFlux client was started.

The fresh Android API 35 x86_64 emulator passed seven synthetic scenarios:
notification Next/Previous, chooser selection, opening the main app from the
chooser icon, process survival, native Stop cleanup, inactive OpenFlux ping
without a document client, and signed-release upgrade retaining profiles.
The owned emulator was stopped completely after this suite.

Separate real Android AWG checks passed: application-level Connected, guest
VPN, authenticated SOCKS5 and guest TUN each carried TLS-verified HTTPS to
Instagram and Wikipedia (HTTP 200), followed by clean Stop and a surviving app
process. olcRTC reached Connected with a guest VPN, but a diagnostic ADB timeout
interrupted the traffic check; that attempt is not counted as a traffic pass.
All owned emulator processes were confirmed absent after the interrupted run.
No physical phone result is claimed.

Debug APK used only for testing:
`8bec523b71a14677c1bd9da0cde01b93bde1a012b84c181a21e032298bfbca43`.
The signed release APK hash is in the table below. Local synthetic receipts are
under `.downloads/android-notification-20260921-120459/`; private connection
receipts and raw logs are not published.

## Release Assets

| File | Bytes | SHA256 |
| --- | ---: | --- |
| `UnifiedVPN-0.0.14-build.2026092103-android-universal.apk` | 139676124 | `1c6a413fbf3de290d09e881d37058292e97ccb0f64e5f9a6d3391cbf95b636de` |
| `UnifiedVPN-0.0.14-build.2026092103-windows-amd64-installer.exe` | 173968896 | `aa022278e8a46fbdddcfbc33ba81aa55eecd27fe5b2f72638e72b9e4b0e6ff8d` |
| `UnifiedVPN-0.0.14-build.2026092103-portable-manual.zip` | 171982418 | `a611d521b207361781d5323da413400039a2760958283fdde9c8bacc9f976d5e` |
| `UnifiedVPN-upstream-notifier-2026092103.zip` | 18258 | `3d49f03295b37b3952375f4cd16cadbb9b174f3f203221f835918e89805d7131` |
| `openflux-source.tar.gz` | 11895734 | `632c9bd5cce1494fdb47d7156699fafd3b6eddc91cb01fe4274f951ee0e8a3cd` |
| `sing-box-awg-7745e9afd0a1-source.tar.gz` | 2071252 | `b0bf17a36475b8ab4be723816b4588b10369c1614b9ca166ec5145e8c09bdb1a` |
| `amneziawg-b311c8ac53ae-source.zip` | 160817 | `52c349ec555093824c9eb7b553da1e497b7c28cb87ea3ee675f3460a4e27b353` |

`SHA256SUMS` lists exactly these seven payloads. The notifier ZIP contains seven
public source/docs/test files under `upstream-notifier/`, without credentials,
state or caches. OpenFlux and AWG source archives retain corresponding sources,
dependency locks and upstream notices. The Windows installer is not
Authenticode-signed. Source staging and asset upload use explicit allowlists;
private profiles, document links, raw logs, secrets and unrelated work are excluded.
This is a release-content review, not a comprehensive security audit.

## Remaining Limits

Windows TUN is experimental. Actual TUN HTTPS/UDP/DNS/IPv6, UAC interaction,
crash recovery, sleep and network changes were not tested in an isolated
Windows VM. There is no persistent kill-switch or leak-proof DNS claim.
Windows olcRTC/OpenFlux TUN remains unavailable; Android keeps their existing
shared split-tunneling group. The user's active OpenFlux document was not used
for new live tests. Not every private profile/server was retested, and no
pre-OLC2 compatibility is implied. No new Linux package is included.

See [release notes](../releases/0.0.14.md) and
[Windows TUN details](windows-tun-2026092102.md).

# Проверки Публикации 0.0.14 Preview

Исторический локальный кандидат: до публикации заменён сборкой `2026092201`
для добавления запрошенной кнопки переподключения в Android-уведомлении.

21.09.2026. Сборка Android и Windows `2026092103` заменяет неопубликованную
`2026092102` после запроса обновить компоненты. Это разрешённый предварительный
выпуск; стабильным остаётся `0.0.12`. Рабочее подключение OpenFlux, системный
прокси, DNS, маршруты ноутбука и серверные службы не менялись.

## Версии И Проверки

olcRTC обновлён до `d7a00da`, непосредственно предшествующего несовместимому
изменению формата Jitsi. Сначала проверено самое свежее ядро оригинального
olcbox: сервер `f616` не понимает его новый формат сообщений. Поэтому выбран
совместимый вариант с прежним форматом и шифрованием OLC2. Это не полное
копирование интерфейса olcbox и не обещание совместимости с серверами до OLC2.
AWG обновлён до `7745e9a` с реализацией `b311c8ac` из ветки разработки
`wip/1.14.0`, не стабильного релиза. Xray и OpenFlux не менялись. Полные commit
указаны выше и в [отчёте обновления](upstream-refresh-2026092103.md).

Пройдены 296 JVM-тестов, 172 Android host-теста, 112 офлайн-тестов серверных
инструментов OpenFlux и 37 тестов бота. Выбранный olcRTC прошёл 234 Go-теста и
31 подтест, свежий кандидат 238 и 31; оба также с `-race`. Семь проверок
настоящих сериализаторов/декодеров подтвердили несовместимость форматов Jitsi.
Приняты 17 конфигураций AWG/WG/VLESS/DNS-моста и 12 конфигураций Xray.

AWG собран для четырёх целей дважды с одинаковыми хешами. Проверены чистый
commit, checksum backend, теги и платформы; неверный pin намеренно отклонён.
Для Android проверены NDK 28.2, API 23, PIE, архитектуры, interpreter и
совместимость LOAD-сегментов со страницами 16 КБ. Windows helper прошёл
28 проверок входных данных, шесть плана запуска и шесть плана маршрутов.

Упаковка Windows проверила запуск JVM, восемь нативных компонентов и замену
сборок установщиком. Все 251 файл portable совпали с образом приложения,
семь нативных файлов совпали с подготовленными ресурсами. Android прошёл
debug/release-сборку, lint и проверки binding/native. У APK правильные
версия, build, API 23/35, три ABI, подписи v1/v2 и прежний сертификат.
Все 12 цепочек упаковки olcRTC/AWG проверены, включая независимый stripping.
Полная команда Gradle приведена выше; нужны локальные зависимости, закреплённые
исходники и закрытые материалы подписи, которые в репозиторий не включаются.

## Подключения

На Windows olcRTC, AWG и VLESS XHTTP достигли Connected, передали HTTPS
Instagram и Wikipedia через авторизованный SOCKS5 с проверкой TLS и корректно
остановились. Использовались отдельные каталоги данных и порты, без прямого
резервного соединения в тестовом запросе. Настройки системного прокси до и
после совпали. Второй клиент OpenFlux не запускался.

На новом эмуляторе Android API 35 x86_64 прошли семь синтетических сценариев:
Next/Previous из уведомления, выбор в списке, открытие приложения значком,
сохранение процесса, остановка native, пинг неактивного OpenFlux без запуска
документа и установка подписанного release с сохранением профилей. Эмулятор
полностью остановлен после набора. SHA256 тестового debug APK приведён выше.

Настоящий Android AWG прошёл Connected, создание гостевого VPN и HTTPS
Instagram/Wikipedia (HTTP 200) как через авторизованный SOCKS5, так и через TUN.
Stop очистил туннель и дочерние процессы, процесс приложения сохранился.
olcRTC достиг Connected с гостевым VPN, но timeout диагностического ADB
прервал проверку трафика: успешным тестом передачи данных это не считается.
После прерывания подтверждено отсутствие всех собственных процессов эмулятора.
Проверка на физическом телефоне не заявляется.

## Состав И Ограничения

В таблице выше имена, размеры и SHA256 семи файлов; отдельно прикладывается
`SHA256SUMS`. ZIP бота содержит только семь публичных файлов в каталоге
`upstream-notifier/`, без токена, состояния и кешей. Исходные архивы сохраняют
авторство и лицензии OpenFlux/AWG. Windows EXE не подписан Authenticode.
Исходники и файлы публикации отбираются явными списками: приватные профили,
ссылки на документы, сырые логи, секреты и посторонние работы исключены.
Это проверка состава релиза, не полный аудит безопасности.

Windows TUN остаётся экспериментальным: реальные HTTPS/UDP/DNS/IPv6, UAC,
аварийное восстановление, сон и смена сети не проверялись в изолированной
Windows VM. Постоянного kill-switch нет, отсутствие утечек DNS не заявляется.
Windows TUN olcRTC/OpenFlux пока недоступен; Android сохраняет их общую группу
раздельного туннелирования. Рабочий документ OpenFlux пользователя не занимался
новым тестом. Проверены не все личные профили/серверы; поддержки серверов до
OLC2 не обещается. Нового Linux-пакета нет. См. [описание выпуска](../releases/0.0.14.md).
