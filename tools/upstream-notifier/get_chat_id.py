#!/usr/bin/env python3
"""List private chat IDs visible to a bot without printing message contents."""

from __future__ import annotations

import getpass
import json
import os
import sys
import urllib.error
import urllib.request


TIMEOUT_SECONDS = 20


def main() -> int:
    token = os.environ.get("TELEGRAM_BOT_TOKEN", "").strip()
    if not token:
        token = getpass.getpass("Bot token (input is hidden): ").strip()
    if not token:
        print("Bot token is empty.", file=sys.stderr)
        return 2

    request = urllib.request.Request(
        f"https://api.telegram.org/bot{token}/getUpdates",
        headers={"User-Agent": "UnifiedVPN-private-upstream-notifier/1"},
    )
    try:
        with urllib.request.urlopen(request, timeout=TIMEOUT_SECONDS) as response:
            payload = json.load(response)
    except urllib.error.HTTPError as error:
        print(f"Bot API returned HTTP {error.code}.", file=sys.stderr)
        return 1
    except urllib.error.URLError as error:
        print(f"Bot API connection failed: {error.reason}", file=sys.stderr)
        return 1

    if payload.get("ok") is not True:
        print("Bot API rejected the request.", file=sys.stderr)
        return 1

    chats: dict[str, str] = {}
    for update in payload.get("result", []):
        message = update.get("message") or update.get("edited_message") or {}
        chat = message.get("chat") or {}
        chat_id = chat.get("id")
        if chat.get("type") != "private" or not isinstance(chat_id, int):
            continue
        display_name = " ".join(
            part.strip()
            for part in (str(chat.get("first_name") or ""), str(chat.get("last_name") or ""))
            if part.strip()
        )
        chats[str(chat_id)] = display_name or "private chat"

    if not chats:
        print("No private chat found. Send /start to the bot, then run this helper again.")
        return 3

    print("Private chats visible to this bot:")
    for chat_id, display_name in sorted(chats.items()):
        print(f"  {chat_id}  {display_name}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
