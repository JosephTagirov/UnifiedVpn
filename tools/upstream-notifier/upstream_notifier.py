#!/usr/bin/env python3
"""Notify the repository owner when the monitored upstream projects change."""

from __future__ import annotations

import json
import os
import sys
import tempfile
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path
from typing import Any


PROJECTS = (
    {
        "id": "olcbox",
        "name": "Original olcbox",
        "repository": "alananisimov/olcbox",
    },
    {
        "id": "amnezia",
        "name": "Amnezia VPN",
        "repository": "amnezia-vpn/amnezia-client",
    },
)
DEFAULT_STATE_FILE = "/var/lib/unifiedvpn-upstream-notifier/state.json"
USER_AGENT = "UnifiedVPN-private-upstream-notifier/1"
REQUEST_TIMEOUT_SECONDS = 20


def request_json(url: str) -> tuple[int, dict[str, Any]]:
    request = urllib.request.Request(
        url,
        headers={
            "Accept": "application/vnd.github+json",
            "User-Agent": USER_AGENT,
            "X-GitHub-Api-Version": "2022-11-28",
        },
    )
    try:
        with urllib.request.urlopen(request, timeout=REQUEST_TIMEOUT_SECONDS) as response:
            return response.status, json.load(response)
    except urllib.error.HTTPError as error:
        if error.code == 404:
            return error.code, {}
        raise RuntimeError(f"GitHub API returned HTTP {error.code}") from None
    except urllib.error.URLError as error:
        raise RuntimeError(f"GitHub API connection failed: {error.reason}") from None


def latest_project_version(project: dict[str, str]) -> dict[str, str]:
    repository = project["repository"]
    status, release = request_json(
        f"https://api.github.com/repos/{repository}/releases/latest"
    )
    if status == 200:
        tag = str(release.get("tag_name") or "unknown")
        url = str(release.get("html_url") or f"https://github.com/{repository}/releases")
        published = str(release.get("published_at") or "")
        release_id = str(release.get("id") or f"{tag}|{published}|{url}")
        return {
            "identity": f"release:{release_id}",
            "version": tag,
            "url": url,
            "published_at": published,
            "source": "release",
        }

    _, commit = request_json(f"https://api.github.com/repos/{repository}/commits/HEAD")
    sha = str(commit.get("sha") or "")
    if not sha:
        raise RuntimeError(f"GitHub API did not return a commit for {repository}")
    details = commit.get("commit") or {}
    committer = details.get("committer") or {}
    return {
        "identity": f"commit:{sha}",
        "version": sha[:12],
        "url": str(commit.get("html_url") or f"https://github.com/{repository}/commit/{sha}"),
        "published_at": str(committer.get("date") or ""),
        "source": "commit",
    }


def load_state(path: Path) -> dict[str, Any]:
    if not path.is_file():
        return {"schema": 1, "projects": {}}
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise RuntimeError(f"Cannot read notifier state: {error}") from None
    if not isinstance(value, dict) or not isinstance(value.get("projects"), dict):
        raise RuntimeError("Notifier state has an invalid format")
    return value


def save_state(path: Path, state: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    descriptor, temporary_name = tempfile.mkstemp(
        prefix=f".{path.name}.", dir=str(path.parent), text=True
    )
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8") as output:
            json.dump(state, output, ensure_ascii=False, indent=2, sort_keys=True)
            output.write("\n")
            output.flush()
            os.fsync(output.fileno())
        os.chmod(temporary_name, 0o600)
        os.replace(temporary_name, path)
    finally:
        if os.path.exists(temporary_name):
            os.unlink(temporary_name)


def send_bot_message(token: str, chat_id: str, text: str) -> None:
    body = urllib.parse.urlencode(
        {
            "chat_id": chat_id,
            "text": text,
            "disable_web_page_preview": "true",
        }
    ).encode("utf-8")
    request = urllib.request.Request(
        f"https://api.telegram.org/bot{token}/sendMessage",
        data=body,
        method="POST",
        headers={"Content-Type": "application/x-www-form-urlencoded"},
    )
    try:
        with urllib.request.urlopen(request, timeout=REQUEST_TIMEOUT_SECONDS) as response:
            result = json.load(response)
    except urllib.error.HTTPError as error:
        raise RuntimeError(f"Bot API returned HTTP {error.code}") from None
    except urllib.error.URLError as error:
        raise RuntimeError(f"Bot API connection failed: {error.reason}") from None
    if result.get("ok") is not True:
        raise RuntimeError("Bot API rejected the notification")


def notification_text(project: dict[str, str], latest: dict[str, str], first_run: bool) -> str:
    heading = (
        "Мониторинг обновлений включён"
        if first_run
        else f"{project['name']} обновился на GitHub"
    )
    source = "релиз" if latest["source"] == "release" else "commit"
    lines = [
        heading,
        f"Проект: {project['name']}",
        f"Версия ({source}): {latest['version']}",
    ]
    if latest["published_at"]:
        lines.append(f"Дата: {latest['published_at']}")
    lines.extend(
        [
            latest["url"],
            "Unified VPN будет обновлён отдельно после проверки клиента.",
        ]
    )
    return "\n".join(lines)


def main() -> int:
    token = os.environ.get("TELEGRAM_BOT_TOKEN", "").strip()
    chat_id = os.environ.get("TELEGRAM_CHAT_ID", "").strip()
    if not token or not chat_id:
        print("Notifier secrets are not configured", file=sys.stderr)
        return 2

    state_path = Path(os.environ.get("STATE_FILE", DEFAULT_STATE_FILE))
    state = load_state(state_path)
    stored_projects = state.setdefault("projects", {})
    failures: list[str] = []

    for project in PROJECTS:
        try:
            latest = latest_project_version(project)
            previous = stored_projects.get(project["id"])
            if isinstance(previous, dict) and previous.get("identity") == latest["identity"]:
                continue
            send_bot_message(
                token,
                chat_id,
                notification_text(project, latest, first_run=previous is None),
            )
            stored_projects[project["id"]] = latest
            save_state(state_path, state)
            print(f"Notification sent for {project['id']} {latest['version']}")
        except Exception as error:  # Errors are sanitized before reaching this boundary.
            failures.append(f"{project['id']}: {error}")

    if failures:
        for failure in failures:
            print(f"Notifier failed for {failure}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
