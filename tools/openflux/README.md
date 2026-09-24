# OpenFlux Native Integration

Experimental integration for Unified VPN. Windows SOCKS/HTTPS and Android emulator
TUN/HTTPS passed [connection tests](../../docs/testing/openflux-2026091401.md);
this is not a production reliability or carrier-availability guarantee.
The upstream is [OpenFlux](https://github.com/p1neappleXpress/OpenFlux), pinned to
`d34dc8caa70ca059cd80d8f5753499361052dabc`. The wrapper protocol is
`unified-openflux-aesgcm-v1`; both endpoints must use this wrapper, not an
unmodified upstream executable.

The local wrapper-5 candidate adds an explicit `vyandex` (Volga/new editor)
transport alongside the unchanged default `yandex` (legacy editor). Both peers
must select the same transport; there is no editor detection or fallback.
Existing installed wrapper-4 binaries are not changed by these source changes.
The wrapper uses the updated upstream base and an explicit
L4 server backend without raw sockets. Existing profiles retain legacy LZ4 and
the encrypted wire format; upstream batch/zstd is deliberately not enabled.
The adapter retains the previous per-stack TCP memory limits, preserves response
delivery after request EOF, and cancels server sockets on shutdown. Real-document
and device tests are separate gates; earlier release tests do not validate this
candidate. See the [upgrade validation](../../docs/testing/openflux-upstream-2026092401.md)
and [experimental browser bootstrap](../../docs/testing/openflux-browser-bootstrap-2026092301.md).

## Build on Windows

Prerequisites: Git, Go **1.26.4**, Windows `tar`, and Android NDK
**28.2.13676358**. Run from the repository root:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File tools/openflux/build.ps1
```

The script checks the upstream commit without resetting an existing checkout,
copies `overlay`, pins `go-socks5` to `v0.1.3`, and runs the transport, Yandex,
configuration, authenticated-session, and local encrypted TCP tests. It builds Windows/Linux amd64
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

Use a separate, empty Yandex document in the editor matching the selected
transport: **legacy editor** for `yandex` (the default), or **new Volga editor**
for explicit `--transport vyandex`. Do not convert an active document in place.
Volga requires matching wrapper-5 client/server binaries and its own separate
connection tests; profile generation alone does not establish compatibility.
The document request is unauthenticated:
access must not require a signed-in account. The experimental bootstrap can
obtain temporary anonymous verification cookies in a separate browser; it never
imports a personal browser session. The command below remains a plain HTTPS
probe and does not run browser verification. A successful HTTP response alone
is not evidence that the transport is compatible.

```powershell
python tools/openflux/prepare_profile.py probe --document-file .downloads/private-test-profiles/yadocs_link.txt
python tools/openflux/prepare_profile.py generate --document-file .downloads/private-test-profiles/yadocs_link.txt --directory .downloads/private-test-profiles/openflux-test --socks-port 19181
python tools/openflux/prepare_profile.py generate --transport vyandex --document-file .downloads/private-test-profiles/yadocs_volga_link.txt --directory .downloads/private-test-profiles/openflux-volga-test --socks-port 19183
python -m unittest discover -s tools/openflux/tests -p test_prepare_profile.py -v
```

Run `generate` as the account that will use the files. It creates a new private
directory, a random key, matching client/server configurations, and an importable
profile/URI. It refuses to overwrite an existing directory. Keep all four files
private; the URI contains credentials. Do not paste them into logs or issues.

The probe performs a bounded HTTPS read and reports only fixed legacy-editor schema diagnostics,
not the URL, document contents, title, or tokens. It does not join the document or
verify an encrypted peer. A compatible structure is necessary but not sufficient
for a working legacy-editor connection; the probe does not validate Volga.
Never change a working olcRTC room for this test.

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

Локальный кандидат wrapper 5 добавляет явный транспорт `vyandex` для нового
редактора Volga. Прежний `yandex` остаётся вариантом по умолчанию. Клиент и сервер
должны использовать один транспорт; автоматического выбора редактора или подмены
транспорта нет. Изменение исходников не обновляет установленные бинарники wrapper 4.
Используется обновлённая база upstream и серверный
режим L4 без raw-сокетов. Существующие профили сохраняют LZ4 и прежний
зашифрованный формат; новый batch/zstd намеренно не включён. Адаптер сохраняет
прежние лимиты TCP-буферов, позволяет получить ответ после завершения отправки
запроса и отменяет серверные сокеты при остановке. Реальные документы и устройства
проверяются отдельно: результаты прежних выпусков не подтверждают этот кандидат.
Результаты и ограничения приведены в двух отчётах выше. Временная анонимная проверка браузером
экспериментальная; личный браузер и cookies аккаунта не используются. Команда
`probe` сама браузерную проверку не запускает.

Команда сборки выше создаёт локальные Windows/Linux-бинарники, три Android ABI,
архив изменённых исходников с зависимостями, лицензии и манифест SHA-256. Сборка
требует Go 1.26.4 и Android NDK 28.2.13676358. Ничего не публикуется и на сервере
не устанавливается. После ошибки нельзя упаковывать частично обновлённые файлы:
сначала должна успешно завершиться вся сборка. Вместе с бинарниками необходимо
распространять соответствующие исходники и лицензии.

Для теста нужен отдельный пустой документ Яндекса, доступный для редактирования
по ссылке без авторизации: **старый редактор** для `yandex` по умолчанию или
**новый редактор Volga** при явном `--transport vyandex`. Не переводите рабочий
документ в другой редактор. Для Volga нужны соответствующие клиент и сервер
wrapper 5; реальные подключения проверяются отдельно от генерации профиля.
Команда `probe` проверяет только структуру старого редактора, не подтверждает
совместимость Volga и не выводит содержимое документа,
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
