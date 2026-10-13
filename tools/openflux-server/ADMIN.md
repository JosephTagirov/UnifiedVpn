# OpenFlux Admin Wizard

## English

This is a **server administrator tool**, separate from the Unified VPN app.
It creates another encrypted OpenFlux profile on an already configured Linux
amd64 server. It does not create Yandex accounts or documents, update an existing
VPN, install Docker, or expose an administration website.

Tool version: **1**. Application release 0.0.14 / build 2026092406 is unchanged.
This wizard is not part of that release's APK or EXE.

The wizard passed a separate live Volga test on Windows and an Android API 35
emulator; see [results and limits](../../docs/testing/openflux-admin-20260925.md).

### Daily Use

After the reviewed admin bundle and its server-specific manifest are installed:

```sh
sudo unifiedvpn-openflux-admin --apply
```

The wizard asks for a short new name, an editor, a document URL with terminal
echo disabled, and confirmation before installation. Use `yandex` for the legacy
editor or `vyandex` for the new Volga editor. There is no automatic transport
fallback. Create a separate empty document, editable through its link without
signing in, for each simultaneously connected device. Never reuse an active
device's document or convert it to a different editor in place.

New helper bundles validate wrapper-7 source artifacts. The codec remains
`legacy-lz4-v1` unless explicitly selected with `--wire-codec batch-zstd-v2`,
which is accepted only for `--transport yandex`. Both peers must use the same
codec; the batch profile requires wrapper 6 or later compatible support on both
endpoints. The nondefault codec is included in the private import URI; legacy
URIs are unchanged.
Install updated helpers and their reviewed manifest in a new directory. Do not
replace frozen helper files for a running service or reuse the old source pins.
The prior live Volga results do not establish batch-codec interoperability.

Roll out the wrapper-7 core and matching browser helper as a reviewed pair.
Older strict cores reject the new optional success-response `user_agent` field.
Wrapper 7 accepts old cookie-only replies, but those do not provide User-Agent
matching. See [paired rollout](BROWSER_BOOTSTRAP.md#paired-core-and-helper-rollout).

The wizard generates a new random AES-256 key, selects an unused private Docker
subnet, and creates an independent server instance with a browser companion and
a persistent systemd unit. Verified public binaries, source, licenses, and
already-present images are reused. No downloads are performed.

Without `--apply`, it prepares private files only, without Docker or network
operations. The subsequent apply command is printed. A generated profile or a
running service is **not proof of connectivity**: import the profile into the
released app and check authenticated readiness and real Internet traffic.

### Get The Import Link

The wizard prints the path to `profile.uri`, not the secret link itself. Files
are root-only. Transfer that file privately to the intended device, or view it
in your own private SSH terminal and copy it into Unified VPN. The entire link
is a credential; Base64 is not encryption. Do not put it in public messages,
GitHub, logs, or screenshots.

One document supports one active client. For a second device, run the wizard
again with a new name and a different document. A shared link is not bound to a
particular person or device, and anyone holding it can use that profile.

### Stop And Remove

Use the exact rollback command printed for the prepared profile. It binds
cleanup to that profile's recorded ownership, files, and unit identity, rather
than finding and stopping every OpenFlux process. Private preparation files and
the original public artifacts remain.

An interrupted apply must be inspected and rolled back with its original plan;
do not repeat installation, edit ownership metadata, recursively delete the
installation, or run Docker prune. A changed/foreign resource causes a refusal.
Stopping or rolling back a profile disconnects that profile's client.

### Installation Contract

The bundle must be placed in a new root-owned directory with no writable-by-others
parents, symbolic links, or hard links. It contains `create_profile.py`,
`browser_service.py`, `browser_supervisor.py`, `browser_worker.py`, `manage.py`,
`Dockerfile`, `entrypoint.sh`, a reviewed browser seccomp file, and
`admin-runtime.json`. The manifest pins:

- Its schema and independent admin tool version.
- A verified installed source instance and public artifact/license hashes.
- Immutable runtime/browser image IDs and the seccomp file/hash.
- The helper source hashes and an RFC1918 subnet allocation pool.

This manifest is server-specific and must come from the reviewed installation;
do not copy image IDs from an unrelated machine. Each new service freezes its
own helper copies and hashes. Updating the admin tools does not silently change
the helpers of an already running profile.

The optional `/usr/local/sbin/unifiedvpn-openflux-admin` launcher calls the
installed `create_profile.py --browser-companion` with isolated Python imports.
An existing command must not be overwritten without checking its ownership and
target. The lower-level entry point is:

```sh
sudo python3 -I /root/REVIEWED-ADMIN-DIRECTORY/create_profile.py \
  --browser-companion --apply
```

Keep a provider console and an existing SSH session available. Docker creates
its own bridge routes and NAT rules; this is not zero-impact networking. No
existing olcRTC, AWG, Xray, OpenFlux, SSH, or Docker service is deliberately
restarted. Only public artifacts are reused from the selected source instance.
Existing document URLs are compared locally in memory to reject duplicate
documents; existing private configurations are not copied or changed.
Root/Docker administrators can read server credentials. TCP over IPv4 is the
supported OpenFlux data path; general UDP/IPv6 are not provided by this wrapper.

### Validation

```sh
python -m unittest discover -s tools/openflux-server/tests -v
```

Unit tests use synthetic credentials and mocked system commands. Separate live
checks must establish encrypted connectivity, real traffic, owned-client stop,
service restart/reconnect, and preservation of existing services. A successful
systemd start alone does not establish these properties. A full VPS reboot and
long-term or physical-device testing must be reported separately.

## Русский

Это **инструмент администратора сервера**, отдельный от Unified VPN. Он создаёт
ещё один зашифрованный профиль OpenFlux на уже настроенном Linux amd64. Мастер
не создаёт аккаунты или документы Яндекса, не обновляет существующий VPN,
не устанавливает Docker и не открывает сайт администрирования.

Версия инструмента: **1**. Релиз приложения 0.0.14 / build 2026092406 не меняется.
В его APK и EXE этот мастер не входит.

Мастер прошёл отдельную живую проверку Volga на Windows и в эмуляторе Android
API 35; см. [результаты и ограничения](../../docs/testing/openflux-admin-20260925.md#русский).

### Обычное Использование

После установки проверенного набора и манифеста именно для вашего сервера:

```sh
sudo unifiedvpn-openflux-admin --apply
```

Мастер спросит короткое новое имя, редактор, ссылку на документ со скрытым
вводом и подтверждение установки. `yandex` означает старый редактор,
`vyandex` означает новый редактор Volga. Автоматической подмены транспорта нет.
Для каждого одновременно подключённого устройства создавайте отдельный пустой
документ с редактированием по ссылке без входа в аккаунт. Не используйте документ
активного подключения и не переводите его в другой редактор.

Новый набор помощников проверяет исходные артефакты wrapper 7. По умолчанию
остаётся `legacy-lz4-v1`; явный `--wire-codec batch-zstd-v2` разрешён только с
`--transport yandex`. Codec должен совпадать на обеих сторонах, для batch нужна
поддержка wrapper 6 или совместимой более новой версии. Новое значение включается
в приватную ссылку импорта, старые ссылки не меняются. Обновлённые помощники и проверенный манифест должны
лежать в новом каталоге: не заменяйте закреплённые файлы работающей службы и
не используйте прежние хеши исходных артефактов. Прошлые живые проверки Volga
не подтверждают совместимость нового batch codec.

Обновляйте ядро wrapper 7 и соответствующий браузерный помощник согласованно.
Старое строгое ядро отклоняет новое необязательное поле `user_agent` успешного
ответа. Wrapper 7 принимает прежний ответ только с cookies, но тогда совпадение
User-Agent не обеспечивается. См. [порядок обновления пары](BROWSER_BOOTSTRAP.md#согласованное-обновление-ядра-и-помощника).

Мастер создаст отдельный случайный ключ AES-256, выберет свободную подсеть Docker
и установит независимый экземпляр с браузерным помощником и постоянной службой
systemd. Используются проверенные публичные бинарники, исходники, лицензии и
уже имеющиеся образы. Скачивания из сети нет.

Без `--apply` создаются только приватные файлы, без обращения к Docker или сети.
Команда последующего применения выводится отдельно. Созданный профиль или
запущенная служба **ещё не подтверждают подключение**: импортируйте профиль в
приложение и проверьте зашифрованное соединение и настоящий интернет-трафик.

### Получение Ссылки

Мастер показывает путь к `profile.uri`, а не саму секретную ссылку. Файлы доступны
только root. Передайте файл нужному устройству приватно либо откройте его в своём
закрытом SSH-терминале и скопируйте ссылку в Unified VPN. Вся ссылка является
секретом: Base64 не защищает её. Не публикуйте её в сообщениях, GitHub, журналах
или скриншотах.

Один документ допускает одного активного клиента. Для второго устройства снова
запустите мастер с другим именем и новым документом. Ссылка не привязана к
конкретному человеку или устройству: использовать профиль сможет любой,
у кого она есть.

### Остановка И Удаление

Используйте точную команду отката, напечатанную для созданного профиля. Она
проверяет сохранённого владельца, файлы и службу этого экземпляра, а не ищет
и останавливает все процессы OpenFlux. Приватные файлы подготовки и исходные
публичные артефакты сохраняются.

Прерванную установку нужно проверить и откатить по исходному плану. Не повторяйте
установку, не меняйте метаданные владельца, не удаляйте каталоги рекурсивно и не
запускайте Docker prune. Чужой или изменённый ресурс приводит к отказу.
Остановка или откат профиля отключают его клиента.

### Установка И Ограничения

Набор устанавливается в новый каталог root, без доступных другим пользователям
для записи родителей и без ссылок. В нём находятся семь файлов помощников,
перечисленных выше, проверенный seccomp-файл и `admin-runtime.json`. Манифест
закрепляет версию инструмента, исходный экземпляр, хеши публичных артефактов и
лицензий, идентификаторы уже имеющихся образов, хеши помощников и пул подсетей.
Он относится к конкретному серверу; нельзя подставлять образы другой машины.

Каждая новая служба получает отдельную копию помощников с закреплёнными хешами.
Обновление мастера не меняет код уже работающих экземпляров. Короткая команда
`unifiedvpn-openflux-admin` запускает установленный `create_profile.py` с
`--browser-companion` и изолированным импортом Python. Существующую команду
нельзя перезаписывать без проверки владельца и пути запуска.

Сохраните доступ к консоли хостинга и открытый SSH-сеанс. Docker добавляет
маршруты своего bridge и NAT-правила, поэтому сетевое влияние не является
нулевым. Мастер не перезапускает существующие olcRTC, AWG, Xray, OpenFlux, SSH
или Docker. Повторно используются только публичные артефакты исходного экземпляра.
Ссылки существующих конфигураций сравниваются локально в памяти для защиты от
повторного документа; существующие приватные конфигурации не копируются и не меняются.
Администратор root/Docker может прочитать серверные ключи. Поддерживается TCP
через IPv4; общего UDP и IPv6 этот wrapper не предоставляет.

### Проверки

Команда unit-тестов приведена выше. Они используют вымышленные ключи и имитацию
системных команд. Живые тесты должны отдельно проверить шифрованное соединение,
реальный трафик, остановку своего клиента, перезапуск и повторное подключение
службы, сохранность существующих служб. Запуск systemd этого не доказывает.
Полная перезагрузка VPS, длительные тесты и проверка физических устройств
должны отмечаться отдельно.
