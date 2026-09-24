#!/usr/bin/env python3
"""Notify the repository owner when the monitored upstream projects change."""

from __future__ import annotations

import hashlib
import json
import os
import re
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
        "tracking": "release",
    },
    {
        "id": "amnezia",
        "name": "Amnezia VPN",
        "repository": "amnezia-vpn/amnezia-client",
        "tracking": "release",
    },
    {
        "id": "olcrtc",
        "name": "olcRTC core",
        "repository": "openlibrecommunity/olcrtc",
        "tracking": "commit",
    },
    {
        "id": "awg",
        "name": "AmneziaWG core (Throne sing-box)",
        "repository": "Throneproj/sing-box",
        "tracking": "commit",
        "ref": "wip/1.14.0",
    },
    {
        "id": "openflux",
        "name": "OpenFlux",
        "repository": "p1neappleXpress/OpenFlux",
        "tracking": "commit",
    },
    {
        "id": "unifiedvpn",
        "name": "Unified VPN",
        "repository": "JosephTagirov/UnifiedVpn",
        "tracking": "release-build",
    },
)
DEFAULT_STATE_FILE = "/var/lib/unifiedvpn-upstream-notifier/state.json"
USER_AGENT = "UnifiedVPN-private-upstream-notifier/1"
REQUEST_TIMEOUT_SECONDS = 20
BUILD_NUMBER_PATTERN = re.compile(r"(?:^|[-_.])build[-_.]?(\d{1,18})(?=[-_.]|$)", re.IGNORECASE)
APPLICATION_ASSETS = {
    "android": ("Android", (".apk",)),
    "windows": ("Windows", (".exe", ".msi", ".zip")),
    "linux": ("Linux", (".appimage", ".deb", ".rpm", ".tar.gz")),
}


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


def application_release_builds(release: dict[str, Any]) -> tuple[str, str]:
    if release.get("draft") or release.get("prerelease"):
        raise RuntimeError("Unified VPN update is not a published stable release")
    assets = []
    builds: dict[str, int] = {}
    for asset in release.get("assets") or []:
        name = str(asset.get("name") or "")
        lower_name = name.lower()
        if not lower_name.startswith("unifiedvpn-") or asset.get("state", "uploaded") != "uploaded":
            continue
        size = asset.get("size")
        if not isinstance(size, int) or size <= 0:
            continue
        for platform, (label, extensions) in APPLICATION_ASSETS.items():
            if f"-{platform}" not in lower_name or not lower_name.endswith(extensions):
                continue
            # Only application payload changes matter, not download counts or release prose.
            assets.append({key: asset.get(key) for key in ("id", "name", "size", "digest")})
            match = BUILD_NUMBER_PATTERN.search(name)
            if match:
                builds[label] = max(builds.get(label, 0), int(match.group(1)))
            break
    if not assets:
        raise RuntimeError("GitHub release has no uploaded Unified VPN application assets")
    assets.sort(key=lambda asset: (str(asset["name"]), str(asset["id"])))
    fingerprint = hashlib.sha256(
        json.dumps(assets, sort_keys=True, separators=(",", ":")).encode("utf-8")
    ).hexdigest()
    summary = ", ".join(f"{label}: {build}" for label, build in sorted(builds.items()))
    return fingerprint, summary


def latest_project_version(project: dict[str, str]) -> dict[str, str]:
    repository = project["repository"]
    tracking = project.get("tracking", "release")
    if tracking not in ("release", "release-build", "commit"):
        raise RuntimeError(f"Unknown update tracking mode for {repository}")
    if tracking in ("release", "release-build"):
        status, release = request_json(
            f"https://api.github.com/repos/{repository}/releases/latest"
        )
        if status == 200:
            tag = str(release.get("tag_name") or "unknown")
            url = str(release.get("html_url") or f"https://github.com/{repository}/releases")
            published = str(release.get("published_at") or "")
            release_id = str(release.get("id") or f"{tag}|{published}|{url}")
            latest = {
                "identity": f"release:{release_id}",
                "version": tag,
                "url": url,
                "published_at": published,
                "source": "release",
            }
            if tracking == "release-build":
                fingerprint, builds = application_release_builds(release)
                latest["identity"] = f"release:{release_id}:{tag}:{fingerprint}"
                latest["builds"] = builds
            return latest
        if tracking == "release-build":
            raise RuntimeError(f"GitHub API did not return a stable release for {repository}")

    ref = project.get("ref", "HEAD")
    if not isinstance(ref, str) or not ref.strip() or ref != ref.strip():
        raise RuntimeError(f"Invalid commit ref for {repository}")
    encoded_ref = urllib.parse.quote(ref, safe="")
    _, commit = request_json(f"https://api.github.com/repos/{repository}/commits/{encoded_ref}")
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
    if latest.get("builds"):
        lines.append(f"Сборки: {latest['builds']}")
    if latest["published_at"]:
        lines.append(f"Дата: {latest['published_at']}")
    lines.extend(
        [
            latest["url"],
            "Unified VPN и серверные компоненты обновляются отдельно, вручную и после проверки совместимости.",
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
