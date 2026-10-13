# Encrypted OpenFlux Exit Node

Opt-in Linux amd64 deployment for the Unified VPN wrapper, pinned to
OpenFlux `d34dc8caa70ca059cd80d8f5753499361052dabc`. Yandex Docs only.
The wrapper requires AES-256-GCM and an authenticated client/server handshake;
upstream's original executable is not interchangeable with this entrypoint.

New candidate packages require wrapper version 7 and use the upstream L4 TCP exit.
They allow explicit `yandex` (legacy editor) or `vyandex` (new Volga editor);
the default remains `yandex`, with no automatic detection or fallback. A new
transport requires matching client/server binaries and separate real traffic
validation. Do not replace an active document or reuse its key for this test. The
legacy AES/LZ4 wire format and peer authentication remain the default. Optional
`wire_codec: "batch-zstd-v2"` is an explicit classic-editor-only opt-in requiring
matching support on both peers; it never falls back to legacy. The anonymous, sandboxed browser
companion is a separate, opt-in experimental path documented in
[BROWSER_BOOTSTRAP.md](BROWSER_BOOTSTRAP.md). Ordinary manager commands do not
launch a browser. Installed legacy helpers/configurations are not upgraded
or changed by editing this repository. See [staged upgrades](UPGRADE.md) for the
explicit, receipt-bound path that retains the original installation for rollback.

Roll out the wrapper-7 core with its matching browser helper. Older strict cores
reject the helper's optional success-response `user_agent` field; wrapper 7
accepts old cookie-only responses without User-Agent matching. See the
[paired rollout warning](BROWSER_BOOTSTRAP.md#paired-core-and-helper-rollout).
The version change alone does not establish a live fix or validate a release.

**Status:** wrapper 5 and the Volga transport passed bounded real-traffic checks
with the packaged Windows client and the exact Android release APK in an
API 35 emulator. See the [Volga validation report](../../docs/testing/openflux-volga-2026092406.md)
and [release notes](../../docs/releases/0.0.14-build.2026092406.md).
These historical results do not validate wrapper 6's optional batch codec; it
requires its own encrypted-handshake and real-traffic checks before deployment.
The earlier [wrapper-4 report](../../docs/testing/openflux-runtime-2026092405.md)
records the previous Android limitation, not the current Volga result.
The separate [systemd browser lifecycle](BROWSER_LIFECYCLE.md) passed real
controller-loss recovery; a full host reboot remains untested.
Do not change an encrypted profile's exact document URL merely to try another
editor URL; it is part of the peer context.

## Isolation And Limits

- No SSH, package installation, service restart, daemon configuration, host
  `iptables`, `sysctl`, route, DNS, or proxy commands are performed.
- An existing local rootful Docker daemon is required. Remote Docker contexts
  and containerized execution of the manager are not supported.
- `run` creates only the named `unifiedvpn-openflux-net` bridge and
  `unifiedvpn-openflux` container. **Docker itself adds its bridge, route, and
  NAT/firewall infrastructure on the host.** This is not a zero-network-change
  deployment. Subnet checks cannot predict future VPN routes or custom rules.
- No host networking, published ports, host PID namespace, privileged mode,
  kernel modules, device mounts, or automatic restart. The container drops all
  capabilities without adding `NET_RAW` or `NET_ADMIN`, with a read-only root,
  no-new-privileges, 512 MiB memory, one CPU, and 128-process limits.
- Wrappers 4 through 7 use OS TCP sockets instead of the old raw exit. No RST suppression
  or other firewall command is run, even inside the new container. The
  entrypoint still refuses the recorded host namespace. The root UID is retained
  only to read the existing root-only secret mount, not to grant capabilities.
- No OpenFlux logs are retained: underlying transport errors can contain the
  private document URL. `check` reports state, not end-to-end readiness.

Do not change, restart, or remove working olcRTC, Xray, AWG, Docker networks, or
host firewall rules to make this test pass. A separate VM remains preferable.
[Docker bridge behavior](https://docs.docker.com/engine/network/drivers/bridge/)
and [upstream exit-node requirements](https://github.com/p1neappleXpress/OpenFlux/blob/d34dc8caa70ca059cd80d8f5753499361052dabc/README.md)
explain why the separation matters.

## Prepare Offline Inputs

Use Python 3.9+, iproute2, and an already-running Docker Engine with the default
local BuildKit builder. Do not install or restart Docker on the shared VPN
server as part of this procedure.

On a disposable builder, obtain the reviewed runtime using `runtime.Dockerfile`
with an explicit `ALPINE_IMAGE=repository@sha256:<reviewed-digest>`. It installs
only CA certificates; package versions at that build time are not
locked. Review and record the resulting image ID, keep an original image tag,
then transfer it with Docker's normal save/load workflow. The server manager
accepts only an already-present immutable image ID or repository digest. It
creates a unique temporary local alias for BuildKit and removes that alias
after the build; it does not pull a base or runtime image deliberately.

The supplied Linux bundle must contain:

- `openflux-linux-amd64`, statically linked with `CGO_ENABLED=0`.
- The matching patched source `.tar.gz`, including upstream source, wrapper,
  dependencies/build information needed to reproduce the binary.
- Unmodified upstream `LICENSE`, `NOTICE`, and `COPYRIGHT` files.
- Trusted SHA256 values for the binary and source archive.

Build instructions and wrapper source live in `../openflux/`. The installer
checks both hashes and validates the executable header. An isolated build with
`--network=none` verifies the exact `--version` output below and required runtime
utilities. Nothing starts the exit node during installation.

```text
unified-openflux 8 upstream=d34dc8caa70ca059cd80d8f5753499361052dabc protocol=unified-openflux-aesgcm-v1
```

Place the manager and inputs under a root-owned directory, such as
`/root/openflux-bundle`, not a user-writable checkout. Inputs and every parent
directory must be root-owned and not writable by others; no symlinks/hard links.
Never include credentials in the source archive, shell commands, environment
variables, links, screenshots, or a Docker build context.

## Private Configuration

Already have a verified installation and need another device? The separate
[admin wizard](ADMIN.md) adds pinned browser verification, automatic subnet
selection, and a persistent service for a fresh profile. It requires a reviewed
server-specific manifest and never replaces a working instance. The original
[profile creation helper](CREATE_PROFILE.md) generates a separate private
profile and reuses verified public artifacts. Its default is preparation only;
Docker changes require explicit `--apply`.

Create a fresh private configuration in an interactive terminal. The document
URL is entered with echo disabled; a random 32-byte key is generated into the
mode-0600 file. Existing files are never overwritten.

```sh
sudo python3 manage.py configure --apply --output /root/openflux-server.json
```

For a separate new-editor document, use `configure --transport vyandex` with a
new output path. `--transport` is accepted only by `configure`; installation and
run commands use the value inside the private JSON and cannot override it.

The schema is:

```json
{
  "version": 1,
  "mode": "server",
  "transport": "yandex",
  "document_url": "https://docs.yandex.ru/REPLACE_WITH_PRIVATE_EDITOR_PATH",
  "encryption_key": "REPLACE_WITH_64_HEX_CHARACTERS_FROM_32_RANDOM_BYTES",
  "handshake_timeout_seconds": 60
}
```

Do not use the placeholders as credentials. The timeout range is 5..180 seconds.
The optional `wire_codec` accepts only `legacy-lz4-v1` (default when omitted) or
`batch-zstd-v2` (only with `transport: "yandex"`). To prepare a separate batch
profile, add `--transport yandex --wire-codec batch-zstd-v2` to `configure` and
use a new output file and unused classic-editor document. Ordinary configuration
generation omits the default field, retaining the original JSON shape. These CLI
options cannot override an installed configuration. Updating the binary alone
does not change the codec. Both peers must explicitly use the same codec;
batch mode requires wrapper 6 or later compatible support on both endpoints.

Both peers need the same exact document URL and encryption key. Move these
values to the client through a private channel; do not publish a profile link.
If a key or link leaks, rotate both peers. Root and Docker administrators can
read the mounted file; a container is not a boundary against those operators.

## Check, Install, Run

Review an unused private subnet before proceeding. The example subnet below is
not guaranteed to be free; the manager checks all IPv4 routing tables and
existing Docker subnets and refuses conflicts, including broad VPN routes.
Replace the three hash placeholders with trusted values, not computed values
from an untrusted download.

```sh
sudo python3 manage.py check \
  --binary /root/openflux-bundle/openflux-linux-amd64 \
  --binary-sha256 BINARY_SHA256 \
  --source-archive /root/openflux-bundle/openflux-source.tar.gz \
  --source-sha256 SOURCE_SHA256 \
  --licenses-dir /root/openflux-bundle/licenses \
  --runtime-image sha256:RUNTIME_IMAGE_ID \
  --config /root/openflux-server.json \
  --subnet 172.30.251.0/28
```

`check` is the default and is read-only: it never executes the supplied binary,
builds an image, starts a container, allocates a network, or contacts Yandex.
After a separate review, repeat the same arguments with `install --apply`
instead of `check`. Installation creates only `/opt/unifiedvpn-openflux` and
its owned Docker image. Build cache may remain; no global prune is performed.
The config copy is outside the image build context and is mounted read-only at
`/run/secrets/server.json` only when running.

```sh
sudo python3 /opt/unifiedvpn-openflux/manage.py run --apply
sudo python3 /opt/unifiedvpn-openflux/manage.py check
```

Keep your existing SSH session open and retain the provider console before any
future shared-server test. Validate an authenticated client handshake, tunneled
TCP traffic and DNS, then independently re-check every existing VPN. IPv6,
general UDP, production stability, and compatibility with the server's custom
firewall have not been validated by these unit tests.

## Stop And Roll Back

```sh
sudo python3 /opt/unifiedvpn-openflux/manage.py stop --apply
sudo python3 /opt/unifiedvpn-openflux/manage.py remove --apply
```

`stop` removes only the recorded, ownership-labeled container and bridge,
preserving installed files and secrets. `remove` also removes the owned image
and the known installation files, including its secret copy. Original input
files and the original runtime image remain. Unknown files, changed resource
IDs/labels, or another endpoint on the bridge cause a refusal, not a force
delete. No unrelated containers, services, images, or networks are stopped.
Interrupted installation/start is handled by the same explicit cleanup commands.
Do not replace a refused cleanup with recursive deletion or a Docker prune.

## Tests And License

`inspect_remote.py` is a separate read-only inspector intended to be sent through
the verified SSH controller's stdin to `python3 -`, without installing it on the
server. It reports only OS/resources, selected service states, field-filtered
Docker metadata, and IPv4 routes. `--firewall-hashes` optionally adds normalized
hashes/counts, never rule contents. It does not read VPN configurations, container
environments, commands, mounts, or logs, and never executes a container. Keep the
full JSON in a private report; only its hostname/IP-free `summary` is suitable for
terminal output. The schema is `unifiedvpn-openflux-server-inspect-v1`; this is
descriptive inspection, not a transport or end-to-end test.

```sh
python -m unittest discover -s tools/openflux-server/tests -v
```

These tests use mocked Docker calls and fake public credentials. They do not
exercise a Linux kernel, Docker networking, or real Yandex service.
The server folder does not install anything when imported or tested.

OpenFlux is GPL-3.0-or-later. The supplied upstream `LICENSE`, `NOTICE`, and
`COPYRIGHT`, provenance, and matching patched source are preserved in the
installation and at `/usr/share/openflux` in the image. These deployment and
wrapper changes belong to Unified VPN, not the upstream author. Preserve the
license notices and provide corresponding source when distributing the binary
or image. [Upstream license and notices](https://github.com/p1neappleXpress/OpenFlux/tree/d34dc8caa70ca059cd80d8f5753499361052dabc).

## Русский

Для дополнительного устройства при уже работающем проверенном сервере есть
[админский мастер](ADMIN.md#русский) с закреплённым браузерным помощником,
автоматическим выбором подсети и постоянной службой нового профиля. Нужен
проверенный манифест для конкретного сервера; рабочие экземпляры не заменяются.
Исходный
[пошаговый помощник создания профиля](CREATE_PROFILE.md#русский): отдельный
документ, новый случайный ключ и приватный файл импорта. По умолчанию выполняется
только подготовка; Docker меняется лишь с явным `--apply`.

Новые пакеты-кандидаты требуют wrapper 7: `yandex` выбирает старый редактор,
`vyandex` выбирает новый Volga. По умолчанию остаётся `yandex`, без автоматической
подмены транспорта. Для отдельного нового документа укажите
`configure --transport vyandex` и новый путь `--output`. Этот параметр допустим
только при создании конфига; запуск использует транспорт из приватного JSON.
Нужны соответствующие клиент и сервер и отдельная проверка реального трафика.
Не меняйте рабочий документ и не переносите его ключ в тестовый профиль.

Обновляйте ядро wrapper 7 вместе с соответствующим браузерным помощником.
Старое строгое ядро отклоняет необязательное поле `user_agent` успешного ответа;
wrapper 7 принимает прежние ответы только с cookies, но без совпадения User-Agent.
См. [предупреждение об обновлении пары](BROWSER_BOOTSTRAP.md#согласованное-обновление-ядра-и-помощника).
Само изменение версии не подтверждает исправление в сети или проверку релиза.

Необязательное поле `wire_codec` принимает `legacy-lz4-v1` (по умолчанию, также
при отсутствии поля) или `batch-zstd-v2` (только для `transport: "yandex"`). Для
отдельного нового профиля добавьте к `configure` параметры
`--transport yandex --wire-codec batch-zstd-v2`, новый выходной файл и неиспользуемый
документ старого редактора. На обеих сторонах должны совпадать codec; для batch
нужна поддержка wrapper 6 или совместимой более новой версии. Автоматического
перехода к legacy нет. Обновление бинарника само по себе codec не меняет, а
значение по умолчанию не добавляется в создаваемый JSON, сохраняя старый формат.

Wrapper 5 с транспортом Volga прошёл ограниченные проверки реального трафика
упакованного Windows-клиента и точного релизного APK в эмуляторе Android API 35.
См. [отчёт Volga](../../docs/testing/openflux-volga-2026092406.md) и
[примечания к релизу](../../docs/releases/0.0.14-build.2026092406.md).
[Отчёт wrapper 4](../../docs/testing/openflux-runtime-2026092405.md) описывает
прежнее ограничение Android, а не актуальный результат Volga.
Поддерживается только Яндекс Документы и обязательное шифрование AES-256-GCM.
По умолчанию L4-сервер сохраняет прежний защищённый протокол, LZ4 и формат профиля;
существующим клиентам не нужна замена ссылки или ключа. Обе стороны используют
одинаковую ссылку на документ и один случайный 32-байтовый ключ. Обычный
upstream-бинарник не подходит. Результаты wrapper 5 не подтверждают новый batch
codec: перед установкой нужны отдельные проверки handshake и реального трафика.
Raw-сокеты, NET_RAW, NET_ADMIN и правила подавления
RST новому контейнеру не нужны.

Сначала проверяйте на отдельной временной VM. Обычный manage.py не подключается
по SSH, не ставит
Docker, не перезапускает службы и не меняет напрямую host firewall, маршруты,
DNS или прокси. Однако создание Docker bridge добавляет собственные правила
NAT и маршрут через Docker. Полное отсутствие влияния на работающие VPN не
гарантируется; их нужно проверить отдельно.

`check` ничего не создаёт. Для `configure`, `install`, `run`, `stop` и `remove`
требуется явный `--apply`. Ссылку вводите только через скрытый запрос
`configure`; ключ и ссылка хранятся в root-файле с правами 0600. Не отправляйте
их в логи, команды, публичные ссылки или архив исходников. Нужна поддерживаемая
ссылка на редактор, соответствующий выбранному транспорту, с редактированием
без входа в аккаунт; обычная ссылка общего доступа может не подойти.

`stop` удаляет только свой контейнер и сеть. `remove` дополнительно удаляет
свой образ и известные файлы установки, включая копию конфигурации. Исходные
файлы и базовый runtime-образ остаются. Чужие ресурсы, неизвестные файлы и
изменённые идентификаторы не удаляются. Работающие olcRTC, Xray и AWG нельзя
останавливать или перенастраивать ради этого теста. Логи OpenFlux отключены,
поскольку ошибки транспорта могут содержать приватную ссылку. Успешный `check`
не заменяет проверку соединения и защищённого handshake с клиентом.

Для обновления существующей установки предназначен отдельный [upgrade.py](UPGRADE.md):
старые файлы, профиль, образ и контейнер сохраняются для отката. Выбор экземпляра
всегда явный. До остановки старого контейнера новый бинарник обязан пройти
HTTPS-проверку документа; затем в ограниченном пробном запуске нужны реальные
handshake, HTTPS и DNS через туннель. Для JavaScript-проверки и независимого
восстановления подготовлен отдельный [systemd-контроллер](BROWSER_LIFECYCLE.md).
Он проверен при SIGKILL пробного контроллера, но не при перезагрузке VPS.
Обычный foreground `upgrade.py` сам по себе не даёт такого восстановления.
Не меняйте точную ссылку зашифрованного профиля ради другого редактора:
она участвует в контексте соединения.
