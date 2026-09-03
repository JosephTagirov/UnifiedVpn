# Private upstream notifier

This optional server-side timer checks the public GitHub releases of original
olcbox and Amnezia VPN once per hour. It sends a direct bot message only to the
configured personal chat. It is not included in Unified VPN APK, EXE, or
AppImage files.

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
or modify olcRTC/Jitsi services. To remove the notifier and its secrets, run
`sudo sh install.sh uninstall` from the same directory.

# Приватный уведомитель обновлений

Этот необязательный серверный таймер раз в час проверяет публичные выпуски
оригинального olcbox и Amnezia VPN на GitHub. Он отправляет личное сообщение
бота только в указанный персональный чат и не входит в APK, EXE или AppImage
Unified VPN.

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
не читает, не перезапускает и не меняет сервисы olcRTC/Jitsi. Для полного
удаления уведомителя и секретов запустите `sudo sh install.sh uninstall` из той
же папки.
