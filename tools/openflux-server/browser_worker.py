#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""One anonymous browser verification; private JSON enters and leaves via pipes."""

import asyncio
import json
import math
import os
import re
import sys
import time
from urllib.parse import unquote, urlsplit


MAX_MESSAGE = 32768
MAX_COOKIES = 64
MAX_COOKIE = 4096
MAX_COOKIE_BYTES = 24576
TIMEOUT_SECONDS = 45
DOCUMENT_HOSTS = frozenset(("docs.yandex.ru", "disk.yandex.ru"))
RESOURCE_ROOTS = ("yandex.ru", "yandex.net", "yastatic.net", "yandexcloud.net")
AUTH_COOKIE_NAMES = frozenset(("session_id", "sessionid2", "yandex_login"))
ID_PATTERN = re.compile(r"[0-9a-f]{32}\Z")
COOKIE_NAME = re.compile(r"[!#$%&'*+\-.^_\x60|~0-9A-Za-z]{1,128}\Z")
READY_SCRIPT = """() => {
  try {
    const nodes = document.querySelectorAll('script#client-config');
    if (nodes.length !== 1 || !nodes[0].textContent || nodes[0].textContent.length > 2097152) return false;
    const root = JSON.parse(nodes[0].textContent);
    const office = root && root.officeActionData;
    const editor = office && office.editor_config;
    const doc = editor && editor.document;
    const nonempty = value => typeof value === 'string' && value.length > 0;
    if (!office || !editor) return false;
    if (office.office_online_editor_type !== 'volga') {
      return !!(doc && nonempty(office.balancer_url) && nonempty(editor.token) &&
        nonempty(doc.key) && doc.permissions && doc.permissions.edit === true);
    }
    // Volga bootstraps anonymous cookies; native authorization still checks write access.
    const denied = rights => Array.isArray(rights) && !rights.includes('write');
    if (denied(root.rights) || denied(root.editorParams && root.editorParams.rights) ||
        (doc && doc.permissions && doc.permissions.edit === false)) return false;
    if (!nonempty(office.access_token) || editor.documentType !== 'text' ||
        !nonempty(office.action_url) || office.action_url.length > 8192 ||
        /[\\u0000-\\u0020\\u007f]/.test(office.action_url)) return false;
    const action = new URL(office.action_url);
    return action.protocol === 'https:' && action.hostname === 'volga.yandex.ru' &&
      (action.port === '' || action.port === '443') && action.username === '' &&
      action.password === '' && action.hash === '';
  } catch (_) { return false; }
}"""


class VerificationError(Exception):
    pass


def decode_message(raw):
    def unique(pairs):
        result = {}
        for name, value in pairs:
            if name in result:
                raise VerificationError()
            result[name] = value
        return result
    if not isinstance(raw, bytes) or len(raw) > MAX_MESSAGE:
        raise VerificationError()
    try:
        return json.loads(raw, object_pairs_hook=unique)
    except (ValueError, UnicodeError, RecursionError):
        raise VerificationError() from None


def encode_message(value):
    raw = json.dumps(value, ensure_ascii=True, separators=(",", ":"), allow_nan=False).encode("ascii")
    if len(raw) > MAX_MESSAGE:
        raise VerificationError()
    return raw + b"\n"


def allowed_url(value, *, navigation=False):
    if not isinstance(value, str) or len(value) > 8192 or any(ord(char) <= 32 or ord(char) == 127 for char in value):
        return False
    try:
        parsed = urlsplit(value)
        host = parsed.hostname or ""
        if parsed.scheme != "https" or parsed.port not in (None, 443) or parsed.username is not None or parsed.password is not None:
            return False
        if navigation:
            if host not in DOCUMENT_HOSTS:
                return False
        elif not any(host == root or host.endswith("." + root) for root in RESOURCE_ROOTS):
            return False
        blocked = ("passport", "oauth", "login", "signin", "sign-in", "authorize")
        return not any(word in (host + "/" + unquote(parsed.path)).lower() for word in blocked)
    except ValueError:
        return False


def validate_request(value):
    if not isinstance(value, dict) or set(value) != {"id", "document_url"}:
        raise VerificationError()
    if not isinstance(value["id"], str) or ID_PATTERN.fullmatch(value["id"]) is None:
        raise VerificationError()
    if not allowed_url(value["document_url"], navigation=True) or urlsplit(value["document_url"]).fragment:
        raise VerificationError()
    return value


def cookie_domain(value, document_url):
    if not isinstance(value, str):
        raise VerificationError()
    domain = value.lower()
    host = urlsplit(document_url).hostname
    if domain not in ("", host, "." + host, "yandex.ru", ".yandex.ru"):
        raise VerificationError()
    # Match Go: an exact document hostname without a leading dot is host-only.
    return "" if domain == host else domain


def validate_response(value, request_id, document_url):
    if not isinstance(value, dict) or value.get("id") != request_id:
        raise VerificationError()
    if set(value) == {"id", "error"} and value["error"] == "verification_failed":
        return value
    if set(value) != {"id", "cookies"} or not isinstance(value["cookies"], list) or not 1 <= len(value["cookies"]) <= MAX_COOKIES:
        raise VerificationError()
    now = int(time.time())
    seen = set()
    total = 0
    for cookie in value["cookies"]:
        if not isinstance(cookie, dict) or set(cookie) != {"name", "value", "domain", "path", "secure", "expires"}:
            raise VerificationError()
        name = cookie["name"]
        if not isinstance(name, str) or COOKIE_NAME.fullmatch(name) is None or name.lower() in AUTH_COOKIE_NAMES:
            raise VerificationError()
        if (not isinstance(cookie["value"], str) or len(cookie["value"]) > MAX_COOKIE
                or any(not 0x20 <= ord(char) <= 0x7e or char in ('"', ";", "\\") for char in cookie["value"])):
            raise VerificationError()
        domain = cookie_domain(cookie["domain"], document_url)
        if cookie["path"] != "/" or cookie["secure"] is not True:
            raise VerificationError()
        if type(cookie["expires"]) is not int or not 0 <= cookie["expires"] <= 9223372036854775807 or cookie["expires"] and cookie["expires"] <= now:
            raise VerificationError()
        identity = (name.lower(), domain.removeprefix("."))
        total += len(name) + len(cookie["value"]) + len(domain) + len(cookie["path"])
        if identity in seen or total > MAX_COOKIE_BYTES:
            raise VerificationError()
        seen.add(identity)
    encode_message(value)
    return value


def export_cookies(cookies, request):
    result = []
    for cookie in cookies:
        try:
            cookie_domain(cookie.get("domain"), request["document_url"])
        except VerificationError:
            continue
        if str(cookie.get("name", "")).lower() in AUTH_COOKIE_NAMES:
            raise VerificationError()
        if cookie.get("path") != "/":
            raise VerificationError()
        expiry = cookie.get("expires", -1)
        if isinstance(expiry, bool) or not isinstance(expiry, (int, float)) or not math.isfinite(expiry):
            raise VerificationError()
        expires = int(expiry) if expiry > 0 else 0
        if expires and expires <= time.time():
            continue
        result.append({name: cookie[name] for name in ("name", "value", "domain", "path")})
        result[-1].update(secure=True, expires=expires)
    return validate_response({"id": request["id"], "cookies": result}, request["id"], request["document_url"])


async def verify(request, playwright_factory=None):
    if playwright_factory is None:
        from playwright.async_api import async_playwright
        playwright_factory = async_playwright
    async with playwright_factory() as playwright:
        browser = await playwright.chromium.launch(
            headless=True, channel="chromium", chromium_sandbox=True, timeout=15000,
            args=["--no-proxy-server"],
            env={"PATH": os.environ.get("PATH", "/usr/bin:/bin"), "HOME": "/home/browser", "TMPDIR": "/tmp"},
        )
        try:
            context = await browser.new_context(accept_downloads=False, service_workers="block",
                                                ignore_https_errors=False, permissions=[])
            async def route_request(route):
                item = route.request
                main_navigation = False
                if item.is_navigation_request():
                    try:
                        main_navigation = item.frame.parent_frame is None
                    except Exception:
                        await route.abort("blockedbyclient")
                        return
                if allowed_url(item.url, navigation=main_navigation):
                    await route.continue_()
                else:
                    await route.abort("blockedbyclient")
            async def block_websocket(websocket):
                await websocket.close(code=1008, reason="bootstrap_only")
            await context.route("**/*", route_request)
            await context.route_web_socket("**/*", block_websocket)
            page = await context.new_page()
            await page.goto(request["document_url"], wait_until="domcontentloaded", timeout=20000)
            await page.wait_for_function(READY_SCRIPT, timeout=20000, polling=250)
            if not allowed_url(page.url, navigation=True):
                raise VerificationError()
            cookies = await context.cookies([request["document_url"]])
            return export_cookies(cookies, request)
        finally:
            await browser.close()


def main():
    request = None
    try:
        # The image runs as its dedicated user; never fall back to a root browser.
        if not hasattr(os, "geteuid") or os.geteuid() == 0 or os.environ.get("OPENFLUX_BROWSER_CONTAINER") != "1":
            raise VerificationError()
        for name in ("DEBUG", "PWDEBUG", "HTTP_PROXY", "HTTPS_PROXY", "ALL_PROXY", "NO_PROXY",
                     "http_proxy", "https_proxy", "all_proxy", "no_proxy"):
            os.environ.pop(name, None)
        request = validate_request(decode_message(sys.stdin.buffer.readline(MAX_MESSAGE + 2).rstrip(b"\n")))
        response = asyncio.run(asyncio.wait_for(verify(request), timeout=TIMEOUT_SECONDS))
    except BaseException:
        if request is None:
            return 1
        response = {"id": request["id"], "error": "verification_failed"}
    sys.stdout.buffer.write(encode_message(response))
    sys.stdout.buffer.flush()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
