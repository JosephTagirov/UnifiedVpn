# Changelog

Все существенные изменения Unified VPN / Olcbox фиксируются в этом файле.

## [0.0.14 / 2026092406] - 2026-09-24 (Preview / Предварительная)

### English

- Added explicit OpenFlux transport selection: the classic Yandex Docs editor (`yandex`) or the new Volga text editor (`vyandex`). Editing, manual entry, sharing, bundle import and server profile tools preserve that selection. Existing profiles stay on `yandex`; unsupported transports are rejected without fallback.
- Native wrapper 5 keeps mandatory AES-256-GCM and authenticated readiness for both transports. Volga uses bounded anonymous browser verification, origin-scoped in-memory cookies, cancellation-aware HTTP/WebSocket requests and bounded queues. Expired sessions fail explicitly instead of silently retrying stale credentials.
- Fixed stopping a session during transport startup. Added profile, browser-policy, transport, server-tool and Android harness regressions. Validation uses a separate document and server instance without replacing existing profiles or changing the Windows proxy, DNS or routes.
- Prepared the exact tested APK and EXE for the GitHub prerelease, with corresponding source, license notices and checksums. Two separate Volga profiles passed packaged Windows HTTPS/stop and unchanged Android release APK TUN/HTTPS/stop in emulators. AWG 3.1 also passed real Windows/Android-emulator traffic with separate keys and rekeying. See the [release notes](docs/releases/0.0.14-build.2026092406.md) and [Volga validation report](docs/testing/openflux-volga-2026092406.md) for limits; the stable update channel remains unchanged.

### Русский

- Добавлен явный выбор транспорта OpenFlux: старый редактор Яндекс Документов (`yandex`) или новый текстовый редактор Volga (`vyandex`). Редактирование, ручной ввод, экспорт, импорт набора профилей и серверные инструменты сохраняют этот выбор. Существующие профили остаются на `yandex`; неизвестные транспорты отклоняются без автоматической подмены.
- Нативная обёртка 5 сохраняет обязательное AES-256-GCM и подтверждение зашифрованного соединения для обоих транспортов. Volga использует ограниченную анонимную браузерную проверку, cookies в памяти с привязкой к доменам, отменяемые HTTP/WebSocket-запросы и ограниченные очереди. Недействительная сессия завершается явной ошибкой вместо бесконечного повтора с устаревшими данными.
- Исправлена остановка сессии во время запуска транспорта. Добавлены проверки профилей, браузерных ограничений, транспорта, серверных инструментов и Android-стенда. Испытания используют отдельный документ и серверный экземпляр, не заменяя прежние профили и не меняя прокси, DNS и маршруты Windows.
- Точные проверенные APK и EXE подготовлены для предварительного релиза GitHub с исходниками, лицензиями и хешами. Два отдельных Volga-профиля прошли HTTPS/остановку собранной Windows-версии и TUN/HTTPS/остановку неизменённой релизной APK в эмуляторах. AWG 3.1 также прошёл реальный Windows/Android-трафик с отдельными ключами и обновлением сессионных ключей. Ограничения указаны в [описании выпуска](docs/releases/0.0.14-build.2026092406.md) и [отчёте Volga](docs/testing/openflux-volga-2026092406.md); стабильный канал обновлений не меняется.

## [0.0.14 / 2026092405] - Unreleased / Не опубликовано

### English

- Android browser verification requests the legacy desktop editor using the installed Chromium version. Anonymous cookies from the exact Disk and Docs origins are handed to native code separately; they are not widened to parent domains. Profile URLs, encryption keys, AES-256-GCM and legacy LZ4 remain unchanged.
- Added fixed-field, debug-only browser checkpoints and regression tests for redirect cookie isolation. Intermediate local builds 2026092402/03 added diagnostics; 2026092404 selected the desktop editor; 2026092405 fixes the two-origin cookie handoff. None was published.
- Added a receipt-bound systemd browser lifecycle with independently supervised trial recovery. A real controller-SIGKILL test restored the exact original OpenFlux container in 7.21 seconds after fixing namespace preflight and normal-stop handling. This does not prove host-reboot recovery.
- Passed 227 server-tool tests on Windows and offline Linux, systemd unit validation, 344 JVM tests, 226 Android host tests and 120 Android harness checks. The packaged Windows build passed authenticated OpenFlux, HTTPS to two external sites, a 25-second liveness check and stop without changing host network settings. Two fresh Android runtime tests timed out during browser verification before TUN. That earlier server trial restored the original through independent recovery in 17.207 seconds; see its [historical report](docs/testing/openflux-runtime-2026092405.md).
- Later new-server work enabled the main managed OpenFlux service after one complete Windows traffic pass, retaining rollback files. A post-commit Windows repeat failed after initial HTTPS success; a fresh Android test again failed browser verification. OpenFlux stability and release gates remain unpassed. A separate AWG peer/export passed two Windows HTTPS checks, including the packaged profile decoder/config builder. No application binary change or GitHub publication; see [new-server results](docs/testing/new-server-connectivity-20260924.md).

### Русский

- Браузерная проверка Android запрашивает старый настольный редактор с версией установленного Chromium. Анонимные cookies Disk и Docs передаются ядру раздельно, только для соответствующих точных хостов, без расширения на родительские домены. Ссылки профилей, ключи, AES-256-GCM и прежний LZ4 не меняются.
- Добавлены фиксированные диагностические этапы только для debug-сборок и тесты изоляции cookies при перенаправлении. Локальные сборки 2026092402/03 добавляли диагностику, 2026092404 выбирала настольный редактор, 2026092405 исправляет передачу cookies двух хостов. Они не публиковались.
- Добавлен systemd-контроллер браузерного процесса с независимым восстановлением после прерванного пробного обновления. Реальный тест SIGKILL восстановил исходный контейнер OpenFlux за 7,21 секунды после исправления проверки namespace и обычной остановки. Это не подтверждает восстановление после перезагрузки VPS.
- Прошли 227 серверных тестов на Windows и в изолированном Linux, проверка systemd, 344 JVM-теста, 226 Android host-тестов и 120 проверок Android-стенда. Упакованная Windows-сборка прошла зашифрованное подключение, HTTPS к двум внешним сайтам, проверку через 25 секунд и остановку без изменения сетевых настроек хоста. Два чистых Android-теста завершились тайм-аутом браузерной проверки до TUN. То предыдущее серверное испытание восстановило исходный экземпляр независимым откатом за 17,207 секунды; см. [исторический отчёт](docs/testing/openflux-runtime-2026092405.md).
- Позднее на новом сервере основной OpenFlux переведён на управляемую службу с автозапуском после одного полного Windows-теста; файлы отката сохранены. Повтор Windows оборвался после первых успешных HTTPS-запросов, а новый Android-тест снова не прошёл браузерную проверку. Устойчивость OpenFlux и готовность к выпуску не подтверждены. Отдельный профиль AWG прошёл два Windows HTTPS-теста, включая декодер и построитель конфигурации собранного приложения. Бинарники приложения не менялись, публикации на GitHub нет; см. [результаты на новом сервере](docs/testing/new-server-connectivity-20260924.md).

## [0.0.14 / 2026092401] - Unreleased / Не опубликовано

### English

- Updated the OpenFlux source base to `d34dc8c` and native wrapper to 4. The server uses explicit L4 forwarding without raw sockets or per-container iptables setup. Existing profile schema, mandatory AES-256-GCM, authenticated readiness and legacy LZ4 framing are preserved; upstream batch/zstd is not silently enabled.
- The L4 adapter keeps the former per-stack TCP buffer limits, waits for destination connection before accepting a tunnel connection, preserves TCP half-close, and cancels outstanding server connections on shutdown. Added loopback-only encrypted traffic and lifecycle regressions.
- Added an explicit staged server-upgrade tool that keeps the original container, image and configuration for rollback. HTTPS document access is checked before stopping the original; a bounded traffic trial requires fresh authenticated peer/DNS/HTTPS evidence before committing. This is not a release or a claim that the live deployment passed. See [validation status](docs/testing/openflux-upstream-2026092401.md).
- Native builds, 205 server-tool tests, 344 JVM tests and 221 Android host tests passed. The new Windows document check passed with isolated WebView2. An isolated server Chromium sandbox and browser-to-native document handoff also passed, using pinned dependencies and no added capabilities. The original server was not replaced; durable migration, real new-pair VPN traffic and Android runtime checks remain pending.

### Русский

- База исходников OpenFlux обновлена до `d34dc8c`, native-обёртка до 4. Сервер использует L4 без raw-сокетов и настройки iptables внутри контейнера. Сохранены формат профилей, обязательное AES-256-GCM, проверка готовности сервера и прежний LZ4; новый batch/zstd не включается незаметно.
- L4-адаптер сохраняет прежние лимиты TCP-буферов, подтверждает соединение только после подключения к назначению, поддерживает TCP half-close и отменяет серверные соединения при остановке. Добавлены локальные тесты зашифрованного трафика и жизненного цикла.
- Добавлено отдельное поэтапное обновление сервера с сохранением прежнего контейнера, образа и конфигурации для отката. HTTPS-доступ к документу проверяется до остановки старого экземпляра; ограниченный пробный запуск требует свежих результатов проверки зашифрованного соединения, DNS и HTTPS перед фиксацией. Это не выпуск и не утверждение об успешном обновлении действующего сервера. См. [статус проверок](docs/testing/openflux-upstream-2026092401.md).
- Прошли native-сборки, 205 серверных тестов, 344 JVM и 221 Android host-тест. Новый Windows-клиент проверил документ через отдельный WebView2. На VPS также прошли реальная sandbox Chromium и передача браузерной сессии ядру, с закреплёнными зависимостями и без дополнительных capabilities. Старый сервер не заменён; постоянный запуск с откатом, реальный VPN-трафик новой пары и проверки Android на устройстве ещё не пройдены.

## [0.0.14 / 2026092302] - Unreleased / Не опубликовано

### English

- Reviewed OpenFlux main `d34dc8c` against the bundled `4f1bdb5` base. Selectively backported Yandex reconnect/socket cleanup, pending-queue recovery and precompiled parsers from `4513a14` and `945cefd`. This is not a full upstream upgrade: the new batch/zstd wire format and rewritten server network stack are not enabled.
- Authenticates a replacement WebSocket before queued packets or keepalives can use it; Stop cancels idle/pending writers, keepalive and reconnect waits. An old connection cannot mark a replacement disconnected.
- Native wrapper is now 3; AES-256-GCM, authenticated readiness, legacy LZ4 and profile format remain unchanged. The artifact manifest records the reviewed head and selected backports. Builds are local only; no server deployment, installer/APK release or GitHub publication. See [validation report](docs/testing/openflux-upstream-backports-2026092302.md).

### Русский

- OpenFlux main `d34dc8c` сравнен со встроенной базой `4f1bdb5`. Выборочно перенесены исправления переподключения Яндекса, закрытия сокетов, сохранения очереди отправки и предварительной компиляции регулярных выражений из `4513a14` и `945cefd`. Это не полный переход на upstream: новый формат batch/zstd и переработанный серверный сетевой стек не включены.
- Авторизация нового WebSocket проходит до отправки очереди и keepalive; Stop прерывает ожидания отправителя, keepalive и переподключения. Старое соединение не может пометить новое отключённым.
- Версия native-обёртки повышена до 3; AES-256-GCM, проверка готовности сервера, прежний LZ4 и формат профилей сохранены. Манифест содержит проверенный upstream и перенесённые коммиты. Только локальные сборки: сервер, установщик/APK и GitHub не обновлялись. См. [отчёт о проверках](docs/testing/openflux-upstream-backports-2026092302.md).

## [0.0.14 / 2026092301] - Unreleased / Не опубликовано

### English

- Added an experimental anonymous browser-verification path for the OpenFlux Yandex Documents transport. Native wrapper 2 exchanges short-lived cookies over inherited pipes; profile format, mandatory AES-256-GCM and peer authentication remain unchanged. Account cookies are rejected; cancellation, response limits and renewal backoff are covered by tests.
- Added an isolated Windows WebView2 helper and an Android API 28+ private-process WebView service. Neither reads a personal browser profile or receives the tunnel encryption key. Automated verification only; an interactive challenge fails explicitly instead of being treated as a connected VPN.
- Added `--check-document` to test the production HTTPS bootstrap without joining a document room, starting SOCKS/TUN or sending tunnel traffic. A real-document check with an isolated Chrome provider passed; this is not an end-to-end connection result for the packaged apps.
- Live WebView2 checks produced one handoff failure followed by one separately approved success. Added fixed, redacted failure-stage diagnostics and regression tests; the first failure remains unexplained, full VPN traffic is unverified and the build remains unreleased.
- Added opt-in server browser-companion tooling restricted to a new test instance, without modifying existing containers. VPS sandbox compatibility, Android WebView runtime and encrypted end-to-end traffic remain release gates. No deployment, installer/APK release or GitHub publication was performed for this build. See [validation status](docs/testing/openflux-browser-bootstrap-2026092301.md).

### Русский

- Добавлен экспериментальный путь анонимной браузерной проверки для OpenFlux через Яндекс Документы. Обёртка ядра версии 2 получает временные cookies через закрытые каналы процессов; формат профилей, обязательное AES-256-GCM и проверка сервера не меняются. Cookies аккаунта отклоняются; отмена, ограничения ответов и интервалы повторной проверки покрыты тестами.
- Добавлены отдельный помощник WebView2 для Windows и WebView-сервис в приватном процессе для Android API 28+. Они не читают личный профиль браузера и не получают ключ шифрования туннеля. Пока поддерживается только автоматическая проверка; интерактивная проверка завершается явной ошибкой, а не ложным статусом подключения.
- Добавлен режим `--check-document`: проверка HTTPS-доступа к редактору без входа в комнату, SOCKS/TUN и VPN-трафика. Проверка реального документа с отдельным Chrome прошла; это не результат сквозного подключения собранных приложений.
- Первая живая проверка WebView2 завершилась ошибкой передачи сессии; отдельно разрешённая повторная попытка прошла. Добавлены безопасные метки этапа ошибки и регрессионные тесты; причина первого сбоя неизвестна, полный VPN-трафик не проверен, сборка не опубликована.
- Подготовлен серверный браузерный помощник только для нового тестового экземпляра, без изменения существующих контейнеров. До выпуска нужны проверка sandbox на VPS, WebView на Android и реального зашифрованного трафика. Развёртывания, выпуска установщика/APK и публикации этой сборки на GitHub не было. См. [статус проверок](docs/testing/openflux-browser-bootstrap-2026092301.md).

## [0.0.14] - Server Tools / Серверные инструменты

### English

- The private update bot now watches Unified VPN stable releases and replacement/new application builds within the same version. Per-platform build numbers are included; cosmetic release edits are ignored. Updating only the bot program preserves credentials, notification state and timer settings.
- Added isolated named OpenFlux server instances for simultaneous devices using separate Yandex documents and encryption keys. Installation paths, containers, bridges, ownership records and deployment receipts are instance-bound; the original default installation remains compatible.
- Added opt-in reuse of hash-verified public server artifacts for slow SSH uploads, without copying the original configuration or changing its files. An installed named manager refuses a missing or mismatched instance argument.
- On 2026-09-17, the server-tools-only change passed 112 tests and real traffic checks on Windows LocalSocks and an Android 35 emulator TUN without changing application binaries. The later application rebuild is recorded below; see [server validation](docs/testing/openflux-multi-instance-20260917.md).

### Русский

- Приватный бот обновлений теперь следит за стабильными релизами Unified VPN и заменой/добавлением сборок приложения той же версии. В сообщениях указаны номера сборок по платформам; правки описания релиза игнорируются. Замена только программы бота сохраняет секреты, историю уведомлений и настройки таймера.
- Добавлены отдельные именованные экземпляры сервера OpenFlux для одновременной работы устройств с разными документами Яндекса и ключами шифрования. Каталоги, контейнеры, сети, записи владения и квитанции развёртывания привязаны к экземпляру; совместимость первой установки сохранена.
- При медленном SSH можно повторно использовать публичные серверные артефакты с проверкой SHA256, не копируя старую конфигурацию и не изменяя её файлы. Установленный именованный manager отказывает при отсутствующем или неверном имени экземпляра.
- 17.09.2026 изменение только серверных инструментов прошло 112 тестов и проверки реального трафика через Windows LocalSocks и TUN эмулятора Android 35 без замены бинарников приложения. Последующая пересборка приложения описана ниже; см. [серверный отчёт](docs/testing/openflux-multi-instance-20260917.md).

## [0.0.14] - 2026-09-23 (Preview / Предварительная)

Build / Сборка: `2026092201` for Android and Windows / для Android и Windows.
[Release notes / Описание выпуска](docs/releases/0.0.14.md).
Earlier local build / Предыдущая локальная сборка: `2026092101`
([Android validation / Проверки Android](docs/testing/android-notifications-openflux-ping-2026092101.md)).
Stable updates remain on 0.0.12. Стабильный канал остаётся на 0.0.12.

### English

- Android's notification profile chooser stays open after connection results and Stop. A separate Reconnect action retries the failed profile, including after an automatic return to the previous working profile; busy requests cannot start parallel engines.
- OpenFlux profiles can be shared from their editor as a QR code or importable link, with Android's system share action. Export requires confirmation that the link contains a secret encryption key and that a document supports only one active client; Android clipboard copies are marked sensitive and expire.
- Added an interactive OpenFlux server profile helper: prepare a new private key/config/import link using verified installed artifacts, then explicitly install or roll back only the new named instance. The default action is offline preparation, not deployment; creating a separate legacy-editor document remains manual. See [instructions](tools/openflux-server/CREATE_PROFILE.md).
- Refreshed the AWG core to Throne sing-box `7745e9a` (`wip/1.14.0`) and its AmneziaWG backend to `b311c8ac`, including AWG 3.1 flags and the reserved-byte corruption fix. Both adapters preserve validated keepalive ranges and Boolean options. Native source/target/backend pins are checked before packaging.
- Tested the newest olcbox 1.0.129 core `08843d6` first. Its new Jitsi envelope is unreadable by an existing `f616` receiver, so this build selects the preceding compatible `d7a00da` core instead. OLC2 encryption is unchanged; servers are not upgraded automatically. See the [upstream compatibility report](docs/testing/upstream-refresh-2026092103.md).
- Ported bounded readiness polling from current olcbox: Android waits up to 60 seconds in cancellable 200ms slices, while retaining Unified VPN's serialized cleanup. Jitsi settling is limited to restarting the same room. This is a focused integration update, not a wholesale import of the upstream application.
- The private notifier now follows the actual AWG `wip/1.14.0` branch instead of its older default branch; a missing branch is an error, not a silent fallback.
- Windows VLESS now accepts TCP/RAW, WS/WebSocket, gRPC and HTTPUpgrade in addition to XHTTP/SplitHTTP. TUN pins the remote endpoint while preserving TLS/Reality SNI and HTTP/gRPC authority. Unsupported transports or combinations fail explicitly.
- Windows AWG TUN DNS requests now use the profile's tunnel resolvers, including private addresses and custom DNS ports. Proxy-mode DNS routing remains unchanged. The separate TUN helper handles both UAC and already elevated broker contexts, with stricter address validation; GUI elevation remains refused.
- Windows TUN remains experimental for VLESS/AWG. olcRTC/OpenFlux TUN is still disabled on Windows until dynamic transport sockets can bypass capture safely and TCP-only DNS handling is added; their Android VPN path is unchanged. Real Windows TUN traffic, DNS/IPv6 leakage and crash recovery are not yet validated.
- Android's main screen, notification profile chooser and VPN service now share one profile repository, mutation lock and change stream. Switching or rolling back from a notification updates the main screen too. Initial subscription and delayed-read races are covered by regression tests.
- The Unified VPN icon in the notification profile chooser opens the main application. Its 48dp touch target has an accessibility label; lock-screen and obscured-touch protection remain enabled.
- Android UI models follow the Activity's ViewModel lifecycle. Returning to the foreground refreshes the saved selection; releasing UI observers does not stop the independently running VPN service.
- OpenFlux latency checks use an HTTPS response through the matching, already connected encrypted tunnel, not the document website or a localhost connect time. Inactive OpenFlux profiles require connecting first; checks never create another document client or fall back to direct traffic.
- Editing a profile's connection settings or deleting it cancels pending latency checks and clears stale results. A late old check cannot overwrite a replacement; renaming a profile preserves valid measurements.
- The separate private update bot now monitors OpenFlux, olcRTC and the AWG core used by Unified VPN, in addition to original olcbox and Amnezia VPN. Core commit changes are tracked independently of releases. Updating the bot program preserves its secrets, notification history and timer; no server component is updated automatically.

### Русский

- Окно профилей из шторки Android остаётся открытым после результата подключения и Stop. Отдельная кнопка «Переподключить» повторяет именно неудавшийся профиль, в том числе после автоматического возврата к прежнему рабочему; повторные нажатия не запускают параллельные движки.
- OpenFlux-профиль можно передать из редактора QR-кодом или ссылкой, а на Android также через системное меню отправки. Перед экспортом нужно подтвердить предупреждение о секретном ключе и одном активном клиенте на документ; буфер Android помечается конфиденциальным и очищается по таймеру.
- Добавлен интерактивный помощник серверных профилей OpenFlux: новый приватный ключ, конфиг и ссылка для импорта создаются на основе проверенных установленных артефактов; установка и откат только нового экземпляра требуют явного действия. По умолчанию выполняется подготовка без сети, не развёртывание; отдельный документ старого редактора создаётся вручную. См. [инструкцию](tools/openflux-server/CREATE_PROFILE.md).
- Ядро AWG обновлено до Throne sing-box `7745e9a` (`wip/1.14.0`), реализация AmneziaWG до `b311c8ac`: параметры AWG 3.1 и исправление повреждения пакетов при обработке reserved bytes. Оба адаптера сохраняют проверенные диапазоны keepalive и логические параметры. Перед упаковкой проверяются настоящие версии исходников, архитектура и AWG-модуль внутри бинарников.
- Сначала проверено свежее ядро olcbox 1.0.129 `08843d6`. Его новый Jitsi-конверт не читается прежним сервером `f616`, поэтому выбран предшествующий совместимый `d7a00da`. Шифрование OLC2 не менялось; серверы автоматически не обновляются. См. [отчёт совместимости](docs/testing/upstream-refresh-2026092103.md).
- Из актуального olcbox перенесено ожидание готовности Android до 60 секунд с отменяемыми интервалами по 200 мс; последовательная остановка Unified VPN сохранена. Задержка очистки Jitsi применяется только при повторном входе в ту же комнату. Это обновление конкретных компонентов, не полное копирование исходного приложения.
- Приватный бот теперь следит за используемой веткой AWG `wip/1.14.0`, а не более старой веткой по умолчанию; пропавшая ветка вызывает ошибку, а не незаметную подмену источника.
- Windows VLESS теперь принимает TCP/RAW, WS/WebSocket, gRPC и HTTPUpgrade в дополнение к XHTTP/SplitHTTP. TUN закрепляет адрес сервера, сохраняя TLS/Reality SNI и HTTP/gRPC authority. Неподдерживаемые транспорты и сочетания параметров отклоняются явно.
- Запросы DNS в Windows AWG TUN теперь используют резолверы профиля внутри туннеля, включая приватные адреса и нестандартные DNS-порты. Маршрутизация DNS прокси-режима не изменена. Отдельный компонент TUN обрабатывает UAC и уже повышенный контекст broker, строже проверяет адреса; запуск GUI от администратора по-прежнему запрещён.
- Windows TUN остаётся экспериментальным для VLESS/AWG. Для olcRTC/OpenFlux он пока отключён: нужны безопасный обход TUN динамическими транспортными сокетами и обработка DNS поверх TCP. Их VPN-путь Android не изменён. Реальный Windows TUN-трафик, утечки DNS/IPv6 и восстановление после сбоя пока не проверены.
- Главный экран Android, окно выбора в шторке и VPN-служба теперь используют общее хранилище профилей, блокировку записи и поток изменений. Переключение или откат через уведомление обновляют и главный экран. Добавлены регрессионные тесты начальной подписки и запоздалого чтения.
- Значок Unified VPN в окне выбора профилей из уведомления открывает само приложение. Область нажатия 48dp имеет подпись для специальных возможностей; защита заблокированного экрана и от перекрывающих касаний сохранена.
- Android ViewModel привязаны к жизненному циклу Activity. При возврате на экран перечитывается сохранённый выбор; освобождение UI-наблюдателей не останавливает независимо работающую VPN-службу.
- Пинг OpenFlux измеряет HTTPS-ответ через соответствующий уже подключённый зашифрованный туннель, а не доступность сайта документов или локального порта. Неактивный профиль сначала нужно подключить; проверка не создаёт второго клиента документа и не переходит на прямой трафик.
- Изменение параметров подключения или удаление профиля отменяет незавершённые проверки задержки и сбрасывает устаревший результат. Запоздалый старый запрос не перезаписывает новый; переименование сохраняет актуальные измерения.
- Отдельный приватный бот обновлений теперь отслеживает OpenFlux, olcRTC и используемое Unified VPN ядро AWG, сохраняя проверки оригинальных olcbox и Amnezia VPN. Для ядер отслеживаются новые коммиты независимо от релизов. Замена программы бота сохраняет секреты, историю уведомлений и таймер; автоматического обновления серверных компонентов нет.

## [0.0.13] - 2026-09-15 (Preview / Предварительная)

Build / Сборка: `2026091401`. Experimental preview; stable updates remain on 0.0.12. Экспериментальная версия; стабильные обновления остаются на 0.0.12.

### English

- Added a visible `Add OpenFlux` action with manual document URL and encryption-key fields. The add sheet scrolls and wraps longer labels. The icon beside `Ping` is now a lightning bolt, with the existing progress indicator preserved.
- Android OpenFlux shares the existing olcRTC per-app split-tunneling settings. The selector shows `olcRTC / OpenFlux`; existing lists are preserved and VLESS/AWG rules remain separate. This does not combine the native transports or enable simultaneous clients on one OpenFlux document.
- Android notification profile switching and rollback reload the saved split-tunneling rules instead of reusing stale lists. Canceled or superseded reads cannot publish settings; a read failure is not silently converted to all-app routing.
- Added an experimental OpenFlux profile with Yandex Documents transport, a separate document/key editor, and URI/JSON import. OpenFlux is pinned to `4f1bdb554c262f3ae9adbfe317a092c6b929ba7d`; original olcbox and other bundled engines remain unchanged.
- The dedicated OpenFlux wrapper requires upstream AES-256-GCM encryption and a fresh authenticated server response before reporting readiness. Plaintext fallback is disabled. Destination DNS uses encrypted tunnel TCP; general UDP and IPv6 are not supported by this first transport integration.
- Added Windows/Android engine lifecycle tests, native handshake tests, and hash-checked native packaging. Fixed late readiness after cancellation and retention of an Android child process that did not stop.
- Fixed combined-profile restore dropping valid non-olcRTC profiles; OpenFlux document URLs and encryption keys are redacted from diagnostic logs.
- Added isolated, opt-in Docker/SSH deployment with host-key pinning, protected configuration transfer, immutable image inputs, and ownership-checked stop. Windows passed authenticated OpenFlux SOCKS/HTTPS and Android 35 emulator passed authenticated TUN/HTTPS, with stable processes and clean shutdown. APK signature/native contents and Windows JVM/installer metadata were verified. See the [current validation report](docs/testing/openflux-2026091401.md).
- Fixed silent Android TUN creation refusal: missing/revoked VPN preparation now reports an error. The debug test receiver calls `VpnService.prepare()` and refuses missing consent; AppOps authorization alone is no longer treated as a prepared connection. Process log readers retry only bounded, typed, no-byte interrupted reads, not closed streams or other failures.
- Passed 234 offline JVM tests, 164 Android host tests, 111 synthetic Android-harness checks, and 17 stdin/checksum tests. Shared transport tests now respect the existing Android restriction on SEI; the restriction itself is unchanged.
- Added an opt-in, bounded Windows download benchmark with authenticated local SOCKS, TLS verification, payload/time caps, no direct fallback, connection monitoring, and verified stop between engines. The first real olcRTC/AWG/VLESS/OpenFlux measurements are recorded in the validation report; they are samples, not guaranteed rates.

### Русский

- Добавлено отдельное действие `Добавить OpenFlux` с ручным вводом ссылки документа и ключа шифрования. Список добавления прокручивается, длинные подписи переносятся. Рядом с `Пинг` теперь молния; индикатор проверки сохранён.
- Android OpenFlux использует общие с olcRTC правила раздельного туннелирования приложений. Вкладка называется `olcRTC / OpenFlux`; существующие списки сохранены, правила VLESS/AWG остаются отдельными. Это не объединяет движки и не добавляет одновременных клиентов на одном документе OpenFlux.
- Переключение профилей и возврат к предыдущему через Android-уведомление перечитывают сохранённые правила раздельного туннелирования, а не используют устаревшие списки. Отменённый или заменённый запрос не применяет настройки; ошибка чтения не включает туннель для всех приложений молча.
- Добавлен экспериментальный профиль OpenFlux через Яндекс Документы: отдельные поля документа и ключа, импорт URI/JSON. Используется закреплённый commit `4f1bdb554c262f3ae9adbfe317a092c6b929ba7d`; исходный olcbox и остальные встроенные движки не обновлялись.
- Обёртка OpenFlux требует встроенное AES-256-GCM-шифрование и свежий аутентифицированный ответ сервера до сообщения о готовности. Передачи без шифрования нет. DNS назначения идёт по TCP внутри зашифрованного туннеля; обычный UDP и IPv6 на первом этапе не поддерживаются.
- Добавлены тесты жизненного цикла Windows/Android, нативного подтверждения соединения и упаковка с проверкой хешей. Исправлены поздняя готовность после отмены и потеря контроля над Android-процессом, который не удалось остановить.
- Исправлено восстановление объединённых пакетов, отбрасывавшее корректные не-olcRTC профили; ссылки на документы и ключи OpenFlux скрываются в диагностике.
- Добавлено изолированное Docker/SSH-развёртывание с проверкой отпечатка, защищённой передачей конфига, закреплёнными образами и остановкой только собственных ресурсов. Windows прошёл аутентифицированный OpenFlux SOCKS/HTTPS, Android 35 на эмуляторе прошёл TUN/HTTPS с контролем процессов и остановкой. Подпись и native-состав APK, JVM и метаданные Windows-установщика проверены. См. [текущий отчёт](docs/testing/openflux-2026091401.md).
- Исправлен молчаливый отказ создания Android TUN: отсутствие или отзыв подготовки VPN теперь приводит к понятной ошибке. Тестовый receiver вызывает `VpnService.prepare()` и отказывает без согласия; одного AppOps больше недостаточно. Чтение логов повторяется только при типизированном прерывании без переданных байтов, с ограничением попыток, но не при закрытом потоке или другой ошибке.
- Прошли 234 офлайн JVM-теста, 164 Android host-теста, 111 синтетических проверок Android-сценария и 17 тестов stdin/контрольных сумм. Общие тесты транспортов теперь учитывают прежнее ограничение Android для SEI; само ограничение не менялось.
- Добавлен отдельный ограниченный замер загрузки Windows: авторизованный локальный SOCKS, проверка TLS, лимиты объёма/времени, отсутствие прямого обхода, контроль соединения и остановка между протоколами. Первые реальные результаты olcRTC/AWG/VLESS/OpenFlux сохранены в отчёте, без обещания постоянной скорости.

## [0.0.12] - 2026-09-09

Сборка: `2026090802`. Публикация разрешена владельцем. Проверки и известные ограничения описаны в [release notes](docs/releases/0.0.12.md).

### Добавлено

- Формат защищённого пакета для друга обновлён до v2: владелец заранее создаёт отдельный AmneziaWG peer, а получатель только расшифровывает и импортирует готовые olcRTC, VLESS и AWG-профили. SSH-логин, пароль, порт и host key больше никогда не включаются в пакет; credential-bearing v1 отклоняется без импорта.
- Перед любым SSH-входом self-hosted и friend-package требуют полный fingerprint `SHA256:...`, полученный независимо через консоль сервера или провайдера. Приложение читает предложенный сервером ключ без аутентификации, сравнивает его с ожидаемым и только после точного совпадения использует пароль.
- Android VPN-уведомление получило действия «предыдущий профиль», «остановить» и «следующий профиль». Быстрые нажатия объединяются, переключение сериализуется с остановкой текущего туннеля и не запускает второй native engine параллельно. Управляющие действия требуют разблокировки устройства.
- Нажатие на Android VPN-уведомление открывает защищённое компактное окно со статусом, длительностью, доступной статистикой native TUN и прокручиваемым списком профилей. Окно не показывается поверх заблокированного экрана, а URI, ключи и идентификаторы профилей не помещаются в `PendingIntent`.
- Монохромный ginger kitty используется как системный малый значок VPN-уведомления и Quick Settings; цветной значок приложения остаётся крупным значком уведомления.
- В настройках приложения появился явный выбор языка (`Системный / English / Русский`) и темы (`Системная / Светлая / Тёмная`) для Android и desktop.
- Добавлен отдельный технический план ветки `0.1.x` для расширенных VLESS/AWG, DNS, Mux, fragmentation, sniffing и безопасного серверного provisioning; функциональность `0.1.x` в эту сборку не включена.

### Изменено

- Self-hosted AmneziaWG использует закреплённый digest Docker-образа и запускает контейнер без `--privileged`: с минимальным набором capabilities, read-only `/lib/modules` и `no-new-privileges`.
- Linux хранит bundle профилей, device identity, SOCKS-учётные данные и временные engine-конфиги атомарно с правами `0700` на каталоги и `0600` на файлы.
- Перетаскивание профиля начинается только с видимой ручки после удержания около 200 мс; захваченная строка получает заметные фон, масштаб и тень. Группировка UI и сохранение порядка теперь используют один и тот же ключ подписки.
- Сохранённая светлая или тёмная тема загружается синхронно перед созданием Compose-интерфейса; Android system bars и QR-сканер используют тот же выбор.
- Поля настроек корректно завершают ввод, скрывают клавиатуру и сохраняют прокрутку на небольших Android-экранах; длинные названия профилей больше не раздвигают строку управления.
- Windows tray больше не перехватывает фокус после открытия меню, использует всю строку как цель клика и переключает только реально доступные режимы маршрутизации.
- Windows-установщик сохраняет machine-wide scope и `upgradeUuid` версии 0.0.11, чтобы обновлять существующую запись и ярлыки вместо параллельной установки. Автоматический update bundle содержит только EXE: приложение выбирает его для максимального build, проверяет HTTPS, обязательный GitHub SHA-256 и размер, запускает установщик и закрывает старый процесс. Portable ZIP остаётся отдельным ручным локальным артефактом и намеренно не распознаётся старой `0.0.11` как автообновление.
- Android updater также требует HTTPS и GitHub SHA-256, ограничивает размер ответа и APK и проверяет имя файла до передачи системному установщику.
- Обновление Android и Windows скачивается внутри приложения с прогрессом по нажатию «Скачать»; после проверки открывается локальный установщик.

### Исправлено

- Windows-установщик получает отдельный идентификатор на каждый build и заменяет старую установку той же версии (например, более ранний build `0.0.12`), сохраняя общий идентификатор семейства обновлений.
- Повторное нажатие «Скачать» не запускает вторую загрузку; окно с прогрессом нельзя случайно закрыть жестом, а отменённая при закрытии экрана загрузка не запускает установку и удаляет временный файл.
- Каждая загрузка обновления получает отдельный временный файл: отменённая операция после пересоздания Android-окна больше не может удалить файл новой загрузки.
- Android-проверка обновлений и загрузка подписок используют фактический SOCKS-порт подключённого VLESS/AWG, а не сохранённый порт olcRTC; временный адрес очищается при остановке и переподключении.
- Внутренние SOCKS-пароли приложения не передаются внешнему SOCKS-серверу, заданному вручную в профиле.
- Главный экран показывает «Подключение» или «Переподключение», пока соединение ещё устанавливается, и не выдаёт восстановление сети за состояние «Подключено».
- Android запрещает cloud/device-transfer backup данных приложения и скрывает чувствительные экраны от скриншотов и списка последних приложений.
- Отдельный Android QR-сканер также защищён от скриншотов и превью в списке последних приложений, где могли отображаться конфигурации с ключами.
- Скопированные Android-конфигурации помечаются как sensitive и через минуту удаляются из clipboard только если пользователь или другое приложение не заменили содержимое; чтение файлов на Android и desktop ограничено 16 МиБ до декодирования.
- Диагностический лог скрывает полные VPN/friend-package URI, URL подписок, credentials и secret-поля JSON/INI; для olcRTC сохраняются только host и непрозрачный идентификатор вместо пути комнаты.
- Вывод VLESS/AWG/TUN в Windows-консоль очищается до передачи как во внутренний лог, так и в stdout; повторная очистка стала идемпотентной и не портит маркеры `<redacted>`.
- APK и Windows release JAR проверяются по точному allowlist native-библиотек текущей платформы; чистый staging отклоняет лишние ABI, старые бинарники, symlink, пустые файлы и известные приватные build inputs.
- Android native-библиотеки собираются с удалением абсолютных локальных путей; APK-проверка отклоняет `.so`, раскрывающие путь рабочей копии, каталога пользователя или исходников olcRTC.
- Загрузчик Windows build-зависимостей проверяет ZIP-кэш, использует отдельные временные файлы и тайм-ауты: прерванная загрузка больше не считается готовым архивом.
- Команды Android-уведомления защищены отдельными immutable `PendingIntent`; в них не передаются URI, ключи или идентификаторы профилей, а содержимое на экране блокировки скрыто приватным public-version уведомлением.
- Устранены две гонки Android-уведомления: счётчик запросов переключения стал атомарным, а устаревшая команда больше не оставляет запущенный не-foreground экземпляр `VpnService`.
- Повторные попытки запуска olcRTC и переключения `olcRTC / VLESS / AmneziaWG` проходят через единый generation/teardown lifecycle, включая команду из шторки и Quick Settings.
- Неудачное переключение из Android-уведомления больше не оставляет выбранным неработающий профиль: сервис возвращает последний профиль, который действительно достиг состояния `Connected`.
- Команды Android-уведомления, обычный запуск, остановка и фоновые retry используют общий lifecycle generation: устаревший switch/retry не может снова запустить VPN после `Stop`, а `Connected` принимается только от точного поколения и профиля нового туннеля.
- Быстрые нажатия `Previous`/`Next` сериализованы вместе с debounce, выбранный профиль сохраняет только принявший команду VPN-сервис, а окно после поворота восстанавливает видимый индикатор продолжающегося переключения.
- Повторное нажатие Quick Settings во время `Connecting` или `Reconnecting` теперь останавливает попытку вместо отправки второго `Start`.
- Настройка и запуск Android olcRTC Runtime теперь используют ту же блокировку, что и `stop`: быстрое переключение больше не пересекает JNI-настройку нового запуска с остановкой предыдущего.
- Старое завершение Android VPN-сервиса использует Android `startId` и больше не может остановить более новую команду запуска между проверкой generation и `stopSelfResult`; отклонённое действие из заблокированной или уже останавливающейся шторки также корректно завершает пустой сервис, не затрагивая последующий настоящий `Start`.
- Окно выбора в Android-уведомлении и действия `Previous`/`Next` пропускают неполные профили, которые всё равно нельзя запустить.
- Android installer запускается из Activity без несовместимого `NEW_TASK`; отмена или ошибка не помечает обновление установленным и сразу возвращает возможность повторить. Отмена UAC Windows сбрасывает срок фоновой проверки для немедленного повторного предложения после запуска старой версии.
- Проверка Windows app-image требует не только нулевой код launcher smoke-test, но и отдельный одноразовый marker от реально запущенной JVM после проверки native assets; локальный EXE handler обязателен до закрытия старого процесса.
- Windows update bundle очищается до упаковки и проходит проверку точного имени, размера и единственного EXE-файла; падение WiX больше не оставляет старый ZIP в каталоге, который можно случайно принять за новый релиз.
- Windows integration tests принудительно используют отдельные `APPDATA`, `LOCALAPPDATA` и Local SOCKS для обоих типов профилей; тест VLESS/AWG больше не может из-за значения `Auto` попытаться изменить системный proxy ноутбука.
- Desktop DNS и системный proxy используют только проверенные абсолютные пути системных Windows-инструментов, а не исполняемые файлы из изменяемого `PATH`.
- Запуск всей Windows app-image от имени администратора запрещён: пользовательская JVM и native cores не должны исполняться с повышенными правами. До появления отдельного минимального привилегированного helper режим Windows TUN временно недоступен; остаются Local SOCKS и System proxy.
- Проверка обновлений явно сообщает, когда уже установлена самая новая версия с учётом пары `version + build`.

## [0.0.11] - 2026-09-03

Проверяемая сборка: `2026090302`.

### Добавлено

- При перетаскивании профилей захваченная за видимый drag-handle карточка следует за пальцем, соседние профили плавно освобождают место при пересечении середины строки, а после отпускания карточка мягко фиксируется в новой позиции; удержание на handle сокращено до 300 мс.
- Отдельный монотонный build-номер отображается в приложении и диагностических логах и участвует в проверке обновлений: при одинаковой версии, например `0.0.11`, build с большим номером считается более новым. Идентификатор также включается в имена релизных APK, EXE и portable ZIP и в Android `versionCode`; identity загружаемого asset учитывает GitHub digest.
- Интерфейс, динамические статусы, ошибки импорта, Android Quick Settings и VPN-уведомление автоматически показываются на русском языке при русской системной локали; для остальных локалей сохраняется английский.
- Английский текст `README.MD` полностью продублирован отдельным русским разделом после оригинала.
- Добавлен изолированный приватный серверный уведомитель: `systemd`-таймер раз в час проверяет публичные релизы оригинального olcbox и Amnezia VPN и пишет только в личный чат владельца. Токен не входит в приложение или сборочные артефакты и хранится на сервере с правами `0600`.
- Подготовлена Linux desktop-сборка в формате AppImage с локальным SOCKS5, необязательным TUN и закреплёнными native-движками olcRTC, Xray, AWG sing-box и `hev-socks5-tunnel`.
- Добавлен безопасный Linux release-helper, который без `sudo` и без вмешательства в системные сервисы получает только закреплённые commit'ы, собирает AppImage и запускает его в extract-mode для проверки JVM и bundled native assets.

### Изменено

- olcRTC и внешние профили получили независимые режимы маршрутизации: `Auto` для olcRTC всегда означает локальный SOCKS5, а для VLESS/AmneziaWG/AmneziaVPN на Windows — системный прокси без прав администратора.
- Для подключённого профиля в Windows tray появляется компактный переключатель режима: VLESS/Amnezia переключаются между системным прокси и полным `TUN (VPN)`, а olcRTC сохраняет выбор `SOCKS5 / System proxy / TUN` с приоритетом SOCKS5 в `Auto`; настройки двух групп независимы.
- Экран Windows-настроек показывает фактический режим текущего типа профиля и отдельно предупреждает о запросе прав администратора для `TUN (VPN)`.
- Встроенный Windows updater точнее находит корень запущенной app-image: после проверки архива он закрывает старый tray-процесс, заменяет launcher/JVM/native assets по тому же пути, сохраняет ярлык меню «Пуск» и запускает обновлённый Unified VPN.
- Тяжёлые HTTP-клиенты проверки обновлений и системный tray создаются после первой отрисовки окна; чтение JSON-настроек перенесено с UI-потока на IO, поэтому основное окно Windows появляется раньше и не блокируется фоновой инициализацией.
- Если один GitHub-релиз содержит несколько файлов одной версии, приложение сначала выбирает максимальный build и только затем предпочтительный формат платформы, а Windows installer и portable ZIP сопоставляются только при одинаковом build.
- Кнопки и интерактивные строки снова показывают ограниченный Material ripple, подтверждающий нажатие; полноэкранный фон QR-диалога остаётся без вспышки.
- Контекстное меню значка Unified VPN в Windows tray стало компактным и тёмным: вся высота строки кликабельна, активные пункты показывают курсор-руку, внешняя белая рамка удалена, а клик по другому окну закрывает меню.
- Tray-иконка загружается через application class loader, поэтому она не пропадает в упакованной jpackage-сборке при отложенной инициализации на AWT-потоке.
- Клиентская проверка обновлений оригинальных olcbox и Amnezia VPN удалена из APK/desktop-приложения; внутри Unified VPN проверяются только собственные выпуски.
- Удалены исходники и упаковка неподдерживаемых Apple-платформ; общий код и документация ориентированы на Android, Windows и Linux.

### Исправлено

- Повышение прав Windows для `TUN (VPN)` теперь перезапускает внешний `UnifiedVPN.exe` из `jpackage.app-path`, передаёт только безопасный служебный аргумент и дожидается живого elevated-процесса; выбор режима сначала сохраняется на диск, поэтому новая копия не возвращается в `Auto`.

- Android Xray теперь разрешает доменное имя XHTTP/SplitHTTP endpoint до установки VLESS-туннеля через отдельный IPv4 bootstrap DNS; только запросы к встроенным DNS-серверам на порту `53` направляются напрямую, поэтому bootstrap не зацикливается через ещё не подключённый VLESS outbound.
- При горячем переключении с olcRTC/AWG на VLESS внешний engine явно привязывается к выбранной физической Android-сети после полного teardown старого TUN; Xray больше не запускает DNS bootstrap в коротком промежутке без default route и не кэширует ложный `no such host`.

## [0.0.10] - 2026-09-01

### Добавлено

- Захваченная при долгом нажатии карточка профиля теперь визуально приподнимается, увеличивается и получает контрастные фон, рамку и подсветку drag-handle.
- VLESS XHTTP/SplitHTTP подключается на Android и Windows через встроенный официальный Xray `26.3.27` (`d2758a023cd7`); остальные поддерживаемые VLESS-транспорты на Android продолжают использовать sing-box.
- Строка версии в диагностическом логе содержит версию и commit Xray вместе с версиями olcRTC и AWG core.
- Закрытые интеграционные тесты могут проверять временные VLESS/AWG-профили через переменные окружения, не включая ссылки или ключи в исходники и test reports.
- Добавлен изолированный release-test: Android проверяется только в выбранном эмуляторе через принадлежащий Unified VPN TUN, а Windows-профили только через локальный SOCKS; до и после теста сверяются настройки системного прокси Windows.
- Release-test подтверждает не только запуск движка, но и реальный внешний HTTPS-трафик через временные VLESS/AWG-профили.
- Изолированный Windows olcRTC release-test проверяет `DesktopVpnManager`, authenticated SOCKS5, внешний HTTPS-трафик и штатную остановку, после чего удаляет временную копию приватных профилей.
- Сборка olcRTC принимает только отдельный чистый Git-клон точного commit `f616f57bb3a90740f1755922ffeaa7acc5cfe4ed`; путь исходника участвует во входах Gradle, а Windows-бинарник дополнительно проверяется через `go version -m`.
- Ручная проверка обновлений на Android и Windows явно сообщает, что последняя версия Unified VPN уже установлена, если более нового релиза нет.
- Android TUN для olcRTC и VLESS использует локальный sing-box DNS bridge: запросы порта `53` преобразуются в DNS-over-HTTPS и отправляются через активный upstream SOCKS, без прямого DNS-доступа в физическую сеть. AWG остаётся подключён напрямую к `tun2socks`, чтобы сохранить полноценный UDP.
- Добавлены скрипты импорта и SHA-256 provenance-проверки свежих Android JNI-библиотек для всех трёх ABI на цепочке `изолированная NDK-сборка -> Gradle CXX -> APK`.

### Исправлено

- Android и Desktop одинаково объясняют `bad record magic` и таймаут authenticated handshake как несовместимую версию olcRTC либо неработающий server peer; небезопасный fallback на старый crypto record layer не добавлялся.
- Android-сборка `libhev-socks5-tunnel.so` больше не включает конфликтующий upstream `hev-jni.c`, а приложение загружает только собственную JNI-обёртку: это устраняет `SIGABRT` из-за отсутствующего класса `hev.htproxy.TProxyService` сразу после успешного olcRTC handshake и создания TUN.
- Остановка или переключение VLESS/AmneziaWG больше не завершает приложение из-за штатного `InterruptedIOException` в потоке чтения логов sing-box.
- Android полностью останавливает частично запущенные olcRTC/VLESS/AmneziaWG engine и TUN, если запрос подключения был заменён новым профилем во время запуска.
- Выбор нового профиля запоминает необходимость перезапуска до асинхронного сохранения: переключение с ещё запускающегося olcRTC на AWG/VLESS больше не теряет запрос нового запуска и не оставляет приложение в промежуточном состоянии.
- Перетаскивание профиля начинается только с видимой drag-handle, не конфликтует с открытием профиля и последовательно сохраняет несколько быстрых перемещений.
- Android импортирует конфигурации через системный `OpenDocument`, принимает текстовые и бинарно обозначенные файлы и показывает явную ошибку вместо незаметного пустого импорта.
- Android AWG использует стандартный SOCKS5 UDP transport вместо несовместимого UDP-over-TCP режима; DNS-запросы перехватываются и отправляются через туннель, с корректной стратегией IPv4/IPv6 и TCP fallback.
- Desktop runtime находит словари `names`/`surnames` как в старом `olcrtc/data`, так и в актуальном для `f616f57` пути `internal/names/data`.
- Amnezia `vpn://` JSON больше не принимается за WireGuard INI только из-за вложенных строк `[Interface]`/`[Peer]`; исправление проверено реальным временным AWG-профилем на Android и Windows.
- Android ограничивает распакованный `vpn://`/`awg://` профиль четырьмя МиБ, как и desktop parser.
- APK-проверка требует непустые `libsing-box.so` и `libxray.so` для каждого выбранного ABI; Windows package smoke-test также требует встроенный Xray executable.
- Windows EXE/MSI собираются из уже проверенного app image и запускают WiX в verbose-режиме; это исключает повторную сборку JVM-образа внутри installer task и сохраняет точную диагностику Windows Installer ICE.
- Windows integration-runner всегда запускает Gradle с `--no-daemon`, поэтому приватные пути и выбранный профиль передаются в новый test process, а старое окружение Gradle daemon больше не может дать ложноположительный пропуск сетевого теста.
- DNS olcRTC, введённый как обычный IPv4/IPv6-адрес без порта, нормализуется в endpoint с портом `53` на Android и Windows.
- Если DNS olcRTC не задан в профиле, Android использует стабильный `1.1.1.1:53` для signaling вместо случайного DNS активной физической сети; явное значение профиля по-прежнему имеет приоритет.
- Проверка исходника olcRTC передаёт Git точный command-scoped `safe.directory` только для выбранного standalone-клона и не требует изменения глобального Git-конфига.
- Проверка задержки VLESS использует TCP до фактического endpoint с ICMP fallback, а AmneziaWG/AWG выполняет обычный системный ICMP ping до хоста вместо бессмысленной TCP-проверки UDP-порта WireGuard.
- olcRTC и VLESS больше не теряют DNS после очистки кэша или активного переключения профиля: DoH bootstrap использует фиксированный `8.8.8.8:443` с TLS-именем `dns.google`, доступный через проверенные FI_2 и VLESS upstream.
- DNS bridge сериализован с TUN и native core: он запускается до `tun2socks`, контролируется watchdog и обязательно завершается после остановки TUN, включая отменённый или частично неудачный запуск.
- Android JNI-обёртка и `hev-socks5-tunnel` пересобраны из проверенного source revision `a0180d5` с Android NDK `28.2.13676358`; итоговые APK-файлы для `armeabi-v7a`, `arm64-v8a` и `x86_64` совпадают с проверенными native outputs.
- На одном APK проверен непрерывный сценарий `FI_2 -> AWG -> VLESS -> FI_2`: PID приложения не меняется, каждый профиль достигает application-level Connected, внешние HTTPS-запросы проходят через туннель, crash-буфер остаётся пустым.

## [0.0.9] - 2026-08-25

### Исправлено

- Desktop автоматически выбирает свободный локальный SOCKS-порт, если заданный порт уже занят другим прокси (например, `xraycore` на `10808`).
- PAC-сервер для режима System proxy больше не падает с `Address already in use`: при занятом `10809` он использует свободный loopback-порт и передаёт Windows фактический PAC URL.
- Готовность olcRTC определяется только сообщением запущенного процесса, а не наличием любого SOCKS-сервера на том же порту; для sing-box сохранена проверка доступности выбранного свободного порта.
- Некорректный olcRTC `crypto.key` теперь отклоняется до запуска native-движка с понятной ошибкой о требуемых 64 hex-символах.
- Таймаут authenticated handshake после успешного Jitsi ICE/SCTP теперь сообщает, что peer сервера не запущен либо использует несовместимую версию olcRTC, вместо общего `exited before SOCKS5 was ready`.

## [0.0.8] - 2026-08-24

### Добавлено

- Windows-приложение обновляется поверх текущей папки из portable ZIP без повторного запуска MSI: отдельный updater ждёт закрытия приложения, атомарно заменяет `app`, `runtime` и launcher, проверяет native-файлы, откатывается при ошибке и запускает Unified VPN снова.
- Загруженные обновления проверяются по HTTP-статусу, размеру и SHA-256 digest из GitHub API; ZIP дополнительно защищён от path traversal, zip bomb, неполной JVM и несовпадающей версии.

### Исправлено

- Проверка original olcbox больше не завершается `HTTP 404`, когда проект не публикует GitHub Releases: приложение использует последний commit default-ветки и продолжает отдельно проверять Amnezia VPN и Unified VPN.
- На Android профиль можно перетащить долгим нажатием на всю строку; жест больше не открывает редактор, порядок сразу отражается в интерфейсе и сохраняется после перезапуска.
- Android использует `adjustResize` вместо `adjustPan`: экран не сдвигается целиком при открытии клавиатуры, а формы остаются прокручиваемыми в доступной области.
- Windows updater предпочитает portable ZIP, умеет скачивать его через активный SOCKS-прокси и удаляет ошибочный compatibility-флаг `RUNASADMIN` перед обычным перезапуском приложения.
- Устранён сценарий неполной Windows-копии после повторной установки разных сборок под одним номером версии; 0.0.8 выпускается как новый неизменяемый пакет.

## [0.0.7] - 2026-08-24

### Исправлено

- Android-приложение снова включает Java-классы и native-библиотеки актуального `olcRTC` gomobile AAR; устранён вылет `NoClassDefFoundError: mobile.Mobile` сразу после запуска VPN-сервиса.
- Сборка Android автоматически проверяет итоговые debug- и release-APK на наличие определений `mobile.Mobile`, `mobile.Runtime` и `mobile.SocketProtector`, поэтому неполный APK теперь завершает сборку ошибкой.
- Удалена регистрация отсутствующего `CaptchaActivity`, а проверенная legacy-ветка Quick Settings корректно отмечена для Android Lint.
- Тестовый debug receiver запуска VPN теперь принимает команды только от `adb`/системных процессов с разрешением `android.permission.DUMP`.
- Windows packaging больше не переиспользует runtime image предыдущей версии: версия участвует в Gradle inputs и сверяется с `UnifiedVPN.cfg` до создания EXE/MSI.
- Windows-сборка использует один совместимый набор словарей olcRTC, проверяет наличие встроенной JVM и запускает app-image с изолированными данными до создания EXE, MSI или portable ZIP.
- README указывает проверенный portable ZIP и фактически используемый Windows `tun2socks` вместо отсутствующего `hev-socks5-tunnel`.
- Android- и Windows-сборки проверяют, что `OLCRTC_REPO` находится точно на чистом commit `f616f57bb3a90740f1755922ffeaa7acc5cfe4ed`, и не позволяют пометить старый бинарник новым SHA.
- Gradle отслеживает исходники и pinned SHA для desktop olcRTC EXE, поэтому смена commit обязательно пересобирает native-бинарник.
- Финальные Android и Windows native-файлы пересобраны из `f616f57` с record layer `OLC2`; Android `gomobile` автоматически получает SDK из `local.properties`.

## [0.0.6] - 2026-08-24

### Добавлено

- Редактирование названия, типа, URI, raw-конфигурации и локального SOCKS endpoint для сохранённых `olcRTC`, VLESS, AmneziaWG и AmneziaVPN-профилей.
- Перестановка профилей долгим нажатием на drag handle с сохранением активного профиля и порядка внутри подписки.
- Сжатый формат буфера `unifiedvpn+zlib:` для надёжного копирования и вставки больших наборов профилей до 16 МБ после распаковки.
- Прокрутка экранов настроек, split tunneling, новых и существующих подключений, self-hosted и friend package на небольших экранах.
- SHA встроенного AWG core в сведениях о сборке и экспортируемых диагностических логах.
- Кнопки видимости для локального просмотра паролей, ключей, VLESS URI и raw-конфигураций; значения по умолчанию скрыты, а режим просмотра сбрасывается при закрытии формы.

### Обновлено

- `olcRTC` обновлён до `f616f57bb3a9`: отдельный `Runtime` на каждое подключение, управляемые `start/waitReady/stop`, встроенные словари имён и актуальная desktop-конфигурация.
- Совместимые изменения оригинального olcbox синхронизированы с `6e26b50a0a5a`, включая автоматический выбор DNS физической Android-сети.
- Android `hev-socks5-tunnel` обновлён до 2.17.1 с исправлениями синхронизации остановки и UDP teardown.
- AWG-совместимый Throne `sing-box` для Android и Windows обновлён до `cbe7088a0723`; конфигурация `amnezia_wg` проверяется реальным core перед релизом.
- Совместимость импорта и self-hosted сверена с AmneziaVPN `5.0.1.5` / `7d4f3e0f5090`; в этой старой сборке self-hosted image ещё не был закреплён immutable digest.

### Исправлено

- Android-переключение работающего `olcRTC` на VLESS/AmneziaWG больше не запускает второй native tunnel: JNI использует атомарное состояние, повторный stop безопасен, а новый запуск ждёт полного завершения старого потока и Go runtime.
- Убрана зависимость от удалённого глобального API `Mobile`; отмена, остановка и освобождение SOCKS-порта выполняются через актуальный экземпляр `olcRTC Runtime`.
- Микро-значок ginger kitty включён в APK и используется для VPN-уведомления и Quick Settings; цветной значок приложения остаётся large icon уведомления.
- Windows по умолчанию использует system proxy, автоматически выбирает DNS активного физического интерфейса и не требует UAC для обычного подключения.
- Повторный запуск Windows через UAC выбирает внешний `UnifiedVPN.exe`, проверяет PID повышенного процесса и не закрывает исходное окно при неудачном elevation.
- Кнопка закрытия Windows завершает приложение вместо сворачивания в трей.
- Актуальный Windows `olcrtc` и перемещённые словари `internal/names/data` включаются и проверяются при упаковке, устраняя ошибку `Bundled native binary is missing`.
- Отключён ripple-эффект, вызывавший вспышки интерфейса при нажатии кнопок.
- Редактирование профиля больше не переносит его в конец списка; перестановка не меняет активный профиль.
- Ошибки чтения большого буфера теперь показываются пользователю вместо незаметного пустого импорта.
- Пароли, private keys, токены и полные VLESS/AWG/объединённые ссылки маскируются в журнале и повторно очищаются перед сохранением или отправкой.
- Удалены неиспользуемые Apple targets и исходники; поддерживаемые платформы ограничены Android, Windows и Linux.
- Клиентская проверка обновлений оригинальных olcbox/Amnezia удалена; для владельца предусмотрен отдельный приватный серверный уведомитель.

## [0.0.5] - 2026-08-18

### Добавлено

- Обычная проверка доступности для VLESS по TCP endpoint и для AmneziaWG по адресу peer с TCP fallback.
- Разбор VLESS IPv4/IPv6 URI, WireGuard `Endpoint` и сжатых `awg://` / `vpn://` профилей для ping.
- Явная поддержка единой зашифрованной строки `unifiedvpn-friend-v1:` в меню добавления подключения.
- Desktop smoke-режим `--verify-native-assets` для проверки файлов olcRTC, sing-box, tun2socks и Wintun внутри упакованного приложения.

### Исправлено

- При переходе `olcRTC -> VLESS/AmneziaWG` Android теперь сначала останавливает и дожидается `tun2socks`, а затем останавливает olcRTC SOCKS; устранена гонка native-процессов, вызывавшая вылет приложения.
- Переключение на внешний профиль ждёт освобождения olcRTC SOCKS endpoint перед запуском нового движка.
- Windows ищет встроенные native-файлы через context/class loader, каталог jpackage и JAR classpath; ошибка `Bundled native binary is missing: native/olcrtc-windows-amd64.exe` больше не зависит от особенностей загрузчика установленной версии.
- Обновление ping в списке профилей больше не пропускает VLESS и AmneziaWG.

## [0.0.4] - 2026-08-18

### Добавлено

- Независимые настройки split tunneling для `olcRTC` и общей группы `VLESS / AmneziaWG`.
- Переключатель профиля split tunneling в Android-настройках.
- Автоматическая миграция прежнего единого списка приложений в обе новые группы.
- Новый единый значок приложения для Android и Windows, включая Play Store, окно, трей и Quick Settings.
- Воспроизводимый генератор PNG, ICO и ICNS из исходного бренд-изображения.
- Защищённый «пакет для друга» для Android и Windows: общие профили `olcRTC`, выбранная владельцем VLESS-ссылка и self-hosted AmneziaWG.
- Шифрование пакета `AES-256-GCM` с ключом из `PBKDF2-HMAC-SHA256`; пароль пакета не сохраняется и передаётся отдельно.
- Однокнопочный импорт защищённого пакета с проверкой закреплённого SSH host key и выпуском отдельного AWG peer для каждого устройства.
- Проверка стабильных релизов Unified VPN на GitHub с загрузкой подходящего APK или Windows-пакета.
- Отдельные информационные уведомления о новых релизах оригинального olcbox и Amnezia VPN без установки их файлов поверх Unified VPN.

### Исправлено

- Устранена гонка при переключении активного профиля с работающим VPN, в том числе `olcRTC -> AmneziaWG`.
- Запуск нового профиля теперь ждёт завершения отменённого запуска и предыдущей очистки transport-процессов.
- Запрещён параллельный запуск двух native-экземпляров `tun2socks`.
- Остановка VPN и смена профиля сериализованы одним mutex; native-поток получает до 10 секунд на корректное завершение.
- Сервис больше не запускает новый туннель, если предыдущий `tun2socks` не завершился.
- В журнале остановки сохраняется правильное имя активного VPN-движка.
- Номер версии синхронизирован для Android и Windows desktop.
- Исправлено двойное название `Unified VPN VPN` в Android-уведомлении; сессия, окно, трей и установщик теперь используют имя `Unified VPN`.
- Quick Settings использует монохромную микро-версию нового значка; длинные зашифрованные пакеты больше не вызывают ошибку генерации QR.
- Экспорт пакета для друга не копирует существующие VLESS/AWG-профили или AWG private key; SSH-пароль не попадает в импортируемый набор профилей.

## [0.0.3] - 2026-08-17

### Добавлено

- Self-hosted настройка AmneziaWG по IP-адресу, SSH-логину и паролю.
- SSH-провижининг сервера с установкой и возвратом готового AmneziaWG-профиля.
- Self-hosted интерфейс для Android и Windows.
- Полноценный AmneziaWG runtime на Windows и Android через AWG-совместимую сборку `sing-box`.
- Windows TUN-маршрутизация для AmneziaWG с обходным маршрутом к адресу VPN-сервера.
- Windows installer и portable ZIP с включёнными `olcrtc`, `sing-box`, `tun2socks` и Wintun.

### Исправлено

- Конфигурация AmneziaWG переведена на поддерживаемый объект `amnezia_wg`; параметры `Jc`, `Jmin`, `Jmax`, `S1-S4`, `H1-H4`, `I1-I5` больше не попадают в неподдерживаемые поля endpoint.
- AWG-совместимый Android core добавлен для `armeabi-v7a`, `arm64-v8a` и `x86_64`.
- Добавлена явная диагностика неподдерживаемого VLESS `xhttp`, вместо запуска заведомо несовместимого профиля.
- Общая SSH-логика вынесена в совместимый Android/JVM source set.

## [0.0.2] - 2026-08-17

### Добавлено

- Единая модель VPN-профилей для `olcRTC`, VLESS, AmneziaWG и Amnezia VPN.
- Импорт `olcrtc://`, `vless://`, VLESS-подписок Base64, AmneziaWG `.conf`, `awg://` и `vpn://`.
- Android runtime для VLESS и AmneziaWG через упакованный `sing-box`.
- Режимы Android TUN и локального SOCKS5-прокси с отдельными учётными данными.
- Split tunneling по списку приложений: весь трафик, только выбранные приложения или обход выбранных приложений.
- Управление подписками, ручное обновление, интервалы обновления и QR-экспорт.
- Проверка обновлений приложения, каналы обновлений и установка загруженного APK.
- Единый экспорт диагностических логов с версией приложения и commit `olcrtc`.
- Мониторинг сети, перенос на новый upstream, watchdog и автоматическое восстановление соединения.
- Desktop TUN и системный PAC-прокси для Windows, включая portable Windows-пакет.

### Исправлено

- Обработка повторного подключения и смены Wi-Fi/mobile upstream без потери состояния профиля.
- Проверки полноты импортированных профилей и сообщения об ошибках запуска движка.
- Сохранение активной локации, настроек прокси и split tunneling между запусками.

## [0.0.1] - 2026-06-02

### Добавлено

- Первая alpha-версия клиента на Kotlin Multiplatform для Android и desktop.
- Подключение через `olcRTC` с провайдерами Jazz, Telemost, WB Stream и Jitsi.
- Транспорты DataChannel, VP8 и SEI, включая параметры FPS и batch для VP8.
- Android `VpnService`, локальный SOCKS5 transport и системный TUN.
- Список локаций, выбор активной локации, редактирование и проверка доступности.
- Импорт и экспорт конфигурации, просмотр состояния подключения и базовые диагностические логи.
