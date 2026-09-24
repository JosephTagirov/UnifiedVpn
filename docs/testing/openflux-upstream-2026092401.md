# OpenFlux Upstream Upgrade: Validation Status

Historical checkpoint for build 2026092401. Later runtime checks and fixes are
recorded in the [build 2026092405 report](openflux-runtime-2026092405.md).

Исторический этап сборки 2026092401. Последующие проверки и исправления описаны
в [отчёте сборки 2026092405](openflux-runtime-2026092405.md).

## English

Candidate: **0.0.14 / 2026092401**, native wrapper **4**, unreleased.
The client source and native artifacts are updated. A new image was prepared
on the VPS, but **production was not switched**. No APK/installer was released,
no existing application was installed over, and nothing was published to GitHub.

### Changes

- Base: `d34dc8caa70ca059cd80d8f5753499361052dabc`, verified against GitHub main
  on September 23 UTC, replacing `4f1bdb554c262f3ae9adbfe317a092c6b929ba7d`.
  See the [upstream comparison](https://github.com/p1neappleXpress/OpenFlux/compare/4f1bdb554c262f3ae9adbfe317a092c6b929ba7d...d34dc8caa70ca059cd80d8f5753499361052dabc).
- Updated Go module imports and removed reliance on the old raw-socket API.
  Explicit L4 forwarding uses ordinary TCP sockets. New server containers drop
  all capabilities and no longer install RST rules inside their namespace.
- AES-256-GCM, directional keys, authenticated readiness, profile schema and
  legacy LZ4 remain unchanged. New upstream batch/zstd is not silently enabled.
  This updates the source base, not every upstream CLI feature or transport UI.
- Kept per-stack TCP buffers at 64 KiB / 256 KiB / 1 MiB instead of upstream's
  4 / 16 / 64 MiB. The adapter does not modify global upstream defaults.
- Fixed L4 request-EOF handling, destination refusal propagation, idle remote
  cleanup and shutdown during dialing. Normalized failed gVisor dials to a nil
  interface so error cleanup cannot panic on a typed nil connection.
- Both Gradle packagers require wrapper 4 and its exact manifest/hash. The
  server upgrade helper preserves the old package, image, container and config,
  serializes mutations per original instance, and verifies final state.

### Results

| Check | Result |
| --- | --- |
| Go transport, Yandex, session and tunnel suites | PASS; each suite repeated 20 times |
| Local encrypted TCP, response after request EOF, refusal, cancellation | PASS; synthetic data and loopback only |
| Windows/Linux amd64 and three Android ABI builds | PASS; Go 1.26.4, source archive, licenses and SHA-256 manifest |
| Server tooling | PASS; 205 offline tests, including staged upgrade, rollback, image preparation and ownership guards |
| JVM tests | PASS; 344 tests, zero failures/errors/skips |
| Android host tests | PASS; 221 tests, zero failures/errors/skips |
| Android compilation and native staging; Windows native staging | PASS |
| Windows wrapper-4 document check with isolated WebView2 | PASS; one request, about 6.6 seconds, direct egress, no native room join |
| VPS new image preparation | PASS; old container and configuration retained |
| VPS native document check without a browser | FAIL: `Yandex browser verification is required` |
| VPS actual Chromium sandbox in a network-disabled container | PASS; namespace/PID/network/Seccomp-BPF isolation, no added capabilities |
| VPS browser-to-wrapper-4 document handoff | PASS twice; about 7.6 and 7.4 seconds, one browser request per test |
| Real encrypted client/server traffic on the new pair | NOT RUN; durable migration/supervision still required |
| Android WebView runtime and real Android VPN traffic | NOT RUN |

Real-profile JVM gates were explicitly disabled; some tests return early rather
than reporting skipped. Test counts are not evidence of live VPN connectivity.
The first tunnel test run exposed typed-nil cleanup and was fixed before the
successful repeated run. Initial Gradle attempts used a wrong task name and a
missing default olcRTC path; the final run used the existing clean pinned tree.

The Windows check used a synthetic encryption key, inherited pipes and temporary
anonymous cookies. The native did not join the document WebSocket or start
SOCKS/TUN; browser WebSocket activity was not separately measured. The owned
helper/native processes and browser directory were removed. Host proxy, routes,
DNS and the protected working listeners matched their pre-test snapshot.

The first two VPS binary transfers stopped before the binary was stored. A
read-only reconciliation confirmed only public helper files in separate partial
directories. Git OpenSSH then completed the pinned transfer; a Git-specific
quoted-path argument was corrected first. No host-key check was disabled.

The new image was built using the already installed immutable runtime, without
pulling a new base or altering the original install. Its HTTPS-only preflight
failed before any production stop. A separate temporary diagnostic confirmed
the browser requirement and was removed by exact identity. Old files, container
state, services and routes matched. Raw firewall snapshots initially differed
because they included timestamps/counters; normalized IPv4 and IPv6 rules match
the original read-only audit. No firewall reset or working VPN restart occurred.

The subsequent browser image is a separate preparation, not the native runtime
described above. It pins Playwright 1.63.0 Noble and all four Python wheels by
SHA-256. Image assembly has no RUN/ADD or package installer. A bounded, non-root
container first ran with no network; the initial Playwright seccomp example
blocked Chromium's internal chroot. A complete, current pinned Moby profile plus
the four namespace-sandbox calls passed without additional capabilities or any
host security change. The initial probe also expected an outdated Chrome table
label; that assertion was corrected and covered by a regression test.

The final built-in probe passed with Chromium 153.0.8010.12, followed by a real
document check with native wrapper 4. Browser and native shared only the owned
probe's network namespace. The browser had no tunnel key/config mount. Ten
anonymous cookies were passed over inherited pipes, not written to receipts or
Docker logs; temporary browser storage disappeared with its container. Browser
WebSockets were blocked by the worker and native used `--check-document`, so no
native room was joined. Native exited 0 with `OPENFLUX_DOCUMENT_OK`; both owned
probe containers were removed. Production images/configs/restart policies were
not switched, and protected service/container/route/firewall snapshots matched.

Final browser image ID:
`sha256:80c9b77631681a3640fc9e2f2929dca9e46828e57ad08ba1055407f1e65d44fa`.
Seccomp policy SHA-256:
`8d150f5b8d503595e317bb75d6209e9f05c2b8deb1391fd7d777f3ff33472617`.
These are test provenance, not a production deployment or vulnerability audit.

### Remaining Gates

The experimental server browser companion is **not ready for production
migration**. The immutable browser image, complete seccomp policy, VPS sandbox
and HTTPS handoff are now tested. It still needs durable supervision/reboot
behavior and a staged-upgrade attachment path. Do not disable Chromium sandbox or global
AppArmor to pass a test. See [companion prerequisites](../../tools/openflux-server/BROWSER_BOOTSTRAP.md).

Before switching an existing instance, add independent exact-identity rollback
supervision that survives SSH/process loss. The current Python helper cannot
guarantee rollback after SIGKILL or host reboot. Then test authenticated peer,
tunneled DNS and strict HTTPS from Windows and Android sequentially, including
restart/reconnect and cleanup. Only one active client/server pair per document.

No full memory/load or race-detector run was performed. The upstream statistics
goroutine remains bounded by the native child process lifetime, not by stack
Close; the app terminates that owned process when stopping the engine.

### Local Outputs

Engine artifacts, not installable application releases:
`.downloads/openflux/artifacts-d34-20260924/`.

- Windows SHA-256: `a89b6781b36a176e694128b94cca3ed25e084a2b1e4d1bed6f4d4be659589b1a`.
- Linux SHA-256: `7b72adc0a3995f129dfdcd9c0c9e9dbca17b4d317e6f3d57ffe23d5dee987d12`.
- Source archive SHA-256: `d66aaccfdf0a8c11f88b23f5373c185e38f450b5e4dcbed03846606350b27c3a`.

Use `-Popenflux.artifactDir=.downloads/openflux/artifacts-d34-20260924` for Gradle.
Old artifacts and installed server packages remain available. Private receipts,
document URLs, keys, cookies and SSH details are not included in this report.

## Русский

Кандидат **0.0.14 / 2026092401**, native-обёртка **4**, не опубликован.
Исходники клиента и ядра обновлены; новый образ подготовлен на VPS, но
**рабочий сервер не переключён**. Установщик/APK не выпущены, установленные
приложения не заменялись, публикации на GitHub не было.

База обновлена с `4f1bdb5` до `d34dc8c`; полные коммиты и сравнение выше.
Адаптированы Go API и импорты, выбран L4 без raw-сокетов и RST-правил.
Формат профилей, AES-256-GCM, проверка сервера и прежний LZ4 сохранены;
batch/zstd и все новые функции upstream автоматически не включаются.
Сохранены прежние TCP-буферы, исправлены ответы после EOF запроса, отказ
назначения, закрытие зависших соединений, отмена dial и ошибка typed nil.

Прошли Go-тесты с 20 повторами каждого набора, сборки Windows/Linux и трёх
Android ABI, 205 серверных тестов, 344 JVM и 221 Android host-тест.
Компиляция Android и подготовка native-файлов обеих платформ прошли.
Реальные профили в этих наборах отключены: числа тестов не доказывают соединение.
Первые ошибочные попытки тестов/Gradle и их исправления описаны выше.

Новый Windows-клиент успешно проверил реальный документ через отдельный WebView2
примерно за 6,6 секунды. Использован случайный тестовый ключ, не настоящий.
Cookies передавались по каналам процессов, временный браузерный профиль удалён.
Native не входил в комнату и не поднимал VPN; активность WebSocket самого браузера
отдельно не измерена. Рабочие proxy, маршруты, DNS и защищённые listeners сохранены.

На VPS подготовлен отдельный образ из уже установленного закреплённого runtime.
Две первые бинарные передачи оборвались до записи ядра; рабочая установка не
изменялась. Передача через Git OpenSSH прошла с сохранённой проверкой host key.
Проверка документа новым контейнером выявила требование браузерной проверки
Яндекса. Отдельная HTTPS-диагностика подтвердила причину и удалена по своему ID.
Старый экземпляр не останавливался; нормализованные IPv4/IPv6 firewall-правила,
сервисы и маршруты сохранены. Начальная разница сырых хешей firewall вызвана
включением времени/счётчиков и не использована как доказательство изменения правил.

После этого отдельно подготовлен браузерный образ: Playwright 1.63.0, закреплённые
хеши всех четырёх Python-пакетов, сборка COPY без запуска установщиков. Первый
офлайн-тест выявил запрет chroot, необходимого Chromium внутри user namespace.
Полная актуальная политика Moby с четырьмя вызовами внутренней sandbox исправила
это без выдачи capabilities и изменения защиты хоста. В тесте также исправлено
устаревшее название строки страницы chrome://sandbox; добавлен регрессионный тест.

В финальном образе подтверждены namespace/PID/network/Seccomp-BPF sandbox,
NoNewPrivs и пустые capabilities. Затем дважды прошла реальная передача анонимной
браузерной сессии новому ядру, примерно за 7,6 и 7,4 секунды. В каждом тесте одна
проверка браузером и 10 cookies, переданных по каналам процессов, без записи в
отчёты или Docker-журналы. Браузер не получал ключ/конфиг туннеля; WebSocket
браузера заблокирован, native не входил в комнату. Результат ядра:
`OPENFLUX_DOCUMENT_OK`, код выхода 0. Оба тестовых контейнера удалены. Рабочие
контейнеры, конфиги, службы, маршруты и firewall сохранены; сервер не переключён.
Хеши финального браузерного образа и политики приведены выше. Полноценное
сканирование образа на уязвимости не проводилось.

Остаются обязательные этапы: постоянный supervisor с автозапуском и независимым
откатом, затем реальный зашифрованный HTTPS/DNS-трафик с Windows и Android по
очереди. SIGKILL/перезагрузка сейчас не покрыты гарантированным откатом.
Отключать защиту браузера или глобальный AppArmor ради успешного теста нельзя.
Полные проверки нагрузки, race detector и Android WebView на устройстве не проведены.

Каталог и хеши выше относятся к ядрам, не к готовым установщику приложения/APK.
Старые артефакты сохранены. Приватные ссылки, ключи, cookies и SSH-данные в этот
отчёт не включены. Готовность Windows-проверки документа не означает готовность VPN.
