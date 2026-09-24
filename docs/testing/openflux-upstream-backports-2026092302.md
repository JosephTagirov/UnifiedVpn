# OpenFlux: Compatible Upstream Fixes

## English

Local candidate: **0.0.14 / 2026092302**, native wrapper **3**. Unreleased;
no server installation, app installation or GitHub publication performed.

### Comparison

The GitHub main head was verified through the existing local proxy on
2026-09-23 UTC as `d34dc8caa70ca059cd80d8f5753499361052dabc` (September 20).
Our base is `4f1bdb554c262f3ae9adbfe317a092c6b929ba7d` (September 12).
The [upstream comparison](https://github.com/p1neappleXpress/OpenFlux/compare/4f1bdb554c262f3ae9adbfe317a092c6b929ba7d...d34dc8caa70ca059cd80d8f5753499361052dabc)
contains substantial changes, not just version metadata.

Included selectively, without claiming complete cherry-picks:

- [4513a14](https://github.com/p1neappleXpress/OpenFlux/commit/4513a14e704b0e5697be4966e4898ac5dcc6eb16): a slower reconnect backoff and closing failed Yandex sockets.
- [945cefd](https://github.com/p1neappleXpress/OpenFlux/commit/945cefd62c9b7e8dddc3952f86b58354d1d81929): blocking while the write queue is idle, retaining a pending packet across reconnects, and precompiled parsers. Its new batch codec is excluded.
- Local integration fixes: cancellation during waits, closing only the failed
  session, and completing both authentication writes before publishing the new
  socket to the writer/keepalive. Authentication has a deadline and cancellation
  closes an unpublished socket too.

A complete main upgrade is **not** a drop-in change. The Go module was renamed,
the old raw-socket API was removed, `NewTCPTunnel` now chooses a different server
backend, and default TCP buffers grew 64-fold (to 4/16/64 MiB). The new default
batch/zstd framing is incompatible with legacy framing. These changes need a
separate client/server migration, memory/load testing and rollback plan.

The base pin, mandatory AES-256-GCM, directional keys, authenticated readiness,
legacy LZ4, raw-server networking and profile format remain unchanged. There is
no plaintext fallback or automatic protocol downgrade. No upstream fix for the
Yandex JavaScript browser-verification issue was found in this comparison.

### Validation

- PASS: focused Go transport, Yandex and wrapper suites, then all three suites
  repeated 20 times using synthetic data/loopback only. Includes pending FIFO,
  auth-before-data, stale-session isolation, late-connection rejection, blocked
  write cancellation and idle/pending/timer cancellation, plus AES/replay/peer
  authentication regression tests.
- PASS: Go 1.26.4 builds Windows/Linux amd64 and three Android ABIs, with source
  archive, licenses and a SHA-256 manifest recording the reviewed head and
  selected compatibility backports.
- PASS: 166 offline server-tooling tests; old-wrapper rejection covers 1 and 2.
- PASS: 343 JVM tests and 221 Android host tests, zero failures/errors/skips.
  Real-profile integration gates were explicitly disabled; some gated tests
  return early, so these counts do not establish live connections. Android
  compilation/manifest processing and final hash-checked staging passed.
- Not run: real-document or encrypted VPN traffic with this wrapper-3 candidate,
  Android WebView runtime, VPS browser sandbox or a deployed-server update.
  Earlier wrapper-2 document checks are recorded separately and are not reused
  as evidence for this candidate. No further live attempt was assumed authorized.

Independent review found no remaining blocking issue in the selected backport.
Residual test gaps: cancellation while blocked inside authentication is covered
by code inspection rather than a dedicated test; idle-loop observation is
scheduler-based; no race-detector run or real-network reliability claim.

Local outputs: `.downloads/openflux/artifacts-yandex-backports-20260923/`.
Windows native SHA-256:
`c142d33fdb1fa2847bd98d2d238ec4d9304002721a4467d27187122d8c7dac51`.
These are engine candidates, not installable APK/EXE application releases.

```powershell
./tools/openflux/build.ps1 -ArtifactsPath .downloads/openflux/artifacts-yandex-backports-20260923
```

Gradle needs `-Popenflux.artifactDir=.downloads/openflux/artifacts-yandex-backports-20260923`.
Keep the old native artifacts and matching server manager for rollback. Do not
mix wrapper versions and manifests or replace a working server during this review.

## Русский

Локальный кандидат **0.0.14 / 2026092302**, native-обёртка **3**. Ничего не
установлено на сервер/устройства и не опубликовано на GitHub.

GitHub проверен через существующий прокси: main `d34dc8c` от 20 сентября
сравнен с нашей базой `4f1bdb5` от 12 сентября. Полные хеши и ссылки приведены
выше. Изменения существенные, но полный переход сейчас не сделан.

Выборочно перенесены исправления `4513a14` и `945cefd`: более спокойное
переподключение, закрытие оборванных сокетов, ожидание пустой очереди без частого
опроса, сохранение пакета при разрыве и предварительная компиляция парсеров.
Дополнительно исправлен порядок: новый WebSocket сначала проходит обе записи
авторизации, затем получает очередь и keepalive. Stop отменяет ожидания; ошибка
старого сокета не меняет состояние нового. Полные коммиты целиком не переносились.

Оставлены прежние AES-256-GCM, проверка сервера, LZ4, серверный сетевой режим
и формат профилей. Новый batch/zstd несовместим со старым форматом; полный
upstream также меняет Go-модуль и API сервера и увеличивает TCP-буферы в 64 раза.
Для этого нужна отдельная миграция обеих сторон с проверкой памяти и откатом.
Готового исправления JavaScript-проверки Яндекса в рассмотренных изменениях нет.

Пройдены Go-тесты и 20 повторов каждого из трёх наборов, сборки Windows/Linux
и трёх Android ABI, 166 серверных офлайн-тестов, 343 JVM-теста и 221 Android
host-тест без ошибок. Компиляция Android, манифест и финальная подготовка
native-артефактов по хешам прошли. Реальные профили явно отключены; некоторые
проверки при этом завершаются досрочно без отметки skipped. Эти числа не
доказывают подключение. Данные синтетические, сетевые тесты Go только loopback.

Другой агент проверил выбранные изменения; блокирующих замечаний не осталось.
Пробелы проверки: нет отдельного теста отмены во время блокирующей авторизации,
ожидание входа в idle-цикл зависит от планировщика, race detector не запускался.
На этой обёртке ещё не проверены реальный документ, VPN-трафик, Android WebView
и серверный браузер. Успех более раннего диагностического кандидата не считается
проверкой новой сборки. Рабочая сеть, сервер и приватные профили не менялись.

Каталог, хеш и команда сборки приведены выше. Это ядра, а не готовые установщик
приложения и APK. Старые артефакты сохранены; серверные инструменты должны
совпадать со своей версией ядра и манифестом, в том числе при откате.
