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
import unicodedata
from urllib.parse import unquote, urlsplit


MAX_MESSAGE = 32768
MAX_COOKIES = 64
MAX_COOKIE = 4096
MAX_COOKIE_BYTES = 24576
MAX_CLASSIC_DOCUMENT = 24576
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
CLASSIC_DOCUMENT_SCRIPT = """() => {
  const nodes = document.querySelectorAll('script#client-config');
  if (nodes.length !== 1 || !nodes[0].textContent || nodes[0].textContent.length > 2097152) throw new Error('invalid_config');
  const root = JSON.parse(nodes[0].textContent);
  const office = root && root.officeActionData;
  const editor = office && office.editor_config;
  if (!office || !editor) throw new Error('invalid_config');
  if (office.office_online_editor_type === 'volga') return {kind: 'volga'};
  const doc = editor.document;
  if (!doc || !doc.permissions || doc.permissions.edit !== true) throw new Error('invalid_config');
  return {kind: 'classic', document: {
    balancer_url: office.balancer_url, token: editor.token, document_key: doc.key,
    file_type: doc.fileType, download_url: doc.url, title: doc.title, edit: true
  }};
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
    try:
        raw = json.dumps(value, ensure_ascii=False, separators=(",", ":"), allow_nan=False).encode("utf-8")
    except (ValueError, TypeError, UnicodeError, RecursionError):
        raise VerificationError() from None
    if len(raw) > MAX_MESSAGE:
        raise VerificationError()
    return raw + b"\n"


def allowed_url(value, *, navigation=False):
    if not valid_url_text(value) or navigation and "#" in value:
        return False
    try:
        parsed = urlsplit(value)
        host = parsed.hostname or ""
        if (parsed.scheme != "https" or parsed.port not in (None, 443)
                or parsed.username is not None or parsed.password is not None
                or parsed.netloc.lower() not in (host, host + ":443")):
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


def valid_user_agent(value):
    return (isinstance(value, str) and 1 <= len(value) <= 1024 and value.strip() == value
            and all(0x20 <= ord(char) <= 0x7e for char in value))


def valid_url_text(value):
    if not isinstance(value, str) or not value:
        return False
    try:
        return (len(value.encode("utf-8")) <= 8192
                and not any(char.isspace() or unicodedata.category(char) == "Cc" or char == "\\" for char in value)
                and re.search(r"%(?![0-9a-fA-F]{2})", value) is None)
    except UnicodeError:
        return False


def classic_url(value, *, origin=False):
    if not valid_url_text(value):
        return False
    try:
        parsed = urlsplit(value)
        host = parsed.hostname or ""
        label_pattern = (r"[a-z0-9_](?:[a-z0-9_-]{0,61}[a-z0-9_])?" if origin
                         else r"[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?")
        if (parsed.scheme != "https" or parsed.port not in (None, 443)
                or parsed.username is not None or parsed.password is not None or "#" in value
                or parsed.netloc.lower() not in (host, host + ":443")
                or len(host) > 253 or not host.isascii()
                or any(re.fullmatch(label_pattern, label) is None for label in host.split("."))
                or not any(host.endswith("." + root) for root in ("yandex.ru", "yandex.net"))):
            return False
        return not origin or (parsed.path == "" and "?" not in value)
    except (ValueError, UnicodeError):
        return False


def classic_document_reference(value):
    if classic_url(value):
        return True
    # OnlyOffice consumes this signed reference remotely; it is never a URL we fetch.
    if not valid_url_text(value) or not value.startswith("http://localhost:12701/disk/") or "#" in value:
        return False
    try:
        parsed = urlsplit(value)
        path = unquote(parsed.path, errors="strict")
        return (parsed.scheme == "http" and parsed.netloc == "localhost:12701"
                and len(path) > len("/disk/")
                and not any(part in (".", "..") for part in path.split("/"))
                and not any(char.isspace() or unicodedata.category(char) == "Cc" or char in "\\%?#" for char in path))
    except (ValueError, UnicodeError):
        return False


def validate_classic_document(value, document_url):
    fields = {"source_url", "captured_at", "balancer_url", "token", "document_key", "file_type", "download_url", "title", "edit"}
    if not isinstance(value, dict) or set(value) != fields:
        raise VerificationError()
    if value["source_url"] != document_url or not allowed_url(document_url, navigation=True):
        raise VerificationError()
    captured_at = value["captured_at"]
    now = int(time.time())
    if type(captured_at) is not int or not now - 120 <= captured_at <= now + 5:
        raise VerificationError()
    if value["edit"] is not True or not classic_url(value["balancer_url"], origin=True) or not classic_document_reference(value["download_url"]):
        raise VerificationError()
    for field, maximum in (("token", 16384), ("document_key", 512)):
        text = value[field]
        if not isinstance(text, str) or not 1 <= len(text) <= maximum or any(not 0x21 <= ord(char) <= 0x7e for char in text):
            raise VerificationError()
    if value["document_key"] in (".", "..") or not isinstance(value["file_type"], str) or re.fullmatch(r"[A-Za-z0-9._-]{1,32}", value["file_type"]) is None:
        raise VerificationError()
    title = value["title"]
    if not isinstance(title, str) or any(unicodedata.category(char) == "Cc" for char in title):
        raise VerificationError()
    try:
        size = len(json.dumps(value, ensure_ascii=False, separators=(",", ":"), allow_nan=False).encode("utf-8"))
        if len(title.encode("utf-8")) > 1024 or size > MAX_CLASSIC_DOCUMENT:
            raise VerificationError()
    except (ValueError, UnicodeError):
        raise VerificationError() from None
    return value


def validate_response(value, request_id, document_url):
    if not isinstance(value, dict) or value.get("id") != request_id:
        raise VerificationError()
    if set(value) == {"id", "error"} and value["error"] == "verification_failed":
        return value
    if not {"id", "cookies"} <= set(value) <= {"id", "cookies", "user_agent", "classic_document"} or not isinstance(value["cookies"], list) or not 1 <= len(value["cookies"]) <= MAX_COOKIES:
        raise VerificationError()
    if "user_agent" in value and not valid_user_agent(value["user_agent"]):
        raise VerificationError()
    if "classic_document" in value:
        validate_classic_document(value["classic_document"], document_url)
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


def export_cookies(cookies, request, user_agent=None, classic_document=None):
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
    response = {"id": request["id"], "cookies": result}
    if user_agent is not None:
        response["user_agent"] = user_agent
    if classic_document is not None:
        response["classic_document"] = classic_document
    return validate_response(response, request["id"], request["document_url"])


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
            navigation_generation = 0
            def on_navigation(frame):
                nonlocal navigation_generation
                if frame == page.main_frame:
                    navigation_generation += 1
            page.on("framenavigated", on_navigation)
            # Read before untrusted document scripts run; this is the context's actual UA.
            user_agent = await page.evaluate("navigator.userAgent")
            if not valid_user_agent(user_agent):
                raise VerificationError()
            await page.goto(request["document_url"], wait_until="domcontentloaded", timeout=20000)
            await page.wait_for_function(READY_SCRIPT, timeout=20000, polling=250)
            if not allowed_url(page.url, navigation=True):
                raise VerificationError()
            verified_url = page.url
            verified_generation = navigation_generation
            snapshot = await page.evaluate(CLASSIC_DOCUMENT_SCRIPT)
            if not isinstance(snapshot, dict):
                raise VerificationError()
            if snapshot == {"kind": "volga"}:
                classic_document = None
            elif (set(snapshot) == {"kind", "document"} and snapshot["kind"] == "classic"
                  and isinstance(snapshot["document"], dict)
                  and set(snapshot["document"]) == {"balancer_url", "token", "document_key", "file_type", "download_url", "title", "edit"}):
                classic_document = {"source_url": request["document_url"], "captured_at": int(time.time()), **snapshot["document"]}
                validate_classic_document(classic_document, request["document_url"])
            else:
                raise VerificationError()
            cookies = await context.cookies([request["document_url"]])
            if verified_generation != navigation_generation or page.url != verified_url:
                raise VerificationError()
            return export_cookies(cookies, request, user_agent, classic_document)
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
