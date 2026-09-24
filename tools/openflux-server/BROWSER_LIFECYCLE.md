# Managed Browser Lifecycle

## English

`browser_lifecycle.py` is the systemd controller for an explicitly prepared
`upgrade.py` receipt. It is separate from the foreground upgrade controller and
the new-instance-only `browser_supervisor.py`. Do not run competing controllers.
Current validation and limitations are in the
[runtime report](../../docs/testing/openflux-runtime-2026092405.md).

Installation requires a reviewed, root-owned helper directory, a prepared
private upgrade receipt, a locally present immutable browser image and the
reviewed seccomp policy/hash. It refuses existing unit files or bindings.
It does not install dependencies, reboot, reset the host firewall or change
Jitsi, AWG, Xray, routes or DNS. Keep provider-console access available.

```sh
sudo python3 browser_lifecycle.py install --instance INSTANCE \
  --receipt-directory /root/REVIEWED_RECEIPT \
  --browser-image sha256:REVIEWED_LOCAL_IMAGE_ID \
  --seccomp /root/REVIEWED_POLICY.json \
  --seccomp-sha256 REVIEWED_POLICY_SHA256 --apply

sudo python3 browser_lifecycle.py check --instance INSTANCE \
  --receipt-directory /root/REVIEWED_RECEIPT

sudo python3 browser_lifecycle.py check-document --instance INSTANCE \
  --receipt-directory /root/REVIEWED_RECEIPT --apply
```

`install` binds helper hashes, exact units, image, policy and host network
namespace. It enables recovery, not the replacement runtime. Document checking
does not stop the original or join the document WebSocket room.

With all clients of that document disconnected, a separately authorized trial
can run for 30 through 600 seconds:

```sh
sudo python3 browser_lifecycle.py switch --instance INSTANCE \
  --receipt-directory /root/REVIEWED_RECEIPT --trial-seconds 600 --apply
```

It arms an independent recovery timer before stopping the exact original
container. The candidate keeps Docker autorestart disabled; systemd supervises
native and the isolated, key-free browser companion. The controller stores its
PID start identity, boot ID and deadline. Recovery is ordered before runtime
startup and restores the original on an interrupted/unproven trial. Changed
resource identities fail closed instead of authorizing arbitrary deletion.

Only a trusted test coordinator may write the fresh, private `traffic-proof.json`
described in [UPGRADE.md](UPGRADE.md), after real authenticated peer, tunneled DNS
and HTTPS tests. An active service, browser success or local SOCKS port is not
proof. Do not create the file to bypass a failing client test. A committed
runtime is enabled only if the original was running before the trial.

For explicit rollback, use the installed, hash-bound helper path:

```sh
sudo python3 /root/REVIEWED_RECEIPT/runtime/browser_lifecycle.py rollback \
  --instance INSTANCE --receipt-directory /root/REVIEWED_RECEIPT --apply
```

First stop or wait for the trial controller; a concurrent instance lock is not
permission to force resource removal. Original packages, images and stopped
candidates are retained for diagnosis. Never use a stale receipt against newer
unit bindings. A real SIGKILL recovery passed; live reboot and long-running
production behavior remain unverified.

## Русский

`browser_lifecycle.py` управляет systemd-запуском для заранее подготовленного
экземпляра `upgrade.py`. Это отдельный контроллер: нельзя одновременно запускать
его и foreground-обновление для одного экземпляра. Актуальные проверки и
ограничения перечислены в [отчёте](../../docs/testing/openflux-runtime-2026092405.md).

Команды выше требуют проверенных root-owned файлов, приватного каталога
результатов подготовки, локального браузерного образа по точному ID и политики
seccomp по точному хешу. Установка отказывается перезаписывать прежние unit-файлы.
Она включает восстановление, но не заменяет рабочий процесс. Проверка документа
не останавливает старый сервер и не входит в WebSocket-комнату.

Перед `switch` отключите все клиенты этого документа. Независимое восстановление
включается до остановки старого контейнера; учитываются точные ID, время старта
PID, идентификатор загрузки и предел 30-600 секунд. Docker autorestart нового
контейнера выключен: им и отдельным браузером без ключей управляет systemd.

Фиксация требует свежего приватного подтверждения настоящего зашифрованного
соединения, DNS и HTTPS. Работающий процесс или открытый SOCKS-порт не являются
подтверждением. Нельзя создавать файл подтверждения ради обхода неудачного теста.
Откат сохраняет старые файлы и образы. Используйте только актуальный связанный
каталог, не снимайте блокировку принудительно и держите доступ к консоли VPS.

Восстановление после настоящего SIGKILL проверено. Перезагрузка VPS и длительная
промышленная работа пока не проверены. Рабочий Jitsi-путь менять нельзя; утилита
не перезагружает VPS и не сбрасывает firewall, DNS или маршруты.
