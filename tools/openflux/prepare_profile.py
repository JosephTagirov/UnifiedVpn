#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Prepare private encrypted OpenFlux profiles or inspect Yandex HTML structure."""

import argparse
import base64
import csv
from html.parser import HTMLParser
import http.client
import io
import json
import os
from pathlib import Path
import re
import secrets
import stat
import subprocess
import sys
import urllib.error
import urllib.parse
import urllib.request


MAX_DOCUMENT_FILE = 32768
MAX_RESPONSE_BODY = 4 * 1024 * 1024
HTTP_TIMEOUT = 20
OUTPUT_FILES = ("server.json", "client.json", "profile.json", "profile.uri")
TRANSPORTS = ("yandex", "vyandex")
SCHEMA_PATHS = {
    "officeActionData": ("officeActionData",),
    "editor_config": ("officeActionData", "editor_config"),
    "balancer_url": ("officeActionData", "balancer_url"),
    "document": ("officeActionData", "editor_config", "document"),
    "key": ("officeActionData", "editor_config", "document", "key"),
    "token": ("officeActionData", "editor_config", "token"),
    "permissions": ("officeActionData", "editor_config", "document", "permissions"),
    "permissions_edit": ("officeActionData", "editor_config", "document", "permissions", "edit"),
}


class PreparationError(Exception):
    pass


class RedirectNeedsReview(Exception):
    def __init__(self, status):
        self.status = status
        super().__init__("Document redirect requires review.")


def validate_document_url(value):
    if not isinstance(value, str) or not 1 <= len(value) <= 8192:
        raise PreparationError("A valid Yandex document link is required.")
    if any(char.isspace() or ord(char) < 32 or ord(char) == 127 for char in value):
        raise PreparationError("The document link contains unsupported whitespace or control characters.")
    try:
        url = urllib.parse.urlsplit(value)
        valid = (url.scheme.lower() == "https" and url.hostname in ("docs.yandex.ru", "disk.yandex.ru")
                 and url.username is None and url.password is None and "#" not in value
                 and url.port in (None, 443) and len(url.path) > 1)
    except ValueError:
        valid = False
    if not valid:
        raise PreparationError("Use a supported HTTPS Yandex Docs or Disk link without userinfo or fragments.")
    return value


def is_reparse(info):
    return bool(getattr(info, "st_file_attributes", 0) & getattr(stat, "FILE_ATTRIBUTE_REPARSE_POINT", 0))


def read_document(path):
    try:
        info = path.lstat()
        if not stat.S_ISREG(info.st_mode) or is_reparse(info) or info.st_nlink != 1 or info.st_size > MAX_DOCUMENT_FILE:
            raise PreparationError("The document input must be a small regular private file, without links.")
        if os.name != "nt" and (info.st_mode & 0o077 or info.st_uid not in (0, os.geteuid())):
            raise PreparationError("The document file must be owner-readable only.")
        with path.open("rb") as handle:
            data = handle.read(MAX_DOCUMENT_FILE + 1)
        if len(data) > MAX_DOCUMENT_FILE:
            raise PreparationError("The document input is too large.")
        value = data.decode("utf-8-sig").strip()
    except (OSError, UnicodeError):
        raise PreparationError("Cannot read the private UTF-8 document file.") from None
    return validate_document_url(value)


def lock_down_windows(directory):
    options = {"stdin": subprocess.DEVNULL, "stderr": subprocess.DEVNULL,
               "timeout": 10, "check": False, "creationflags": getattr(subprocess, "CREATE_NO_WINDOW", 0)}
    try:
        identity = subprocess.run(["whoami", "/user", "/fo", "csv", "/nh"], stdout=subprocess.PIPE, **options)
        rows = list(csv.reader(io.StringIO(identity.stdout.decode("utf-8", errors="replace"))))
        if identity.returncode or len(rows) != 1 or len(rows[0]) != 2 or not re.fullmatch(r"S-1(?:-[0-9]+)+", rows[0][1]):
            raise PreparationError("Cannot identify the current user for private output permissions.")
        sid = rows[0][1]
        result = subprocess.run(["icacls", str(directory), "/inheritance:r", "/grant:r",
                                 f"*{sid}:(OI)(CI)F", "*S-1-5-18:(OI)(CI)F", "/Q"], stdout=subprocess.DEVNULL, **options)
        if result.returncode:
            raise PreparationError("Cannot restrict the new output directory permissions.")
    except (OSError, subprocess.TimeoutExpired):
        raise PreparationError("Private output permission setup failed.") from None


def create_private_directory(directory):
    try:
        parent = directory.parent.lstat()
        if not stat.S_ISDIR(parent.st_mode) or is_reparse(parent):
            raise PreparationError("Use an existing private parent directory without links.")
        if os.name != "nt" and (parent.st_mode & 0o022 or parent.st_uid not in (0, os.geteuid())):
            raise PreparationError("The output parent must not be writable by other users.")
        directory.mkdir(mode=0o700)
    except FileExistsError:
        raise PreparationError("Output directory already exists; no key or file was overwritten.") from None
    except OSError:
        raise PreparationError("Cannot create the new private output directory.") from None
    if os.name == "nt":
        lock_down_windows(directory)
    else:
        os.chmod(directory, 0o700)


def write_private(path, data):
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, "wb") as handle:
        if os.name != "nt":
            os.fchmod(handle.fileno(), 0o600)
        handle.write(data)


def json_bytes(value, pretty=False):
    return json.dumps(value, ensure_ascii=False, indent=2 if pretty else None,
                      separators=None if pretty else (",", ":")).encode("utf-8")


def generate(document_file, directory, socks_port, transport="yandex"):
    if not isinstance(transport, str) or transport not in TRANSPORTS:
        raise PreparationError("Select the yandex or vyandex transport explicitly; automatic fallback is not supported.")
    if type(socks_port) is not int or not 1 <= socks_port <= 65535 or socks_port == 10808:
        raise PreparationError("Use an isolated SOCKS port from 1 through 65535, excluding 10808.")
    document_url = read_document(document_file)
    create_private_directory(directory)
    # Refusing an existing directory happens before creating any new key.
    key = secrets.token_hex(32)
    profile = {"document_url": document_url, "encryption_key": key, "version": 1, "transport": transport}
    server = dict(profile, mode="server", handshake_timeout_seconds=60)
    client = dict(profile, mode="client", handshake_timeout_seconds=60, socks5=f"127.0.0.1:{socks_port}",
                  socks_username="", socks_password="", dns_server="1.1.1.1:53")
    uri = b"openflux://" + base64.urlsafe_b64encode(json_bytes(profile)).rstrip(b"=")
    outputs = {"server.json": json_bytes(server, pretty=True), "client.json": json_bytes(client, pretty=True),
               "profile.json": json_bytes(profile, pretty=True), "profile.uri": uri}
    try:
        for name in OUTPUT_FILES:
            write_private(directory / name, outputs[name] + b"\n")
    except OSError:
        raise PreparationError("Private generation did not finish; the new directory was retained and must not be overwritten.") from None
    return {"status": "created", "encrypted": True, "files_created": len(OUTPUT_FILES), "socks_loopback": True, "socks_port": socks_port}


class YandexRedirectHandler(urllib.request.HTTPRedirectHandler):
    def __init__(self):
        super().__init__()
        self.followed = 0

    def redirect_request(self, req, fp, code, msg, headers, newurl):
        if self.followed >= 5:
            raise RedirectNeedsReview(code)
        try:
            validate_document_url(newurl)
        except PreparationError:
            raise RedirectNeedsReview(code) from None
        self.followed += 1
        return super().redirect_request(req, fp, code, msg, headers, newurl)

    def http_error_302(self, req, fp, code, msg, headers):
        try:
            location = headers.get("location") or headers.get("uri")
            if not location or any(char.isspace() or ord(char) < 32 or ord(char) == 127 for char in location):
                raise RedirectNeedsReview(code)
            newurl = urllib.parse.urljoin(req.full_url, location)
            redirected = self.redirect_request(req, fp, code, msg, headers, newurl)
        except ValueError:
            raise RedirectNeedsReview(code) from None
        finally:
            # urllib's default handler drains redirect bodies without a size limit.
            fp.close()
        return self.parent.open(redirected, timeout=req.timeout)

    http_error_301 = http_error_302
    http_error_303 = http_error_302
    http_error_307 = http_error_302
    http_error_308 = http_error_302


class ClientConfigParser(HTMLParser):
    def __init__(self):
        super().__init__(convert_charrefs=False)
        self.capturing = False
        self.parts = []
        self.scripts = []

    def handle_starttag(self, tag, attrs):
        ids = [value for name, value in attrs if name == "id"]
        if tag == "script" and ids == ["client-config"]:
            self.capturing = True
            self.parts = []

    def handle_data(self, data):
        if self.capturing:
            self.parts.append(data)

    def handle_endtag(self, tag):
        if tag == "script" and self.capturing:
            self.scripts.append("".join(self.parts))
            self.parts = []
            self.capturing = False


def describe_schema_types(config):
    def describe(path):
        value = config
        for name in path:
            if not isinstance(value, dict):
                return "parent_unavailable"
            if name not in value:
                return "missing"
            value = value[name]
        if value is None:
            return "null"
        if isinstance(value, bool):
            return "boolean"
        for kind, label in ((dict, "object"), (list, "array"), (str, "string")):
            if isinstance(value, kind):
                return label if value else "empty_" + label
        return "number" if isinstance(value, (int, float)) else "unknown"

    # Only these fixed paths and type labels are exposed, never page field names or values.
    return {name: describe(path) for name, path in SCHEMA_PATHS.items()}


def safe_structure(html):
    flags = {name: False for name in ("client_config", "client_config_json", "officeActionData", "editor_config", "balancer_url",
                                    "document", "key", "token", "permissions", "permissions_edit_present", "permissions_edit_enabled")}
    result = {"status": "client_config_missing", "structure": flags, "top_level_key_count": None,
              "schema_types": {name: "unavailable" for name in SCHEMA_PATHS},
              "legacy_editor_fields_present": False, "encrypted_peer_verified": False}
    parser = ClientConfigParser()
    try:
        parser.feed(html)
        parser.close()
    except (ValueError, AssertionError):
        result["status"] = "html_invalid"
        return result
    flags["client_config"] = bool(parser.scripts)
    if not parser.scripts:
        return result
    if len(parser.scripts) != 1:
        result["status"] = "ambiguous_client_config"
        return result
    def unique(pairs):
        value = {}
        for key, entry in pairs:
            if key in value:
                raise ValueError()
            value[key] = entry
        return value
    try:
        config = json.loads(parser.scripts[0], object_pairs_hook=unique)
        if not isinstance(config, dict):
            raise ValueError()
    except (ValueError, RecursionError):
        result["status"] = "client_config_invalid"
        return result
    flags["client_config_json"] = True
    result["status"] = "parsed"
    result["top_level_key_count"] = len(config)
    result["schema_types"] = describe_schema_types(config)
    office = config.get("officeActionData")
    flags["officeActionData"] = isinstance(office, dict)
    office = office if flags["officeActionData"] else {}
    editor = office.get("editor_config")
    flags["editor_config"] = isinstance(editor, dict)
    editor = editor if flags["editor_config"] else {}
    document = editor.get("document")
    flags["document"] = isinstance(document, dict)
    document = document if flags["document"] else {}
    flags["balancer_url"] = isinstance(office.get("balancer_url"), str) and bool(office["balancer_url"])
    flags["key"] = isinstance(document.get("key"), str) and bool(document["key"])
    flags["token"] = isinstance(editor.get("token"), str) and bool(editor["token"])
    permissions = document.get("permissions")
    flags["permissions"] = isinstance(permissions, dict)
    permissions = permissions if flags["permissions"] else {}
    flags["permissions_edit_present"] = "edit" in permissions
    flags["permissions_edit_enabled"] = permissions.get("edit") is True
    result["legacy_editor_fields_present"] = all(flags[name] for name in ("officeActionData", "editor_config", "balancer_url", "document", "key", "token"))
    return result


def probe(document_file, opener_factory=None):
    document_url = read_document(document_file)
    redirects = YandexRedirectHandler()
    factory = opener_factory or urllib.request.build_opener
    # The standard opener retains urllib's normal proxy discovery and TLS checks.
    opener = factory(redirects)
    request = urllib.request.Request(document_url, headers={"User-Agent": "Mozilla/5.0", "Accept": "text/html", "Accept-Encoding": "identity"}, method="GET")
    result = {"status": "request_failed", "http_status": None, "redirects_followed": 0, "encrypted_peer_verified": False}
    try:
        with opener.open(request, timeout=HTTP_TIMEOUT) as response:
            status = response.status
            result["http_status"] = status if type(status) is int and 100 <= status <= 599 else None
            if not 200 <= status < 300:
                result["status"] = "http_error"
                return result
            if response.headers.get("Content-Encoding", "identity").lower() not in ("", "identity"):
                result["status"] = "unsupported_content_encoding"
                return result
            length = response.headers.get("Content-Length", "")
            if length.isdigit() and int(length) > MAX_RESPONSE_BODY:
                result["status"] = "body_limit"
                return result
            body = response.read(MAX_RESPONSE_BODY + 1)
            if len(body) > MAX_RESPONSE_BODY:
                result["status"] = "body_limit"
                return result
            result.update(safe_structure(body.decode("utf-8", errors="replace")))
    except RedirectNeedsReview as error:
        result["status"] = "redirect_requires_review"
        result["http_status"] = error.status
    except urllib.error.HTTPError as error:
        result["status"] = "redirect_requires_review" if 300 <= error.code < 400 else "http_error"
        result["http_status"] = error.code
        error.close()
    except (urllib.error.URLError, OSError, ValueError, RecursionError, http.client.HTTPException):
        pass
    finally:
        result["redirects_followed"] = redirects.followed
    return result


class SafeArgumentParser(argparse.ArgumentParser):
    def error(self, message):
        raise PreparationError("Invalid command arguments; supply private file paths, not secret values.")


def argument_parser():
    parser = SafeArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    generate_parser = commands.add_parser("generate")
    generate_parser.add_argument("--document-file", type=Path, required=True)
    generate_parser.add_argument("--directory", type=Path, required=True)
    generate_parser.add_argument("--socks-port", type=int, required=True)
    generate_parser.add_argument("--transport", choices=TRANSPORTS, default="yandex",
                                 help="yandex: legacy editor (default); vyandex: new Volga editor; no automatic fallback")
    probe_parser = commands.add_parser("probe")
    probe_parser.add_argument("--document-file", type=Path, required=True)
    return parser


def main(argv=None):
    try:
        args = argument_parser().parse_args(argv)
        if args.command == "generate":
            result = generate(args.document_file, args.directory, args.socks_port, args.transport)
        else:
            result = probe(args.document_file)
    except (PreparationError, OSError, KeyboardInterrupt, EOFError):
        print(json.dumps({"status": "operation_failed", "secrets_printed": False}))
        return 1
    print(json.dumps(result, sort_keys=True))
    return 0 if result["status"] in ("created", "parsed") else 1


if __name__ == "__main__":
    sys.exit(main())
