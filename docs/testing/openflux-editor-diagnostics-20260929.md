# OpenFlux Editor Diagnostics, 2026-09-29

## Status

This is a local diagnostic report, not a release acceptance report. No GitHub
release was created. No account credentials were used. Private document links,
profile keys, cookies, addresses and raw page content are intentionally omitted.

The user confirmed AWG as the protected Windows connection and enabled the new
Yandex editor for the tests below. Editor selection affects this account's
documents collectively; replacing one link does not independently change its
editor protocol. No active AWG, classic production OpenFlux, olcRTC or other
production service was restarted by these tests.

## Results

| Check | Observed result | What it does not prove |
| --- | --- | --- |
| New-editor HTTPS bootstrap | `vyandex` returned `OPENFLUX_DOCUMENT_OK`; `yandex` did not accept the new-editor configuration. | VPN handshake or useful traffic. |
| Windows native client, unused test profile | Authenticated AES-256-GCM readiness in 10.504 s; Instagram and Wikipedia HTTPS 200. | Android compatibility or target throughput. |
| Packaged Windows `DesktopVpnManager`, local build 2026092704 | Connected in 12.531 s, both HTTPS checks 200, manager stopped cleanly. | A newer desktop package, system proxy/TUN, or an installer test. |
| Packaged Windows 1 MiB download | Completed in 17.1208 s, approximately 0.49 Mbit/s. | Sustained bandwidth or the requested 10-20 Mbit/s. |
| Signed Android build 2026092901, API 35 emulator | Three attempts failed before native readiness and TUN activation, in browser verification; no app crash observed. | Behavior of the user's physical Android 13 device. |
| Diagnostic Android debug build 2026092902 | One attempt: page finished, 77 polls, `Verification` page category, zero blocked requests, no TLS callback checkpoint. | Exact challenge type, broken TLS callback, or a proven WebView-version regression. |
| Fresh anonymous Windows Chrome through the existing VPS egress | The separate second document reached the new-editor configuration. Browser closed and cookies discarded. | Durable server startup or client connectivity. |
| Cold disposable server native with that browser's anonymous cookies | One nonce-bound cookie response, `OPENFLUX_DOCUMENT_OK`, exit 0 in 1.37 s. Container and temporary directory removed. | An encrypted tunnel, persistent recovery, or a working second production profile. |

The cold native check used a throwaway key and `--check-document`, not a live
VPN client. It shared only the exact owned test container's network namespace.
It did not attach stdin to an existing native process, modify an existing
configuration, or store cookies in files. It had independent process deadlines,
resource limits and ownership-checked cleanup. All 13 offline protocol and
cleanup tests passed before the live run.

Windows proxy, routes, DNS, protected listeners and active VPN process
fingerprints matched before/after the completed live tests. The Android emulator
used for the tests was stopped. These checks do not constitute a general
security audit or a proof that every network condition is covered.

## Android Findings

The bootstrap WebView is created by `OpenFluxBrowserVerificationService`, not
attached to a visible activity. It cannot present a challenge for the user to
complete. The diagnostic page category recognizes robot/CAPTCHA text without
logging page content, URLs or cookies. Text classification alone does not
identify the particular challenge.

Chromium logged certificate error `-202`, but the diagnostic attempt did not
invoke `onReceivedSslError`. Consequently, changing that callback would not be
an evidence-based fix for this attempt. Its behavior remains `cancel()` and
fail closed; no certificate-validation bypass or new Android trust anchor was
added. The emulator's WebView was 124.0.6367.219; that version alone does not
establish the cause.

A possible follow-up is an explicitly user-opened verification screen in the
same isolated browser process. It must retain navigation/account/cookie/TLS
restrictions, support cancellation, and coordinate deadlines with native
bootstrap. A visible page is not an automatic CAPTCHA solver. This UI is not
implemented or claimed as fixed by debug 2026092902.

The current app deadline is 45 s and the native cookie-response deadline is
50 s. A safer interactive design would verify before starting a replacement
native client, then consume a one-use, short-lived in-memory result bound to
the exact document, profile, connection generation and Android Network. An
unexported Activity would be opened by an explicit user action, without URLs
or cookies in notification/Intent extras. Stop, profile/network changes,
expiration and process death must invalidate the result. This requires a
coordinated Binder/UI/engine change, not merely a longer WebView timeout.

The separate Android olcRTC certificate fix and its signed APK results remain
documented in [the olcRTC report](android-olcrtc-tls-2026092901.md). Success there
must not be presented as success of OpenFlux.

### Signed Android Candidate 2026100601

On October 6, one application-level attempt used the separately owned classic43
profile in a fresh API 35 x86_64 emulator. The exact signed release candidate was
`0.0.14 / 2026100601`, APK SHA-256
`32ce6dbef248a5a0a6b07a5259f8ca4c9bc1c7674a168d27b42d5ee8ed19bc93`.
It is staged locally at
`.downloads/local-artifacts/0.0.14-build.2026100601/UnifiedVPN-0.0.14-build.2026100601-android.apk`.

The build and native/privacy gates passed, as did all 19 focused Android browser
policy tests and 10 adapter tests. All three packaged OpenFlux ABI hashes match
the wrapper7 manifest exactly. The olcRTC AAR and all three packaged `libgojni.so`
files match the working signed 2026092901 baseline; the pinned public R46
certificate is present. This is artifact provenance, not a fresh Jitsi test.

The test exercised UI Start and Android VPN consent after same-signer private
storage staging. Installed APK hash, signer and non-debug release state were
verified; UI link import and the physical phone were not tested. Browser
verification timed out before authenticated READY, Connected or TUN. No external
HTTPS probe was attempted. The app stopped cleanly and was uninstalled; both
owned emulator process identities were subsequently confirmed absent. All five
host fingerprints matched before emulator startup and after final cleanup.
Ignored receipt: `vps-preflight-android-classic43-wrapper7-e444ae0c3b0a7c2b285a3571`.

The release does not emit the debug-only WebView checkpoints. A subsequent,
separately authorized single debug attempt used a fresh owned emulator and the
exact same-build debug APK, SHA-256
`27538e3af0ec6cab0cbdf7d6e6a9f192cb00ce6397dd62dfbb00a5d896f8cbc0`.
It collected only fixed stage names, bounded counters and page-state categories
from the app UID, including the separately observed `:openflux_verification`
process. No raw logs, document URLs, cookies, account data or JavaScript dumps
were saved. The provider was `com.google.android.webview / 124.0.6367.219`.

The final checkpoint was `view=true`, `page=true`, `polls=65`,
`state=Verification`, `blocked=0`, followed by `response_failed` and browser
timeout. Thus initial navigation and JavaScript callbacks did complete, but
Yandex verification did not. This does not identify the exact challenge or
prove visual rendering from the unattached WebView. Authenticated READY and
external HTTPS were not reached/tested; a successful diagnostic exit status is
not VPN success. TLS remained fail-closed. The app was stopped and removed,
both owned emulator process identities were confirmed absent, and all five
preboot-to-final-cleanup host fingerprints matched. No automatic retry followed.
Ignored diagnostic receipt:
`vps-preflight-android-classic43-debug-5868b0c3b8aae3cad1236f9d`.

### Подписанный Android-кандидат 2026100601

6 октября выполнена одна попытка classic43 в новом отдельном эмуляторе API 35
x86_64 на точном подписанном APK `0.0.14 / 2026100601`; путь и SHA-256 указаны выше.
Сборка, проверки упаковки и приватности, 19 тестов политики WebView и 10 тестов
адаптера прошли. Все три ABI OpenFlux совпадают с manifest wrapper7 побайтно;
olcRTC AAR, `libgojni.so` и публичный корень R46 сохранены относительно рабочей
сборки 2026092901. Это проверка состава APK, не новое подключение Jitsi.

Проверены UI Start, системное разрешение VPN, подпись, SHA установленного APK и
отсутствие debug-режима. Профиль переносился через приватное хранилище debug с той
же подписью, затем устанавливался release; импорт ссылки через интерфейс и
физический телефон не проверялись. Браузерная проверка завершилась тайм-аутом до
READY, Connected и TUN. Проверка внешнего HTTPS не запускалась. Приложение
остановлено и удалено, оба процесса собственного эмулятора завершены; все пять
показателей сети ноутбука совпали до запуска и после полной очистки.

Release не выводит отладочные этапы WebView. После него выполнена одна отдельно
разрешённая debug-попытка в новом собственном эмуляторе на APK той же сборки;
SHA-256 указан выше. Собраны только фиксированные этапы, ограниченные счётчики и
категории страницы из UID приложения, включая подтверждённый отдельный процесс
`:openflux_verification`. Сырые журналы, URL, cookies, данные аккаунта и JavaScript
dump не сохранялись. Версия провайдера: `com.google.android.webview / 124.0.6367.219`.

Итоговый checkpoint: `view=true`, `page=true`, `polls=65`,
`state=Verification`, `blocked=0`, затем `response_failed` и тайм-аут. Начальная
навигация завершилась, JavaScript отвечал, но проверка Яндекса не закончилась.
Это не устанавливает точный тип проверки и не доказывает визуальную отрисовку
WebView, не прикреплённого к окну. READY не получен, внешний HTTPS не проверялся;
успешное завершение диагностического скрипта не означает успешный VPN. Приложение
остановлено и удалено, оба собственных процесса эмулятора завершены; все пять
показателей хоста совпали до загрузки эмулятора и после полной очистки. Повторная
автоматическая попытка не выполнялась. Проверка TLS не ослаблялась; этот кандидат
не принят как рабочий Android-релиз.

## Classic Editor Follow-Up

The user subsequently restored the classic editor while keeping the protected
AWG connection on the laptop. Cold HTTPS-only bootstrap of the second document
with a fresh anonymous same-egress browser session passed in 1.93 s. The
existing second server profile still failed the packaged Windows connection
test; a running service was not treated as proof of a working tunnel.

A read-only inventory checked all 12 existing installation directories and
confirmed that a third document was unused. An initial diagnostic failure was
the inspector applying a deployment-only mode-0600 restriction to a historical
root-owned mode-0644 configuration. The inventory now validates its ownership,
parents, links, size and full schema without altering permissions. Deployment
and owned-runtime secret-file checks remain strict.

A disposable server used this third document and a fresh key, with one
nonce-bound anonymous browser session supplied over pinned SSH. The exact
packaged Windows manager from build 2026092704 reached authenticated Connected
in 11.690 s, returned verified HTTPS 200 from Instagram and Wikipedia, completed
a 1 MiB download in 6.936 s (approximately 1.21 Mbit/s including setup), and
stopped cleanly. This small transfer is not a sustained-throughput benchmark.

The temporary server was independently limited to 240 s. Its container and
private configuration directory were removed; proxy, DNS, routes, protected
listeners and active VPN process fingerprints were unchanged. This proves the
bounded Windows path, not durable server recovery or Android OpenFlux success.
The temporary profile must not be issued as a permanent working link.

### Persistent Third-Document Follow-Up

A separate persistent third-document server was created and its private profile
exported. Acknowledgment of the nonce-bound anonymous-cookie handoff confirms
delivery, not authenticated peer readiness. It is not a verified working profile
and must not be issued as one.

| Exact application test | Observed result |
| --- | --- |
| Packaged Windows build 2026092704, `DesktopVpnManager` | Did not reach Connected; no successful HTTPS tests. `context deadline exceeded` followed the message that anonymous browser verification was starting; the precise timed-out substage is not established. Manager stopped and all five protected host fingerprints were unchanged. |
| Signed Android build 2026092901, fresh API 35 emulator | Two attempts, neither reached authenticated readiness, Connected, TUN or HTTPS. Attempt 1 reported `Browser verification timed out`; attempt 2 reported `Browser verification failed`. Both reported `Yandex browser verification did not complete`. |

The Android installed APK hash and signer were verified; the release was
non-debuggable. Profile transfer used private storage under a same-signer debug
install followed by the exact signed release. UI Start and Android VPN consent
were exercised; UI link import was not. There was no observed application crash.
The application stopped cleanly, was uninstalled from the guest, and the owned
emulator was stopped. All five host fingerprints matched both during cleanup and
after emulator shutdown. No further live attempts were made.

Local ignored receipts: Windows
`.downloads/private-test-profiles/vps-preflight-packaged-codec-76c36a9c937fe6d79b6873e0/result.json`
and Android
`.downloads/private-test-profiles/vps-preflight-android-classic-doc3-a023fb17bb0ec42566924e39/result.json`.
The Android adapter's 18 offline tests passed. Those unit tests and a successful
cookie-delivery acknowledgment do not override either failed live result. The
earlier temporary Windows success remains valid only for that temporary setup.

Read-only inspection of the new server found its service active/running with
zero unit/container restarts, no OOM, one owned bridge network, and no remaining
manual-bootstrap socket. Its journal contained only the safe status
`SERVER_WAITING` once. The native logging driver was `none` and the Docker log
query failed, so the absence of logged errors is not evidence of completed
bootstrap or a working tunnel. The service remains active/enabled for diagnosis;
the existing two production profiles were not changed. The inspection changed
no services and all five host fingerprints matched. Ignored receipt:
`.downloads/private-test-profiles/vps-preflight-1ceef903218648d55bf06a06/result.json`.

## Upstream Context

[Issue 116](https://github.com/p1neappleXpress/OpenFlux/issues/116) reports Android
handshake timeouts with two nodes. Its issue body does not establish a common
cause or demonstrate a fix for our browser-verification failure.

[Issue 106](https://github.com/p1neappleXpress/OpenFlux/issues/106) and the
[upstream CAPTCHA notes](https://github.com/p1neappleXpress/OpenFlux#captchas)
describe browser verification and exit-IP session concerns. The cold native test
above independently demonstrates a same-egress cookie handoff for this test
document, but not an unattended production solution.

## Remaining Gates

1. Classic-editor cold bootstrap and a bounded Windows tunnel test have passed.
2. Establish a recoverable server-side bootstrap path before issuing a second
   profile as production-ready. Do not restart a working server merely to
   discard its current in-memory session.
3. Verify authenticated readiness, real HTTPS traffic, disconnect/reconnect and
   recovery on both packaged Windows and signed Android builds.
4. Test the physical Android device separately if emulator challenge behavior
   differs. Do not weaken TLS or use account cookies as a shortcut.

## Русский

Это локальный диагностический отчёт, не подтверждение готовности релиза.
Публикации на GitHub не было. Логин и пароль Яндекса не использовались;
ссылки, ключи, cookies и содержимое документов в отчёт не включены.

- На Windows новый редактор (`vyandex`) прошёл шифрованное подключение и
  реальные HTTPS-запросы через `DesktopVpnManager`. Загрузка 1 МиБ заняла
  17,1208 с: примерно 0,49 Мбит/с, значительно ниже целевой скорости.
- На Android подписанная сборка 2026092901 не прошла браузерную проверку
  в трёх попытках. Отдельная debug-сборка 2026092902 показала страницу
  проверки робота до получения конфигурации редактора. VPN/TUN не запустился.
- WebView проверки сейчас невидимый: пользователь не может пройти
  интерактивную проверку. Видимый экран с явным действием пользователя
  рассматривается как доработка, но пока не реализован.
- В Chromium есть ошибка сертификата `-202`, однако callback TLS приложения
  в диагностической попытке не вызывался. Его изменение не подтверждено
  результатами теста. Проверка сертификатов не отключалась.
- Свежий Windows-браузер через тот же внешний IP VPS получил анонимную сессию
  документа. Новое серверное ядро приняло её через приватный stdin и прошло
  проверку доступа за 1,37 с. Это проверка HTTPS-загрузки документа, не VPN.
- Временный контейнер и файлы удалены. Рабочий AWG, настройки Windows и
  действующие серверные службы не менялись. Эмулятор остановлен.

После возврата старого редактора холодная проверка второго документа прошла
за 1,93 с, но его существующая серверная служба ещё не обеспечила подключение.
На свободном третьем документе отдельный временный сервер со свежей анонимной
сессией прошёл полный Windows-тест: Connected за 11,690 с, HTTPS 200 от Instagram
и Wikipedia, 1 МиБ за 6,936 с, около 1,21 Мбит/с с учётом установки соединения.
Это короткий замер, не доказательство постоянной скорости.

Временный контейнер и его конфигурация удалены, рабочий AWG и настройки
ноутбука сохранились. Этот тест ещё не доказывает восстановление постоянной
службы и работу OpenFlux на Android; временную ссылку нельзя выдавать как
готовый второй профиль. Исправление Android olcRTC пользователь отдельно
подтвердил на своём телефоне: подключение и интернет работают.

Затем создан отдельный постоянный сервер на третьем документе и приватно
экспортирован профиль. Подтверждение передачи анонимных cookies означает только
получение ответа, не готовность VPN. На Windows build 2026092704 точный
`DesktopVpnManager` не дошёл до Connected: после сообщения о начале браузерной
проверки появился `context deadline exceeded`, успешных HTTPS-запросов нет.
Точный подэтап таймаута этим журналом не установлен.

На подписанном Android build 2026092901 обе попытки в отдельном эмуляторе API 35
остановились до Connected/TUN/HTTPS: сначала таймаут браузерной проверки, затем
ошибка проверки. SHA установленного APK, подпись и отсутствие debug-режима
проверены. Запуск из интерфейса и системное разрешение VPN проверены; вставка
ссылки через интерфейс не проверялась, профиль переносился через приватное
хранилище и установку release поверх debug с той же подписью.

Приложение остановлено и удалено из тестового эмулятора, сам эмулятор остановлен.
Все пять показателей защиты рабочего AWG сохранились, включая повторную проверку
после остановки эмулятора. 18 offline-тестов адаптера прошли, но это не успех
живого подключения. Постоянный профиль пока не выдаём как рабочий; прежний успех
временного Windows-сервера не переносим на постоянный сервер или Android.

Read-only проверка нового сервера показала active/running, ноль перезапусков
службы и контейнера, отсутствие OOM и одну собственную bridge-сеть. Сокет ручной
проверки уже закрыт. В безопасном журнале только один `SERVER_WAITING`; native
logging driver равен `none`, запрос Docker logs завершился ошибкой. Отсутствие
ошибок в таких логах не доказывает прохождение проверки или работу туннеля.
Новая служба оставлена active/enabled для диагностики; существующие два профиля
не менялись. Сам осмотр не менял службы, все пять показателей защиты AWG сохранены.

## October 6 Follow-up / Проверка 6 октября

### English

The current source candidate is `0.0.14 / 2026100601`, with native wrapper 7.
Anonymous browser replies can now carry their actual User-Agent alongside the
nonce-bound cookies. Native document requests and editor WebSockets reuse it.
Legacy cookie-only responses remain accepted, but do not prove matching browser
identity. Old strict cores reject the new field, so helpers and cores must be
updated together. The encryption protocol, profile keys and TLS verification
are unchanged. No account login was used and no release was published.

Focused Go tests, 337 Python tests (one platform-specific skip), 55 Kotlin tests
and 45 Windows-helper policy checks passed. Windows, Linux and all three Android
native artifacts built successfully. These are component checks, not a tested
new APK/EXE release or proof of a working OpenFlux tunnel.

The isolated classic43 live test still failed to reach authenticated readiness:

- Fresh anonymous browser bootstrap opened the classic editor. Three bounded
  matched-User-Agent native attempts did not reach READY or transfer HTTPS data.
- Restarting only the dedicated test service and delivering a fresh anonymous
  session did not fix the failure. Cookie-delivery ACK is not tunnel readiness.
- A private, opt-in stage build observed WebSocket connection success, followed
  by no authenticated peer before the bounded timeout. This does not establish
  successful editor authorization or document message forwarding.
- A read-only inspection compared the local pinned profile with both the
  installed server file and the configuration bound into the running container.
  Document, key, transport and codec matched. The actual running executable and
  source archive also matched the owned deployment plan.

Owned native clients, browsers and temporary client configurations were cleaned
up. All five protected host fingerprints matched after each completed operation.
The active AWG, production classic27, host proxy, DNS and routes were unchanged.
The host routing mode was not established, so no result here is a direct-path
throughput measurement. Further editor-auth and paired-server tests are required;
classic43 must not be presented as a working second profile.

### Русский

Текущий кандидат исходников: `0.0.14 / 2026100601`, native wrapper 7.
Анонимная сессия браузера теперь может передавать фактический User-Agent вместе
с cookies, привязанными к nonce. Ядро использует его для загрузки документа и
WebSocket редактора. Прежние ответы только с cookies принимаются, но не
подтверждают совпадение User-Agent. Старые строгие ядра отвергают новое поле,
поэтому ядро и помощники обновляются согласованно. Шифрование, ключи и проверка
TLS сохранены. Вход в аккаунт не использовался, публикации релиза не было.

Прошли целевые Go-тесты, 337 Python-тестов (один платформенный пропуск), 55
Kotlin-тестов и 45 проверок политики Windows-помощника. Собраны ядра Windows,
Linux и трёх Android ABI. Это проверка компонентов, а не готового нового APK/EXE
и не подтверждение работающего OpenFlux.

Изолированный classic43 пока не прошёл подключение:

- Свежий анонимный браузер открыл старый редактор. Три ограниченные по времени
  попытки с согласованным User-Agent не дошли до READY и передачи HTTPS.
- Перезапуск только тестовой службы и передача новой анонимной сессии не помогли.
  Подтверждение получения cookies не является готовностью туннеля.
- Отдельная приватная диагностическая сборка подтвердила подключение WebSocket,
  но не получение ответа VPN-сервера до таймаута. Это ещё не подтверждает
  авторизацию участника редактора или пересылку сообщений документа.
- Read-only проверка подтвердила совпадение документа, ключа, транспорта и кодека
  клиента с установленной конфигурацией и конфигурацией запущенного контейнера.
  Исполняемый файл работающего процесса и архив исходников соответствуют плану.

Тестовые клиенты, браузеры и временные конфигурации удалены или остановлены.
После каждой завершённой операции совпали все пять показателей защиты рабочего
подключения. AWG, основной classic27, системный прокси, DNS и маршруты не менялись.
Режим маршрутизации ноутбука не подтверждён, поэтому прямую скорость эти тесты
не измеряют. Нужны дальнейшие проверки авторизации редактора и согласованной
серверной части; classic43 пока нельзя выдавать как рабочий второй профиль.

## October 6 Controlled Recovery / Контрольное восстановление 6 октября

### English

A fourth diagnostic attempt refined the earlier failure: the client received its
own editor-auth success, sent the co-authoring unlock and cursor frames, but
received no cursor frames or authenticated peer. Thus this client attempt was
past editor authorization; server participation remained unproven.

The proposed fourth-document deployment was not applied. Its preparation guard
found that document already assigned to the main OpenFlux installation and a
stopped older verification installation. Both were left untouched. The earlier
staging phase reported a protected-server snapshot mismatch; later read-only
snapshots were stable but cannot reconstruct the missing historical diff.
The failed receipt was retained. An isolated wrapper7 seed image was built,
without a running container; no classic44 service/profile was created.

For the existing, dedicated classic43 test server, a fresh anonymous browser
context explicitly used `Mozilla/5.0`, matching that legacy core's HTTP and
WebSocket User-Agent. Browser-only verification passed before restarting only
classic43 and handing over the RAM-only, nonce-bound cookies. No account login,
system trust change or TLS bypass was used.

| Client check after server-session refresh | Outcome |
| --- | --- |
| Typed diagnostic Windows native client | Authenticated READY in 11.750 s; encrypted tunnel, Instagram/Wikipedia HTTPS 200, complete 1 MiB in 1.391 s (6.03 Mbit/s including connection setup). |
| Normal, uninstrumented wrapper7 Windows core | Authenticated READY in 8.578 s; both HTTPS sites passed, complete 1 MiB in 1.202 s (6.98 Mbit/s including connection setup). |
| Existing packaged Windows build 2026092704 | DesktopVpnManager still failed before Connected. It stopped cleanly; no successful HTTPS traffic. |

This supports the session/User-Agent mismatch hypothesis but does not exclude
time-dependent Yandex behavior. The short transfers are not sustained or
direct-underlay throughput measurements. Matching new native/helper components
still need packaged Windows and Android validation. A running old server with
manually supplied in-memory cookies is not proof of unattended restart recovery.

Local ignored receipts: `vps-preflight-classic43-ua-2614ded6dc6de3e8de34a152`
(earlier editor-auth diagnostic),
`vps-preflight-classic43-legacy-ua-e088d276a1cb73f16a125c99` (browser-only),
`vps-preflight-classic43-legacy-ua-60f5b0ef0ceb15ec2a5dfceb` (server handoff),
`vps-preflight-classic43-ua-63e8a4b11a290cadac81f014` (diagnostic success),
`vps-preflight-classic43-ua-680909f8223babbfe806deda` (normal-core success), and
`vps-preflight-packaged-codec-a437d2ef310c7e2eab7160da` (old app failure).
All owned local clients/browsers were stopped and all five host fingerprints
matched after each completed test. Active AWG and production profiles were not
changed. No GitHub publication occurred.

### Русский

Четвёртая диагностическая попытка уточнила причину прежнего таймаута: клиент
получил подтверждение собственной авторизации редактора, отправил разблокировку
совместного редактирования и cursor-сообщения, но не получил ни cursor-ответов,
ни аутентифицированного ответа VPN-сервера.

Четвёртый документ уже закреплён за основным OpenFlux и старым остановленным
тестовым экземпляром. Защита мастера остановила создание дубликата; эти экземпляры
не менялись. На этапе загрузки файлов ранее было несовпадение серверных снимков;
повторные read-only снимки стабильны, но исходное различие не восстановлено.
Неуспешная квитанция сохранена. Подготовлен отдельный образ wrapper7 без запущенного
контейнера; профиль и служба classic44 не создавались.

Для отдельного тестового classic43 браузер запущен с `Mozilla/5.0`, то есть точно
с User-Agent старого серверного ядра. После успешной проверки документа была
перезапущена только эта тестовая служба; анонимные cookies переданы в памяти с
проверкой nonce. Пароль аккаунта, изменение системного доверия и отключение TLS
не использовались.

Диагностическое ядро Windows затем установило шифрованное соединение за 11,750 с,
обычное wrapper7 за 8,578 с. Оба прошли HTTPS 200 от Instagram/Wikipedia и полную
загрузку 1 МиБ: соответственно 6,03 и 6,98 Мбит/с с учётом установки соединения.
Это короткие замеры, не доказательство устойчивой скорости или прямого маршрута.
Старая Windows-сборка 2026092704 через DesktopVpnManager всё ещё не подключилась.

Результат поддерживает гипотезу о несогласованной браузерной сессии/User-Agent,
но не исключает меняющееся поведение Яндекса. Нужны проверки согласованных новых
компонентов в приложениях Windows и Android. Ручная передача cookies работающему
серверу не подтверждает восстановление после его перезапуска. Тестовые локальные
процессы остановлены; пять показателей защиты рабочего AWG совпали. Пользовательские
профили и сеть не менялись, публикации на GitHub не было.

## Packaged Windows Candidate 2026100601 / Кандидат Windows 2026100601

### English

The local Windows app image built with wrapper7. Its launcher/JVM, application
version and bundled native-asset gates passed. This is not a published installer
or a replacement of the running application.

The first real `DesktopVpnManager`/WebView2 trial reached Connected but all three
traffic probes failed before TLS. A following standalone wrapper7 control passed
both HTTPS sites and a complete 1 MiB in 1.169 s (7.18 Mbit/s). Therefore the first
app failure cannot be explained merely by an unavailable server.

Two later diagnostic trials stopped at a faulty test assertion before probing
traffic: the first used the wrong temporary-config filename pattern; both also
used Groovy `Long in IntRange`, which rejects a normal `File.length()` value.
The original receipts are retained. Explicit long bounds were verified with
actual temporary files and the packaged Groovy runtime before another live test.
These two trials are not evidence of application traffic failures.

The corrected R4 test used the actual packaged manager, bundled wrapper7 and
production WebView2 helper. All 13 runtime-config checks matched the imported
profile and the isolated SOCKS credentials. It reached Connected in 14.081 s,
passed authenticated SOCKS and verified HTTPS 200 from Instagram/Wikipedia,
then downloaded all 1,048,576 bytes in 1.121 s (7.49 Mbit/s including setup).
The previous traffic failure did not recur; this does not establish its cause
or prove the suspected browser-WebSocket interference.

R4's overall gate remains failed: the native client stopped, the manager became
Disconnected and the listener closed, but one `conhost` process was still a JVM
descendant during the in-JVM cleanup check. The owned job was subsequently
closed and all five protected host fingerprints matched. The console lifetime
must be checked against the JVM/job lifetime, not silently exempted. No claim
of a completely validated release or sustained/direct-path speed is made.

Private receipts: `vps-preflight-packaged-openflux7-f327b34e8d56a03750e14268`,
`vps-preflight-classic43-ua-3250726e13b07bd9011d10ae`,
`vps-preflight-packaged-openflux7-r2-930084d1c462161ed49cb117`,
`vps-preflight-packaged-openflux7-r3-70f08c3adfe968246ae680ef`, and
`vps-preflight-packaged-openflux7-r4-44741bcea23efec5c3dbaf21`.

### Русский

Локальный Windows-образ приложения с wrapper7 собран; проверки запуска JVM,
версии и встроенных ядер пройдены. Это не опубликованный установщик и не замена
приложения, через которое сейчас работает пользователь.

В первой проверке настоящего DesktopVpnManager и WebView2 был Connected, но три
пробы трафика завершились до TLS. Контрольное отдельное ядро после этого прошло
оба HTTPS-сайта и загрузило полный 1 МиБ за 1,169 с, то есть 7,18 Мбит/с.
Недоступностью сервера одной этой разницы объяснить нельзя.

Две следующие попытки остановились из-за ошибок диагностического инструмента
до проверки трафика: неверного шаблона имени временного конфига и сравнения
`File.length()` типа Long с диапазоном IntRange в Groovy. Исправление проверено
на настоящих временных файлах; исходные неуспешные результаты сохранены. Эти две
попытки нельзя учитывать как сбои передачи данных самого приложения.

Исправленный тест R4 проверил настоящий manager, встроенное ядро wrapper7 и
обычный WebView2-помощник. Все 13 проверок рабочего конфига совпали с профилем и
тестовыми SOCKS-реквизитами. Connected получен за 14,081 с; авторизация SOCKS,
TLS и HTTPS 200 обоих сайтов прошли. Полный 1 МиБ загружен за 1,121 с, около
7,49 Мбит/с с учётом установки соединения. Прежний сбой трафика не повторился;
его причина и предполагаемое влияние браузерного WebSocket пока не доказаны.

Общий результат R4 пока не PASS: ядро остановилось, manager стал Disconnected,
порт закрылся, но внутри ещё работающей JVM оставался дочерний `conhost`.
После закрытия тестового Job Object пять показателей защиты рабочего подключения
совпали. Нужно отдельно доказать завершение консоли после JVM, а не исключать её
из проверки без оснований. Полностью проверенный релиз и стабильная скорость
прямого соединения этим результатом не подтверждены.

### Windows Follow-up / Повторная проверка Windows

R5 (`vps-preflight-packaged-openflux7-r5-40f9aea6969e8cb80f59adc4`) failed before
READY with `stage=verification_repeated`, after one browser-helper request.
No traffic probes ran. The native bootstrap classified its post-browser HTTPS
response as another verification page; the response body was not retained or
inspected, so the classification does not exclude an HTML-parser regression.
The earlier R4 traffic success is valid, but does not establish repeatable startup.

R5 also exposed a failure-path bug in the diagnostic harness: after a connection
error, the JVM requested console-handle capture while the parent was still
waiting for READY. The acknowledgement timed out. Native stop and manager
Disconnected were recorded; the JVM exited and the owned job was closed. A later
read-only identity check found neither the original recorded console instance
nor the recorded JVM PID. All five host fingerprints matched. This retrospective
check does not turn R5 into a successful lifetime-attestation test.

Следующий тест R5 остановился до READY с `stage=verification_repeated` после
одного обращения к браузерному помощнику; проверки трафика не запускались.
Ядро классифицировало повторный HTTPS-ответ как страницу проверки. Сам ответ не
сохранялся и не изучался, поэтому ошибка разбора HTML пока тоже не исключена.
Успешный трафик R4 подтверждён, но стабильный повторный запуск не доказан.

В диагностическом инструменте найден отдельный дефект ветки ошибки: JVM ждала
подтверждения захвата дескриптора консоли, пока родитель ещё ожидал READY.
Подтверждение не пришло вовремя. Ядро остановилось, manager стал Disconnected,
JVM завершилась и тестовый Job был закрыт. Последующая read-only проверка не
нашла исходный экземпляр консоли и PID тестовой JVM; пять показателей защиты
сети совпали. Это не заменяет предусмотренное R5 доказательство через заранее
открытый дескриптор и не превращает результат в PASS.

### Next Changes To Validate / Следующие проверяемые изменения

- Compare real HTML classification with a structured `client-config` parser.
  The current classic parser only recognizes a double-quoted script ID; a
  changed HTML serialization must not be mistaken for a browser challenge.
- If the browser has already obtained a valid editor configuration, evaluate a
  bounded, typed bootstrap handoff instead of fetching the document again from
  Go. Bind it to the requested document, nonce, connection generation and short
  lifetime; validate editor type, editing permission and allowed HTTPS/WSS
  destinations. Never export arbitrary page HTML, account state or an unchecked
  destination. This design has not yet been implemented or proven live.
- Android needs a current official WebView control test. If verification still
  requires interaction, use a visible, non-exported verification Activity in
  the dedicated process before native startup. Keep existing origin/TLS rules,
  secure window and cancellation cleanup. Do not rely on an automatic launch
  from a background service or lengthen just the Kotlin timeout: the native
  provider also has a 50-second limit.

- Сопоставить фактическую классификацию HTML со структурным разбором
  `client-config`. Сейчас классический парсер распознаёт ID скрипта только
  в двойных кавычках; изменение HTML не должно становиться ложной проверкой.
- Проверить передачу ограниченного набора типизированных параметров редактора
  прямо из уже проверенного браузера, без повторного GET из Go. Нужны привязки
  к документу, nonce, поколению подключения и короткому сроку действия, проверка
  типа редактора, права редактирования и разрешённых HTTPS/WSS-адресов. Нельзя
  передавать произвольный HTML, состояние аккаунта или непроверенный адрес.
  Это пока проект исправления, а не реализованный и проверенный механизм.
- Для Android нужен контроль на актуальном официальном WebView. Если требуется
  участие пользователя, проверку следует показывать в видимом, неэкспортируемом
  Activity выделенного процесса до запуска ядра, сохранив TLS, ограничения
  адресов, защиту окна и очистку при отмене. Автоматический запуск из фоновой
  службы ненадёжен; увеличение только Kotlin-таймера не решает ограничение
  provider в native, равное 50 секундам.

## October 7: Wrapper 8 Candidate

Build `0.0.14 / 2026100701` implements the changes proposed above: structured
HTML parsing, a nonce/source/time-bound classic-document snapshot, and explicit
visible Android verification before native startup. Classic metadata is limited
to 24 KiB within the 32 KiB reply; the peer encryption protocol is unchanged.
New server helper/core pairs use browser contract 3; running server profiles
have not been migrated by this change. Android below API 28 retains native-only
startup and reports unsupported interactive verification if it is needed.

Offline results: four Go package suites, 261 Android host tests, 385 JVM tests,
161 Windows helper policy checks, and 346 server tests (one platform-specific
skip) passed. Windows JVM/native-asset packaging checks and signed Android
release build/bindings checks passed. These are not live connection passes.

The first packaged Windows wrapper-8 classic43 attempt failed at connection
startup after one browser verification request, before Connected or HTTPS
traffic. The fixed-category harness lacked sufficient stage detail for that
failure; it must be expanded before attributing the cause. Native, helper and
test JVM cleanup passed, including the retained console-handle check. All five
AWG host fingerprints (processes, listeners, proxy, DNS and routes) stayed
unchanged. The user's active channel was AWG, not the previously reported Jitsi.
No direct mobile-carrier throughput was measured and nothing was published.

## 7 Октября: Кандидат Wrapper 8

Сборка `0.0.14 / 2026100701` реализует предложенные выше изменения: структурный
разбор HTML, снимок параметров старого редактора с привязкой к nonce, документу
и времени, а также открываемую пользователем проверку Android до запуска ядра.
Снимок ограничен 24 КиБ внутри ответа 32 КиБ; протокол шифрования между узлами
не меняется. Новая пара серверных помощников и ядра использует браузерный
контракт 3; работающие серверные профили этим изменением не обновлялись.
На Android ниже API 28 сохранён запуск без браузера; при необходимости
интерактивной проверки возвращается явная ошибка отсутствия поддержки.

Прошли четыре набора Go-тестов, 261 Android host-тест, 385 JVM-тестов,
161 проверка Windows-помощника и 346 серверных тестов (один платформенный
пропуск). Пройдены проверки запуска JVM/ресурсов Windows и сборки подписанного
Android APK с native-библиотеками. Это не подтверждение живого подключения.

Первая проверка classic43 через готовый Windows-пакет wrapper 8 завершилась
ошибкой запуска после одного обращения к браузеру, до «Подключено» и HTTPS.
Тестовому инструменту не хватило детализации этапа отказа; перед выводом о
причине нужна дополнительная диагностика. Подтверждена остановка ядра,
помощника и JVM, включая проверку дескриптора консоли. Все пять контрольных
показателей AWG на ноутбуке (процессы, порты, proxy, DNS и маршруты) совпали.
Текущий канал пользователя был AWG, а не ранее указанный Jitsi. Прямую скорость
через мобильного оператора не измеряли; публикации не было.

### October 7 Follow-Up: Confirmed Metadata Rejections

The R7 packaged Windows test narrowed the failure to the browser helper, about
7.7 seconds after its request. No peer handshake or traffic check was reached.
Two subsequent browser-only tests used a separately built diagnostic copy;
production binaries were not replaced. Only fixed categories and booleans were
exported, never URLs, hostnames, document contents, tokens or cookies.

The browser obtained classic-editor parameters, but wrapper 8 rejected two
legitimate shapes: an HTTPS Yandex balancer hostname containing an underscore,
and an internal `http://localhost:12701/disk/...` file reference with a query.
The latter is sent inside OnlyOffice `openCmd` and is not locally fetched.
Both failures are in the new metadata policy, not evidence of a carrier outage.
The next source candidate, `2026100702`, handles these shapes separately from
document navigation and network destinations; TLS verification is unchanged.

The signed `2026100701` release APK was also installed in a fresh, isolated
API 35 x86_64 emulator. The explicit verification action opened the new visible
Activity, then preflight failed before native startup. Google WebView was
`124.0.6367.219`; this is not validation on the user's physical Android 13 phone.
The guest application and native processes were removed after the attempt.
All five host AWG protection checks matched after every live test. There was
no successful `2026100701` OpenFlux connection, no throughput result and no
GitHub publication. The `2026100702` candidate still requires new live tests.

### 7 Октября: Подтверждённые Отказы Проверки Параметров

Windows-проверка R7 локализовала отказ в браузерном помощнике примерно через
7,7 секунды после обращения к нему. До рукопожатия с сервером и трафика дело
не дошло. Две последующие браузерные проверки использовали отдельную
диагностическую копию без замены рабочих бинарников. В отчёт выводились только
фиксированные категории и логические значения, без адресов, имён узлов,
содержимого документов, токенов и cookies.

Браузер получил параметры старого редактора, но wrapper 8 отклонил две
допустимые формы: HTTPS-имя балансировщика Яндекса с подчёркиванием и внутреннюю
ссылку `http://localhost:12701/disk/...` со строкой запроса. Последняя передаётся
в команде OnlyOffice `openCmd`, а не загружается на устройстве. Это ошибки новой
проверки параметров, а не доказательство сбоя оператора. Следующий кандидат
`2026100702` обрабатывает эти формы отдельно от адресов переходов и сетевых
запросов; проверка TLS сохраняется.

Подписанный релизный APK `2026100701` установлен в чистый изолированный
эмулятор API 35 x86_64. Нажатие кнопки проверки открыло новую видимую Activity,
после чего предварительная проверка завершилась ошибкой до запуска ядра.
Версия Google WebView: `124.0.6367.219`; это не проверка на физическом телефоне
пользователя с Android 13. После попытки приложение и ядра удалены из гостевой
системы. Все пять проверок сохранности AWG совпали после каждого живого теста.
Успешное подключение OpenFlux в `2026100701` и скорость не подтверждены,
публикации на GitHub не было. Для `2026100702` ещё нужны новые живые проверки.

## October 8: Build 2026100702 Live Results

The corrected wrapper-8 client passed live tests with the isolated `classic43`
profile, sequentially on Windows and Android. Existing production profiles and
the running server service were not changed or restarted. The encrypted peer
protocol remains `unified-openflux-aesgcm-v1`.

| Check | Result |
| --- | --- |
| Packaged Windows, first fresh browser session | Connected in 16.389 s; authenticated SOCKS; verified TLS and HTTP 200 from Instagram and Wikipedia; Stop and complete process cleanup passed |
| Packaged Windows, second fresh browser session | Connected in 13.307 s; the same traffic, config identity and cleanup checks passed |
| Signed Android release, API 35 x86_64 | User-opened verification Activity, authenticated READY, app Connected, TUN and probe UID included in the VPN; Instagram and Wikipedia HTTP 200; UI Stop and cleanup passed |
| Host connection protection | All five fingerprints matched after each live attempt: active VPN processes, protected listeners, proxy, DNS and routes |

The two complete 1 MiB Windows downloads measured 7.689 and 8.503 Mbit/s,
including connection establishment. These short samples traversed the laptop's
active AWG and do not measure direct MegaFon throughput, sustained capacity or
the requested 13-20 Mbit/s target. Android throughput was not measured.

The first Android test of this APK (driver r2) falsely missed READY: a global
200-line logcat tail could omit the app's readiness records. It did not run the
traffic probe and is not counted as a pass. Driver r3 removed that tail while
retaining UID/tag filters and a 1 MiB bound, reset state for each attempt and
passed 20 offline checks. Its fresh live run is the Android pass above. The APK
was identical in both attempts. Google WebView was `124.0.6367.219`; this does
not establish behavior on a physical Android 13 phone or a newer WebView.

Offline and packaging checks also passed: four Go package suites, 389 JVM
tests, 267 Android host tests, 273 Windows helper policy checks and 349 Python
server tests (348 passed, one platform-specific skip). The Windows harness passed 41
offline checks. Windows JVM/native-asset smoke checks and Android release
signing, native bindings and non-debuggable identity checks passed.

Evidence directories below contain private, local-only receipts. They are not
release assets; no document URL, key or cookie is included in this report.

- Windows first pass: `vps-preflight-packaged-openflux8-r8-842564a3758de69e380ed0fe`.
- Windows second pass: `vps-preflight-packaged-openflux8-r8-447d8103a1e7e810424b280f`.
- Android driver-r2 failure: `vps-preflight-android-classic43-wrapper8-r2-8eb6643cff0aaa4918f82787`.
- Android driver-r3 pass: `vps-preflight-android-classic43-wrapper8-r3-a975c46ae874b93b408d53da`.

Tested release APK SHA-256:
`9e0968a9ec8e8cf469b44509bda77d3fc61822b4ef9310067476fb09a3283755`.
Tested Windows OpenFlux SHA-256:
`4ef2d5be93b15175eba6125e2121516bbc0127329d9f20cb9502078d07c0292c`.

The owned emulator and its separate ADB server were stopped after the test;
host ADB and the installed Unified VPN/AWG were not stopped. Unattended server
bootstrap/restart recovery, a physical phone, direct carrier speed, vyandex
and other VPN transports remain outside this build's live validation. This is
a local candidate, not a GitHub release or a claim that every server profile
now recovers automatically.

The signed universal APK, Windows installer and portable ZIP are in the local
workspace directory `.downloads/local-artifacts/0.0.14-build.2026100702/`.
The installer passed compiled identity, upgrade-ordering and non-destructive
preflight policy checks; it was not installed over the user's active AWG app.
The portable package uses the tested app image. Copy hashes matched; no private
test profile was copied into that artifact directory.

| Artifact | SHA-256 |
| --- | --- |
| `UnifiedVPN-0.0.14-build.2026100702-android-universal.apk` | `9e0968a9ec8e8cf469b44509bda77d3fc61822b4ef9310067476fb09a3283755` |
| `UnifiedVPN-0.0.14-build.2026100702-windows-amd64-installer.exe` | `ae3dfbc5a5796cff49cb56d53014ecb8fea0b81d6345fb865c00650c4a30f6e7` |
| `UnifiedVPN-0.0.14-build.2026100702-portable-manual.zip` | `8d9505a5601552039365f68c0be5b6ad813d54aba64bea446dd84631007d3312` |

## 8 Октября: Живые Проверки Сборки 2026100702

Исправленный клиент wrapper 8 прошёл последовательные живые проверки отдельного
профиля `classic43` на Windows и Android. Рабочие профили пользователя и
запущенная серверная служба не менялись и не перезапускались. Зашифрованный
протокол между узлами остаётся `unified-openflux-aesgcm-v1`.

| Проверка | Результат |
| --- | --- |
| Готовый Windows-пакет, первая новая сессия браузера | «Подключено» за 16,389 с; авторизация SOCKS; проверенный TLS и HTTP 200 от Instagram и Wikipedia; остановка и полная очистка процессов |
| Готовый Windows-пакет, вторая новая сессия браузера | «Подключено» за 13,307 с; те же проверки трафика, соответствия конфига и очистки пройдены |
| Подписанный Android release, API 35 x86_64 | Открытая пользователем Activity проверки, аутентифицированный READY, «Подключено», TUN и UID теста внутри VPN; HTTP 200 от Instagram и Wikipedia; остановка кнопкой и очистка |
| Защита рабочего подключения | После каждой попытки совпали пять показателей: процессы активного VPN, защищённые порты, proxy, DNS и маршруты |

Две полные загрузки по 1 МиБ на Windows дали 7,689 и 8,503 Мбит/с с учётом
установки соединения. Это короткие проверки поверх действующего AWG ноутбука,
не прямая скорость через Мегафон, не длительная нагрузка и не достижение цели
13-20 Мбит/с. Скорость Android не измерялась.

Первая Android-попытка этого APK с инструментом r2 ошибочно пропустила READY:
общий хвост logcat из 200 строк вытеснял сообщения приложения. Проверки трафика
тогда не запускались; это не успешный тест. В r3 убрано это ограничение хвоста,
но сохранены фильтры UID/tag и предел 1 МиБ, а состояние сбрасывается перед
каждой попыткой. Прошли 20 офлайн-проверок инструмента и новый живой тест,
указанный выше. APK в обеих попытках одинаковый. Использовался Google WebView
`124.0.6367.219`; работа на физическом Android 13 и новом WebView не доказана.

Также пройдены четыре набора Go-тестов, 389 JVM-тестов, 267 Android host-тестов,
273 проверки Windows-помощника и 349 Python-тестов сервера (348 успешных,
один платформенный пропуск). Windows-инструмент прошёл 41 офлайн-проверку. Пройдены smoke-проверка
JVM и native-ресурсов Windows, проверка подписи APK, native-библиотек и запрета
отладки в release. Идентификаторы приватных локальных протоколов и контрольные
суммы указаны в английском разделе выше; приватные адреса, ключи и cookies
сюда не включены.

После теста остановлены только созданный эмулятор и его отдельный ADB.
Системный ADB и установленный Unified VPN/AWG не останавливались. Автономный
запуск сервера после перезагрузки, физический телефон, прямая скорость через
оператора, vyandex и остальные VPN-транспорты в этой сборке не проверялись.
Это локальный кандидат, не релиз на GitHub и не подтверждение автоматического
восстановления всех серверных профилей.

Подписанный универсальный APK, установщик Windows и portable ZIP находятся
в локальной папке проекта `.downloads/local-artifacts/0.0.14-build.2026100702/`.
Установщик прошёл проверки идентификаторов, порядка обновления и сохранности
данных при предварительной проверке; поверх работающего приложения с AWG его
не устанавливали. Portable содержит проверенный образ приложения. Хеши копий
совпали с оригиналами; приватные тестовые профили в папку сборок не копировались.
Имена файлов и SHA-256 приведены в таблице английского раздела выше.

## October 8: Classic27 Is Not Covered By The Classic43 Pass

The user's fresh Android and Windows logs identify build `2026100702` and
`classic27`. Read-only comparison confirmed that the installed Windows profile,
the issued private profile and the VPS use the same document, encryption key,
transport and codec. Comparisons used local booleans and one-use HMACs; private
values are not included here.

- Android fails during the WebView preflight, before native startup. A normal
  successful Activity dismissal does not by itself cancel verification. The
  existing log did not distinguish TLS resources or metadata-validation stages.
- The original Windows package reproduced a readiness timeout in an isolated
  local-SOCKS manager run. No application traffic was tested or claimed.
- A second Windows run used the same packaged manager/helper with only a pinned
  diagnostic native resource substituted inside an isolated class loader. The
  first connection closed with WebSocket code 1005. The second received the
  actual OnlyOffice `auth` response with `result=1` at approximately 13.9 s, but
  timed out waiting for the encrypted peer at approximately 111 s.
- A new VPS document-only process requested browser assistance but received no
  validated document metadata. A diagnostic repeat confirmed untrusted TLS on
  `hdrc.yandex.net` and `mdd.yandex.net`, followed by a CAPTCHA page, no editor
  configuration and a READY timeout. This is evidence of failed cold bootstrap,
  not proof of every internal state of the already-running older server.

Both VPS probes used temporary, owner-checked containers in the existing
classic27 network namespace, with no tunnel WebSocket connection, new bridge,
service restart or configuration change. Candidate containers/images were
cleaned up. Original classic27 and protected Jitsi unit identities were
unchanged. All five host connection fingerprints matched around each test;
the second Windows run and VPS probes preserved the user-confirmed AWG.

Private, local-only evidence:

- Original Windows reproduction: `vps-preflight-packaged-openflux8-classic27-r9-b2eb7b1d3328912bf8fab4c9`.
- Diagnostic native reproduction: `vps-preflight-packaged-openflux8-classic27-r10-947095ddfd6126e3fe8129c3`.
- VPS document-only probe: `vps-preflight-classic27-document-coordinator-e8f66ba1604ff94ebe0df5ae`.
- VPS browser-stage probe: `vps-preflight-classic27-document-coordinator-d502cad64afc6253757493be`.

The source diagnostics passed 392 JVM tests, 279 Android host tests, four Go
package suites and repeated focused race tests. The signed, non-debuggable,
three-ABI diagnostic APK `2026100801` was built locally; its signing certificate
matches `2026100702`. It has not been validated on the user's physical Android
13 device, and it is not a repaired-connectivity release. It does not contain
the separate Engine.IO handshake-fix candidate. APK location:
`.downloads/local-artifacts/0.0.14-build.2026100801-diagnostic/UnifiedVPN-0.0.14-build.2026100801-android-universal.apk`.
SHA-256: `772da509e9bf4e32c211c75bb4841d143292ad79a3a38cd98b0536e810f4557b`.

Upstream [issue 31](https://github.com/p1neappleXpress/OpenFlux/issues/31) and
[PR 115](https://github.com/p1neappleXpress/OpenFlux/pull/115) describe the
missing Engine.IO polling/upgrade handshake. The observed first-attempt 1005
is consistent with that report. It does not explain away the separate missing
encrypted server response after successful editor authorization. No GitHub
release was published and no sustained-throughput claim is made.

## October 8: Scoped TLS And Engine.IO Follow-Up

The fresh VPS browser probe was repeated with certificate trust restricted to
`hdrc.yandex.net` and `mdd.yandex.net` inside a temporary anonymous helper. Both
resources then returned HTTP 200 with certificate and hostname verification,
but document readiness still timed out. No operating-system trust store was
changed. A read-only DOM classification found a fast CAPTCHA page initially and
one visible standard robot checkbox at the deadline, with no editor config.
This separates the resource TLS problem from the remaining interactive check;
adding the CA alone is not a complete repair. No checkbox was clicked in these
two probes. All candidate resources were cleaned up and original server units
and the laptop connection remained unchanged.

The Engine.IO candidate also received the initial 1005 closure on classic27,
then genuine editor authorization and an encrypted-peer timeout at about
111 seconds. A separate classic43 regression run with the same native binary
reached application-level Connected in 13.42 seconds. Authenticated SOCKS and
certificate-verified HTTPS returned HTTP 200 and 4096 bytes each from Instagram
and Wikipedia. Stop, browser cleanup, process cleanup and all five host guards
passed. This is not a classic27 pass, an Android test or a throughput result.
Both runs used the production `2026100702` manager/helper with only the pinned
candidate native resource substituted in an isolated class loader; the
installed application was not replaced.

Local-only evidence:

- Scoped TLS probe: `vps-preflight-classic27-document-coordinator-7ada4a8b5d654a92cfebb002`.
- Challenge-shape probe: `vps-preflight-classic27-document-coordinator-b975454571b7b31d7b52cf5a`.
- Classic27 Engine.IO attempt: `vps-preflight-packaged-openflux8-classic27-r11-bcc02a71f96c62e6e4f84738`.
- Classic43 traffic regression: `vps-preflight-packaged-openflux8-classic43-r12-0cb5cf0ba6eeaaba4bc00131`.

## 8 Октября: Дополнительные Проверки TLS И Engine.IO

Временный анонимный помощник на VPS повторно проверил документ с доверием
дополнительному сертификату только для `hdrc.yandex.net` и `mdd.yandex.net`.
Оба ресурса вернули HTTP 200 с проверкой сертификата и имени узла, но документ
так и не стал готов. Системные хранилища сертификатов не менялись. Проверка
структуры страницы обнаружила сначала быструю CAPTCHA, а к таймауту одну
видимую галочку проверки робота, без конфигурации редактора. Таким образом,
ошибка TLS и интерактивная проверка являются отдельными препятствиями;
добавления сертификата недостаточно. В этих двух тестах галочку не нажимали.
Временные ресурсы удалены, исходные службы и соединение ноутбука сохранены.

Кандидат Engine.IO на classic27 по-прежнему получил первое закрытие 1005,
затем прошёл авторизацию редактора, но примерно через 111 секунд достиг
таймаута ответа зашифрованного узла. Отдельный контрольный тест classic43
с тем же ядром дошёл до состояния «Подключено» за 13,42 секунды. Через SOCKS
с авторизацией и HTTPS с проверкой сертификатов получены HTTP 200 и по
4096 байт от Instagram и Wikipedia. Остановка, очистка браузера и процессов,
все пять проверок сохранности соединения ноутбука прошли. Это не успешный
тест classic27, не проверка Android и не измерение скорости. Использовались
manager и браузерный помощник сборки `2026100702`; тестовое ядро подставлялось
только в изолированном загрузчике. Установленное приложение не заменялось.
Идентификаторы локальных протоколов проверки приведены в английском разделе;
ссылки документов, ключи и cookies в отчёт не включены.

## October 9: Confirmed Phone Failure And Build 2026100802

The user's physical Android 13 log from signed build `2026100801` now identifies
the immediate failure as `tls_untrusted, resource=mdd`. This is before native
startup, not evidence of a bad profile key or an encrypted peer rejection.
Build `2026100802` cancels that invalid resource request without immediately
aborting the otherwise allowed document verification. It does not proceed past
an invalid TLS certificate, trust a new CA or mark an unverified document ready.
The document, anonymous session, metadata and encrypted peer checks still apply.
Physical-device recovery has not yet been tested with this new APK.

The new native candidate also gates the persistent session on a valid Socket.IO
namespace acknowledgment and successful OnlyOffice authorization. The old code
fails the deterministic packet-order regression; the new implementation passes
four Go package suites and 20 focused race-test repetitions. The ordering follows
the [Socket.IO namespace protocol](https://github.com/socketio/socket.io-protocol#connection-to-a-namespace).

A Windows test retained the original packaged `2026100702` manager/helper and
substituted only the pinned final candidate native resource in a private class
loader. Classic43 reached Connected in 16.45 seconds. Both Instagram and Wikipedia
returned verified HTTPS 200 and 4096 bytes through authenticated SOCKS. An initial
authorization closure still required a retry; this is not proof of fixing that
closure, classic27, the installed Windows build or sustained throughput. Stop,
browser/process cleanup and all five host-network fingerprints passed.

The last VPS document-only test with verified, two-host-scoped TLS loaded both
resources. One standard checkbox was clicked; the page then presented an
additional interactive challenge and no editor metadata before the deadline.
No challenge image was solved automatically and no account credentials were
used. The original classic27 service and protected Jitsi services were untouched;
temporary containers/images were removed. Further unattended probes were stopped.
A same-VPS-egress manual verification remains a separate recovery step, not a
completed server repair.

Local-only evidence:

- Windows final-native test: `vps-preflight-packaged-openflux8-classic43-r13-778d1c9c9e6b320899667ebc`.
- Last document-only challenge test: `vps-preflight-classic27-document-coordinator-eb93afe5a2758e0e5485378f`.
- Final native manifest: `.downloads/openflux/artifacts-wrapper8-20261008-namespace/manifest.json`.
- Android APK SHA-256: `82481f3a9cad568f0c7ef9815cb0cd430b4bb925544d7d6dc3e9b10d9e053fc9`.
- Windows native SHA-256: `13c8f68e7c21e161f128849bb973fb8f788f9c04d10e485b77c851f1bc78ec2a`.

### Signed APK Runtime Result

The exact signed `2026100802` APK was installed in a fresh, owned API 35 emulator
with WebView `124.0.6367.219`. The test used the normal app UI and Android VPN
consent, followed by the visible browser verification Activity. It reached
native encrypted READY, app-level Connected and TUN establishment. The test
proved that its HTTPS probe UID was inside the app VPN, then received HTTP 200
from both Instagram and Wikipedia. UI Stop, process stability, guest uninstall,
absence of native transports and all five host-network fingerprints passed.

The emulator and its dedicated ADB server were stopped; the initial shutdown
check observed delayed process termination, and a second identity-checked cleanup
confirmed all owned processes absent. The first live invocation stopped during
preflight, before installation; the subsequent read-only check confirmed a
fully booted, empty guest before the actual test.
No physical device, installed Windows app or server service was changed.

Receipt: `vps-preflight-android-classic43-wrapper8-r4-417404011e4e8f75c03da051`.
APK: `.downloads/local-artifacts/0.0.14-build.2026100802/UnifiedVPN-0.0.14-build.2026100802-android-universal.apk`.
The ABI binaries match the final native manifest; the signing certificate and
all olcRTC JNI libraries match the previous verified release. This is a
classic43/API 35 test, not classic27/Android 13 or a sustained-speed measurement.

### Final Windows Package And Local Artifacts

The packaged `2026100802` manager, browser helper and byte-verified native core
were then exercised on classic43 with isolated data and authenticated local
SOCKS. The harness verified that the candidate native bytes equal the native
resource already bundled in this package. It reached Connected in 13.40 seconds,
then verified TLS and HTTP 200 with 4096 bytes from each of the two test sites.
The first internal authorization attempt still closed before a successful retry.
The manager stopped, all owned child processes and browser directories were
removed, and all five host fingerprints were unchanged. Receipt:
`vps-preflight-packaged-openflux8-classic43-r14-ed98cc8517427611527edee7`.

Packaging independently passed JVM startup, embedded version, native asset and
compiled-installer identity/upgrade-policy checks. All 251 files in the portable
ZIP match the tested app image by SHA-256. The installer was not installed over
the user's active VPN application. The current suites contain 392 passing JVM
tests and 287 passing Android host tests, with no skipped tests in those suites.

Local output directory: `.downloads/local-artifacts/0.0.14-build.2026100802/`.

| Artifact | SHA-256 |
| --- | --- |
| `UnifiedVPN-0.0.14-build.2026100802-android-universal.apk` | `82481f3a9cad568f0c7ef9815cb0cd430b4bb925544d7d6dc3e9b10d9e053fc9` |
| `UnifiedVPN-0.0.14-build.2026100802-windows-amd64-installer.exe` | `076d1ba771a95d5f04b9ee2195bc7ac9f4260d0170564a73bb655a2dec55a11f` |
| `UnifiedVPN-0.0.14-build.2026100802-portable-manual.zip` | `b28275912d934626b28422e66eeeb73e6752f60b99745a47c933f2e030e877ee` |

No private profiles, browser sessions or test logs are included in that output
directory. No GitHub publication or server deployment was performed.

## 9 Октября: Подтверждённая Ошибка Телефона И Сборка 2026100802

Журнал физического Android 13 со сборкой `2026100801` подтвердил причину
мгновенного отказа: `tls_untrusted, resource=mdd`. Это происходит до запуска
ядра и не доказывает ошибку ключа профиля. В `2026100802` недоверенный ресурс
по-прежнему блокируется, но всё окно проверки сразу не закрывается. Доверие
сертификатам не расширяется; документ, анонимная сессия, параметры редактора
и зашифрованное соединение должны пройти независимые проверки. Этот APK ещё
не проверен на физическом телефоне пользователя.

Новое ядро также ожидает подтверждение Socket.IO и настоящую авторизацию
OnlyOffice перед постоянной отправкой. Регрессионный тест воспроизводит ошибку
порядка пакетов в старом коде; исправление прошло четыре набора Go-тестов и
20 повторов проверки гонок. В Windows-тесте заменялось только ядро внутри
изолированного загрузчика, установленное приложение не менялось. Classic43
дошёл до «Подключено» за 16,45 секунды и получил HTTPS 200 и по 4096 байт
от Instagram и Wikipedia через SOCKS с авторизацией. Сначала всё ещё был
обрыв авторизации с повторной попыткой. Это не подтверждает исправление
classic27, установленной Windows-сборки или достижение целевой скорости.
Остановка, очистка и пять проверок сохранности сети прошли.

Последний временный тест документа на VPS с ограниченным доверием только
двум ресурсам загрузил их успешно. После одной стандартной галочки Яндекс
показал дополнительное интерактивное задание; параметры редактора не появились.
Изображения задания автоматически не решались, данные аккаунта не использовались.
Исходный classic27 и службы Jitsi не менялись, временные ресурсы удалены.
Автоматические повторы остановлены. Ручная проверка с выходом через тот же VPS
остаётся отдельным незавершённым этапом, а не выполненным ремонтом сервера.
Идентификаторы приватных протоколов и хеши приведены выше; секреты не включены.

### Проверка Подписанного APK

Итоговый APK `2026100802` установлен в отдельный свежий эмулятор API 35 с WebView
`124.0.6367.219`. Через обычный интерфейс и разрешение Android на VPN пройдена
видимая браузерная проверка. Получены зашифрованный READY, «Подключено» и TUN;
подтверждено включение UID сетевого теста в VPN приложения. Instagram и Wikipedia
вернули HTTPS 200. Остановка кнопкой, стабильность процесса, удаление гостевого
приложения, отсутствие ядер и пять проверок сохранности сети прошли.

Эмулятор и отдельный ADB остановлены. Первая проверка завершения процесса
наблюдала задержку; повторная очистка с проверкой идентификаторов подтвердила
их отсутствие. Первый запуск теста до этого завершился на предварительной
проверке до установки приложения; перед настоящим тестом подтверждены полная загрузка
и отсутствие гостевого приложения. Телефон, установленное Windows-приложение
и службы сервера не менялись. Путь APK и идентификатор протокола приведены выше.
Тест относится к classic43/API 35, а не classic27/Android 13 и не целевой скорости.

### Итоговый Windows-Пакет И Файлы

После упаковки проверены manager, браузерный помощник и ядро именно `2026100802`
на classic43 с отдельными данными и локальным SOCKS с авторизацией. Байты
тестируемого ядра совпали с уже встроенными в пакет. Получено «Подключено»
за 13,40 секунды, затем HTTPS 200 и по 4096 байт от двух сайтов. Первое внутреннее
подключение авторизации всё ещё оборвалось, повторное прошло. Manager остановлен,
принадлежащие тесту процессы и браузерные каталоги очищены, пять проверок
сохранности сети прошли. Идентификатор протокола указан в английском разделе.

Отдельно проверены запуск JVM, версия, встроенные ядра и правила обновления
скомпилированного установщика. Все 251 файл portable ZIP совпали с проверенным
образом приложения по SHA-256. Установщик поверх работающего VPN не запускали.
392 JVM-теста и 287 Android host-тестов прошли без ошибок и пропусков.
Все три файла лежат в `.downloads/local-artifacts/0.0.14-build.2026100802/`;
имена и хеши приведены выше. Приватных профилей, браузерных сессий и журналов
в этой папке нет. Публикации на GitHub и изменений сервера не было.

## 8 Октября: Classic27 Проверен Отдельно

Свежие журналы пользователя относятся к `classic27` и сборке `2026100702`,
а прежние успешные проверки относились к `classic43`. Документ, ключ, транспорт
и кодек клиента и сервера classic27 совпадают; приватные значения в отчёт
не включены.

Android останавливается до запуска ядра, на проверке WebView. На Windows сбой
воспроизведён: первая попытка получает закрытие WebSocket 1005, вторая примерно
через 13,9 с проходит настоящую авторизацию редактора, но примерно через 111 с
завершается таймаутом зашифрованного ответа сервера. Трафик через это соединение
не проверялся, успешного подключения нет.

Отдельный временный процесс на VPS не смог получить параметры документа.
Уточняющая проверка выявила недоверенные сертификаты `hdrc.yandex.net` и
`mdd.yandex.net`; страница осталась на CAPTCHA без конфигурации редактора.
Это подтверждает ошибку нового запуска браузерной проверки, но не раскрывает
всё внутреннее состояние ранее запущенного сервера.

Сервисы и настройки не менялись. Временные Docker-ресурсы удалены, идентификаторы
classic27 и защищённых Jitsi-служб сохранились. Все пять проверок подключения
ноутбука совпали до и после тестов; работающий AWG не отключался.

Пройдены 392 JVM-теста, 279 Android host-тестов, четыре набора Go-тестов и
повторные проверки гонок. Диагностический подписанный APK `2026100801` собран
для трёх ABI, отладка отключена, подпись совпадает с `2026100702`. Его путь и
SHA-256 указаны выше. Проверки на физическом Android 13 ещё нет; это не готовый
релиз с исправленным подключением. Отдельный кандидат исправления Engine.IO
в этот APK не включён. На GitHub ничего не опубликовано.

### Проверка 2 ноября 2026: страница просмотра вместо редактора

С явного разрешения пользователя проверены сохранённые приватные конфиги
через прямой HTTPS без изменения VPN, маршрутов, DNS или системного прокси.
Восемь конфигов после удаления дублей (три `vyandex`, пять `yandex`) возвращают
HTTP 200 со страницей просмотра Диска (`store-prefetch`), без `client-config`.
Полный настольный User-Agent не изменил результат. Наличие `write` в метаданных
файла не предоставило конфигурацию редактора. Поэтому эти результаты не доказывают,
что владелец отозвал права доступа или что требуется вход в аккаунт.

Одна попытка штатного анонимного WebView2 не получила конфигурацию редактора
за 47,349 секунды; приватный каталог удалён. Вход в аккаунт и перенос cookies
пользовательского браузера не выполнялись. Трафик OpenFlux и аутентифицированный
READY не получены. Это соответствует классу проблемы
[upstream #170](https://github.com/p1neappleXpress/OpenFlux/issues/170),
но не доказывает совпадение всех её причин.

В локальном кандидате 0.1.5 / 2026110203 добавлена отдельная диагностика страницы
просмотра. Нативный клиент различает её для обоих транспортов, не запускает
бессмысленную браузерную проверку и не сообщает Ready. Android сохраняет
обезличенную причину `OPENFLUX_FATAL` вместо общего сообщения о завершении процесса;
после браузерного ожидания отдельно распознаётся загруженная страница просмотра.
Подсказка предлагает обновить подписку или запросить у владельца рабочую ссылку
на редактор. Эти изменения исправляют диагностику; они не восстанавливают
зашифрованное соединение через недоступный редактор на стороне Яндекса.

Нативные тесты четырёх пакетов прошли. Две живые проверки `--check-document`
на новом бинарнике правильно распознали страницу просмотра: `vyandex` за 0,969 с,
`yandex` за 2,879 с. Они не открывали WebSocket-комнату и не являются проверкой
туннельного трафика. Адреса, ключи, содержимое документов и cookies в отчёт не включены.

Живая проверка через `DesktopVpnManager` на сохранённом профиле прошла за 2,389 с:
получена конкретная ошибка страницы просмотра, ложный READY отсутствует,
остановлены собственный дочерний процесс и SOCKS-слушатель, временный конфиг удалён.
Полная сборка Windows/Android и 498 JVM + 351 Android host-тест завершились успешно.
Восемь синтетических проверок JavaScript различают редактор, просмотр, загрузку,
CAPTCHA и повреждённые метаданные. Подпись APK v1/v2 и CRC всех 273 элементов ZIP
проверены. Обновлённые файлы 0.1.5 / 2026110203 сохранены локально; это не новый
GitHub Release и не подтверждение успешного OpenFlux-трафика.

### Дополнительная проверка 2 ноября: адрес редактора и видимое окно

Девять новых публичных ссылок пользователя при анонимном HTTPS-запросе также
открыли страницу просмотра без конфигурации редактора. Пользователь отдельно
передал адрес из адресной строки открытого редактора. Этот адрес относится
к старому транспорту `yandex`; прямой запрос получил страницу проверки браузера.
Адреса и параметры доступа сохранены только в защищённом локальном каталоге.

С разрешения пользователя запущено отдельное видимое окно с новым приватным
профилем WebView2. Диагностический помощник получил конфигурацию старого редактора
и анонимные cookies за 9,552 секунды. Полученные данные однократно переданы
нативному `--check-document --bootstrap-stdio`; он завершился с
`OPENFLUX_DOCUMENT_OK`. Окно автоматически закрылось, приватный каталог удалён.
Вход в аккаунт, перенос сессии пользовательского браузера и сохранение cookies
не выполнялись. Это проверка получения конфигурации, без зашифрованного READY
и туннельного трафика; случайный локальный тестовый ключ на сервере не настроен.

В Windows-кандидате проверка при обычном подключении теперь может показывать
окно верификации; фоновые проверки сохраняют скрытый режим. Повторная живая
проверка штатного помощника завершилась тайм-аутом через 45,539 секунды,
с очисткой приватного каталога. Поэтому стабильное прохождение проверки
не подтверждено. Первый успех не доказывает, что причиной прежних сбоев была
только невидимость окна. Увеличение одного тайм-аута без согласования с нативным
протоколом в кандидат не включено.

Отдельный HTTPS-запрос с существующего VPS также получил страницу проверки
без конфигурации редактора. SSH-ключ хоста проверен по закреплённому отпечатку;
сервисы, файлы, VPN, маршруты, DNS и системный прокси сервера не менялись.
Рабочий адрес нового редактора Volga ещё не получен. Успешное соединение
OpenFlux через новые документы на обоих узлах остаётся непроверенным.

Исправлен русский текст ошибки страницы просмотра при обёрнутом сообщении
`DesktopVpnManager`. Итоговая сборка 0.1.5 / 2026110203 успешна: 500 JVM-тестов
и 352 Android host-теста, без ошибок и пропусков. Подпись APK v1/v2 и CRC всех
273 элементов Windows ZIP проверены повторно. Контрольные суммы обновлены
в локальном `build/SHA256-maintenance-2026110203.json`. Файлы не опубликованы
как GitHub Release; прохождение тестов не заменяет живую проверку OpenFlux.
