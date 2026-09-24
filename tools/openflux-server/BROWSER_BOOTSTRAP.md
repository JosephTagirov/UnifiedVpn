# Experimental Anonymous Browser Companion

This page describes standalone `browser_supervisor.py`. For staged migration
of an existing instance, see the separate
[managed lifecycle](BROWSER_LIFECYCLE.md); its current results do not remove
the standalone supervisor's restrictions below.

Эта страница описывает отдельный `browser_supervisor.py`. Для поэтапного
обновления существующего экземпляра см. [управляемый запуск](BROWSER_LIFECYCLE.md).
Его результаты не отменяют ограничения отдельного supervisor ниже.

This is an opt-in implementation, not a production-migrated fix. A sandboxed
browser and HTTPS-only native handoff passed on the VPS on September 24; this
does not establish encrypted tunnel traffic or durable service recovery.
The supervisor supports only a **new** instance whose name starts with "browser".
It refuses the existing default, phone2, releasewin and other production names.
No image is downloaded, built, installed or started by the read-only check.
Do not migrate a working exit node until the isolated end-to-end test passes.

Wrapper 5 supports explicitly selected `yandex` (classic Docs) and `vyandex`
(Volga/new editor) transports. Both peers must use the matching transport,
document and encryption key. Existing `yandex` profiles and documents are not
converted automatically; an editor or transport change requires a separate
profile and an isolated test instance. There is no automatic fallback between
the two document protocols.

## Boundaries

The wrapper-5 native L4 exit remains in its manager-created bridge/network
namespace, without raw-socket capabilities or namespace-local RST rules. The
browser runs in a **different** container as UID/GID 10001, dropping every
capability, with no key/config mount, Docker socket, published port, host PID,
host IPC, privileged mode or remote-debugging TCP listener. It shares the exact
owned native network namespace so browser and native requests use the same
egress path. This intentionally shares networking, not process or filesystem
privileges. Never merge the browser and native container or add sandbox-bypass
capabilities to either one.

Playwright launches Chromium over its internal pipe with its sandbox explicitly
enabled, strict TLS and a new anonymous nonpersistent context. There is no
profile reuse, login, SSO, cookie file, storage-state export, HAR, trace or
screenshot. Temporary home, profile and shared memory use private container
storage; no browser state is bind-mounted or copied to the host. Browser
resources are capped at 768 MiB, 0.75 CPU and 256 processes. Both containers use
Docker's "none" log driver; browser stdout is consumed only by the supervisor
pipe. Browser/native diagnostics are discarded.

Top-level navigation is limited to HTTPS Docs/Disk hosts. Iframes and HTTPS subresources
are limited by DNS-label suffix to the reviewed Yandex service/CDN roots.
Account/login/Passport/OAuth destinations, all browser WebSockets, service workers, downloads and
browser permissions are blocked. The browser parses exactly one bounded
client-config script. Classic Docs requires its balancer, editor token, document
key and explicit edit permission. Volga requires its explicit editor type, text
document configuration, a nonempty access token and an action URL on the exact
HTTPS `volga.yandex.ru` origin, with no credentials or fragment. Recognized
explicit read-only fields are rejected. These checks establish eligibility for
anonymous cookie bootstrap only; they do not establish editable access, a peer
connection or usable VPN traffic. Native authorization, the encrypted peer
handshake and real HTTPS traffic must pass independently. Auth cookie names,
other domains, non-root paths, malformed or oversized messages fail closed.
Applicable anonymous cookies are promoted to Secure because all requests use
HTTPS. No HTML, editor token or encryption key is passed to the browser parent.

Native requests have the stdout format:

    OPENFLUX_BROWSER_VERIFY <32-lowercase-hex-id>

The supervisor replies privately on stdin with the matching ID and cookie array
or the fixed verification_failed error. JSON payloads are at most 32 KiB;
1 through 64 cookies with names at most 128 bytes, values at most 4096 bytes
and aggregate native field size at most 24576 bytes are accepted. Exact
document-host domains without a leading dot remain host-only; duplicate
identities use the native's case-insensitive name/domain rules. Chromium uses
an explicit no-proxy-server flag for direct egress. The supervisor permits two
browser launches per rolling five-minute window, not two per process lifetime.
An ordinary verification failure cleans up only the browser; native remains
waiting and applies its own retry backoff. A changed cleanup identity disables
further browser launches and requests operator review, without restarting native.

## Prerequisites

Prepare the public-only context with `prepare_browser_image.py --output NEW_DIR`.
It verifies exact wheel hashes in `browser-image-lock.json`, including transitive
dependencies, preserves their license files and emits `browser-context.tar`, a
complete seccomp policy, its upstream license and a provenance receipt. An optional
`--proxy http://127.0.0.1:PORT` applies only to these public downloads. Nothing is
installed or executed from the wheels during preparation. Existing output
directories are refused; an incomplete context without a final receipt is invalid.

The locked Linux amd64 base is Playwright Python 1.63.0 Noble, manifest
`sha256:96b39581c89131729a7ecb8d532314af54c7f9bcc7a61fe15c7a9e77602acf59`.
Python packages are Playwright 1.63.0, pyee 13.0.1, greenlet 3.5.6 and
typing_extensions 4.16.0. `browser.Dockerfile` has COPY instructions but no RUN
or ADD instructions; it does not execute package installers or download code.
Build with `--network=none --pull=false`, supplying the locked base as
`--build-arg PLAYWRIGHT_IMAGE=...` and the generated tar as context. The base
must already have been pulled by exact digest. Check the resulting image ID
and inventory and retain its original tag. This is not a vulnerability-scan result.

A disposable builder remains preferred. For the separately authorized VPS test,
resource reserves were checked first, about 1 GB of public image layers were
downloaded, and only an isolated image was assembled. No host package, running
service or Docker daemon setting changed. Image preparation is not an incidental
side effect of `check`, `run` or the read-only preflight.

The generated **complete**, default-deny seccomp profile derives from Moby
`245180c51918481c0525424b3ee025d2b435d46c`, not the older profile in Playwright's
example. It retains current io_uring and socket-family restrictions, adding only
clone/setns/unshare/chroot for Chromium's internal user-namespace sandbox.
`chroot` remains subject to kernel permissions; no outer-container capability
is granted. The final policy SHA256 is
`8d150f5b8d503595e317bb75d6209e9f05c2b8deb1391fd7d777f3ff33472617`.
The supervisor requires a root-owned profile and its reviewed SHA256.
Unconfined seccomp/AppArmor, SYS_ADMIN, a disabled Chromium sandbox, and host IPC
are not supported.
A host denying unprivileged user namespaces may also require a separately
reviewed container-specific AppArmor policy. Never disable the global policy.
The current helper has no AppArmor-policy override; if the default profile
blocks Chromium, stop and design/review that support separately.

browser_preflight.py reports only resource and prerequisite metadata.
An enabled kernel user-namespace setting does **not** prove Chromium's sandbox
will run inside Docker. `browser_sandbox_probe.py` is an actual offline browser
self-test for an isolated `--network=none` container. It checks UID/GID, empty
effective capabilities, NoNewPrivs, container seccomp and Chromium's namespace,
PID, network and Seccomp-BPF status. That test passed on the VPS; the initial
missing chroot allowance and a Chromium status-label mismatch were corrected.
The later HTTPS-only document check passed twice, including the final image,
without joining the native room. All owned probes were removed; original
containers/services/routes and normalized IPv4/IPv6 rules matched their snapshots.
Unit tests alone do not prove these results. See the
[validation report](../../docs/testing/openflux-upstream-2026092401.md).

Official references:
[Playwright Docker isolation](https://playwright.dev/python/docs/docker),
[explicit Chromium sandbox option](https://playwright.dev/python/docs/api/class-browsertype#browser-type-launch-option-chromium-sandbox).

## New Test Instance

1. Build and hash wrapper-5 native artifacts with their matching source archive.
   Updated server helpers require exact wrapper-5 provenance and do not accept
   wrapper-1/2/3/4 artifacts under a fallback. Keep the corresponding old helper
   with an old package when operating it. Mandatory encrypted records and the
   version-1 private configuration envelope are retained; `transport` explicitly
   selects `yandex` or `vyandex` and is not inferred from a replacement URL.
   Staged legacy upgrades are deliberately refused by this experimental
   supervisor: durable attachment, reboot behavior and independent rollback
   supervision require a separate review before migrating a browser-gated exit.
2. Use the existing reviewed install procedure for a fresh name such as
   "browsercheck", a new document/key and an independently checked unused subnet.
   Stop after **install**. Do not run the ordinary manager's "run" first.
3. Place reviewed browser_supervisor.py, browser_worker.py and matching manage.py
   together in a root-owned, non-writable-by-others helper directory. These
   optional files are not silently added to the SSH uploader's fixed manifest;
   transferring them needs a separate reviewed operation.
4. Run the read-only companion check, replacing public image/hash placeholders:

       sudo python3 browser_supervisor.py check --instance browsercheck \
         --browser-image sha256:REVIEWED_BROWSER_IMAGE_ID \
         --seccomp /root/reviewed-browser-seccomp.json \
         --seccomp-sha256 REVIEWED_SECCOMP_SHA256

5. After explicit authorization, use the same arguments with "run --apply".
   Keep it foregrounded under a controlled supervisor; no systemd unit or
   autostart is installed by this experimental helper. It refuses preexisting
   runtime resources, creates the new native container with private stdin
   enabled, and launches a browser only when native requests one. Browser
   execution is bounded below native's 50-second response deadline. A failed
   browser does not trigger a native restart.
6. Record the browser sandbox result, matched protocol response, authenticated
   encrypted handshake and strict HTTPS traffic. Recheck every protected service
   independently before treating the new exit node as usable.

## Stop And Roll Back

A normal foreground stop removes only the recorded new browser/native
containers and owned bridge, keeping installed files and config. If interrupted,
inspect first. "cleanup --apply" with the same arguments checks the instance,
image, owner labels, recorded IDs and shared namespace before removing anything.
The private browser-companion.json receipt contains resource IDs, not cookies or
the document URL. Changed identities cause refusal, not force removal.

An interruption while creating the initial native network/container may precede
the browser-mode state marker; use the matching installed manager's reviewed
"stop --apply --instance browsercheck" only after inspecting that partial state.
Do not restart, rebuild, remove or change policy on old working instances.
No firewall, route, DNS, proxy, Docker-daemon or kernel setting is edited by the
helper. Docker's creation/removal of the new owned bridge still changes its own
network infrastructure as documented in the main README.

## Русский

Это экспериментальный помощник, а не завершённое обновление рабочего сервера.
24 сентября на VPS прошли реальная проверка sandbox и HTTPS-проверка документа
новым ядром через браузер. Это ещё не проверка зашифрованного VPN-трафика.
Он работает только с новым экземпляром с именем `browser...`; существующие
рабочие имена отклоняются. Команда `check` ничего не скачивает, не устанавливает
и не запускает. Рабочий сервер нельзя переносить до успешного отдельного теста.

Wrapper 5 поддерживает явно выбранные транспорты `yandex` (старый редактор Docs)
и `vyandex` (Volga, новый редактор). На клиенте и сервере должны совпадать
транспорт, документ и ключ шифрования. Существующие профили `yandex` и документы
автоматически не переводятся на новый редактор. Для смены редактора или
транспорта нужны отдельный профиль и изолированный тестовый экземпляр.
Автоматического переключения между двумя протоколами документов нет.

Браузер разбирает ровно один `client-config` ограниченного размера. Для старого
редактора обязательны адрес балансировщика, токен редактора, ключ документа и
явное разрешение редактирования. Для Volga проверяются тип редактора, текстовый
тип документа, непустой токен доступа и HTTPS-адрес действия строго на
`volga.yandex.ru`, без данных авторизации в адресе и без фрагмента. Известные
явные запреты записи отклоняются. Это только допуск к получению временных
анонимных cookies, а не подтверждение права редактирования, подключения к серверу
или передачи VPN-трафика. Авторизация ядра, зашифрованное рукопожатие и реальный
HTTPS-трафик проверяются отдельно.

Браузер запускается в отдельном контейнере от пользователя `10001:10001`, без
capabilities, привилегированного режима, Docker socket, ключа туннеля, серверного
конфига и общих PID/IPC-пространств хоста. Только сетевое пространство нового
проверяемого native-контейнера общее: проверка Яндекса и ядро должны выходить с
одного адреса. Действуют ограничения памяти, CPU, числа процессов и времени.
Chromium sandbox и проверка TLS обязательны, WebSocket браузера закрывается,
личный профиль и аккаунт не используются. Cookies передаются через каналы
процессов, не через файлы или Docker-журнал. Ошибка проверки не перезапускает
native-контейнер бесконечно.

`prepare_browser_image.py --output НОВЫЙ_КАТАЛОГ` готовит только публичный контекст
сборки, проверяя закреплённые хеши всех Python-зависимостей. Установщики пакетов
не запускаются: Dockerfile использует COPY без RUN/ADD. Для загрузок доступен
необязательный локальный HTTP proxy. Незавершённый контекст без итогового отчёта
использовать нельзя. Полная seccomp-политика и её лицензия также входят в результат.

База, версии пакетов и хеш политики перечислены выше. Политика основана на
актуальном закреплённом профиле Moby; добавлены только системные вызовы внутренней
песочницы Chromium, включая chroot внутри user namespace. Capabilities внешнего
контейнера остаются пустыми. Сохраняются ограничения io_uring и семейств сокетов.
Совместимость с обычным AppArmor на этом VPS подтверждена офлайн-запуском браузера.
Отключать глобальную защиту или запускать браузер с `--no-sandbox` нельзя.

Отдельно согласованная подготовка образа на VPS проверила запас ресурсов,
загрузила около 1 ГБ публичных слоёв и не меняла системные пакеты или службы.
Проверки sandbox и передачи временных cookies ядру прошли; пробные контейнеры
удалены, рабочие контейнеры, маршруты, службы и правила IPv4/IPv6 сохранены.
Это не полноценное сканирование образа на уязвимости. Обычная команда `check`
ничего не собирает и не скачивает автоматически.

Новые серверные помощники требуют точные сведения о сборке wrapper 5 и не
принимают wrapper 1/2/3/4 как запасной вариант. Старый пакет следует запускать
только с соответствующим ему помощником. Обязательное шифрование и версия 1
формата приватного конфига сохраняются; поле `transport` явно выбирает `yandex`
или `vyandex`. Замена одной ссылки не переключает транспорт.

Порядок проверки: собрать wrapper 5, подготовить новый документ и ключ,
установить отдельный тестовый экземпляр без запуска, проверить владельца и
права файлов помощника, затем выполнить `check`. Только после подготовки
выполняется `run --apply` с теми же параметрами. Автозапуск/systemd этим
экспериментальным помощником не устанавливается. Успех подтверждается не одним
состоянием процесса, а проверкой зашифрованного соединения и реального HTTPS.

Остановка удаляет только записанные ресурсы нового экземпляра. После аварийного
прерывания сначала нужна проверка состояния; `cleanup --apply` проверяет
идентификаторы, владельца, образ и сетевое пространство. При несовпадении
удаление запрещено. Рабочие экземпляры не перезапускаются и не удаляются.
Создание нового Docker bridge меняет собственную сетевую инфраструктуру Docker;
настройки proxy, DNS, ядра и глобальные правила firewall помощник не меняет.
