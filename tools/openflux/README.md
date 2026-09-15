# OpenFlux Native Integration

Experimental integration for Unified VPN. Windows SOCKS/HTTPS and Android emulator
TUN/HTTPS passed [connection tests](../../docs/testing/openflux-2026091401.md);
this is not a production reliability or carrier-availability guarantee.
The upstream is [OpenFlux](https://github.com/p1neappleXpress/OpenFlux), pinned to
`4f1bdb554c262f3ae9adbfe317a092c6b929ba7d`. The wrapper protocol is
`unified-openflux-aesgcm-v1`; both endpoints must use this wrapper, not an
unmodified upstream executable.

## Build on Windows

Prerequisites: Git, Go **1.26.4**, Windows `tar`, and Android NDK
**28.2.13676358**. Run from the repository root:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File tools/openflux/build.ps1
```

The script checks the upstream commit without resetting an existing checkout,
copies `overlay`, pins `go-socks5` to `v0.1.3`, and runs the transport, Yandex,
configuration, and authenticated-session tests. It builds Windows/Linux amd64
and Android arm64-v8a, armeabi-v7a, and x86_64 binaries. Android PIE executables
use 16 KiB ELF alignment and `-checklinkname=0`, required by the pinned `anet`
dependency. `-AndroidNdk PATH` overrides NDK discovery. `-PrepareOnly` prepares
sources; `-SkipAndroid` deliberately produces an incomplete Android manifest.

Outputs are local and ignored by Git in `.downloads/openflux/artifacts`:

- `openflux-windows-amd64.exe` and `openflux-linux-amd64`;
- `android/<abi>/libopenflux.so`;
- `openflux-source.tar.gz`, including modified sources and vendored dependencies;
- `licenses/LICENSE`, `NOTICE`, `COPYRIGHT`, and a SHA-256 `manifest.json`.

Only stage artifacts after the complete build succeeds. Gradle verifies the
manifest and binary hashes before packaging. A failed build may leave individual
outputs newer than the last successful manifest. Distribute the corresponding
source archive and license notices with any distributed binaries. This script
does not publish releases or install anything on a server.

## Private Profiles and Link Check

Use a separate, empty Yandex document in the **legacy editor**. The pinned
upstream does not support the new editor. Its document request is unauthenticated:
access must work through the supplied link without browser cookies. A successful
HTTP response alone is not evidence that the transport is compatible.

```powershell
python tools/openflux/prepare_profile.py probe --document-file .downloads/private-test-profiles/yadocs_link.txt
python tools/openflux/prepare_profile.py generate --document-file .downloads/private-test-profiles/yadocs_link.txt --directory .downloads/private-test-profiles/openflux-test --socks-port 19181
python -m unittest discover -s tools/openflux/tests -p test_prepare_profile.py -v
```

Run `generate` as the account that will use the files. It creates a new private
directory, a random key, matching client/server configurations, and an importable
profile/URI. It refuses to overwrite an existing directory. Keep all four files
private; the URI contains credentials. Do not paste them into logs or issues.

The probe performs a bounded HTTPS read and reports only fixed schema diagnostics,
not the URL, document contents, title, or tokens. It does not join the document or
verify an encrypted peer. A compatible structure is necessary but not sufficient
for a working connection. Never change a working olcRTC room for this test.

## Runtime Contract and Limits

- Payload encryption is mandatory and uses upstream AES-256-GCM with directional
  keys. A fresh authenticated challenge is required before `OPENFLUX_READY`.
  There is no plaintext fallback. This is not a claim of a full security audit,
  forward secrecy, or unlimited replay protection; the upstream nonce cache is
  bounded to 4,096 entries. The provider can still observe connection metadata.
- Use one active client per document/key/server instance. Windows and Android
  tests must run sequentially. Upstream uses a fixed client tunnel address.
- The wrapper provides loopback-only SOCKS5 for TCP/IPv4. General UDP and IPv6 are
  not supported. Destination DNS uses TCP through the encrypted tunnel; resolving
  the Yandex transport itself still requires the underlying network.
- Windows uses the existing local SOCKS/system-proxy modes; this integration does
  not enable Windows TUN. Android uses the application's existing VPN bridge with
  the same TCP-only transport limits. Linux output here is the server binary, not
  confirmation of a Linux desktop OpenFlux client.
- `OPENFLUX_SERVER_WAITING` is not a successful client connection. Verification
  requires authenticated readiness, SOCKS5, real external HTTPS traffic, and clean
  shutdown on each platform. Compilation and HTTP 200 do not establish this.
- Isolated tests must record whether Yandex traffic uses an inherited proxy.
  Success through another VPN does not prove independent transport availability.
  Change only the test child environment, never the operator's active proxy/VPN.
- Server networking must be isolated. See [server tooling](../openflux-server/README.md).
  It must not apply a host-wide TCP RST rule, replace existing VPN services, or use
  host networking. Docker bridge creation still adds Docker-managed host routes
  and NAT rules, so allocation and rollback must be reviewed before starting it.

## Русский

Это экспериментальная интеграция. Windows SOCKS/HTTPS и Android TUN/HTTPS на
эмуляторе прошли [проверки](../../docs/testing/openflux-2026091401.md), что не
гарантирует производственную надёжность или доступность у оператора. Используется
OpenFlux с указанным выше закреплённым коммитом. Клиенту и серверу нужна одна версия
нашей обёртки; обычный upstream-бинарник с ней несовместим.

Команда сборки выше создаёт локальные Windows/Linux-бинарники, три Android ABI,
архив изменённых исходников с зависимостями, лицензии и манифест SHA-256. Сборка
требует Go 1.26.4 и Android NDK 28.2.13676358. Ничего не публикуется и на сервере
не устанавливается. После ошибки нельзя упаковывать частично обновлённые файлы:
сначала должна успешно завершиться вся сборка. Вместе с бинарниками необходимо
распространять соответствующие исходники и лицензии.

Для теста нужен отдельный пустой документ Яндекса в **старом редакторе**, доступный
по ссылке без авторизации в браузере. Новый редактор upstream пока не поддерживает.
Команда `probe` проверяет только структуру ответа и не выводит содержимое документа,
ссылку или токены. HTTP 200 и наличие нужных полей ещё не подтверждают VPN-соединение.

Команду `generate` следует запускать от пользователя, который будет использовать
конфиги. Она создаёт новую защищённую папку с ключом, конфигами клиента и сервера,
профилем и ссылкой импорта; существующие файлы не перезаписываются. Эти файлы и
ссылку нельзя публиковать: они содержат доступ к подключению.

Шифрование AES-256-GCM обязательно, открытого режима нет. Перед готовностью
проверяется зашифрованный ответ сервера. Это не заменяет полный аудит безопасности:
нет гарантии прямой секретности, а защита от повторов ограничена кэшем 4096 nonce.
Провайдер транспорта по-прежнему видит метаданные соединения.

Сейчас поддерживается один активный клиент на документ/ключ/серверный экземпляр.
Проверять Windows и Android нужно по очереди. Поддерживается SOCKS5 TCP/IPv4;
произвольный UDP и IPv6 не поддерживаются. DNS адресов назначения проходит через
зашифрованный туннель; для самого транспорта Яндекса нужна исходная сеть.

На Windows используются локальный SOCKS и системный прокси; режим TUN эта
интеграция не включает. Android использует существующий VPN-мост приложения с
тем же ограничением TCP. Linux-бинарник здесь предназначен для сервера, а не
подтверждает поддержку OpenFlux в Linux-версии интерфейса.

Сервер запускается отдельно от действующих VPN. Нельзя менять рабочие комнаты
olcRTC, останавливать AWG/Xray, использовать сеть хоста или блокировать TCP RST
на всём сервере. Создание Docker-сети всё же добавляет её маршруты и NAT-правила:
это нужно учесть при запуске и откате. Готовность подтверждается только реальным
HTTPS-трафиком через SOCKS и корректной остановкой на Windows и Android.
Если тестовый процесс использует унаследованный прокси другого VPN, это нужно
отмечать отдельно: такой успех не подтверждает самостоятельную доступность
транспорта. Менять можно окружение тестового процесса, но не рабочий VPN оператора.
