# OpenFlux 0.0.13 Local Validation

Date: 2026-09-13. App build: `2026091301`. **Development only, not released.**
This report contains no document link, encryption key, server address, or profile.

## Passed

- Kotlin compilation and focused JVM/Android host tests for profile import,
  configuration, log redaction, provenance, and native process lifecycle.
- Native transport, Yandex, configuration, and encrypted peer-session Go tests.
  Tests cover wrong keys/direction, plaintext rejection, stale welcome records,
  cancellation, replay, and shutdown. These are in-memory tests, not proof of
  provider connectivity or a comprehensive security audit.
- All five native targets built: Windows/Linux amd64 and Android arm64-v8a,
  armeabi-v7a, x86_64. Upstream pin:
  `4f1bdb554c262f3ae9adbfe317a092c6b929ba7d`.
- Profile helper: 18 tests. Isolated server/SSH tooling: 84 local tests.
- `:androidApp:assembleDebug` and `:androidApp:verifyDebugOlcRtcBindings` passed,
  including the selected native-library allowlist and private/build-path checks.
- A fresh, offline Android 35 x86_64 emulator installed the APK. Its extracted
  OpenFlux executable ran under the application UID and reported the expected
  pin/protocol. An empty config failed without reporting Ready. The app process
  remained alive after cold launch, with no recorded package crash or transport
  child. First launch reached the system notification permission prompt; the
  main-screen interaction flow was not tested. The owned emulator was shut down.
- An isolated Windows app-image passed package-version validation and the actual
  `UnifiedVPN.exe` JVM/native-assets smoke test. The verification argument returns
  before normal app initialization; this is not a full GUI or connection test.
- A separate Windows `DesktopVpnManager` integration test passed against the
  isolated real server and the new legacy-editor document. It observed
  `OPENFLUX_ENCRYPTION AES-256-GCM` and authenticated `OPENFLUX_READY`, then
  hostname-verified HTTPS 200 responses from Instagram and Telegram through
  the authenticated loopback SOCKS proxy. A repeat Telegram request after a
  25-second liveness window passed. Stop terminated the owned native child,
  closed its SOCKS listener, and removed its transient runtime config.
  The test used private application data and did not change host proxy/routes.
- The bounded download benchmark's 25 offline tests passed, followed by one
  real sequential Windows run for olcRTC, AWG, VLESS, and OpenFlux. All four
  passed authenticated SOCKS setup, HTTPS download, continuous connection/process
  checks, and verified stop before the next engine. The original host application
  and olcRTC process identities/start times remained unchanged.
- Android test tooling: 17 byte-pipe/checksum tests and 74 synthetic orchestration
  tests passed. An exact stdout-reader warning is reported separately; fatal
  output, lost/replaced processes, missing TUN, and recovery still fail the test.

## Download Measurements

One Windows sample per engine on 2026-09-13, through a separate app-managed
authenticated LocalSocks connection. Source: Cloudflare's public HTTPS
[`/__down` endpoint](https://github.com/cloudflare/speedtest). Each request
downloaded 8,388,608 bytes (8 MiB); no redirects or retries were used.

| Engine / Profile | Download, Mbit/s | Payload Time, s |
| --- | ---: | ---: |
| olcRTC / FI_2 | 5.55 | 12.100 |
| AmneziaWG | 68.19 | 0.984 |
| VLESS / XHTTP | 30.46 | 2.203 |
| OpenFlux / Yandex, AES-256-GCM | 19.93 | 3.366 |

Decimal Mbit/s = received payload bytes * 8 / seconds / 1,000,000. Connection,
DNS and TLS setup time is excluded. These are short single-stream application
payload samples, not wire rates, a sustained capacity test, or guaranteed speed.
The AWG sample lasted less than one second. Server/provider/local-network load
can change the result. Upload speed and Android throughput were not measured.

## Local Outputs

These paths are relative to the repository root and are not public downloads:

- Android debug APK: `androidApp/build/outputs/apk/debug/androidApp-debug.apk`.
  SHA-256: `cbe9cdf852821429eb321f1611959228d6f12a285207b8513da1497551f34c68`.
  This is a debug build, not a signed production update.
- Windows app-image:
  `.downloads/openflux/windows-build/compose/binaries/main-release/app/UnifiedVPN`.
  Launch file: `UnifiedVPN.exe`; keep the entire app-image together.
  No installer or update bundle was produced.
- Native artifacts, matching source archive, licenses, and raw binary hashes:
  `.downloads/openflux/artifacts/manifest.json`.
  Android packaging strips the raw executables; the packaged x86_64 SHA-256 is
  `b76cefd5e6feb45ca3595b97f52757424e89863a74e0887ef045b9ce254d36eb`.
- Offline emulator details: `.downloads/openflux/emulator-smoke/RESULTS.md`.
- Windows smoke output:
  `.downloads/openflux/windows-build/tmp/verifyDesktopAppImage/launcher-smoke.log`.
- Windows real-connection result:
  `.downloads/openflux/windows-connection-result.xml`.
- Windows download benchmark result:
  `.downloads/openflux/windows-download-results.xml`.
- Latest fixed-field Android failure summary:
  `.downloads/openflux/android-app-test/failure-bc6a6546da7344928dda9e97f6caecce.json`.

## Remaining Gate

The replacement private document passed the legacy-editor field/edit-permission
probe and the real encrypted Windows connection. Its link and newly generated
key remain in protected local/server files, not this report or build artifacts.

The isolated server is installed and running. The original three containers
retained their identities/running states; the previous active olcRTC, x-ui,
Docker and SSH units remained active. Docker added a dedicated nonoverlapping
bridge/NAT; no existing VPN service, host package, or Docker daemon was changed.
The new container has no published ports, uses a read-only root filesystem,
and is limited to 512 MiB and one CPU. Automatic restart is not enabled yet.

Android app-level TUN connectivity and external traffic are still pending.
The initial stdin-transfer checksum failure no longer reproduces after using
acknowledged non-PTY ADB stdin; 17 local byte-pipe/checksum tests passed. A later
live attempt observed AES-256-GCM, authenticated native readiness, and DNS-bridge
readiness. After separating the exact native stdout-reader warning from fatal
conditions, a fresh repeat still failed the transport-log health check before
TUN establishment was observed. Its fixed error is `TRANSPORT_HEALTH_REJECTED`,
with retained warning `NATIVE_OUTPUT_READER_STOPPED`. The remaining offending
log condition is not identified by this summary; no Android TUN/HTTPS pass is
claimed. No production reader fix was made and no further log exceptions were
added. Guest app data was cleared and the owned emulator processes exited.
Carrier availability
and Android olcRTC/AWG/VLESS regression connections remain unverified in this
phase. Test Windows and Android sequentially: only one active
client per document/key/server instance is supported initially. Do not publish
a release based only on these checks or treat the tests as a security audit.

## Workspace Safety

The running Windows application used the old normal build-output directory.
An initial packaging cleanup failed on locked files. Comparison against the
matching local 0.0.12 archive found one missing generated file; it was restored
without overwriting existing files. A subsequent full comparison found all
251 archive files matching and none missing. Running application and VPN
processes were not stopped. Later packaging used the separate output above.

The local-only init script `.downloads/openflux/windows-isolated.init.gradle`
redirects only the desktop build directory and stages reused native files after
copy-task cleanup. Future tests must not package over a running app-image. The
working host proxy/routes and existing server VPN services were not reconfigured.
No GitHub upload, merge, or release was performed.

## Русский

Проверена локальная разработческая сборка `0.0.13`, build `2026091301`. Нативные
компоненты собраны для Windows/Linux и трёх Android ABI. Прошли тесты конфигов,
шифрованного обмена, отмены/остановки, импорта и очистки логов. У помощника профилей
18 тестов, у серверных и SSH-инструментов 84 локальных теста.

APK собран и проверен по составу. На чистом Android 35 x86_64 эмуляторе без сети
бинарник из установленного APK запустился от UID приложения, подтвердил версию и
отклонил пустой конфиг без ложной готовности. Холодный запуск дошёл до системного
запроса разрешения уведомлений без обнаруженного падения. Действия на главном
экране и подключение не проверялись. Тестовый эмулятор выключен.

Windows app-image собран отдельно и прошёл запуск JVM и проверку встроенных
движков. Это служебный запуск, а не проверка всего интерфейса или VPN. Пути к
артефактам и хеши приведены выше; APK отладочный, Windows-установщик не создавался.

Новая приватная ссылка прошла проверку старого редактора и реальное подключение
Windows через DesktopVpnManager. Получены AES-256-GCM и аутентифицированный Ready,
затем HTTP 200 от Instagram и Telegram через отдельный SOCKS. Повторная проверка
Telegram после 25 секунд также прошла. Тестовый процесс остановлен, порт закрыт,
временный конфиг удалён. Данные тестового приложения были изолированы.

Сервер OpenFlux установлен отдельно и запущен. Прежние три контейнера сохранили
идентичность и состояние, активные службы olcRTC, x-ui, Docker и SSH остались
активными. Docker создал отдельный bridge/NAT без пересечения подсетей. Контейнер
не публикует порты, корневая ФС доступна только для чтения, лимиты: 512 МиБ и одно
ядро CPU. Автоперезапуск пока не включён. Ссылка и ключ нигде не публиковались.

На Windows все четыре протокола также прошли реальную загрузку по 8 МиБ из одного
источника Cloudflare и проверку остановки. olcRTC FI_2: 5,55 Мбит/с; AWG: 68,19;
VLESS XHTTP: 30,46; OpenFlux с AES-256-GCM: 19,93. Это по одному короткому замеру
полезной загрузки, без времени установки соединения/DNS/TLS и без гарантий
постоянной скорости. Замер AWG занял менее секунды. Отдача и скорость на Android
не измерялись. Офлайн-тесты измерителя: 25 успешных проверок.

Реальный трафик через Android TUN ещё предстоит проверить. Ошибка контрольной
суммы больше не повторяется после перехода на ADB stdin с подтверждением
завершения; 17 локальных проверок передачи байтов и контрольной суммы прошли.
Следующий запуск подтвердил AES-256-GCM, аутентифицированную готовность OpenFlux
и готовность DNS-моста. После отдельной классификации точного предупреждения
stdout новый запуск всё равно остановился на проверке логов до подтверждения
TUN: `TRANSPORT_HEALTH_REJECTED`, предупреждение `NATIVE_OUTPUT_READER_STOPPED`.
Оставшееся условие ошибки по этой сводке не определено; успешное подключение
Android и прохождение HTTPS не подтверждены. Код чтения логов приложения пока
не меняли и дополнительные исключения ошибок не добавляли. Данные гостевого
приложения очищены, эмулятор завершён. У проверочного сценария прошли 74
синтетических теста: потеря процесса/TUN, fatal и повторное подключение по-прежнему
отклоняются, предупреждение сохраняется в результате.
Доступность у оператора и Android-подключения olcRTC/AWG/VLESS ещё не проверены.
Тесты Windows и Android выполняются последовательно:
на документ/ключ/экземпляр пока допускается один активный клиент. Эти проверки
не заменяют аудит безопасности и не являются достаточным основанием для релиза.

Работающий Windows Unified VPN оказался запущен из обычной папки сборки. Неудачная
очистка упаковщика оставила отсутствующим один файл; он восстановлен из точно
совпадающего локального архива 0.0.12. Повторная сверка подтвердила совпадение всех
251 файлов архива. Работающие процессы не останавливали, дальнейшая сборка шла
в отдельной папке. Прокси, маршруты ноутбука и действующие серверные VPN не
перенастраивались. Публикации на GitHub не было.
