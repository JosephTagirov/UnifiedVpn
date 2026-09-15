# Encrypted OpenFlux Exit Node

Opt-in Linux amd64 deployment for the Unified VPN wrapper, pinned to
OpenFlux `4f1bdb554c262f3ae9adbfe317a092c6b929ba7d`. Yandex Docs only.
The wrapper requires AES-256-GCM and an authenticated client/server handshake;
upstream's original executable is not interchangeable with this entrypoint.

**Status:** prepared and unit-tested locally, not deployed or tested on the
shared Finland server. First validate on a disposable Linux VM. A document
share link may need to be replaced with its supported legacy editor link.

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
  capabilities before adding `NET_RAW` and `NET_ADMIN`, with a read-only root,
  no-new-privileges, 512 MiB memory, one CPU, and 128-process limits.
- RST suppression exists only in the container's own network namespace. The
  entrypoint refuses the recorded host namespace and fails if any rule fails.
  There is no host-wide RST suppression or firewall flush fallback.
- No OpenFlux logs are retained: underlying transport errors can contain the
  private document URL. `check` reports state, not end-to-end readiness.

Do not change, restart, or remove working olcRTC, Xray, AWG, Docker networks, or
host firewall rules to make this test pass. A separate VM remains preferable.
[Docker bridge behavior](https://docs.docker.com/engine/network/drivers/bridge/)
and [upstream exit-node requirements](https://github.com/p1neappleXpress/OpenFlux/blob/4f1bdb554c262f3ae9adbfe317a092c6b929ba7d/README.md)
explain why the separation matters.

## Prepare Offline Inputs

Use Python 3.9+, iproute2, and an already-running Docker Engine with the default
local BuildKit builder. Do not install or restart Docker on the shared VPN
server as part of this procedure.

On a disposable builder, obtain the reviewed runtime using `runtime.Dockerfile`
with an explicit `ALPINE_IMAGE=repository@sha256:<reviewed-digest>`. It installs
only CA certificates and iptables; package versions at that build time are not
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
unified-openflux 1 upstream=4f1bdb554c262f3ae9adbfe317a092c6b929ba7d protocol=unified-openflux-aesgcm-v1
```

Place the manager and inputs under a root-owned directory, such as
`/root/openflux-bundle`, not a user-writable checkout. Inputs and every parent
directory must be root-owned and not writable by others; no symlinks/hard links.
Never include credentials in the source archive, shell commands, environment
variables, links, screenshots, or a Docker build context.

## Private Configuration

Create a fresh private configuration in an interactive terminal. The document
URL is entered with echo disabled; a random 32-byte key is generated into the
mode-0600 file. Existing files are never overwritten.

```sh
sudo python3 manage.py configure --apply --output /root/openflux-server.json
```

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
exercise a Linux kernel, raw sockets, Docker networking, or real Yandex service.
The server folder does not install anything when imported or tested.

OpenFlux is GPL-3.0-or-later. The supplied upstream `LICENSE`, `NOTICE`, and
`COPYRIGHT`, provenance, and matching patched source are preserved in the
installation and at `/usr/share/openflux` in the image. These deployment and
wrapper changes belong to Unified VPN, not the upstream author. Preserve the
license notices and provide corresponding source when distributing the binary
or image. [Upstream license and notices](https://github.com/p1neappleXpress/OpenFlux/tree/4f1bdb554c262f3ae9adbfe317a092c6b929ba7d).

## Русский

Это подготовленный, но ещё не развёрнутый серверный вариант для Linux amd64.
Поддерживается только Яндекс Документы и обязательное шифрование AES-256-GCM.
Клиент и сервер должны использовать одну версию обёртки, одинаковую ссылку на
документ и один случайный 32-байтовый ключ. Обычный upstream-бинарник не подходит.

Сначала проверяйте на отдельной временной VM. На рабочем сервере Финляндии
ничего не запускалось и не менялось. Скрипт не подключается по SSH, не ставит
Docker, не перезапускает службы и не меняет напрямую host firewall, маршруты,
DNS или прокси. Однако создание Docker bridge добавляет собственные правила
NAT и маршрут через Docker. Полное отсутствие влияния на работающие VPN не
гарантируется; их нужно проверить отдельно.

`check` ничего не создаёт. Для `configure`, `install`, `run`, `stop` и `remove`
требуется явный `--apply`. Ссылку вводите только через скрытый запрос
`configure`; ключ и ссылка хранятся в root-файле с правами 0600. Не отправляйте
их в логи, команды, публичные ссылки или архив исходников. Нужна поддерживаемая
ссылка на старый редактор; обычная ссылка общего доступа может не подойти.

`stop` удаляет только свой контейнер и сеть. `remove` дополнительно удаляет
свой образ и известные файлы установки, включая копию конфигурации. Исходные
файлы и базовый runtime-образ остаются. Чужие ресурсы, неизвестные файлы и
изменённые идентификаторы не удаляются. Работающие olcRTC, Xray и AWG нельзя
останавливать или перенастраивать ради этого теста. Логи OpenFlux отключены,
поскольку ошибки транспорта могут содержать приватную ссылку. Успешный `check`
не заменяет проверку соединения и защищённого handshake с клиентом.
