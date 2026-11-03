# Changelog

Все существенные изменения Unified VPN / Olcbox фиксируются в этом файле.

## [0.1.7 / 2026110303] - Windows installer: portable copy no longer blocks updates

- Исправлен код 1603 при запущенной переносной копии UnifiedVPN. MSI проверяет
  полный путь процесса в целевом и прежнем зарегистрированном каталогах,
  включая восстановление. Переносная копия остаётся открытой и не мешает
  обновлению установленного приложения.
- EXE и MSI используют одну проверку пути процесса. Устранена ошибка при
  завершении дочернего процесса во время закрытия приложения. Для отказа из-за
  работающей установленной копии показана причина и действие для повтора.
- Настоящие транзакции отдельной тестовой семьи проверили обновление 0.1.5,
  восстановление и сохранение переносного процесса; проверка выполняется до
  удаления прежней версии. Android и остальные функции сохранены.

## [0.1.6 / 2026110301] - VLESS/XHTTP and OpenFlux shutdown

- Android и Windows используют общий генератор конфигурации Xray для VLESS:
  одинаковая обработка XHTTP `extra`, отдельных download-настроек, TLS/REALITY,
  пустых параметров и псевдонимов транспорта. На Android исправлен порядок
  повторного запуска, при котором старый процесс мог удалить новый конфиг.
- Классическое ядро OpenFlux 9 и Session 4 принимают STOP/закрытие управляющего
  канала, отменяют ожидающие операции и ограничивают завершение процесса
  1,5 секунды. Windows отменяет незавершённый запуск перед очисткой.
- В ручном профиле VK Calls добавлены кнопка создания звонка и инструкция:
  ссылка должна совпадать на сервере и в клиентах существующей подписки.
- Windows получил окно установки в цветах UnifiedVPN. Установщик различает
  новую установку, обновление и восстановление той же сборки; закрывает
  зарегистрированное приложение и проверяет его файлы перед заменой.
  Убрано требование перезагрузки из-за посторонних отложенных операций Windows;
  автоматическая перезагрузка отключена. Обновлён экран загрузки обновления.
- Уточнён [механизм локального адблока](docs/AD_BLOCKING.md). Список HaGeZi,
  кнопка обновления подписки и редакция владельца сохранены.
- Доступность редакторов Яндекса остаётся отдельным ограничением OpenFlux;
  изменение остановки не подтверждает работоспособность недоступной комнаты.

## [0.1.5 / 2026110204] - Combined build / Объединённая сборка

### Русский

- В единую версию Android и Windows объединены встроенная блокировка рекламы,
  обновлённые движки и диагностика OpenFlux, а также кнопка «Обновить подписку»
  на главном экране. Повторное обновление подписки не запускается параллельно;
  ошибка загрузки сохраняет существующие конфигурации.
- Сохранены проверка другого активного VPN перед подключением, автоматический
  выбор системного прокси или SOCKS, VPN/TUN для AWG и VLESS и исправления XHTTP
  с отдельным адресом загрузки и параметрами `extra`.
- На Windows при подключении OpenFlux доступно видимое окно проверки Яндекса.
  Страница просмотра файла распознаётся отдельно и сопровождается подсказкой
  обновить подписку или получить рабочую ссылку редактора.
- Известное ограничение: получение конфигурации старого редактора Яндекса
  подтверждено в одной живой попытке, повтор завершился тайм-аутом. Устойчивое
  соединение OpenFlux через новые документы и новый редактор Volga ещё не
  подтверждено. Подробнее: [проверка OpenFlux](docs/testing/openflux-editor-diagnostics-20260929.md).

### English

- Combined Android and Windows packages include local ad blocking, the updated
  OpenFlux engines and diagnostics, and the home-screen subscription refresh
  button. Existing external VPN checks, automatic proxy selection, AWG/VLESS
  TUN support and XHTTP options/download endpoint fixes are retained.
- Windows foreground OpenFlux connections can show the Yandex verification
  window. File viewers produce an actionable editor-link error.
- Known limitation: one live classic-editor bootstrap succeeded, but a repeat
  timed out. Stable OpenFlux traffic with the new documents and Volga remains
  unverified. This build does not imply that the Yandex verification issue is
  fully resolved.

## [0.1.5 / 2026110203] - Local candidate / Локальная сборка

### Русский

- Добавлен встроенный фильтр рекламных и следящих доменов для Android и Windows.
  Он включён по умолчанию; выключатель находится в настройках подключения и
  применяется после переподключения. Используется закреплённый локальный список
  HaGeZi Multi LIGHT с проверкой SHA-256 и лицензией в ресурсах приложения.
- Рекламные DNS-запросы получают NXDOMAIN, известные рекламные домены блокируются
  в локальном SOCKS-мосте. Весь разрешённый трафик использует прежний VPN-движок;
  разделение маршрутов по странам не добавлялось. HTTPS не расшифровывается.
- Пройдены JVM/Android host-тесты и проверки настоящего sing-box с подставным
  upstream: домены, поддомены, похожие разрешённые имена, DNS TCP/UDP и остановка.
  Старый приватный VLESS fixture возвращает ошибку HTTPS как с фильтром, так и
  без него; успешная живая передача через него не заявляется. Физический Android
  ещё не проверен. Подробности: [блокировка рекламы](docs/AD_BLOCKING.md).

### English

- Added opt-out local ad/tracker domain filtering to Android and Windows using
  a bundled, integrity-checked HaGeZi Multi LIGHT snapshot. DNS blocks return
  NXDOMAIN; allowed traffic retains the existing VPN upstream. No country split
  routing, HTTPS interception, or new server configuration is introduced.
- JVM/Android host checks and real sing-box loopback tests pass. The old private
  VLESS fixture fails HTTPS with filtering enabled and disabled; no live end-to-end
  or physical Android pass is claimed for this local candidate.

## [0.0.14 / 2026101301] - 2026-10-13

### English

- Added an experimental VK Calls provider with VP8 and Chrome-compatible DTLS, using a separate pinned olcRTC fork at `403edd409011`. Existing Jitsi native libraries and the Android AAR retain `d7a00da5242f`.
- Android VK Calls uses the external-engine lifecycle, authenticated loopback SOCKS and the existing olcRTC split-tunneling group. Cancellation and Stop terminate only the owned process and remove its private temporary configuration. Windows uses a separate native executable and the existing olcRTC startup retry path.
- Build packaging verifies VK Calls source revision and artifact hashes for Windows and three Android ABIs. No private rooms or keys are bundled. Signaling URLs and credentials are redacted from startup errors as well as logs; Android certificate failures are not retried.
- Local verification passed: 410 JVM tests, 304 Android host tests and four native Go suites. The final packaged Windows manager/core and exact signed release APK both reached application-level Connected through a separate permanent VK service and returned HTTPS 200 from Instagram and Wikipedia. Android API 35 TUN/UID routing, clean Stop, complete emulator shutdown, Windows JVM/native-assets and MSI policy checks passed. Nine Android core hashes, three unchanged Jitsi AAR library hashes and all 251 portable files were verified. Host VPN/proxy/routes were preserved. Physical Android 13, direct Megafon whitelist operation, long-duration stability, sustained speed and in-place MSI installation remain untested.
- Publication uses the checked APK/MSI/manual ZIP, build-specific checksums, corresponding native sources and notices for 226 native dependency versions. Private-profile/credential scanning found no private data in the selected source or application archives. All 18 updater tests passed, including newer MSI selection over the historical EXE and no repeat update for the same build. Historic release files and tags are retained; the newer source snapshot is `build-2026101301`.

### Русский

- Добавлен экспериментальный провайдер VK Calls с VP8 и Chrome-совместимым DTLS: используется отдельный форк olcRTC `403edd409011`. Библиотеки Jitsi и Android AAR сохраняют ядро `d7a00da5242f`.
- На Android VK Calls работает через отдельный процесс, локальный SOCKS с авторизацией и существующую группу раздельного туннелирования olcRTC. Отмена и остановка завершают только собственный процесс и удаляют временный файл с ключом. Windows использует отдельный исполняемый файл и существующие повторные попытки запуска olcRTC.
- При упаковке проверяются версия исходников и хеши ядер для Windows и трёх Android ABI. Приватные комнаты и ключи не включаются. Ссылки сигналинга и учётные данные скрываются как в журнале, так и в ошибках запуска; Android не повторяет попытки при недоверенном сертификате.
- Пройдены 410 JVM-тестов, 304 Android host-теста и четыре набора Go-тестов. Manager и ядро итогового Windows-пакета, а также точный подписанный release APK подключились через отдельную постоянную VK-службу и получили HTTPS 200 от Instagram и Wikipedia. Проверены TUN/UID-маршрутизация API 35, штатная остановка, полное завершение эмулятора, JVM и встроенные ядра Windows, правила MSI. Сверены девять Android-ядер, три неизменившиеся библиотеки AAR Jitsi и все 251 файл portable. Рабочие VPN, прокси и маршруты ноутбука сохранены. Физический Android 13, прямое подключение при белых списках Мегафона, длительная стабильность, скорость и установка MSI поверх рабочей версии ещё не проверены.
- Для публикации используются проверенные APK/MSI/ручной ZIP, отдельные контрольные суммы, соответствующие исходники ядер и уведомления для 226 версий зависимостей. Проверка выбранных исходников и архивов приложения не обнаружила приватных профилей и учётных данных. Все 18 тестов апдейтера пройдены: новая MSI выбирается вместо старого EXE, одинаковая сборка не предлагается повторно. Прежние файлы и теги сохранены; новые исходники отмечены `build-2026101301`.

## [0.0.14 / 2026101102] - Local Test Build / Локальная тестовая сборка

### English

- Integrated the separate pinned OpenFlux 0.5.0 Session engine into Android and Windows for experimental Telemost and Mail.ru profiles. Classic Yandex profiles retain their existing engine and wire format. Mail.ru still requires live validation with a working link.
- Added bounded raw-DEFLATE `openflux://v1/` import/export, case-sensitive Session secrets, transport selection and manual editing. A compatibility fixture comes from upstream's pinned Go sharing implementation. Direct, plaintext and legacy-negotiation fallbacks are not accepted.
- Oversized Session profile names are rejected by sharing without throwing an exception.
- Session profiles start without the Yandex Docs browser verification window. Connected requires an authenticated Session and the local SOCKS listener; Stop requests graceful native shutdown before bounded termination. Diagnostics identify both bundled OpenFlux cores. Android uses the existing OpenFlux/olcRTC split-tunneling group; Windows Telemost uses local SOCKS or system proxy, not TUN.
- The two earlier native Telemost tests do not establish stable reconnects or a throughput guarantee: intermittent `TIE-0003` and stalled transfers remain known limitations. This build is for local user testing, not a GitHub release. No private rooms, credentials or user profiles are bundled.
- Local verification passed: 120 JVM tests, 139 Android host tests, five Go suites and 31 installer-policy tests. The final packaged Windows manager/core reached authenticated Connected on Telemost-01, returned HTTPS 200 from Instagram and Telegram, and stopped cleanly. The exact signed release APK reached Connected on Telemost-02 in an isolated API 35 emulator, with verified TUN/UID routing, HTTPS 200 from Instagram and Wikipedia, and clean Stop. An earlier Android attempt required a retry. Physical Android 13, sustained throughput, Mail.ru and an in-place Windows install remain untested. Host AWG/proxy/routes/DNS were preserved; the test emulator was stopped. Six Android core hashes and all 251 portable files were verified.

### Русский

- В Android и Windows добавлено отдельное ядро OpenFlux 0.5.0 Session для экспериментальных профилей Телемоста и Mail.ru. Старые профили Яндекс Документов сохраняют прежнее ядро и протокол. Mail.ru ещё требует проверки с рабочей ссылкой.
- Добавлены импорт и экспорт `openflux://v1/` с ограничением размера распаковки, сохранение регистра ключа Session, выбор транспорта и ручное редактирование. Совместимость проверяется на примере ссылки из закреплённой Go-версии upstream. Прямой и незашифрованный обход, а также переход на старое согласование сеанса запрещены.
- Слишком длинное имя профиля Session при экспорте отклоняется без исключения.
- Профили Session запускаются без окна проверки Яндекс Документов. Состояние «Подключено» требует подтверждённого зашифрованного сеанса и локального SOCKS; при остановке сначала отправляется команда штатного завершения ядра. Журнал показывает версии обоих ядер OpenFlux. Android использует общую группу раздельного туннелирования OpenFlux/olcRTC; Телемост в Windows работает через локальный SOCKS или системный прокси, без TUN.
- Прежние проверки двух профилей отдельным ядром не гарантируют устойчивое переподключение или скорость: периодические `TIE-0003` и остановки передачи остаются известными ограничениями. Это локальная сборка для проверки пользователем, а не релиз на GitHub. Приватные комнаты, ключи и профили в приложения не включаются.
- Пройдены 120 JVM-тестов, 139 Android host-тестов, пять наборов Go-тестов и 31 тест правил установщика. Manager и ядро итогового Windows-пакета подключились к Telemost-01, получили HTTPS 200 от Instagram и Telegram и штатно остановились. Точный подписанный release APK подключился к Telemost-02 в изолированном эмуляторе API 35: подтверждены TUN, маршрутизация UID, HTTPS 200 от Instagram и Wikipedia и остановка. В более ранней Android-попытке потребовался повтор. Физический Android 13, длительная скорость, Mail.ru и установка Windows поверх рабочей версии ещё не проверены. AWG, прокси, маршруты и DNS ноутбука сохранены; тестовый эмулятор остановлен. Проверены шесть хешей Android-ядер и все 251 файл portable.

## [0.0.14 / 2026101101] - In Development / В разработке

### English

- Prepared a separate configuration model for encrypted upstream OpenFlux Session profiles using Telemost or Mail.ru. It retains the exact encryption secret/context, requires strict negotiation, rejects direct/unencrypted fallbacks and config injection, binds client SOCKS to loopback, and records Telemost's desktop TUN restriction. Existing UnifiedVPN classic profiles and their engine pin are unchanged.
- Added a separate pinned OpenFlux 0.5.0 build with two regression-tested fixes: application destination DNS goes through the encrypted tunnel with IPv4-only results, and a saturated retransmission counter prevents the negative-shift panic observed after a Telemost client disconnected. The existing classic engine is not replaced. The transport, tunnel, SOCKS and sharing Go suites pass; focused classic/Session configuration tests pass on JVM and Android host.
- Both separately provisioned Telemost peers passed encrypted Windows-core connections and verified HTTPS, including Instagram HTTP 200, without altering the active host VPN, proxy or routes. Short 2 MB transfers measured approximately 1.4 Mbit/s, not a sustained-throughput guarantee. These native-core tests do not establish Android or UnifiedVPN application support; Mail.ru has not been live-tested.
- Reconnect checks are not a release pass: one Telemost peer subsequently stalled a transfer and received WebSocket closure `TIE-0003` on new attempts. Restarting only that experimental exit restored authenticated HTTPS and a full 2 MB download. The recurrence/recovery issue remains open; no automatic server restart workaround or change to existing VPN services was introduced.
- This is integration groundwork, not released transport support: importing upstream links through the official parser, packaging the second core, runtime/status handling, Android socket protection and end-to-end app validation remain required. No new APK/EXE is released. Private provisioning data and test credentials are not included in the repository.

### Русский

- Подготовлена отдельная модель конфигурации зашифрованных OpenFlux Session-профилей для Телемоста и Mail.ru. Ключ и контекст шифрования сохраняются без преобразований; обязательна строгая проверка сеанса, запрещены прямые и незашифрованные резервные подключения и внедрение настроек через поля. Клиентский SOCKS доступен только локально; учтено ограничение Телемоста для desktop TUN. Старые профили UnifiedVPN и версия их ядра не изменены.
- Добавлена отдельная сборка закреплённой версии OpenFlux 0.5.0 с двумя исправлениями и регрессионными тестами: DNS для адресов приложений проходит через зашифрованный туннель и выбирает IPv4; защита счётчика повторных отправок предотвращает падение из-за отрицательного сдвига после отключения клиента Телемоста. Старое ядро не заменяется. Пройдены Go-тесты транспорта, туннеля, SOCKS и ссылок, а также целевые тесты конфигураций classic/Session на JVM и Android host.
- Оба отдельно созданных профиля Телемоста прошли зашифрованное подключение Windows-ядром и проверенный HTTPS, включая HTTP 200 от Instagram, без изменения рабочего VPN ноутбука, прокси и маршрутов. Короткие загрузки по 2 МБ показали примерно 1,4 Мбит/с; это не гарантия устойчивой скорости. Проверка отдельного ядра не подтверждает работу на Android или внутри UnifiedVPN; Mail.ru в сети не проверен.
- Повторные подключения пока не проходят критерии релиза: у одного профиля остановилась передача, а следующие попытки получили закрытие WebSocket `TIE-0003`. После перезапуска только этого экспериментального сервиса восстановились зашифрованный HTTPS и полная загрузка 2 МБ. Причина повторного сбоя и устойчивое восстановление ещё требуют исправления; автоматический перезапуск сервера как обходной способ не добавлялся, существующие VPN-службы не менялись.
- Это подготовка интеграции, а не выпущенная поддержка транспортов: ещё нужны импорт через официальный парсер, упаковка второго ядра, управление процессом и состоянием, защита Android-сокетов и проверки соединений в приложениях. Новые APK/EXE не опубликованы. Приватные конфиги и тестовые ключи в репозиторий не включены.

## [0.0.14 / 2026100802] - Local Candidate / Локальный кандидат

### English

- Android no longer closes the whole verification window solely because an exact `hdrc.yandex.net` or `mdd.yandex.net` HTTPS subresource has invalid TLS. The request is still cancelled; the main document, cookie/metadata validation and encrypted peer readiness must independently succeed. Unknown hosts and main-document TLS failures remain fatal. No certificate trust is added. The Android host suite passes 287 tests; recovery on the physical phone is not yet confirmed.
- Classic Yandex now completes the Engine.IO polling/session upgrade before sending editor authorization, with a direct-WebSocket fallback that waits for the server's OPEN packet. Adapted from upstream OpenFlux PR #115 with retained attribution, bounded reads/timeouts, cancellation cleanup and the verified browser identity. TLS and encrypted peer protocols remain unchanged.
- Classic Yandex also waits for the Socket.IO namespace acknowledgment and the actual successful editor authorization before publishing the session or starting its persistent writer. Early peer packets, reads and authorization time are bounded; cancellation closes unpublished sockets. A deterministic regression reproduces the premature send in the old code, and the focused race suite passes 20 repetitions.
- Added strict/lenient handshake, malformed packet, cookie/identity, cancellation and real transport-callsite regression tests. A live classic27 attempt still received an initial WebSocket 1005 closure, then passed editor authorization but timed out waiting for its encrypted peer. Neither that closure nor classic27 connectivity is confirmed fixed. The previously built Android diagnostic APK remains build 2026100801.
- The physical phone's 2026100801 log confirms `tls_untrusted` for the `mdd` resource. The signed, non-debuggable 2026100802 APK contains the new resource handling and native handshake changes; its three ABI hashes and unchanged signing certificate/olcRTC libraries were verified. A separate Windows classic43 test with the final native candidate reached Connected in 16.45 seconds and passed authenticated SOCKS plus verified HTTPS to two sites. It still required an internal retry and does not establish classic27 recovery or sustained throughput.
- The exact signed 2026100802 APK passed visible browser verification, encrypted readiness, application Connected, TUN/UID routing and HTTPS 200 from both test sites in an isolated API 35 emulator. The final packaged Windows 0802 manager/core also reached Connected in 13.40 seconds and passed the same traffic checks via authenticated local SOCKS. Stop, cleanup and preservation of the active host AWG all passed. Local APK, EXE and portable ZIP are built; Windows JVM/native-assets, installer-policy and 251 portable-file checks passed. These classic43 results do not cover the user's Android 13/classic27 combination. No release was published.

### Русский

- Android больше не закрывает всё окно проверки только из-за ошибки TLS у вспомогательного HTTPS-ресурса `hdrc.yandex.net` или `mdd.yandex.net`. Сам запрос по-прежнему отменяется; основной документ, проверка cookies и параметров, а затем готовность зашифрованного узла должны завершиться успешно независимо. Ошибки TLS основного документа и неизвестных узлов остаются критическими. Доверие сертификатам не расширяется. Пройдены 287 Android host-тестов; восстановление работы на физическом телефоне ещё не подтверждено.
- Старый транспорт Яндекса завершает открытие сессии и переключение Engine.IO до отправки авторизации редактора. Резервное прямое WebSocket-подключение ожидает пакет OPEN от сервера. Подход адаптирован из OpenFlux PR #115 с сохранением авторства, ограничениями чтения и времени, очисткой при отмене и сохранением проверенной браузерной сессии. TLS и зашифрованный протокол между узлами не меняются.
- Старый транспорт также ждёт подтверждение пространства Socket.IO и успешную авторизацию редактора, прежде чем публиковать сессию или запускать постоянную отправку. Ограничены ранние пакеты, объём чтения и время авторизации; отмена закрывает ещё не опубликованный сокет. Регрессионный тест воспроизводит преждевременную отправку в старом коде; проверка гонок прошла 20 повторов.
- Добавлены тесты строгого и обычного рукопожатия, некорректных пакетов, cookies, параметров браузера, отмены и вызова из настоящего транспорта. В живой попытке classic27 сохранилось начальное закрытие WebSocket 1005; затем авторизация редактора прошла, но зашифрованный ответ узла не пришёл. Устранение ни этого закрытия, ни проблемы подключения classic27 пока не подтверждено. Ранее собранный диагностический Android APK остаётся сборкой 2026100801.
- Журнал физического телефона со сборкой 2026100801 подтвердил `tls_untrusted` у ресурса `mdd`. В подписанный APK 2026100802 без отладки включены новая обработка ресурсов и изменения рукопожатия ядра; проверены три ABI, неизменность подписи и библиотек olcRTC. Отдельный Windows-тест classic43 с итоговым ядром дошёл до «Подключено» за 16,45 секунды и передал проверенный HTTPS-трафик к двум сайтам через SOCKS с авторизацией. Внутренняя повторная попытка всё ещё потребовалась; восстановление classic27 и устойчивая скорость этим тестом не подтверждены.
- Именно подписанный APK 2026100802 прошёл видимую браузерную проверку, готовность зашифрованного соединения, «Подключено», маршрутизацию TUN/UID и HTTPS 200 от двух тестовых сайтов в отдельном эмуляторе API 35. Итоговый Windows-пакет 0802 также дошёл до «Подключено» за 13,40 секунды и передал проверенный трафик через локальный SOCKS с авторизацией. Остановка, очистка и сохранность работающего AWG ноутбука подтверждены. Собраны локальные APK, EXE и portable ZIP; пройдены проверки JVM, встроенных ядер, правил установщика и 251 файла portable. Результаты classic43 не охватывают сочетание Android 13 и classic27 пользователя. Публикации не было.

## [0.0.14 / 2026100801] - Unreleased Diagnostics / Неопубликованная диагностика

### English

- The user's classic27 profile still fails on both platforms in build 2026100702; earlier classic43 test passes do not cover it. Android fails at browser preflight; Windows reaches native startup and then its initial readiness deadline. No claim of repaired classic27 connectivity is made by these diagnostic changes.
- Android release logs now retain allowlisted browser TLS, WebView, HTTP and metadata-validation categories across the private verification process. Windows records accepted browser responses and browser timeout/failure paths. URLs, keys, cookies and arbitrary page/error text remain excluded.
- OpenFlux preserves the initial readiness timeout stage while distinguishing cancellation and queued browser errors. The server supervisor forwards the exact safe browser-unavailable marker instead of silently discarding it. Encryption, certificate verification and routing policies are unchanged.
- Classic Yandex connections emit bounded, fixed-category lifecycle diagnostics. `auth_accepted` requires an actual successful editor response, not merely a sent frame. A live classic27 Windows test reached editor authorization but still timed out waiting for its encrypted peer; an isolated VPS document-only test failed at browser bootstrap. These results do not establish a complete fix.

### Русский

- Профиль пользователя classic27 по-прежнему не подключается на обеих платформах в сборке 2026100702; прежние успешные тесты classic43 на него не распространяются. Android останавливается на предварительной проверке браузера, Windows запускает ядро и затем достигает таймаута готовности. Эти диагностические изменения не означают, что связь classic27 исправлена.
- Релизный журнал Android сохраняет разрешённые категории ошибок TLS, WebView, HTTP и проверки параметров при обмене с приватным процессом проверки. Windows отмечает принятие ответа браузера, таймаут и отказ. Адреса, ключи, cookies и произвольный текст страниц или ошибок не выводятся.
- OpenFlux сохраняет этап таймаута начальной готовности, отличая его от отмены и уже полученных ошибок браузера. Серверный supervisor передаёт точный безопасный маркер недоступности браузера, который раньше отбрасывался. Шифрование, проверка сертификатов и правила маршрутизации не менялись.
- Для старого редактора Яндекса добавлены ограниченные фиксированные метки этапов подключения. `auth_accepted` означает полученное подтверждение редактора, а не просто отправку кадра. Живой Windows-тест classic27 прошёл авторизацию редактора, но не дождался зашифрованного ответа сервера; отдельная проверка документа на VPS завершилась ошибкой браузера. Полное исправление этими результатами не подтверждается.

## [0.0.14 / 2026100702] - Local Tested Candidate / Локально проверенный кандидат

### English

- Corrected two classic-editor metadata compatibility errors observed in a live browser test: Yandex balancer hostnames containing underscores and the editor's internal `localhost:12701/disk/` file reference. The internal reference is forwarded unchanged to the remote editor, never fetched locally; document navigation and TLS verification are not relaxed.
- Android metadata validation now uses strict UTF-8 byte limits and rejects document fragments and malformed Unicode consistently with the native core.
- On October 8, the packaged Windows client passed two fresh OpenFlux connections, authenticated SOCKS, verified HTTPS traffic and complete process cleanup. The exact signed Android APK passed visible verification, Connected, TUN routing, HTTPS traffic and Stop in an isolated API 35 emulator. The user's active AWG and host network settings were preserved. Physical-phone testing, direct carrier throughput and unattended server restart recovery remain unverified; nothing was published. See [test results and limits](docs/testing/openflux-editor-diagnostics-20260929.md#october-8-build-2026100702-live-results).

### Русский

- Исправлены две ошибки совместимости параметров старого редактора, подтверждённые живой браузерной проверкой: подчёркивания в имени балансировщика Яндекса и внутренняя ссылка редактора `localhost:12701/disk/`. Эта ссылка передаётся удалённому редактору без изменений и никогда не загружается локально; ограничения переходов и проверка TLS не ослабляются.
- Проверка параметров Android использует строгие ограничения размера UTF-8 и отклоняет фрагменты адреса документа и некорректный Unicode согласованно с ядром.
- 8 октября Windows-клиент из готового пакета дважды прошёл новое подключение OpenFlux, авторизацию SOCKS, проверенный HTTPS-трафик и полную очистку процессов. Итоговый подписанный APK прошёл видимую проверку, «Подключено», маршрутизацию TUN, HTTPS-трафик и остановку в изолированном эмуляторе API 35. Работающий AWG и сетевые настройки ноутбука сохранены. Физический телефон, прямая скорость через оператора и автоматическое восстановление сервера после перезагрузки ещё не проверены; публикации не было. См. [результаты и ограничения](docs/testing/openflux-editor-diagnostics-20260929.md#october-8-build-2026100702-live-results).

## [0.0.14 / 2026100701] - Unreleased Source Candidate / Неопубликованный кандидат

### English

- OpenFlux wrapper 8 adds a bounded, short-lived classic-document handoff from the verified browser to the native client. The document URL, nonce, write permission and permitted HTTPS endpoints are validated; raw page contents are not forwarded. Cookie-only bootstrap remains supported. This does not change the encrypted wire protocol.
- Android gains a user-initiated verification window before native startup, opened from the app or a notification action. A one-use result belongs to one connection attempt and upstream network; stopping or switching invalidates it. Native startup no longer has to wait for an invisible user challenge.
- Server browser contract 3 forwards the same validated snapshot and rejects a page navigation during capture. Existing running server profiles are not upgraded automatically. Live connection and restart checks are still required before release; no GitHub publication is included.

### Русский

- Обёртка OpenFlux 8 передаёт ядру ограниченный по размеру и сроку действия снимок параметров старого редактора из проверенного браузера. Проверяются адрес документа, nonce, право редактирования и допустимые HTTPS-адреса; содержимое страницы не передаётся. Прежний обмен только cookies остаётся допустимым. Зашифрованный протокол между клиентом и сервером не меняется.
- На Android добавлено открываемое пользователем окно проверки до запуска ядра: из приложения или кнопкой уведомления. Одноразовый результат привязан к попытке подключения и исходной сети; остановка и переключение его аннулируют. Ядру больше не нужно ждать невидимую пользователю проверку.
- Серверный браузерный контракт 3 передаёт тот же проверенный снимок и отклоняет смену страницы во время его получения. Работающие серверные профили автоматически не обновляются. До релиза необходимы живые проверки подключения и повторного запуска; публикация на GitHub не выполняется.

## [0.0.14 / 2026100601] - Unreleased Source Candidate / Неопубликованный кандидат

### English

- OpenFlux anonymous browser bootstrap now carries the verified browser's User-Agent with its nonce-bound cookies into native HTTPS and WebSocket requests. Cookie-only replies remain accepted by the new core; new helpers require the matching new core. Encryption, profile keys, TLS verification and system trust stores are unchanged.
- Added malformed-header, legacy-response and forwarding regression tests across Go, Kotlin, Windows helper and server tools. This source change is not yet a verified APK/EXE release or a claim that CAPTCHA and throughput issues are solved.
- Android browser verification now waits for the first navigation instead of rejecting a cold WebView's initial missing URL. Disallowed destinations and TLS errors still fail closed. APK packaging preserves the already-stripped OpenFlux executable bytes for exact artifact verification.
- Independently provisioned and verified additional Jitsi profiles using separate rooms and keys. Both passed packaged Windows manager/SOCKS/HTTPS/Stop and signed Android 2026092901 emulator TUN/HTTPS/Stop; existing FI2 and the user's active AWG were preserved. Private profiles are not release assets. See [test scope](docs/testing/olcrtc-fi3-2026092702.md).
- Local Windows candidate 2026100601 transferred real HTTPS traffic through OpenFlux after refreshing the isolated test server's anonymous session, but a subsequent launch failed at browser re-verification. Signed Android 2026100601 still times out at Yandex browser verification before the tunnel; this is not a cross-platform OpenFlux release. Short transfers do not establish sustained throughput. See [diagnostic evidence and remaining gates](docs/testing/openflux-editor-diagnostics-20260929.md).

### Русский

- Анонимная проверка браузера OpenFlux теперь передаёт его User-Agent вместе с привязанными к nonce cookies в HTTPS- и WebSocket-запросы ядра. Новое ядро принимает и прежние ответы только с cookies; новые помощники требуют соответствующего нового ядра. Шифрование, ключи профилей, проверка TLS и системные хранилища доверия не изменены.
- Добавлены регрессионные тесты некорректных заголовков, прежнего формата ответа и передачи данных в Go, Kotlin, Windows-помощнике и серверных инструментах. Это изменение исходников, а не проверенный релиз APK/EXE и не заявление об устранении CAPTCHA или проблем скорости.
- Android-проверка браузера теперь ожидает первую навигацию вместо ошибки на изначально пустом URL холодного WebView. Запрещённые адреса и ошибки TLS по-прежнему отклоняются. Упаковка APK сохраняет байты уже очищенного от отладочных символов OpenFlux для точной проверки артефактов.
- Созданы и проверены дополнительные Jitsi-профили с отдельными комнатами и ключами. Оба прошли менеджер Windows из готового пакета/SOCKS/HTTPS/остановку и подписанный Android 2026092901 в эмуляторе/TUN/HTTPS/остановку; исходный FI2 и активный AWG пользователя сохранены. Приватные профили не входят в файлы релиза. См. [границы проверки](docs/testing/olcrtc-fi3-2026092702.md).
- Локальный Windows-кандидат 2026100601 передал реальный HTTPS-трафик через OpenFlux после обновления анонимной сессии отдельного тестового сервера, но следующий запуск завершился ошибкой повторной проверки браузера. Подписанный Android 2026100601 пока останавливается по таймауту на проверке Яндекса до создания туннеля; это не готовый OpenFlux-релиз для обеих платформ. Короткие загрузки не подтверждают устойчивую скорость. См. [результаты диагностики и оставшиеся проверки](docs/testing/openflux-editor-diagnostics-20260929.md).

## [Unreleased Server Tools / Неопубликованные серверные инструменты] - 2026-09-29

### English

- Added an explicit `manual-once` bootstrap option for a new, isolated OpenFlux browser profile. A root-only Unix socket accepts one nonce-, document-, owner- and native-process-bound anonymous cookie response. Cookies stay in memory; no new TCP port, account login or TLS bypass is introduced. Existing profiles retain automatic bootstrap.
- The socket's private runtime directory belongs to its own systemd service. The admin manifest and unit receipt bind the selected mode; pre-existing runtime paths are never adopted. This is an operator-assisted startup option, not unattended CAPTCHA recovery or a client APK/EXE update.

### Русский

- Добавлен явный режим `manual-once` для нового изолированного серверного профиля OpenFlux. Локальный Unix-сокет, доступный только root, принимает одну анонимную сессию браузера с привязкой к nonce, документу, владельцу и native-процессу. Cookies остаются в памяти; новый TCP-порт, вход в аккаунт и обход TLS не добавляются. Существующие профили сохраняют автоматический запуск.
- Приватным временным каталогом сокета управляет только его служба systemd. Выбранный режим закреплён в манифесте и контрольной сумме unit; существующие каталоги не присваиваются. Это запуск с участием администратора, не автоматическое решение CAPTCHA и не обновление APK/EXE.

## [0.0.14 / 2026092902] - Local Debug Diagnostic / Локальная debug-диагностика

### English

- Added fixed-category Android OpenFlux browser diagnostics for TLS resources and robot-verification pages. Logs do not include document URLs, page contents or cookies; TLS handling and trust stores are unchanged.
- The isolated API 35 Volga test still stopped at browser verification before an authenticated tunnel. This debug-only build is not a connectivity fix or a published release. Browser policy host tests and diagnostic page-state checks passed; signed build 2026092901 remains unchanged.

### Русский

- Добавлена диагностика Android-браузера OpenFlux с фиксированными категориями TLS-ресурсов и страниц проверки на робота. Ссылки документов, содержимое страниц и cookies в журнал не выводятся; обработка TLS и хранилища доверия не изменены.
- В изолированном тесте API 35 Volga по-прежнему останавливается на проверке браузера до готовности зашифрованного туннеля. Эта debug-сборка не является исправлением подключения или опубликованным релизом. Тесты браузерной политики и классификации страниц прошли; подписанная сборка 2026092901 не изменена.

## [0.0.14 / 2026092901] - Local Android Candidate / Локальный Android-кандидат

### English

- Fixed Android's supplemental olcRTC TLS root handoff: libc `setenv` did not update the embedded Go runtime's environment. The fingerprint-verified GlobalSign R46 root is now configured directly in Go before certificate loading, and effective trust is checked. System root directories, hostname verification and expiry checks remain enabled.
- Added focused certificate/bootstrap regression tests. The local Go shim is built as a versioned, checksum-pinned module from a local file proxy so native build metadata does not expose the developer's absolute workspace path. Upstream files, server rooms and VPN protocols are unchanged.
- Debug and exact signed-release APKs separately passed FI_2 Connected, Android TUN, Instagram/Wikipedia HTTPS and clean Stop in an isolated API 35 emulator. On September 29 the user also confirmed connection and working Internet on their physical Android 13 phone. This candidate is not published. See [results and limits](docs/testing/android-olcrtc-tls-2026092901.md).

### Русский

- Исправлена передача дополнительного TLS-сертификата в olcRTC на Android: вызов libc `setenv` не обновлял окружение встроенного Go. Проверенный по отпечатку корень GlobalSign R46 теперь передаётся непосредственно в Go до загрузки сертификатов, после чего проверяется фактическое доверие к нему. Системные корни, проверка имени сайта и срока действия сохраняются.
- Добавлены регрессионные тесты сертификатов и инициализации. Локальная Go-обёртка собирается как версионированный модуль с закреплённой контрольной суммой из локального файлового proxy, чтобы метаданные библиотеки не содержали абсолютный путь рабочей папки разработчика. Исходники upstream, серверные комнаты и VPN-протоколы не менялись.
- Debug и итоговый подписанный release APK отдельно прошли подключение FI_2 до «Подключено», Android TUN, HTTPS к Instagram/Wikipedia и корректную остановку в изолированном эмуляторе API 35. 29 сентября пользователь также подтвердил подключение и работающий интернет на физическом Android 13. Кандидат не опубликован. См. [результаты и ограничения](docs/testing/android-olcrtc-tls-2026092901.md#русский).

## [0.0.14 / 2026092704] - Unreleased / Не опубликовано

### English

- Windows installation/repair detects a running UnifiedVPN before replacing files and asks the user to close it. The guard does not terminate applications or change their VPN connections. A pending file-replacement reboot also blocks replacement until Windows is restarted.
- Old-product removal is scheduled after installation initialization and validation. The compiled MSI policy is checked for guard conditions and execution order.
- Windows app-image verification requires the Java module image and every declared classpath JAR. Investigation of a silent installed-app failure found missing runtime files; the complete 2026092703 portable package opened its real window successfully. In-place upgrade validation of this new candidate is still pending.
- This packaging fix does not change VPN cores or claim higher throughput. No release has been published.

### Русский

- Перед заменой файлов установщик Windows проверяет, закрыта ли UnifiedVPN. Если приложение запущено, установка просит закрыть его; защита не завершает процессы и не меняет VPN-соединения. Ожидающая перезагрузки замена файлов также блокирует установку до перезапуска Windows.
- Удаление предыдущей версии перенесено после инициализации установки и проверок. В готовом MSI проверяются условия защиты и порядок выполнения.
- Проверка Windows-пакета требует наличие модулей Java и всех JAR из конфигурации запуска. При диагностике молчаливого сбоя установленного приложения обнаружены пропавшие файлы; полная portable-копия 2026092703 успешно открыла настоящее окно. Проверка обновления установленной версии новым кандидатом ещё не завершена.
- Исправление упаковки не меняет VPN-ядра и не заявляет прирост скорости. Релиз не опубликован.

## [0.0.14 / 2026092703] - Unreleased / Не опубликовано

### English

- Classic OpenFlux batching now drains ready packets before starting the linger timer. Its input queue has a 2 MiB byte budget, including packets being collected or sent, instead of a small packet-count-only limit. This addresses verified backlog/overflow cases without changing encryption, legacy profiles or Volga.
- Windows briefly drains pending core diagnostics after an early process exit, so a delayed certificate/configuration failure is not replaced by a generic retryable error. Waiting is bounded and remains cancellable.
- Added byte-budget, ordering, stop/cancellation and burst regression checks, including Go race tests. Build 2026092702 measured 13.548 Mbit/s on Windows and 13.068 Mbit/s through Android emulator TUN; neither those results nor the offline tests establish 20 Mbit/s for this candidate.

### Русский

- Пакетирование классического OpenFlux сначала забирает готовые пакеты, затем запускает таймер ожидания. Входная очередь ограничена 2 МиБ с учётом собираемых и отправляемых пакетов вместо небольшого ограничения только по их количеству. Это исправляет проверенные случаи накопления и переполнения очереди, не меняя шифрование, старые профили или Volga.
- Windows ограниченно дочитывает диагностику быстро завершившегося ядра: запоздавшая ошибка сертификата или конфигурации больше не заменяется общей ошибкой с лишними повторами. Ожидание ограничено по времени и поддерживает отмену.
- Добавлены проверки лимита байтов, порядка, остановки/отмены и всплесков нагрузки, включая Go race-тесты. Сборка 2026092702 показала 13,548 Мбит/с на Windows и 13,068 Мбит/с через TUN Android-эмулятора; эти результаты и тесты без сети не подтверждают 20 Мбит/с для нового кандидата.

## [0.0.14 / 2026092702] - Unreleased / Не опубликовано

### English

- Added the explicit OpenFlux `batch-zstd-v2` codec for classic Yandex Docs, with bounded batching and codec-specific TCP tuning. AES-256-GCM and authenticated readiness remain mandatory. Legacy LZ4 stays the default for existing profiles; Volga does not silently switch codecs.
- Profile editing, sharing, runtime configuration and the server admin wizard preserve the selected codec. Both ends must use matching codec settings; native wrapper 6 is required for batch mode.
- Includes the Android certificate and Windows olcRTC startup-retry fixes from build 2026092701. This is a local candidate, not a published release or a guarantee of 20 Mbit/s on a mobile network.

### Русский

- Добавлен явный кодек OpenFlux `batch-zstd-v2` для классических Яндекс Документов: ограниченные очереди пакетирования и отдельные параметры TCP. AES-256-GCM и проверка готовности зашифрованного соединения обязательны. Старые профили по умолчанию используют прежний LZ4; Volga не переключается на другой кодек автоматически.
- Редактирование, передача профилей, конфигурация ядра и серверный мастер сохраняют выбранный кодек. Значения на клиенте и сервере должны совпадать; пакетному режиму требуется native-обёртка 6.
- Включены исправления сертификатов Android и повторных подключений olcRTC на Windows из сборки 2026092701. Это локальный кандидат, а не опубликованный релиз и не гарантия 20 Мбит/с в мобильной сети.

## [0.0.14 / 2026092701] - Unreleased / Не опубликовано

### English

- Android prepares the fingerprint-verified public GlobalSign R46 root before loading the Go runtime, supplementing its system CA directories. This addresses the observed Jitsi `certificate signed by unknown authority` error on older Android trust stores without disabling certificate, hostname or expiry checks.
- Windows retries temporary olcRTC startup failures up to three times, with bounded backoff and a separate process/readiness state for each attempt. Stop or a different profile cancels pending retries; invalid keys, incompatible protocols and certificate errors do not trigger retries.
- Added certificate integrity, retry-limit and cancellation tests. The olcRTC core, wire protocol, server configuration and OpenFlux codec are unchanged. Local validation is in progress; this is not a published release.
- Validation: 366 JVM and 239 Android host tests passed. A separate live Windows manager test recovered after a handshake timeout and passed HTTPS/stop; an intermediate Android debug APK passed TUN/HTTPS in an API 35 emulator. Exact final package and physical-phone checks remain pending. See [results and limits](docs/testing/olcrtc-startup-2026092701.md).

### Русский

- Android подготавливает проверенный по отпечатку публичный корень GlobalSign R46 до загрузки Go, дополняя системные каталоги сертификатов. Это исправление наблюдавшейся ошибки Jitsi `certificate signed by unknown authority` на старых хранилищах Android; проверки сертификата, имени сайта и срока действия не отключаются.
- Windows автоматически повторяет временно неудачный запуск olcRTC до трёх раз, с ограниченной задержкой и отдельным процессом/состоянием готовности каждой попытки. Остановка или выбор другого профиля отменяет повторы; неправильные ключи, несовместимый протокол и ошибки сертификата повторно не запускаются.
- Добавлены тесты целостности сертификата, лимита повторов и отмены. Ядро olcRTC, протокол обмена, конфигурация сервера и кодек OpenFlux не менялись. Локальные проверки продолжаются; сборка не опубликована.
- Проверки: прошли 366 JVM и 239 Android host-тестов. Отдельный реальный Windows-тест восстановился после тайм-аута handshake и прошёл HTTPS/остановку; промежуточная debug APK прошла TUN/HTTPS в эмуляторе API 35. Точная итоговая упаковка и физический телефон ещё требуют проверки. См. [результаты и ограничения](docs/testing/olcrtc-startup-2026092701.md#русский).

## [OpenFlux Admin Tools 1] - Unreleased / Не опубликовано

### English

- Added a separate, admin-only server wizard for a fresh device name, explicit Yandex editor, and hidden document URL. It generates an independent key and private Unified VPN import file; existing documents and installations are not reused.
- Added pinned browser/runtime artifacts, frozen helper copies, automatic collision-checked subnet allocation, and an owned persistent systemd service. Preparation stays offline; installation requires explicit confirmation. Rollback checks resource ownership, unit identity and locking without replacing existing VPN services.
- Tightened Docker inspection errors: an inaccessible daemon or failed inspection is no longer treated as an absent resource. Added regression tests for duplicate document aliases, partial installation, foreign resources, changed helpers and interrupted cleanup.
- This is a server-tool update, not a replacement of the published 0.0.14 / 2026092406 APK or EXE. See the [admin instructions](tools/openflux-server/ADMIN.md).
- Validation on September 25: 291 local tests passed; the focused VPS suite ran 159 with one optional JavaScript skip. A fresh wizard-created Volga profile passed encrypted traffic and stop on the unchanged packaged Windows client and released APK in an Android API 35 emulator. The new service passed an explicit restart; no host reboot was performed. See [results and limits](docs/testing/openflux-admin-20260925.md).

### Русский

- Добавлен отдельный админский мастер на сервере: новое имя устройства, явный выбор редактора Яндекса и скрытый ввод ссылки. Он создаёт независимый ключ и приватный файл импорта Unified VPN; существующие документы и установки повторно не используются.
- Добавлены закреплённые браузерные/runtime-артефакты, отдельные копии помощников, автоматический выбор подсети с проверкой пересечений и собственная постоянная служба systemd. Подготовка выполняется без сети, установка требует явного подтверждения. Откат проверяет владельца, службу и блокировки, не заменяя существующие VPN-службы.
- Ошибка доступа к Docker или неудачная проверка больше не считаются отсутствующим ресурсом. Добавлены тесты повторных ссылок на документ, частичной установки, чужих ресурсов, изменённых помощников и прерванной очистки.
- Это обновление серверного инструмента, а не замена опубликованных APK и EXE 0.0.14 / 2026092406. См. [инструкцию администратора](tools/openflux-server/ADMIN.md#русский).
- Проверка 25 сентября: 291 локальный тест прошёл; на VPS выполнены 159 с одним необязательным JS-пропуском. Новый созданный мастером Volga-профиль прошёл зашифрованный трафик и остановку неизменённой Windows-сборки и релизной APK в эмуляторе Android API 35. Перезапуск новой службы проверен, весь VPS не перезагружался. См. [результаты и ограничения](docs/testing/openflux-admin-20260925.md#русский).

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
