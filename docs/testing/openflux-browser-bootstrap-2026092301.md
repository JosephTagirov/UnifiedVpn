# OpenFlux Browser Bootstrap: Local Validation

## English

Status: **unreleased and not deployed**. Application build identity is
`0.0.14 / 2026092301`; previous application installers/APKs are preserved.
This report does not claim that Windows or Android VPN traffic is restored.

This is the wrapper-2 test snapshot. Subsequent local changes and wrapper 3 are
tracked [separately](openflux-upstream-backports-2026092302.md); the live results
below apply only to the recorded hashes, not to later rebuilt candidates.

### Scope

The Yandex Documents HTTPS response can be a browser-verification page instead
of the legacy editor configuration. Merely opening the document in a personal
browser does not transfer that verification session to OpenFlux.

Native wrapper 2 retains upstream pin
`4f1bdb554c262f3ae9adbfe317a092c6b929ba7d` and encrypted wire protocol
`unified-openflux-aesgcm-v1`. A request carries a random 128-bit ID over stdout;
its matching bounded response arrives on stdin. The parent supplies the
document URL to an isolated browser, never the tunnel key. Imported cookies
are origin-scoped, secure, account-cookie-filtered and given a maximum validity
of two hours in an in-memory native cookie jar. Renewal is limited to two
attempts per rolling five minutes, with a failure backoff. Document URLs,
editor tokens and cookies are not logged. Browser cookies and editor tokens
are not added to profiles; the existing private document URL/key fields remain.

Windows uses a dedicated InPrivate WebView2 helper with its own process job and
temporary data directory; the Evergreen Runtime must already be installed.
Android uses a non-exported service in `:openflux_verification`, a dedicated
WebView data-directory suffix and the selected non-VPN network. It requires
API 28 or newer only when browser verification is needed. Android WebView is
not an OS incognito API: its private app-owned store may transiently write data
and is cleared before and after each request. Platform navigation callbacks
are not an absolute network firewall. Interactive human verification is not
implemented and must fail closed.

### Verified

- Focused Go tests pass for transport encryption, Yandex bootstrap and the
  native wrapper. Tests include malformed/oversized/duplicate JSON, mismatched
  IDs, cancellation, cookie scope, redirects, renewal and production parsing.
- Go 1.26.4 builds Windows amd64, Linux amd64 and Android arm64-v8a,
  armeabi-v7a and x86_64 candidates. Artifacts and source archive have a
  manifest with exact wrapper-2 provenance and SHA-256 hashes. They are separate
  from the previous release artifacts.
- The full offline JVM suite reported 343 tests and the full Android host
  suite 221 tests, with no failures/errors/skips. Real-profile integration
  gates were explicitly disabled, so these totals do not establish any live
  VPN connection. Kotlin compilation, manifest merge, manifest-verified
  staging of all three Android OpenFlux libraries and Windows native staging
  passed. These are not device/WebView tests.
- The server tooling suite passed 166 local tests, including cookie-contract
  limits, origin policy, resource ownership and failure backoff. No browser
  image was installed or executed on the VPS by these tests.
- Windows isolated offline WebView2 smoke passed: fresh InPrivate context,
  synthetic editor predicate, secure synthetic cookie, no allowed network
  requests, restricted temporary-directory permissions and cleanup of owned
  browser processes and storage. This is not a live Yandex test.
- The production native `--check-document` path plus an anonymous Chrome pipe
  provider passed a real-document check on 2026-09-23 at 20:01-20:02 UTC.
  It requested verification once, returned `OPENFLUX_DOCUMENT_OK` and exited
  successfully in approximately 12 seconds. Main-frame navigation remained on
  Docs/Disk hosts. Browser WebSockets were blocked; no encrypted room, real
  tunnel key, SOCKS listener or VPN/TUN was used. Protected listeners and host
  proxy/routes/DNS were unchanged. The receipt is private and not published.

### Approved Live WebView2 Tests

The first approved attempt ran on 2026-09-23 at 20:24:59-20:25:07 UTC:

- WebView2 reached the editable document and returned 14 anonymous cookies
  in 4.97 seconds; the isolated helper exited successfully.
- The native `--check-document` handoff **failed**, with exit code 1 and the
  generic `browser_session` category. That binary did not distinguish provider
  response rejection, cookie validation, retry HTTPS failure or repeated
  verification. The precise cause is therefore not established.
- Native tunnel startup was not invoked. Browser WebSocket activity was not
  explicitly blocked or measured in this WebView2 test and must not be inferred
  from the earlier Chrome test.
- Owned helper/native processes stopped, remaining owned browser processes
  were zero, and temporary browser data/config were removed. Protected
  listeners and host proxy/routes/DNS remained unchanged. No automatic retry
  was made.

First tested native SHA-256:
`7f7e864637c37c449c16d48346360f50425fd133939634d5657691bf95dc8207`.
Tested packaged helper SHA-256:
`c1f4d09fe14a6e4ef76215eba6c20b64894006b48646807b7c3eb3b8cc7a06a3`.
The private receipt contains no cookie values or document URL.

Subsequent local source adds fixed failure-stage labels without private error
text, URLs or cookies. Focused Go tests, all five native targets and hash-checked
Android/Windows staging pass. These labels are diagnostic only and do not change
cookie transfer or retry behavior.

After separate user approval, exactly one further attempt ran at
20:39:52-20:39:59 UTC and **passed**: WebView2 returned 13 anonymous cookies in
4.33 seconds, the native process returned `OPENFLUX_DOCUMENT_OK`, and both
processes exited successfully. The total recorded interval was 6.67 seconds.
Cleanup and host-network/listener checks passed again; native tunnel startup
was not invoked and browser WebSocket activity remained unmeasured.

Second tested native SHA-256:
`5852fa019c3c8d35bb9f72d9171a7d5d20b0a89088bb3d70d94dd453dc1b363d`.
The packaged helper was unchanged. The earlier failure is still unexplained;
one successful handoff is not evidence that intermittent failures or VPN
connectivity are fixed. No third live attempt was made or assumed authorized.

### Release Gates

1. Resolve the intermittent WebView2 handoff failure and validate the full
   packaged desktop lifecycle, including Kotlin orchestration, not just a
   successful isolated browser/native diagnostic.
2. Use a fresh owned API 35 emulator and test-only same-UID instrumentation to
   exercise the actual Android service, network binding, cancellation and
   cleanup. No suitable independent browser-service runtime harness exists yet;
   the old debug receiver starts VPN and is not used as a shortcut.
3. Build/review a pinned server browser image and complete seccomp policy;
   verify Chromium sandbox compatibility with VPS AppArmor in an isolated new
   instance. The read-only prerequisite check alone is insufficient. Do not
   disable the sandbox or global AppArmor restrictions to make the test pass.
4. Only after these gates, test authenticated encrypted readiness, strict TLS
   external traffic, disconnect/reconnect and teardown on Windows and Android
   with a separately designated document. No production client slot is assumed
   available indefinitely.

The server helper and rollback boundaries are described in
[BROWSER_BOOTSTRAP.md](../../tools/openflux-server/BROWSER_BOOTSTRAP.md).
No server restart, global networking change, GitHub push or release was made.

### Reproduce Local Native Build

With the existing pinned source, cached dependencies, Go 1.26.4 and Android NDK:

```powershell
./tools/openflux/build.ps1 -ArtifactsPath .downloads/openflux/artifacts-browser-20260923
```

Gradle packaging must use
`-Popenflux.artifactDir=.downloads/openflux/artifacts-browser-20260923`.
Old wrapper-1 manifests are deliberately rejected by the new packaging checks.
The diagnostic CLI is `openflux --config <private-json> --bootstrap-stdio
--check-document`; it requires a cooperating parent on the private pipes, not
cookies entered on the command line. `--check-config` remains fully offline.

## Русский

Статус: **не опубликовано и не установлено на сервере**. Идентификатор локальной
разработки: `0.0.14 / 2026092301`; прежние APK и установщики сохранены. Этот
отчёт не подтверждает восстановление VPN-трафика на Windows или Android.

Это снимок проверок wrapper 2. Последующие изменения и wrapper 3 описаны в
[отдельном отчёте](openflux-upstream-backports-2026092302.md); живые результаты
ниже относятся только к указанным хешам, не к последующим пересборкам.

Причина исследуемой ошибки: вместо параметров старого редактора Яндекс отдаёт
ядру страницу браузерной проверки. Проверка в личном браузере не передаёт его
сессию автоматически в OpenFlux. Новая обёртка получает временные анонимные
cookies через закрытые каналы процессов, проверяет источник, размер, срок и
идентификатор ответа. Ключ туннеля браузеру не передаётся; шифрование и формат
профилей не меняются. Автоматическая проверка поддержана экспериментально;
интерактивная проверка человеком пока не реализована.

Пройдены тесты Go, сборки ядра для Windows/Linux и трёх Android ABI, 343 JVM-теста,
221 Android host-тест и 166 серверных тестов. Компиляция Kotlin, манифест и
проверенная по хешам подготовка native-библиотек также прошли. Реальные профили
в полном офлайн-прогоне были отключены; число тестов не доказывает VPN-соединение.
Отдельный WebView2 прошёл офлайн-проверку
с синтетическим документом и проверкой очистки. Реальный документ прошёл новый
режим `--check-document` с анонимным Chrome примерно за 12 секунд: без
WebSocket-комнаты, реального ключа, SOCKS/TUN и изменений рабочей сети. Это
проверяет получение параметров редактора, но не VPN-подключение.

Разрешённая живая проверка WebView2 23 сентября 2026 года, 20:24:59-20:25:07 UTC,
**не прошла сквозным образом**. Браузер открыл редактируемый документ и вернул
14 анонимных cookies за 4,97 секунды, но ядро завершилось с кодом 1 и общей
категорией `browser_session`. Эта версия диагностики не различала отказ при
передаче ответа, валидации cookies, повторном HTTPS-запросе и повторной проверке
Яндекса. Точная причина пока не установлена; хеши проверенных бинарников указаны
выше. Автоматического повтора не было.

Ядро не запускало туннель. WebSocket-активность самого WebView2 отдельно не
блокировалась и не измерялась. Созданные процессы остановлены, временные данные
удалены, рабочие слушатели портов, прокси, маршруты и DNS не изменились.
В локальный код после теста добавлены безопасные метки этапа ошибки без ссылок,
cookies и исходного текста приватных ошибок. Это только диагностика, не
изменение передачи сессии или правил повтора. После изменения прошли тесты Go,
сборки всех пяти native-целей и проверка упаковки Windows/Android по хешам.

После отдельного разрешения пользователя выполнена ровно одна дополнительная
попытка в 20:39:52-20:39:59 UTC: **проверка прошла**. WebView2 вернул 13
анонимных cookies за 4,33 секунды, ядро ответило `OPENFLUX_DOCUMENT_OK`, оба
процесса завершились успешно. Общий измеренный интервал составил 6,67 секунды.
Очистка и неизменность рабочей сети подтверждены повторно. Ядро не запускало
туннель; WebSocket-активность браузера не измерялась. Хеш второго ядра указан
выше, браузерный помощник не менялся. Причина первого сбоя остаётся неизвестной;
один успешный результат не доказывает стабильность или работу VPN-трафика.
Третьего живого теста не было, разрешение на него не предполагается.

На Android используется отдельный процесс и хранилище WebView. Это не системный
режим инкогнито: временные данные могут записываться в приватное хранилище
приложения, которое очищается до и после проверки. Нужна проверка на эмуляторе;
компиляция и host-тесты её не заменяют. На VPS пока нет проверенного браузерного
образа и полной seccomp-политики; совместимость sandbox с AppArmor не доказана.
Рабочие контейнеры не изменялись, ограничения ОС не отключались.

До выпуска остаются выяснение причины первого сбоя WebView2, проверка полного
жизненного цикла Windows-приложения, запуск Android-сервиса на эмуляторе,
отдельный серверный тест и сквозной зашифрованный трафик на обеих
платформах. Ничего не отправлено на GitHub. Команда локальной сборки и параметр
Gradle приведены выше; старые артефакты намеренно не перезаписываются.
