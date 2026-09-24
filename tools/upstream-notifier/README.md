# Private upstream notifier

This optional server-side timer checks the following public GitHub projects.
It sends a direct bot message only to the configured personal chat. It is not
included in Unified VPN APK, EXE, or AppImage files.

| Project | Repository | Updates watched |
| --- | --- | --- |
| Original olcbox | [alananisimov/olcbox](https://github.com/alananisimov/olcbox) | Latest stable release |
| Amnezia VPN | [amnezia-vpn/amnezia-client](https://github.com/amnezia-vpn/amnezia-client) | Latest stable release |
| olcRTC core | [openlibrecommunity/olcrtc](https://github.com/openlibrecommunity/olcrtc) | Default-branch commit |
| AWG core used by Unified VPN | [Throneproj/sing-box](https://github.com/Throneproj/sing-box) | Commit of `wip/1.14.0` |
| OpenFlux | [p1neappleXpress/OpenFlux](https://github.com/p1neappleXpress/OpenFlux) | Default-branch commit |
| Unified VPN | [JosephTagirov/UnifiedVpn](https://github.com/JosephTagirov/UnifiedVpn) | Latest stable release and its application builds |

Original olcbox and Amnezia VPN retain the release-first policy, falling back
to the default-branch commit only when there is no stable release. Core projects
track commits directly, so an old release cannot hide new development. These
are upstream change alerts, not a promise that a revision is compatible with
the installed client/server. No binaries are downloaded or installed, and no
services are restarted.

AWG follows `wip/1.14.0` explicitly because that branch contains the newer core
used by Unified VPN, while the repository's default branch can be older.
The optional `ref` field in `PROJECTS` selects a branch, tag, or commit for commit
lookups; without it, `HEAD` is used. A missing configured ref is an error, not a
fallback to another branch. This fixed branch does not automatically change to
future `wip` branches; changing it requires updating the notifier program.

Unified VPN tracks published stable releases only, not preview builds or
unpublished commits. A new or replaced application file within the same release
also triggers an alert, even when the version number is unchanged. Messages
show the highest build number present in filenames for each platform; older
files without build numbers still work. Asset IDs, names, sizes and digests
identify changes. Release prose, download counts, checksums and bot archives
do not trigger application-build alerts. A release without uploaded application
files is retried on a later check without replacing the last notified state.

The first successful check sends one baseline message for each newly monitored
project. Later checks send a message only when its release ID or commit SHA
changes, or when Unified VPN application files change; several commits between
checks are combined into one latest-commit
notification. Failed delivery is retried on a later check.

## Install

1. Create a bot with `@BotFather` and copy its token.
2. Open the new bot and send `/start`.
3. Run `python3 get_chat_id.py`, enter the token at the hidden prompt, and copy
   the numeric ID shown for your private chat. The helper prints neither message
   text nor the token. If no chat is shown, send `/start` and run it again.
4. Copy this directory to the Ubuntu server.
5. Run `sudo sh install.sh` and enter the token and chat ID when prompted.
6. Check the timer with `systemctl list-timers unifiedvpn-upstream-notifier.timer`.
7. Check sanitized logs with `journalctl -u unifiedvpn-upstream-notifier.service`.

The token is written only to `/etc/unifiedvpn-upstream-notifier.env` with mode
`0600`. The service uses a dynamic unprivileged user and does not read, restart,
or modify VPN services. To remove the notifier and its secrets, run
`sudo sh install.sh uninstall` from the same directory.

## Update An Existing Bot

Upload the updated notifier directory privately to the server, open that
directory and replace only the program:

```sh
sudo install -m 0755 upstream_notifier.py /usr/local/lib/unifiedvpn-upstream-notifier/upstream_notifier.py
```

Do not run the installer again just to add projects. This command preserves
the existing token, chat ID, timer overrides and `/var/lib/unifiedvpn-upstream-notifier/state.json`.
No new environment variables are needed. All existing project state IDs
remain unchanged. On the next scheduled check, only newly added projects send
their baseline message; unchanged existing entries are not resent. When updating
the five-project bot that already watches OpenFlux, only Unified VPN is added.
AWG may also send one change notification when its tracked branch changes from
the default branch to `wip/1.14.0` and the commit SHA differs; its state is not reset.
For an immediate check, explicitly run
`sudo systemctl start unifiedvpn-upstream-notifier.service`.

## Check Every 24 Hours

The bundled timer defaults to one hour; updating only the program does not
change your current schedule. To use approximately 24-hour intervals, run
`sudo systemctl edit unifiedvpn-upstream-notifier.timer` and add:

```ini
[Timer]
OnUnitActiveSec=
OnBootSec=3min
OnUnitActiveSec=24h
```

Then run:

```sh
sudo systemctl daemon-reload
sudo systemctl restart unifiedvpn-upstream-notifier.timer
systemctl list-timers unifiedvpn-upstream-notifier.timer
```

The empty assignment resets the old schedule. A boot check is retained, and
the existing short randomized delay still applies. These commands affect only
the notifier timer, not VPN services. See the [systemd timer documentation](https://www.freedesktop.org/software/systemd/man/latest/systemd.timer.html).

## Offline Tests

From this directory, run `python3 -m unittest discover -s tests -v`.
The tests use synthetic state and mocked GitHub/bot requests, with no secrets
or real messages.

# Приватный уведомитель обновлений

Этот необязательный серверный таймер проверяет перечисленные ниже публичные
проекты GitHub. Он отправляет личное сообщение бота только в указанный
персональный чат и не входит в APK, EXE или AppImage Unified VPN.

| Проект | Репозиторий | Какие обновления отслеживаются |
| --- | --- | --- |
| Оригинальный olcbox | [alananisimov/olcbox](https://github.com/alananisimov/olcbox) | Последний стабильный релиз |
| Amnezia VPN | [amnezia-vpn/amnezia-client](https://github.com/amnezia-vpn/amnezia-client) | Последний стабильный релиз |
| Ядро olcRTC | [openlibrecommunity/olcrtc](https://github.com/openlibrecommunity/olcrtc) | Коммит основной ветки |
| Ядро AWG, используемое в Unified VPN | [Throneproj/sing-box](https://github.com/Throneproj/sing-box) | Коммит ветки `wip/1.14.0` |
| OpenFlux | [p1neappleXpress/OpenFlux](https://github.com/p1neappleXpress/OpenFlux) | Коммит основной ветки |
| Unified VPN | [JosephTagirov/UnifiedVpn](https://github.com/JosephTagirov/UnifiedVpn) | Последний стабильный релиз и его сборки приложения |

Для оригинальных olcbox и Amnezia VPN сохранена проверка стабильных релизов; если
релизов нет, проверяется коммит основной ветки. Для ядер проверяются коммиты
напрямую, чтобы старый релиз не скрывал новую разработку. Уведомление сообщает
об изменении исходного проекта, но не гарантирует совместимость с установленными
клиентом и сервером. Программы не скачиваются и не устанавливаются, сервисы не
перезапускаются.

Для AWG явно выбрана ветка `wip/1.14.0`: в ней находится более новое ядро,
используемое Unified VPN, а основная ветка репозитория может отставать.
Необязательное поле `ref` в `PROJECTS` задаёт ветку, тег или коммит для проверки
коммитов; без него используется `HEAD`. Отсутствие указанной ветки считается
ошибкой и не переключает проверку на другую ветку. Переход на будущие ветки
`wip` не автоматический: для него нужно обновить программу уведомителя.

Unified VPN отслеживается только по опубликованным стабильным релизам, без
предварительных сборок и неопубликованных коммитов. Добавление или замена файла
приложения внутри того же релиза тоже вызывает уведомление, даже если номер
версии не изменился. В сообщении показан максимальный номер сборки из имён файлов
для каждой платформы; старые файлы без номера сборки тоже поддерживаются.
Изменения определяются по ID, имени, размеру и хешу файла. Правки описания релиза,
счётчики скачиваний, файлы контрольных сумм и архивы бота не вызывают уведомлений
о сборке приложения. Релиз без загруженных файлов приложения проверяется позже,
не заменяя последнее успешно отправленное состояние.

При первой успешной проверке бот сообщает текущую версию каждого нового
отслеживаемого проекта. Затем сообщения приходят только при изменении ID релиза
или SHA коммита, а для Unified VPN ещё и файлов приложения. Несколько коммитов
между проверками объединяются в одно сообщение
о последнем коммите. После ошибки отправки бот повторяет попытку при следующей проверке.

## Установка

1. Создайте бота через `@BotFather` и сохраните его токен.
2. Откройте нового бота и отправьте ему `/start`.
3. Запустите `python3 get_chat_id.py`, введите токен в скрытом поле и сохраните
   числовой ID своего личного чата. Помощник не печатает ни сообщения, ни токен.
   Если чат не найден, отправьте боту `/start` и повторите команду.
4. Перенесите эту папку на Ubuntu-сервер.
5. Запустите `sudo sh install.sh` и введите токен и ID чата по запросу.
6. Проверьте таймер: `systemctl list-timers unifiedvpn-upstream-notifier.timer`.
7. Проверьте очищенные логи: `journalctl -u unifiedvpn-upstream-notifier.service`.

Токен записывается только в `/etc/unifiedvpn-upstream-notifier.env` с правами
`0600`. Сервис работает от динамического непривилегированного пользователя и
не читает, не перезапускает и не меняет VPN-сервисы. Для полного
удаления уведомителя и секретов запустите `sudo sh install.sh uninstall` из той
же папки.

## Обновление Установленного Бота

Приватно загрузите обновлённую папку уведомителя на сервер, откройте её и замените
только программу:

```sh
sudo install -m 0755 upstream_notifier.py /usr/local/lib/unifiedvpn-upstream-notifier/upstream_notifier.py
```

Не запускайте установщик повторно только ради добавления проектов. Эта команда
сохраняет токен, ID чата, настройки расписания и файл состояния
`/var/lib/unifiedvpn-upstream-notifier/state.json`. Новые переменные окружения
не нужны. Идентификаторы всех существующих записей не меняются.
При следующей плановой проверке только новые проекты отправят по одному
сообщению о начале мониторинга; неизменившиеся старые записи повторно не
отправляются. Если бот уже следит за пятью проектами, включая OpenFlux,
добавится только Unified VPN. Для AWG также может прийти одно уведомление об
изменении коммита при переходе с основной ветки на `wip/1.14.0`, если SHA отличается;
состояние AWG не сбрасывается. Для немедленной проверки отдельно выполните
`sudo systemctl start unifiedvpn-upstream-notifier.service`.

## Проверка Раз В 24 Часа

В комплекте установлен интервал один час; замена только программы не меняет
текущее расписание. Для интервала примерно 24 часа выполните
`sudo systemctl edit unifiedvpn-upstream-notifier.timer` и добавьте:

```ini
[Timer]
OnUnitActiveSec=
OnBootSec=3min
OnUnitActiveSec=24h
```

Затем выполните:

```sh
sudo systemctl daemon-reload
sudo systemctl restart unifiedvpn-upstream-notifier.timer
systemctl list-timers unifiedvpn-upstream-notifier.timer
```

Пустая строка настройки сбрасывает старое расписание. Проверка после загрузки
сервера сохраняется, как и небольшая случайная задержка. Команды затрагивают
только таймер уведомителя, а не VPN-сервисы. См. [документацию таймеров systemd](https://www.freedesktop.org/software/systemd/man/latest/systemd.timer.html).

## Тесты Без Сети

В этой папке выполните `python3 -m unittest discover -s tests -v`.
Тесты используют вымышленные данные и подменённые запросы GitHub/бота,
без секретов и настоящей отправки сообщений.
