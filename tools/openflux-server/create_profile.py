#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Prepare a separate private OpenFlux profile; deploy only with explicit --apply."""

import argparse
import base64
import getpass
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import platform
import re
import secrets
import shlex
import stat
import sys
from types import SimpleNamespace
from urllib.parse import parse_qsl, quote, urlsplit
import warnings


SCRIPT_DIR = Path(__file__).resolve().parent
manage = None
PROFILE_PARENT = Path("/root")
SCHEMA = "unifiedvpn-openflux-profile-plan-v1"
PUBLIC_LIMITS = {"openflux": 256 * 1024 * 1024, "source.tar.gz": 512 * 1024 * 1024,
                 "LICENSE": 1024 * 1024, "NOTICE": 1024 * 1024, "COPYRIGHT": 1024 * 1024,
                 "provenance.json": 65536}
PLAN_FIELDS = {"schema", "instance", "owner", "source_instance", "binary_sha256", "source_sha256",
               "runtime_image", "licenses_sha256", "subnet", "server_sha256", "profile_sha256"}
ADMIN_PLAN_FIELDS = PLAN_FIELDS | {"display_name", "admin_manifest_sha256"}
_browser_backend = None


class PreparationError(Exception):
    pass


def fail(message):
    if manage is None:
        raise PreparationError(message)
    manage.fail(message)


def require_secure_tool_file(path):
    # Validate dependency code before importing it with root privileges.
    for directory in (path.parent, *path.parent.parents):
        info = directory.lstat()
        if not stat.S_ISDIR(info.st_mode) or info.st_uid != 0 or info.st_mode & 0o022:
            fail("Tool directories must be root-owned, without writable parents or symlinks.")
    info = path.lstat()
    if not stat.S_ISREG(info.st_mode) or info.st_nlink != 1 or info.st_uid != 0 or info.st_mode & 0o022:
        fail("Tool files must be root-owned regular files, without links or writable-by-others permissions.")


def require_host():
    if platform.system() != "Linux" or platform.machine() not in ("x86_64", "amd64") or os.geteuid() != 0:
        fail("Run this helper as root on the intended Linux amd64 server; preparation does not contact Docker.")
    for name in ("create_profile.py", "manage.py", "Dockerfile", "entrypoint.sh"):
        require_secure_tool_file(SCRIPT_DIR / name)
    global manage
    spec = importlib.util.spec_from_file_location("openflux_profile_manager", SCRIPT_DIR / "manage.py")
    module = importlib.util.module_from_spec(spec)
    # Do not load a pre-existing bytecode cache that was not part of the file checks.
    with (SCRIPT_DIR / "manage.py").open("rb") as stream:
        source = stream.read(256 * 1024 + 1)
    if len(source) > 256 * 1024:
        fail("The manager source exceeds the reviewed helper size limit.")
    exec(compile(source, str(SCRIPT_DIR / "manage.py"), "exec", dont_inherit=True), module.__dict__)
    manage = module


def named_instance(value):
    manage.instance_name(value)
    if value == "default":
        fail("Choose a new named instance; this helper never replaces the default installation.")
    return value


def read_bounded(path, limit, private=False):
    info = manage.require_secure_file(path, private=private)
    if not 0 < info.st_size <= limit:
        fail("An input file is empty or exceeds its size limit.")
    with path.open("rb") as stream:
        value = stream.read(limit + 1)
    if len(value) > limit:
        fail("An input file changed beyond its size limit.")
    return value


def private_directory(path):
    manage.secure_directory(path)
    if path.lstat().st_mode & 0o077:
        fail("The prepared directory must be root-only (mode 0700).")


def write_private(path, data):
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, "wb") as stream:
        stream.write(data)
        stream.flush()
        os.fsync(stream.fileno())


def json_bytes(value):
    return (json.dumps(value, ensure_ascii=False, indent=2) + "\n").encode("utf-8")


def document_identity(value):
    manage.validate_document_url(value)
    if any(char.isspace() for char in value) or "#" in value:
        fail("The private document URL must not contain whitespace or fragments.")
    parts = urlsplit(value)
    query = parse_qsl(parts.query, keep_blank_values=True)
    authoritative = [item for key, item in query if key.lower() == "url"]
    if authoritative:
        if len(authoritative) != 1 or not authoritative[0]:
            fail("The document URL contains an ambiguous document identifier.")
        return ("yandex-document", authoritative[0])
    if re.fullmatch(r"/(?:i|d)/[A-Za-z0-9_-]+/?", parts.path):
        return ("yandex-share", parts.path.rstrip("/"))
    parameters = [(key, item) for key, item in query
                  if not key.lower().startswith("utm_") and key.lower() not in ("gclid", "fbclid", "yclid")]
    return parts.hostname, parts.path, tuple(sorted(parameters))


def validate_output_path(output):
    if not output.is_absolute() or ".." in output.parts:
        fail("Use an absolute prepared-directory path without parent traversal.")
    try:
        relative = output.relative_to(manage.INSTALL_DIR.parent)
    except ValueError:
        relative = None
    if relative is not None and relative.parts and (
        relative.parts[0] == manage.NAME or relative.parts[0].startswith(manage.NAME + "-")
    ):
        fail("Keep prepared files outside all existing or future managed installation directories.")


def require_new_document(value):
    identity = document_identity(value)
    parent = manage.INSTALL_DIR.parent
    manage.secure_directory(parent)
    for path in parent.iterdir():
        if path.name == manage.NAME:
            instance = "default"
        elif path.name.startswith(manage.NAME + "-"):
            instance = path.name[len(manage.NAME) + 1:]
            if not re.fullmatch(r"[a-z][a-z0-9]{0,15}", instance):
                continue
        else:
            continue
        # Compare only in memory; never copy or export another instance's config.
        config = manage.read_config(path / "server.json")
        if document_identity(config["document_url"]) == identity:
            fail("That document is already used by an installed instance. Create a separate unused document.")


def public_artifacts(source_instance):
    source = manage.Manager(instance=source_instance).root
    manage.secure_directory(source)
    for name, maximum in PUBLIC_LIMITS.items():
        info = manage.require_secure_file(source / name)
        if not 0 < info.st_size <= maximum:
            fail("An installed public artifact is empty or too large.")
    provenance = manage.decode_json(read_bounded(source / "provenance.json", 65536))
    if not isinstance(provenance, dict) or (
        provenance.get("upstream_repository") != "https://github.com/p1neappleXpress/OpenFlux"
        or provenance.get("upstream_commit") != manage.UPSTREAM
        or provenance.get("wrapper_version") != manage.VERSION
        or provenance.get("protocol") != manage.PROTOCOL
    ):
        fail("Installed public artifacts do not match this pinned encrypted wrapper.")
    for field, name in (("binary_sha256", "openflux"), ("source_sha256", "source.tar.gz")):
        expected = provenance.get(field)
        if not isinstance(expected, str) or not manage.HASH_RE.fullmatch(expected) or manage.sha256_file(source / name) != expected:
            fail("An installed public artifact does not match its recorded SHA256.")
    runtime = provenance.get("runtime_image_id")
    if not isinstance(runtime, str) or not manage.IMAGE_RE.fullmatch(runtime):
        fail("An immutable reviewed runtime image ID is required.")
    manage.validate_elf(source / "openflux")
    with (source / "source.tar.gz").open("rb") as stream:
        if stream.read(2) != b"\x1f\x8b":
            fail("The matching source archive must be gzip-compressed.")
    return {"source_instance": source_instance, "binary_sha256": provenance["binary_sha256"],
            "source_sha256": provenance["source_sha256"], "runtime_image": runtime,
            "licenses_sha256": {name: manage.sha256_file(source / name) for name in ("LICENSE", "NOTICE", "COPYRIGHT")}}


def read_document(args):
    transport = manage.validate_transport("yandex" if args.transport is None else args.transport)
    legacy = transport == "yandex"
    if (legacy and args.confirm_volga_document) or (not legacy and args.confirm_legacy_document):
        fail("The document confirmation must match --transport; never convert an existing document in place.")
    if args.document_file is not None:
        try:
            value = read_bounded(args.document_file, 32768, private=True).decode("utf-8-sig").strip()
        except UnicodeError:
            fail("The private document file must contain one UTF-8 URL.")
    else:
        if not sys.stdin.isatty():
            fail("Hidden input requires a terminal; otherwise use --document-file with a private mode-0600 file.")
        with warnings.catch_warnings():
            warnings.simplefilter("error", getpass.GetPassWarning)
            try:
                value = getpass.getpass("Private Yandex document URL (hidden): ")
            except getpass.GetPassWarning:
                fail("Hidden terminal input is unavailable; refusing an echo fallback.")
    document_identity(value)
    confirmed = args.confirm_legacy_document if legacy else args.confirm_volga_document
    if not confirmed and sys.stdin.isatty():
        editor = "legacy editor" if legacy else "new Volga editor"
        confirmed = input("Separate unused document, " + editor + ", editable without login? Type yes: ").strip().lower() == "yes"
    if not confirmed:
        option = "--confirm-legacy-document" if legacy else "--confirm-volga-document"
        fail("Confirm a separate editable document for the selected editor. Noninteractive use requires " + option + ".")
    return value


def profile_uri(config, name):
    profile = {field: config[field] for field in ("document_url", "encryption_key", "version", "transport")}
    codec = manage.validate_wire_codec(config.get("wire_codec", manage.DEFAULT_WIRE_CODEC), config["transport"])
    if codec != manage.DEFAULT_WIRE_CODEC:
        profile["wire_codec"] = codec
    encoded = base64.urlsafe_b64encode(json.dumps(profile, ensure_ascii=False, separators=(",", ":")).encode("utf-8"))
    return b"openflux://" + encoded.rstrip(b"=") + b"#" + quote(name, safe="").encode("ascii") + b"\n"


def browser_backend():
    global _browser_backend
    if _browser_backend is None:
        for name in ("browser_service.py", "browser_supervisor.py", "browser_worker.py", "manage.py", "create_profile.py", "Dockerfile", "entrypoint.sh"):
            manage.require_secure_file(SCRIPT_DIR / name)
        spec = importlib.util.spec_from_file_location("openflux_profile_browser_service", SCRIPT_DIR / "browser_service.py")
        module = importlib.util.module_from_spec(spec)
        source = read_bounded(SCRIPT_DIR / "browser_service.py", 262144)
        exec(compile(source, str(SCRIPT_DIR / "browser_service.py"), "exec", dont_inherit=True), module.__dict__)
        module.manage = manage
        module.browser.manage = manage
        _browser_backend = module
    return _browser_backend


def prepare_admin(args):
    backend = browser_backend()
    if args.reuse_instance is not None or args.subnet is not None:
        fail("Browser administration uses only the reviewed manifest's artifact source and subnet pool.")
    manifest_path = args.admin_manifest or SCRIPT_DIR / "admin-runtime.json"
    manifest = backend.read_manifest(manifest_path)
    name = args.instance
    if name is None and sys.stdin.isatty():
        name = input("New profile name (1..9 lowercase letters/digits, for example phone3): ").strip()
    if not isinstance(name, str) or not re.fullmatch(r"[a-z][a-z0-9]{0,8}", name):
        fail("Use a new name of 1..9 lowercase letters/digits, starting with a letter.")
    instance = "browser" + name
    target = manage.Manager(instance=instance).root
    if target.exists() or target.is_symlink():
        fail("That browser profile already exists; it will not be replaced.")
    if args.transport is None:
        if not sys.stdin.isatty():
            fail("Browser administration requires an explicit --transport yandex or vyandex.")
        args.transport = input("Editor: yandex (classic) or vyandex (Volga): ").strip()
    manage.validate_transport(args.transport)
    codec = manage.validate_wire_codec(manage.DEFAULT_WIRE_CODEC if args.wire_codec is None else args.wire_codec, args.transport)
    if manifest["source_instance"] == instance:
        fail("The new instance cannot be its own artifact source.")
    artifacts = public_artifacts(manifest["source_instance"])
    if any(artifacts[field] != value for field, value in manifest["artifacts"].items()):
        fail("Installed public artifacts differ from the reviewed admin manifest.")
    output = args.output or PROFILE_PARENT / ("unifiedvpn-openflux-profile-" + instance)
    backend.canonical_path(output)
    validate_output_path(output)
    manage.secure_directory(output.parent)
    if output.exists() or output.is_symlink():
        fail("The prepared directory already exists; no profile or key was overwritten.")
    document = read_document(args)
    require_new_document(document)
    output.mkdir(mode=0o700)
    manifest_hash = backend.freeze_runtime(output, manifest)
    config = {"version": 1, "mode": "server", "transport": args.transport, "document_url": document,
              "encryption_key": secrets.token_hex(32), "handshake_timeout_seconds": 60}
    if codec != manage.DEFAULT_WIRE_CODEC:
        config["wire_codec"] = codec
    server = json_bytes(manage.validate_config(config))
    uri = profile_uri(config, "OpenFlux " + name)
    plan = dict(artifacts, schema=backend.PLAN_SCHEMA, instance=instance, owner=secrets.token_hex(32), subnet=None,
                display_name=name, admin_manifest_sha256=manifest_hash,
                server_sha256=hashlib.sha256(server).hexdigest(), profile_sha256=hashlib.sha256(uri).hexdigest())
    write_private(output / "server.json", server)
    write_private(output / "profile.uri", uri)
    write_private(output / "plan.json", json_bytes(plan))
    return output


def prepare(args):
    if args.browser_companion:
        return prepare_admin(args)
    transport = manage.validate_transport("yandex" if args.transport is None else args.transport)
    codec = manage.validate_wire_codec(manage.DEFAULT_WIRE_CODEC if args.wire_codec is None else args.wire_codec, transport)
    instance = args.instance
    if instance is None:
        if not sys.stdin.isatty():
            fail("Specify --instance when no interactive terminal is available.")
        instance = input("New device/instance name (for example phone2): ").strip()
    named_instance(instance)
    target = manage.Manager(instance=instance).root
    if target.exists() or target.is_symlink():
        fail("That instance already exists; no existing installation will be overwritten.")
    output = args.output if args.output is not None else PROFILE_PARENT / ("unifiedvpn-openflux-profile-" + instance)
    validate_output_path(output)
    manage.secure_directory(output.parent)
    if output.exists() or output.is_symlink():
        fail("The prepared directory already exists; no profile or key was overwritten.")
    source_instance = args.reuse_instance
    if source_instance is None:
        source_instance = "default"
        if sys.stdin.isatty():
            source_instance = input("Reuse public artifacts from instance [default]: ").strip() or "default"
    if source_instance == instance:
        fail("The new instance must differ from the installed artifact source.")
    artifacts = public_artifacts(source_instance)
    subnet = args.subnet
    if subnet is None:
        subnet = "172.30.252.0/28"
        if sys.stdin.isatty():
            subnet = input("Unused Docker subnet [172.30.252.0/28]: ").strip() or subnet
    manage.validate_subnet(subnet)
    document = read_document(args)
    require_new_document(document)
    output.mkdir(mode=0o700)
    config = {"version": 1, "mode": "server", "transport": transport, "document_url": document,
              "encryption_key": secrets.token_hex(32), "handshake_timeout_seconds": 60}
    if codec != manage.DEFAULT_WIRE_CODEC:
        config["wire_codec"] = codec
    manage.validate_config(config)
    server = json_bytes(config)
    uri = profile_uri(config, "OpenFlux " + instance)
    plan = dict(artifacts, schema=SCHEMA, instance=instance, owner=secrets.token_hex(32), subnet=subnet,
                server_sha256=hashlib.sha256(server).hexdigest(), profile_sha256=hashlib.sha256(uri).hexdigest())
    write_private(output / "server.json", server)
    write_private(output / "profile.uri", uri)
    write_private(output / "plan.json", json_bytes(plan))
    return output


def read_plan(directory, with_inputs=True):
    validate_output_path(directory)
    private_directory(directory)
    plan = manage.decode_json(read_bounded(directory / "plan.json", 65536, private=True))
    admin = isinstance(plan, dict) and plan.get("schema") == "unifiedvpn-openflux-admin-profile-v1"
    if not isinstance(plan, dict) or set(plan) != (ADMIN_PLAN_FIELDS if admin else PLAN_FIELDS) or not admin and plan["schema"] != SCHEMA:
        fail("The prepared plan has an unsupported schema.")
    named_instance(plan["instance"])
    manage.instance_name(plan["source_instance"])
    if plan["instance"] == plan["source_instance"]:
        fail("The prepared target must not be the artifact source.")
    for field in ("owner", "binary_sha256", "source_sha256", "server_sha256", "profile_sha256"):
        if not isinstance(plan[field], str) or not manage.HASH_RE.fullmatch(plan[field]):
            fail("The prepared plan contains an invalid identity or checksum.")
    if not isinstance(plan["runtime_image"], str) or not manage.IMAGE_RE.fullmatch(plan["runtime_image"]):
        fail("The prepared runtime image identity is invalid.")
    licenses = plan["licenses_sha256"]
    if not isinstance(licenses, dict) or set(licenses) != {"LICENSE", "NOTICE", "COPYRIGHT"} or any(
        not isinstance(value, str) or not manage.HASH_RE.fullmatch(value) for value in licenses.values()
    ):
        fail("The prepared license checksums are invalid.")
    if admin:
        browser_backend().validate_snapshot(directory, plan)
    else:
        manage.validate_subnet(plan["subnet"])
    if with_inputs:
        server = read_bounded(directory / "server.json", 16384, private=True)
        uri = read_bounded(directory / "profile.uri", 32768, private=True)
        if hashlib.sha256(server).hexdigest() != plan["server_sha256"] or hashlib.sha256(uri).hexdigest() != plan["profile_sha256"]:
            fail("Prepared private files changed; refusing to use a modified plan.")
        config = manage.validate_config(manage.decode_json(server))
        if uri != profile_uri(config, "OpenFlux " + (plan["display_name"] if admin else plan["instance"])):
            fail("The private client profile does not match the server configuration.")
    return plan


def apply_prepared(directory):
    plan = read_plan(directory)
    manager = manage.Manager(instance=plan["instance"])
    if manager.root.exists() or manager.root.is_symlink():
        fail("The target installation already exists. Review it; do not repeat an interrupted apply.")
    artifacts = public_artifacts(plan["source_instance"])
    if any(plan[field] != value for field, value in artifacts.items()):
        fail("The source artifacts changed after preparation; no installation was started.")
    config = manage.read_config(directory / "server.json")
    require_new_document(config["document_url"])
    source = manage.Manager(instance=plan["source_instance"]).root
    args = SimpleNamespace(binary=source / "openflux", binary_sha256=plan["binary_sha256"],
                           source_archive=source / "source.tar.gz", source_sha256=plan["source_sha256"],
                           licenses_dir=source, runtime_image=plan["runtime_image"], config=directory / "server.json", subnet=plan["subnet"])
    if plan["schema"] != SCHEMA:
        browser_backend().AdminService(directory).install(args)
        print("New browser service enabled. A client handshake and real traffic are still required; Connected is not asserted.")
        return
    manager.check(args)
    manager.install(args, owner=plan["owner"])
    if manager.load_state()["owner"] != plan["owner"]:
        fail("The installation owner changed; refusing to start it.")
    manager.run()
    manager.check(args)
    print("New instance start requested. Client handshake and real traffic are still required; Connected is not asserted.")


def rollback_prepared(directory):
    plan = read_plan(directory, with_inputs=False)
    if plan["schema"] != SCHEMA:
        backend = browser_backend()
        if not (directory / "browser-service.json").exists():
            if manage.Manager(instance=plan["instance"]).root.exists():
                fail("An installation without the prepared service receipt requires inspection; nothing was removed.")
            print("No service installation was begun. Private preparation files were retained.")
            return
        backend.AdminService(directory).rollback()
        print("Only the new owned browser service and instance were removed. Private preparation files were retained.")
        return
    manager = manage.Manager(instance=plan["instance"])
    if not manager.root.exists() and not manager.root.is_symlink():
        print("No installation exists for this prepared instance. Private input files were retained.")
        return
    if manager.load_state()["owner"] != plan["owner"]:
        fail("That installation is not owned by this prepared plan; nothing was stopped or removed.")
    manager.remove()
    print("Only the new owned instance was removed. Existing instances and private preparation files were retained.")


class SafeArgumentParser(argparse.ArgumentParser):
    def error(self, message):
        raise PreparationError("Invalid arguments. Use --help; private argument values were not printed.")


def parser():
    result = SafeArgumentParser(description=__doc__)
    result.add_argument("--instance", help="New name, 1..16 lowercase letters/digits; never default")
    result.add_argument("--document-file", type=Path, help="Private mode-0600 URL file instead of hidden terminal input")
    result.add_argument("--transport", choices=("yandex", "vyandex"),
                        help="yandex: legacy editor (default); vyandex: new Volga editor; no automatic fallback")
    result.add_argument("--wire-codec", choices=("legacy-lz4-v1", "batch-zstd-v2"),
                        help="legacy-lz4-v1 (default), or explicit yandex-only batch-zstd-v2; both peers must match")
    result.add_argument("--confirm-legacy-document", action="store_true", help="Confirm a separate unused editable legacy document")
    result.add_argument("--confirm-volga-document", action="store_true", help="Confirm a separate unused editable new Volga document")
    result.add_argument("--reuse-instance", help="Installed verified public artifact source (default: default)")
    result.add_argument("--subnet", help="Unused RFC1918 /24../29; checked against host routes/Docker only with --apply")
    result.add_argument("--output", type=Path, help="New private directory; never overwrites existing files")
    result.add_argument("--browser-companion", action="store_true", help="Prepare a fresh browser-backed profile using the reviewed admin manifest")
    result.add_argument("--admin-manifest", type=Path, help="Root-owned admin manifest (default: adjacent admin-runtime.json)")
    result.add_argument("--confirm-start", action="store_true", help="Explicitly confirm new browser service startup for noninteractive --apply")
    result.add_argument("--prepared", type=Path, help="Previously prepared private directory")
    result.add_argument("--apply", action="store_true", help="Explicitly permit local Docker installation/start or rollback")
    result.add_argument("--rollback", action="store_true", help="Remove only the new plan-owned instance; requires --prepared --apply")
    return result


def prepared_command(directory):
    frozen = directory / "runtime/create_profile.py"
    script = frozen if frozen.exists() else SCRIPT_DIR / "create_profile.py"
    return "sudo python3 -I " + shlex.quote(str(script)) + " --prepared " + shlex.quote(str(directory))


def main(argv=None):
    args = None
    try:
        args = parser().parse_args(argv)
        os.umask(0o077)
        require_host()
        if args.rollback and (args.prepared is None or not args.apply):
            fail("Rollback requires --prepared and explicit --apply; nothing was changed.")
        if args.admin_manifest is not None and not args.browser_companion:
            fail("An admin manifest applies only to a new browser-backed profile.")
        if args.prepared is not None and any((args.instance, args.document_file, args.transport, args.wire_codec,
                                             args.confirm_legacy_document, args.confirm_volga_document,
                                             args.reuse_instance, args.subnet, args.output, args.browser_companion, args.admin_manifest)):
            fail("Do not combine --prepared with new-profile options.")
        directory = args.prepared if args.prepared is not None else prepare(args)
        if args.rollback:
            rollback_prepared(directory)
            return 0
        plan = read_plan(directory)
        if args.apply:
            if plan["schema"] != SCHEMA and not args.confirm_start:
                if not sys.stdin.isatty() or input("Create and enable only this new browser service? Type yes: ").strip().lower() != "yes":
                    fail("New service startup was not confirmed. Prepared files remain; use --confirm-start for explicit noninteractive apply.")
            apply_prepared(directory)
        else:
            print("Prepared private files only. Docker was not contacted; no network or server process was changed.")
        print("Private client import file: " + str(directory / "profile.uri"))
        command = prepared_command(directory)
        if not args.apply:
            print("After review: " + command + " --apply")
        print("Rollback only this new instance: " + command + " --rollback --apply")
    except (Exception, KeyboardInterrupt):
        error = sys.exc_info()[1]
        public_errors = (PreparationError, getattr(manage, "DeploymentError", PreparationError))
        message = str(error) if isinstance(error, public_errors) else "Operation interrupted or a private file could not be processed. Contents were not printed."
        print(message, file=sys.stderr)
        print("Partial private preparation/install may remain. Do not overwrite files or repeat an interrupted apply; use its explicit owned rollback.", file=sys.stderr)
        if args is not None and args.apply and not args.rollback and "directory" in locals():
            print("Rollback only this new instance: " + prepared_command(directory) + " --rollback --apply", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
