# OpenFlux Runtime Validation, Build 2026092405

Later checks and deployment on the new server are recorded in the
[2026-09-24 connectivity report](new-server-connectivity-20260924.md).
The restored-server state below describes the earlier trial, not that later
deployment. Its Windows repeat and Android checks did not pass.

Последующее развёртывание на новом сервере описано в
[отчёте от 24.09.2026](new-server-connectivity-20260924.md).
Восстановленный исходный сервер ниже относится к предыдущему испытанию.
После нового развёртывания повтор Windows и проверка Android не прошли.

## English

Local candidate: **0.0.14 / 2026092405**, OpenFlux base `d34dc8c`, wrapper 4.
No GitHub publication, user-app replacement or permanent server migration is
claimed. The earlier [2026092401 report](openflux-upstream-2026092401.md) is a
historical checkpoint, not the current runtime result.

### Changes And Checks

| Check | Result |
| --- | --- |
| Go transport, Yandex, session and tunnel tests | PASS; Yandex additionally repeated 20 times |
| Server-tool tests on Windows and isolated offline Linux | 227 PASS on each platform |
| systemd-analyze verify | PASS, no warnings |
| JVM tests | 344 PASS |
| Android host tests | 226 PASS |
| Android harness synthetic checks | 120 PASS |
| New server document-only browser handoff | PASS; original container unchanged |
| Packaged Windows DesktopVpnManager and bundled native identity | PASS |
| Windows encrypted Ready, SOCKS5, Instagram and Telegram HTTPS | PASS; HTTP 200 for both, strict certificate validation |
| Windows 25-second HTTPS liveness, stop and owned-process cleanup | PASS |
| Windows host proxy, DNS, routes and existing processes | Unchanged during the client test |
| Android fresh API 35 x86_64 guest, first build-2405 trial | FAIL before TUN: browser-verification timeout |
| Android build-2405 repeat in another fresh guest | FAIL at the same browser-verification stage, before TUN |
| Physical Android 13, server reboot, sustained load | Not tested |

The Android change selects the legacy desktop editor while keeping the installed
Chromium version. Cookies are exported separately for exact HTTPS Disk and Docs
origins and imported as host-only cookies. Tests cover redirects, duplicate
aliases, account-cookie refusal and absence of cookies at unrelated/subdomain
destinations. No user browser profile is read. TLS checks and encryption remain
mandatory. A browser success is not counted as tunnel success.

Earlier Android attempts identified two different stages: the mobile page did
not provide editable configuration; a desktop-page attempt did, but the native
retry still requested verification. Build 2405 adds the scoped two-origin
handoff. Its first live attempt timed out before that handoff could be verified.
The repeat failed at the same stage. Both guests were shut down by verified
ownership. The cause of that timeout has not yet been established; no TLS,
sandbox or cookie-scope restriction was relaxed to obtain a passing result.

### Server Recovery

`browser_lifecycle.py` installs receipt-bound runtime, recovery and timer units.
Trials are bounded to 600 seconds and require fresh authenticated tunnel/DNS/HTTPS
proof before committing. No proof is manufactured from process uptime.

Fault injection uncovered a restricted-unit namespace preflight failure and a
normal SIGTERM incorrectly recorded as runtime failure. After fixing both,
killing only the verified trial controller restored the exact old container,
restart policy and running state in **7.21 seconds**. The candidate stopped and
the managed runtime became disabled/inactive. The same recovery test on the
final cookie-fix server candidate passed in **17.207 seconds** after the real
Windows test and two Android failures. No traffic proof was written; the
original server was restored and the new runtime remains disabled/inactive.
Reboot recovery is covered by
unit logic tests but has not been exercised on the live VPS.

The working Jitsi path, AWG and Xray were not targeted. Do not infer global
firewall equivalence from that scope: a later metadata comparison found a
different normalized IPv4 rules hash with the same rule count. Its cause was
not established; IPv6 and routes matched. Docker bookkeeping can change when
test endpoints are attached. No host firewall-reset command was issued.

### Artifacts

Native artifacts: `.downloads/openflux/artifacts-d34-cookies-20260924`.
These hashes identify the exact local candidate, not a published release.

| Artifact | SHA-256 |
| --- | --- |
| Android debug APK | `c354d250846159318ea04319782379151c4ec134feb69cc87e5516eea9d5e41e` |
| Windows native | `97261770f82971f3cb181d9acea47390d3452e75a30dda0a5f8bf34a95d1852c` |
| Linux native | `c6e3fabb09ce5105da424ec4b6c6f9f2185a8801d90716a8a1ddd6f068b4afa1` |
| Source archive | `19279a9209140d42db7368b064c8a4aaf8427f21426f512ff02fc831ea0573a2` |

The APK is in `androidApp/build/outputs/apk/debug/androidApp-debug.apk`.
The Windows app image is in
`desktopApp/build/compose/binaries/main-release/app/UnifiedVPN`.
Neither path implies a tested installer or an Android release candidate.
Private receipts remain in the ignored, ACL-restricted test directory. They
are not copied into this report; document links, keys and cookies are omitted.

### Remaining Gate

Next isolate Android document verification from server traffic. Record only
fixed navigation/error categories and cold-start timing, then distinguish
WebView loading/lifecycle behavior from a native cookie retry failure. Do not
change TLS, scope cookies more broadly or extend timeouts without evidence.
The release gate remains authenticated Android TUN plus HTTPS from a separate
guest UID, followed by stop/cleanup; a browser-only pass cannot waive it.

## Русский

Локальная сборка: **0.0.14 / 2026092405**, база OpenFlux `d34dc8c`, обёртка 4.
Публикации на GitHub, замены установленных приложений и постоянного перевода
сервера пока нет. Отчёт 2026092401 сохраняет результаты предыдущего этапа.

Прошли Go-тесты четырёх пакетов (Yandex дополнительно 20 раз), по 227 серверных
тестов на Windows и в изолированном Linux, проверка systemd, 344 JVM-теста,
226 Android host-тестов и 120 проверок Android-стенда.

Новый сервер получил доступ к документу через отдельный браузер. Упакованный
Windows-клиент подтвердил версию и встроенное ядро, установил зашифрованный
OpenFlux и загрузил Instagram и Telegram через SOCKS5 с ответами HTTP 200 и
обычной проверкой сертификатов. Повторная HTTPS-проверка через 25 секунд,
остановка и очистка прошли. Прокси, DNS, маршруты и прежние процессы Windows
во время этого теста не изменились.

Первая проверка Android-сборки 2405 в чистом API 35 x86_64 завершилась тайм-аутом
браузерной проверки, до создания TUN. Повторная проверка в другом чистом
эмуляторе дала тот же результат. Оба тестовых эмулятора остановлены с проверкой
принадлежности. Физический Android 13, перезагрузка VPS и длительная нагрузка
не проверялись. Успешную сборку или открытие документа нельзя считать
доказательством работоспособности VPN.

Android теперь запрашивает старый настольный редактор. Cookies точных хостов
Disk и Docs передаются раздельно, без расширения на родительские домены и
без чтения пользовательского браузерного профиля. Ссылки, ключи, TLS,
AES-256-GCM и прежний LZ4 сохраняются. Предыдущий тест получил настройки
редактора, но native-повтор снова потребовал проверку; исправление передачи
cookies в 2405 пока не подтверждено сквозным Android-тестом.

При испытании отказа исправлены проверка namespace внутри ограниченной
systemd-службы и ложная ошибка при обычном SIGTERM. После SIGKILL проверенного
контроллера исходный контейнер и его политика запуска восстановились за
**7,21 секунды**. Повтор этого испытания на финальном серверном кандидате после
Windows-успеха и двух Android-сбоев прошёл за **17,207 секунды**. Кандидат
остановился, исходный сервер восстановлен, новый runtime отключён. Файл
подтверждения трафика не создавался, постоянной миграции не было. Это не заменяет
реального испытания после перезагрузки сервера. Пробный запуск ограничен
600 секундами; фиксация требует настоящего зашифрованного трафика, DNS и HTTPS.

Рабочие Jitsi, AWG и Xray не были целью изменений. Позднее сравнение метаданных
выявило другой нормализованный хеш IPv4-правил при прежнем числе правил;
причина не установлена. IPv6 и маршруты совпали. Команд сброса firewall не было.

Пути и хеши артефактов приведены выше. Windows app image не означает готовность
установщика; debug APK не означает готовность Android-релиза. Приватные
результаты остаются в игнорируемом каталоге с ограниченным ACL. Ссылок
документов, ключей и cookies в этом отчёте нет.

Следующий этап: отдельно проверить загрузку документа на Android, фиксируя
только категории навигации/ошибок и длительность холодного старта. Нужно
отличить поведение WebView от ошибки передачи cookies ядру. Увеличение
тайм-аутов или ослабление TLS и области cookies без доказательств не является
исправлением. Для выпуска нужны зашифрованный Android TUN, HTTPS из отдельного
процесса эмулятора и корректная остановка, а не только успех браузера.
