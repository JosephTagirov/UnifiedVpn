# Create Another OpenFlux Profile

## English

`create_profile.py` is a local Linux amd64 helper for an **already installed,
verified encrypted OpenFlux server**. It wraps the existing `manage.py` named
instance operations. It does not connect over SSH, download software, create a
Yandex document, install Docker, or update an existing server. The helper is
offline-tested with simulated Docker responses, not a live deployment guarantee.

### One Command To Prepare

Create a **separate empty document in the legacy Yandex editor**, with editing
access through its link without browser login. One simultaneously connected
device needs one document, one key, and one server instance. Never give a second
device the document used by your active OpenFlux connection.

`yandex` is the default transport. For a separate document in the **new Volga
editor**, explicitly pass `--transport vyandex`. Both endpoints must use matching
wrapper-5 binaries; the helper refuses older public artifacts. This is not an
in-place migration, and a prepared profile is not proof of a working connection.
There is no automatic editor detection or transport fallback.

Place these four matching reviewed files in a new root-owned tools directory,
for example `/root/openflux-profile-tools`: `create_profile.py`, `manage.py`,
`Dockerfile`, and `entrypoint.sh`. Files and all parent directories must be
root-owned, not writable by other users, and not symlinks or hard links. Do not
copy tools into an existing `/opt/unifiedvpn-openflux*` installation. Python
3.9+ and the original reviewed public artifacts must already be present.

```sh
sudo python3 -I /root/openflux-profile-tools/create_profile.py
```

The prompts ask for:

- A new instance name, for example `phone2`: 1..16 lowercase ASCII letters or
  digits, starting with a letter. `default` and existing names are refused.
- The existing instance supplying public artifacts, normally `default`.
- An unused private Docker subnet. `172.30.252.0/28` is a candidate, **not a
  guarantee that the subnet is available**. Live overlap checks happen on apply.
- The new document URL, with terminal echo disabled. The helper refuses an echo
  fallback and requires confirmation that the document is separate and legacy.

By default, this creates only a new mode-0700 directory:

```text
/root/unifiedvpn-openflux-profile-phone2/
    server.json
    profile.uri
    plan.json
```

All three files are mode 0600. A new cryptographically random 32-byte AES key is
generated locally. `profile.uri` is directly importable into Unified VPN and
includes the name `OpenFlux phone2`. It contains only the document, shared key,
profile version, and transport. It contains no SSH credentials, server login,
Docker state, or another device's configuration. Base64 encoding is **not**
secret protection: treat the whole URI as a password.

Preparation does not contact Docker or Yandex and starts no process. It refuses
existing output files, verifies public artifact SHA256/pins, and privately
compares the document with known installed managed configurations. This catches
identical URLs, default HTTPS port/query ordering changes, and common tracking
parameters. It cannot prove that different URL aliases refer to different
documents or find servers installed outside the managed paths.

An offline URL check cannot confirm the editor version or editing permissions.
The separate opt-in `tools/openflux/prepare_profile.py probe` checks page
structure. Neither that probe nor Docker `running` proves encrypted connectivity.

### Explicit Install And Start

Review the prepared plan, retain provider-console access and a working SSH
session, then use the command printed by the helper:

```sh
sudo python3 -I /root/openflux-profile-tools/create_profile.py \
  --prepared /root/unifiedvpn-openflux-profile-phone2 --apply
```

For a reviewed one-command workflow, the initial interactive invocation may
include `--apply`; it still creates a fresh plan and new named instance only.
Without this flag, there are no Docker operations.

Apply rechecks private files, source artifacts, the immutable runtime image,
existing names, and all IPv4 host routes/Docker subnets. The existing manager
creates only the new instance's image, container and bridge. Public executable,
matching source and license files are read from the selected installed instance;
its secret configuration and state are never reused as deployment inputs.
No image is pulled and no existing VPN service/container is restarted or stopped.

**Docker bridge creation still adds Docker-managed host routes and NAT rules.**
This is not zero-impact networking. After apply, privately transfer only
`profile.uri` to the intended device, import it, then verify application-level
Connected, authenticated OpenFlux readiness and real tunneled traffic. Check
existing connections separately. The helper deliberately does not claim that a
running container is ready or begin a second client against a document.

For noninteractive preparation, supply an already-private mode-0600 URL file:

```sh
sudo python3 -I /root/openflux-profile-tools/create_profile.py \
  --instance phone2 --reuse-instance default --subnet 172.30.252.0/28 \
  --document-file /root/new-device-document.txt --confirm-legacy-document
```

For Volga, replace the last line with
`--document-file /root/new-volga-document.txt --transport vyandex --confirm-volga-document`.
The confirmation must match the selected editor. `--prepared` cannot be combined
with transport or document options, so applying a reviewed plan cannot silently
change its transport. The existing `probe` command examines legacy-editor fields;
it does not validate Volga's relay or authenticated connection.

Never place the URL or key in command arguments or environment variables. The
program prints only the private profile file's path, not its contents. A custom
`--output /root/new-private-directory` must be new and outside every managed
installation directory. This helper intentionally does not automate the first
server installation; use the reviewed [manager procedure](README.md) for that.

### Roll Back Only The New Instance

```sh
sudo python3 -I /root/openflux-profile-tools/create_profile.py \
  --prepared /root/unifiedvpn-openflux-profile-phone2 --rollback --apply
```

The plan records a separate random installation-owner ID **before installation**.
Rollback must match that owner and named instance, then uses the manager's
existing resource-ID/label checks. Another installation, the default instance,
or a replacement with the same name cannot be adopted or removed. Original
runtime/public inputs and private preparation files remain; keep them private.
Unknown files or foreign Docker endpoints can cause a safe refusal.

After an interrupted apply, do not repeat apply, change ownership metadata,
delete installation directories recursively, or prune Docker. Use the printed
owned rollback command and review any refusal. A partial preparation directory
is retained and never silently overwritten. No automatic rollback touches a
running instance.

### Offline Tests

```sh
python -m unittest discover -s tools/openflux-server/tests -v
```

For the standalone `UnifiedVPN-openflux-profile-tools` ZIP, run from its
extracted `openflux-profile-tools` directory:

```sh
python3 -I -m unittest discover -s tests -v
```

Tests cover no-Docker preparation, private/exclusive files, hidden input,
duplicate documents, provenance/hashes, URI roundtrip, preserved default
installation, partial install, and refusal to roll back another owner's instance.
These are synthetic tests; they do not validate actual Docker networking,
legacy-editor availability, mobile networks, or end-to-end encrypted traffic.

## Русский

`create_profile.py` упрощает добавление ещё одного устройства к **уже
установленному и проверенному серверу OpenFlux**. Это локальный помощник для
Linux amd64 поверх существующего `manage.py`, а не новое серверное приложение.
Он не подключается по SSH, не скачивает программы, не создаёт документ Яндекса,
не устанавливает Docker и не обновляет работающий экземпляр.

### Подготовка Одной Командой

Создайте **отдельный пустой документ в старом редакторе Яндекс Документов** с
редактированием по ссылке без входа в браузере. Для каждого одновременно
подключённого устройства нужны свой документ, ключ и экземпляр сервера. Нельзя
использовать на втором устройстве документ активного подключения OpenFlux.

По умолчанию используется транспорт `yandex`. Для отдельного документа в
**новом редакторе Volga** явно укажите `--transport vyandex`. Обеим сторонам нужны
соответствующие бинарники wrapper 5; старые публичные артефакты помощник отклонит.
Рабочая установка не мигрирует, а подготовленный профиль ещё не подтверждает
соединение. Автоматического определения редактора и подмены транспорта нет.

Поместите четыре соответствующих проверенных файла `create_profile.py`,
`manage.py`, `Dockerfile`, `entrypoint.sh` в новый каталог, например
`/root/openflux-profile-tools`. Файлы и родительские каталоги должны принадлежать
root, не быть доступными другим пользователям для записи и не содержать ссылок.
Не копируйте инструменты внутрь работающих `/opt/unifiedvpn-openflux*`.
Нужны уже установленный Python 3.9+ и ранее проверенные серверные артефакты.

```sh
sudo python3 -I /root/openflux-profile-tools/create_profile.py
```

Помощник попросит новое имя, например `phone2`, исходный экземпляр с публичными
артефактами (обычно `default`), свободную подсеть Docker и ссылку нового документа.
Ссылка вводится **без отображения**. Нужно подтвердить, что это отдельный
документ старого редактора. Имя допускает 1..16 строчных латинских букв или цифр,
первая буква обязательна. `default` и занятые имена запрещены.

По умолчанию создаётся только `/root/unifiedvpn-openflux-profile-phone2` с
правами 0700 и тремя файлами 0600: `server.json`, `profile.uri`, `plan.json`.
Ключ AES генерируется криптографически случайно, отдельно для нового профиля.
Файл `profile.uri` можно импортировать в Unified VPN; имя будет
`OpenFlux phone2`. В нём только документ, общий ключ, версия профиля и транспорт,
без логина сервера, SSH-доступа, состояния Docker и конфигурации другого
устройства. Вся ссылка секретна: Base64 не является шифрованием.

**На этапе подготовки Docker и Яндекс не вызываются, процессы не запускаются.**
Публичные артефакты проверяются по закреплённой версии и SHA256. Ссылка сравнивается
в памяти с конфигурациями известных установленных экземпляров без вывода их
содержимого. Сравнение учитывает перестановку параметров, стандартный HTTPS-порт
и обычные tracking-параметры, но не может распознать все разные ссылки на один
документ или установки вне стандартных каталогов.

Проверка без сети не подтверждает старый редактор и права редактирования.
Для проверки структуры страницы есть отдельный добровольный
`tools/openflux/prepare_profile.py probe`. Ни он, ни состояние Docker `running`
не подтверждают работу зашифрованного VPN.

### Явная Установка

После проверки плана, сохранив открытый SSH-сеанс и доступ к консоли провайдера:

```sh
sudo python3 -I /root/openflux-profile-tools/create_profile.py \
  --prepared /root/unifiedvpn-openflux-profile-phone2 --apply
```

Для заранее проверенного сценария можно добавить `--apply` к первому
интерактивному запуску. Без этого флага Docker не вызывается. Перед запуском
проверяются существующие маршруты IPv4, подсети Docker, неизменяемый runtime-образ,
имена, файлы и контрольные суммы. Подсеть по умолчанию `172.30.252.0/28` является
лишь кандидатом, а не гарантированно свободным диапазоном.

Публичные бинарник, исходники и лицензии читаются из выбранной установки.
Её секреты и состояние не копируются в новую. Docker и существующие VPN не
перезапускаются, образы из сети не загружаются. Но **Docker при создании bridge
добавляет собственные маршруты и NAT-правила на хосте**; отсутствие влияния на
другие подключения не гарантируется.

Передайте только приватный `profile.uri` нужному устройству по защищённому
каналу и импортируйте его. Обязательно проверьте Connected, зашифрованный
handshake и реальный трафик, а также сохранность старых подключений. Помощник
не запускает клиента и не выдаёт Docker `running` за готовое соединение.

Без интерактивного терминала допустим заранее подготовленный файл 0600:

```sh
sudo python3 -I /root/openflux-profile-tools/create_profile.py \
  --instance phone2 --reuse-instance default --subnet 172.30.252.0/28 \
  --document-file /root/new-device-document.txt --confirm-legacy-document
```

Не передавайте ссылку или ключ аргументами команд либо переменными окружения.
Для Volga вместо `--confirm-legacy-document` используйте
`--transport vyandex --confirm-volga-document` и файл нового документа.
Подтверждение должно соответствовать редактору. Нельзя совместить `--prepared`
с транспортом или документом: применение проверенного плана не меняет их.
Команда `probe` проверяет поля старого редактора, но не relay или соединение Volga.
В терминале отображается только путь к приватному файлу профиля. Свой
`--output` должен указывать на новый приватный каталог вне всех установок.
Для первой установки сервера остаётся процедура из [README.md](README.md).

### Откат Только Нового Экземпляра

```sh
sudo python3 -I /root/openflux-profile-tools/create_profile.py \
  --prepared /root/unifiedvpn-openflux-profile-phone2 --rollback --apply
```

До установки в плане сохраняется отдельный случайный идентификатор владельца.
Откат сверяет его, имя экземпляра, Docker ID и метки. Чужой экземпляр, `default`
или замена с таким же именем не будут удалены. Исходные публичные файлы,
runtime-образ и приватные файлы подготовки сохраняются. Неизвестные файлы либо
чужие подключения к bridge приводят к отказу, а не принудительному удалению.

При прерванной установке не повторяйте `--apply`, не меняйте метаданные владельца,
не удаляйте каталоги рекурсивно и не запускайте Docker prune. Используйте
напечатанную команду отката и разберите возможный отказ. Частично подготовленные
каталоги не перезаписываются. Автоматического отката работающих экземпляров нет.

Команда локальных тестов приведена выше. Для отдельного ZIP перейдите в
распакованный каталог `openflux-profile-tools` и выполните
`python3 -I -m unittest discover -s tests -v`. Тесты используют имитацию Docker и
публичные тестовые данные; они не проверяют реальный сервер, доступность Яндекса
или интернет через VPN. Данный помощник пока проверен только локально.
