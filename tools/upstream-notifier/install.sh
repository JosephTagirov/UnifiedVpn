#!/bin/sh
set -eu

service_name="unifiedvpn-upstream-notifier"
script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
install_dir="/usr/local/lib/$service_name"
environment_file="/etc/$service_name.env"

require_root() {
    if [ "$(id -u)" -ne 0 ]; then
        echo "Run this command with sudo." >&2
        exit 1
    fi
}

uninstall_notifier() {
    systemctl disable --now "$service_name.timer" 2>/dev/null || true
    systemctl stop "$service_name.service" 2>/dev/null || true
    rm -f "/etc/systemd/system/$service_name.service"
    rm -f "/etc/systemd/system/$service_name.timer"
    rm -rf "$install_dir"
    rm -f "$environment_file"
    rm -rf "/var/lib/$service_name"
    systemctl daemon-reload
    echo "Unified VPN upstream notifier was removed."
}

install_notifier() {
    command -v python3 >/dev/null 2>&1 || {
        echo "python3 is required." >&2
        exit 1
    }
    command -v systemctl >/dev/null 2>&1 || {
        echo "systemd is required." >&2
        exit 1
    }

    printf "Bot token: "
    stty -echo
    IFS= read -r bot_token
    stty echo
    printf "\n"
    echo "Private chats currently visible to the bot:"
    TELEGRAM_BOT_TOKEN="$bot_token" python3 "$script_dir/get_chat_id.py" || true
    printf "Personal chat ID: "
    IFS= read -r chat_id

    if ! printf '%s\n' "$bot_token" | grep -Eq '^[0-9]+:[A-Za-z0-9_-]+$'; then
        echo "Bot token format is invalid." >&2
        exit 1
    fi
    if ! printf '%s\n' "$chat_id" | grep -Eq '^-?[0-9]+$'; then
        echo "Chat ID must be numeric." >&2
        exit 1
    fi

    install -d -m 0755 "$install_dir"
    install -m 0755 "$script_dir/upstream_notifier.py" "$install_dir/upstream_notifier.py"
    install -m 0644 "$script_dir/$service_name.service" "/etc/systemd/system/$service_name.service"
    install -m 0644 "$script_dir/$service_name.timer" "/etc/systemd/system/$service_name.timer"

    umask 077
    temporary_environment=$(mktemp)
    trap 'rm -f "$temporary_environment"' EXIT HUP INT TERM
    printf 'TELEGRAM_BOT_TOKEN=%s\nTELEGRAM_CHAT_ID=%s\n' \
        "$bot_token" "$chat_id" > "$temporary_environment"
    install -m 0600 "$temporary_environment" "$environment_file"
    rm -f "$temporary_environment"
    trap - EXIT HUP INT TERM
    unset bot_token

    systemctl daemon-reload
    systemctl enable --now "$service_name.timer"
    systemctl start "$service_name.service"
    systemctl --no-pager --full status "$service_name.service" || true
    echo "Notifier installed. The timer checks once per hour."
}

require_root
case "${1:-install}" in
    install) install_notifier ;;
    uninstall) uninstall_notifier ;;
    *) echo "Usage: sudo sh install.sh [install|uninstall]" >&2; exit 2 ;;
esac
