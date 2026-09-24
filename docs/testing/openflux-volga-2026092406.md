# OpenFlux Volga Validation: 2026092406

## English

Local application candidate: **0.0.14, build 2026092406**. No GitHub publication.
OpenFlux source: `d34dc8caa70ca059cd80d8f5753499361052dabc`, Unified wrapper 5,
protocol `unified-openflux-aesgcm-v1`. Both peers must use the encrypted wrapper.

### Scope

- Added explicit `vyandex` for the new Volga text editor alongside classic `yandex`.
- A separate empty document, fresh encryption key and separate server instance
  were used. Existing documents, profiles and the main/phone OpenFlux instances
  were not migrated. One active client per document/key/instance remains required.
- Both transports retain mandatory AES-256-GCM and authenticated peer readiness.
  No plaintext, automatic editor conversion or protocol fallback was introduced.
- Browser verification uses fresh anonymous contexts and bounded in-memory cookie
  handoff. Personal browser sessions and account cookies are not imported.
- Volga queues and HTTP responses are bounded. Stop cancels startup, WebSocket and
  HTTP work; authorization failures invalidate the session rather than retrying
  stale credentials. Fatal session errors have a dedicated notification channel.
- Windows checks used isolated loopback SOCKS. System proxy, DNS and routes were
  unchanged; the operator's protected proxy listeners/processes were preserved.

### Offline Checks

- Native build: transport, Yandex/Volga, tunnel and wrapper tests passed. Windows
  amd64, Linux amd64 and three Android ABIs built with a hash-verified source bundle.
- Profile/import/editor/localization: 89 selected JVM tests passed.
- Windows packaging suite: 57 selected JVM tests and 34 browser-helper policy
  checks passed, including actual launcher/JVM and bundled-native smoke tests.
- Android: 68 host tests, 120 harness ownership/cleanup tests and 26 public stdin
  fixtures passed. Unknown transports are rejected before private data transfer.
- Profile generator: 21 tests; server tooling: 235 tests passed.
- Browser policy tests included 120 JavaScript fixture evaluations across the
  Windows, Android and server predicates. Independent targeted code review found
  and verified fixes for startup-stop blocking and lost fatal authorization errors.

### Real Windows Traffic

1. Native wrapper 5 reached authenticated readiness. Instagram and Telegram each
   returned HTTPS 200 with certificate verification at roughly 0, 45 and 90 seconds.
   The bounded run lasted 136 seconds, including browser verification and cleanup.
   All six requests passed; the owned process stopped and its SOCKS listener and
   temporary configuration were removed.
2. The **packaged Windows application** was then tested against the persistent
   Volga service. Production `DesktopVpnManager`, build metadata, native resources
   and supporting application classes came from the final packaged JARs, not test
   replacements. Authenticated readiness, Instagram/Telegram HTTPS 200, a further
   25-second HTTPS liveness check and clean stop all passed. No owned process remained.

These are short connection/liveness tests, not a speed measurement or multi-hour
reliability guarantee. The new-document result does not prove the old editor's
previous disconnection issue is fixed.

### Real Android Traffic

The exact debug APK for build 2026092406 passed in a fresh owned Android API 35
x86_64 emulator: authenticated readiness, TUN establishment, strict-TLS Instagram
and Wikipedia HTTPS 200, destination hostname resolution, uninterrupted app/native/
DNS-bridge process health and TUN health, VPN stop and temporary-config cleanup.
Both owned emulator processes stopped; no warning codes were reported.

The **unchanged release APK** was validated separately in a fresh owned Android
API 35 emulator. The installed artifact's SHA256 matched the release entry below;
its signer matched the prior release and debug APK. The APK was non-debuggable,
the debug receiver was absent and `run-as` access was rejected. The installed
native wrapper version was verified before the connection test.

Starting through the application UI and accepting Android's VPN consent reached
authenticated readiness and established TUN. Instagram and Wikipedia returned
HTTPS 200; hostname resolution and continuous app/native/DNS-bridge/TUN health
checks passed. Stopping through the UI, clearing the owned test app data and
stopping the owned emulator all passed, with no warning codes. This is release
artifact evidence from an emulator, not validation on a physical phone or an
endurance or leak audit.

### Server State

- A dedicated Volga service is enabled and running. It uses the verified native
  image and a separate sandboxed browser image; old instances were not replaced.
- The actual binary inside the running container and its mounted private config
  were checked against local hashes before enabling the service. Image ID, owner,
  private-config hash, helper hashes and unit hash are kept in private receipts.
- Startup and post-stop cleanup operate only on that instance's verified IDs.
  Unexpected termination retries after five minutes, avoiding rapid browser loops.
- The packaged Windows test passed **after** the persistent service was enabled.
  A later status check showed active/enabled with zero automatic restarts.
- Host reboot, long outages and forced-crash recovery were not tested in this run.
  Other VPN services were not intentionally restarted or reconfigured.

### Artifact Identity

| Artifact | SHA256 |
| --- | --- |
| Windows installer | `854f39ab80c9bbc908f2a35a63828047ab7159a5599ea35c2b2f5676df0dce1f` |
| Windows portable ZIP | `58f0eb8481f0fb641567e01174736ba527d6df6ef9c1b7e62186b3b363e6038a` |
| Tested Android debug APK | `62f6dab8dc1f64679d82b091656b222adce66786d4624db880a9330934f7e4fd` |
| Tested Android release APK | `9643b4360e223d334a1c870b5072238ae7f375c22c3fce02bc1f90535230a3ab` |
| Windows OpenFlux native | `464e5b4fd0cb23745457adb957937c2dd683f26a30597f0ef015e5ee354e4065` |
| Linux OpenFlux native | `32376e33b43722ed81cf4bfe7658c246b7c6cb1aa7afda4f3624194f6aa44e4e` |
| Patched OpenFlux source archive | `eaad3767a18c9a1b0160bb0e5518390395c40b4c2f2333496cd074df3645649b` |

Limitations: physical Android devices (including Android 13), carrier-specific
availability, independent traffic/DNS leak analysis, sustained throughput,
concurrent clients, long sessions and network
handover are not covered. General UDP and IPv6 remain unsupported by this wrapper.
Private document URLs, profile links, keys, cookies and SSH credentials are excluded
from this report, Git and application packages.

## Русский

Локальный кандидат: **0.0.14, сборка 2026092406**. Публикации на GitHub нет.
OpenFlux закреплён на `d34dc8caa70ca059cd80d8f5753499361052dabc`, обёртка Unified 5,
протокол `unified-openflux-aesgcm-v1`. Обоим участникам нужна зашифрованная обёртка.

### Изменения

- Добавлен явный `vyandex` для нового текстового редактора Volga; прежний `yandex`
  сохранён. Использованы отдельный пустой документ, новый ключ и отдельный серверный
  экземпляр. Старые документы и основной/телефонный OpenFlux не переносились.
- AES-256-GCM и подтверждение зашифрованного соединения обязательны. Открытого
  транспорта, автоматической конвертации редактора и подмены протокола нет.
- Анонимная браузерная проверка не использует личные сессии браузера или аккаунт.
  Cookies передаются через память с ограничениями размера и области действия.
- Очереди и HTTP-ответы ограничены. Исправлена отмена запуска и остановка сетевых
  операций. Недействительная авторизация завершает сессию явной ошибкой, которая
  не теряется за предыдущими некритическими ошибками браузерной проверки.
- Проверки Windows не меняли системный прокси, DNS или маршруты; рабочие прокси-
  процессы пользователя сохранены. Одновременно допустим один клиент на документ,
  ключ и серверный экземпляр.

### Автоматические Проверки

Прошли четыре набора Go-тестов и сборка Windows/Linux/трёх Android ABI; 89 JVM-тестов
моделей, импорта, редактора и перевода; 57 Windows JVM-тестов и 34 проверки браузерного
помощника; 68 Android host-тестов, 120 проверок стенда и 26 stdin-сценариев; 21 тест
генератора и 235 серверных тестов. Браузерные сценарии включают 120 JavaScript-проверок
для трёх платформ. Отдельный агент проверил исправления остановки и ошибок сессии.

### Реальный Трафик

Windows-ядро подтвердило зашифрованное соединение и шесть HTTPS 200 к Instagram и
Telegram, включая повторы через 45 и 90 секунд. Проверка длилась 136 секунд; процесс,
SOCKS-слушатель и временная конфигурация корректно завершены/удалены.

Затем проверено **собранное Windows-приложение** с настоящим `DesktopVpnManager`,
метаданными и встроенным ядром из финальных JAR. После включения постоянного сервиса
прошли зашифрованное подключение, HTTPS 200 к обоим сайтам, дополнительный запрос
через 25 секунд и остановка. Оставшихся тестовых процессов нет.

Точная отладочная Android APK сборки 2026092406 прошла проверку в новом собственном
эмуляторе API 35: подтверждённое соединение, TUN, HTTPS 200 к Instagram и Wikipedia
с проверкой сертификатов, разрешение имён, непрерывная работа приложения, ядра и
DNS-моста, остановка VPN и удаление временной конфигурации. Эмулятор остановлен,
предупреждений нет.

**Неизменённая релизная APK** отдельно прошла проверку в новом собственном эмуляторе
Android API 35. SHA256 установленного файла совпал со строкой релизной APK в таблице
выше; подпись совпала с предыдущим релизом и debug APK. Отладка отключена,
отладочный receiver отсутствует, доступ через `run-as` отклонён. Перед подключением
проверена версия установленной нативной обёртки.

Запуск через интерфейс приложения и системное разрешение Android VPN привели к
подтверждённому соединению и созданию TUN. Instagram и Wikipedia вернули HTTPS 200;
прошли разрешение имён и непрерывные проверки приложения, ядра, DNS-моста и TUN.
Остановка через интерфейс, очистка данных тестового приложения и завершение
собственного эмулятора прошли без предупреждений. Это проверка релизного артефакта
в эмуляторе, а не на физическом телефоне; длительная стабильность и утечки трафика
этим тестом не проверены.

### Сервер И Ограничения

Новый отдельный Volga-сервис включён в автозапуск. Старые экземпляры не заменялись.
Перед включением проверены фактический бинарник внутри контейнера, образ, владелец
и хеш смонтированной конфигурации. Квитанции с деталями хранятся приватно. Очистка
затрагивает только собственные ресурсы; повтор после неожиданной остановки назначен
через пять минут. После Windows-теста сервис активен, автоматических перезапусков нет.

Хеши артефактов приведены в таблице выше. Физические Android-устройства, включая
Android 13, перезагрузка VPS, аварийное восстановление, конкретные операторы,
независимый анализ утечек трафика и DNS,
скорость, длительные сессии и переходы между сетями пока не проверены. UDP и IPv6
по-прежнему не поддерживаются этой обёрткой. Успех нового документа не доказывает,
что устранён прежний обрыв старого редактора. Приватные ссылки, ключи, cookies и SSH-
данные не включаются в отчёт, Git или сборки.
