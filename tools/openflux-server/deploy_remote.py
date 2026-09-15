#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Fixed-phase deployment worker, supplied through verified SSH stdin only."""

import hashlib
import importlib.util
import ipaddress
import json
import os
from pathlib import Path
import platform
import re
import signal
import stat
import subprocess
import sys


SCHEMA = "unifiedvpn-openflux-deploy-v1"
UPSTREAM = "4f1bdb554c262f3ae9adbfe317a092c6b929ba7d"
PROTOCOL = "unified-openflux-aesgcm-v1"
VERSION = f"unified-openflux 1 upstream={UPSTREAM} protocol={PROTOCOL}"
STAGING_ROOT = Path("/root")
INSTALL_ROOT = Path("/opt/unifiedvpn-openflux")
MAX_METADATA = 65536
FILES = {
    "bundle/manage.py": 262144,
    "bundle/Dockerfile": 65536,
    "bundle/runtime.Dockerfile": 65536,
    "bundle/entrypoint.sh": 65536,
    "bundle/openflux-linux-amd64": 128 * 1024 * 1024,
    "bundle/openflux-source.tar.gz": 256 * 1024 * 1024,
    "bundle/manifest.json": 65536,
    "bundle/licenses/LICENSE": 1024 * 1024,
    "bundle/licenses/NOTICE": 1024 * 1024,
    "bundle/licenses/COPYRIGHT": 1024 * 1024,
    "private/server.json": 16384,
}
PHASES = ("upload", "preflight", "runtime-pull", "runtime-build", "install", "run", "check", "stop")
MUTATING = {"upload", "runtime-pull", "runtime-build", "install", "run", "stop"}
ERRORS = {"invalid_request", "unsupported_host", "unsafe_path", "stage_exists", "stage_incomplete",
          "hash_mismatch", "provenance_mismatch", "config_invalid", "runtime_missing", "runtime_unsafe",
          "runtime_exists", "installation_exists", "installation_mismatch", "command_failed",
          "command_timeout", "filesystem_failed", "unexpected_failure", "apply_required"}


class DeploymentError(Exception):
    def __init__(self, code):
        self.code = code if code in ERRORS else "unexpected_failure"
        super().__init__(self.code)


def fail(code):
    raise DeploymentError(code)


def decode_json(data):
    def unique(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                fail("invalid_request")
            result[key] = value
        return result
    try:
        return json.loads(data, object_pairs_hook=unique)
    except (ValueError, UnicodeError, RecursionError):
        fail("invalid_request")


def json_bytes(data):
    return json.dumps(data, sort_keys=True, separators=(",", ":"), ensure_ascii=True).encode("ascii")


def hash_bytes(data):
    return hashlib.sha256(data).hexdigest()


def valid_hash(value):
    return isinstance(value, str) and re.fullmatch(r"[0-9a-f]{64}", value) is not None


def stage_path(stage_id):
    if not isinstance(stage_id, str) or not re.fullmatch(r"[0-9a-f]{32}", stage_id):
        fail("unsafe_path")
    return STAGING_ROOT / ("unifiedvpn-openflux-stage-" + stage_id)


def secure_directory(path):
    for directory in (path, *path.parents):
        info = directory.lstat()
        if not stat.S_ISDIR(info.st_mode) or info.st_uid != 0 or info.st_mode & 0o022:
            fail("unsafe_path")


def secure_file(path, maximum, private=False):
    secure_directory(path.parent)
    info = path.lstat()
    if not stat.S_ISREG(info.st_mode) or info.st_nlink != 1 or info.st_uid != 0:
        fail("unsafe_path")
    if info.st_mode & (0o077 if private else 0o022) or not 0 < info.st_size <= maximum:
        fail("unsafe_path")
    return info


def read_private(path, maximum=MAX_METADATA):
    secure_file(path, maximum, private=True)
    with path.open("rb") as handle:
        data = handle.read(maximum + 1)
    if len(data) > maximum:
        fail("unsafe_path")
    return data


def write_exclusive(path, data):
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, "wb") as handle:
        os.fchmod(handle.fileno(), 0o600)
        handle.write(data)


def require_host():
    if platform.system() != "Linux" or platform.machine() not in ("x86_64", "amd64") or os.geteuid() != 0:
        fail("unsupported_host")
    if Path("/.dockerenv").exists() or Path("/run/.containerenv").exists():
        fail("unsupported_host")
    if os.readlink("/proc/self/ns/net") != os.readlink("/proc/1/ns/net"):
        fail("unsupported_host")
    secure_directory(STAGING_ROOT)


def validate_manifest(manifest):
    if not isinstance(manifest, dict) or set(manifest) != {"schema", "upstream", "files"}:
        fail("invalid_request")
    if manifest["schema"] != SCHEMA or manifest["upstream"] != UPSTREAM:
        fail("provenance_mismatch")
    entries = manifest["files"]
    if not isinstance(entries, dict) or set(entries) != set(FILES):
        fail("invalid_request")
    for name, limit in FILES.items():
        entry = entries[name]
        if not isinstance(entry, dict) or set(entry) != {"size", "sha256"}:
            fail("invalid_request")
        if type(entry["size"]) is not int or not 0 < entry["size"] <= limit or not valid_hash(entry["sha256"]):
            fail("invalid_request")
    return manifest


def validate_runtime(value):
    if not isinstance(value, str) or not re.fullmatch(r"sha256:[0-9a-f]{64}", value):
        fail("invalid_request")
    return value


def validate_alpine(value):
    if not isinstance(value, str) or not re.fullmatch(r"docker\.io/library/alpine@sha256:[0-9a-f]{64}", value):
        fail("invalid_request")
    return value


def validate_subnet(value):
    try:
        network = ipaddress.IPv4Network(value, strict=True)
    except (ValueError, TypeError):
        fail("invalid_request")
    allowed = ("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16")
    if not 24 <= network.prefixlen <= 29 or not any(network.subnet_of(ipaddress.IPv4Network(item)) for item in allowed):
        fail("invalid_request")
    if network.overlaps(ipaddress.IPv4Network("10.10.10.0/24")):
        fail("invalid_request")
    return str(network)


def validate_request(request):
    if not isinstance(request, dict):
        fail("invalid_request")
    phase = request.get("phase")
    base = {"schema", "phase", "stage", "manifest_sha256", "apply"}
    extra = {"upload": {"manifest"}, "preflight": {"runtime_image", "subnet"},
             "install": {"runtime_image", "subnet"}, "runtime-build": {"alpine_image", "allow_build_network"},
             "runtime-pull": {"alpine_image", "allow_network"}}
    if phase not in PHASES or set(request) != base | extra.get(phase, set()) or request["schema"] != SCHEMA:
        fail("invalid_request")
    stage_path(request["stage"])
    if not valid_hash(request["manifest_sha256"]) or type(request["apply"]) is not bool:
        fail("invalid_request")
    if phase in MUTATING and not request["apply"]:
        fail("apply_required")
    if phase == "upload":
        validate_manifest(request["manifest"])
        if hash_bytes(json_bytes(request["manifest"])) != request["manifest_sha256"]:
            fail("hash_mismatch")
    if phase in ("preflight", "install"):
        validate_runtime(request["runtime_image"])
        validate_subnet(request["subnet"])
    if phase in ("runtime-build", "runtime-pull"):
        validate_alpine(request["alpine_image"])
        consent = "allow_build_network" if phase == "runtime-build" else "allow_network"
        if request[consent] is not True:
            fail("apply_required")
    return request


def read_request(stream):
    length = stream.readline(12)
    if not re.fullmatch(rb"[0-9]{1,6}\n", length):
        fail("invalid_request")
    count = int(length)
    if not 0 < count <= MAX_METADATA:
        fail("invalid_request")
    data = stream.read(count)
    if len(data) != count:
        fail("invalid_request")
    return validate_request(decode_json(data))


def upload(request, stream):
    stage = stage_path(request["stage"])
    if stage.exists() or stage.is_symlink():
        fail("stage_exists")
    stage.mkdir(mode=0o700)
    for relative in ("bundle", "bundle/licenses", "private"):
        (stage / relative).mkdir(mode=0o700)
    for name in FILES:
        entry = request["manifest"]["files"][name]
        remaining, digest = entry["size"], hashlib.sha256()
        descriptor = os.open(stage / name, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(descriptor, "wb") as handle:
            os.fchmod(handle.fileno(), 0o600)
            while remaining:
                block = stream.read(min(remaining, 1024 * 1024))
                if not block:
                    fail("stage_incomplete")
                handle.write(block)
                digest.update(block)
                remaining -= len(block)
        if digest.hexdigest() != entry["sha256"]:
            fail("hash_mismatch")
    if stream.read(1):
        fail("invalid_request")
    write_exclusive(stage / "upload-manifest.json", json_bytes(request["manifest"]))
    return {"files_uploaded": len(FILES)}


def verify_stage(request):
    stage = stage_path(request["stage"])
    secure_directory(stage)
    raw = read_private(stage / "upload-manifest.json")
    if hash_bytes(raw) != request["manifest_sha256"]:
        fail("hash_mismatch")
    manifest = validate_manifest(decode_json(raw))
    for name, limit in FILES.items():
        path = stage / name
        info = secure_file(path, limit, private=name.startswith("private/"))
        entry = manifest["files"][name]
        if info.st_size != entry["size"]:
            fail("hash_mismatch")
        digest = hashlib.sha256()
        with path.open("rb") as handle:
            for block in iter(lambda: handle.read(1024 * 1024), b""):
                digest.update(block)
        if digest.hexdigest() != entry["sha256"]:
            fail("hash_mismatch")
    return stage, manifest


def load_manager(stage):
    specification = importlib.util.spec_from_file_location("openflux_stage_manager", stage / "bundle/manage.py")
    module = importlib.util.module_from_spec(specification)
    specification.loader.exec_module(module)
    if module.UPSTREAM != UPSTREAM or module.VERSION != VERSION or module.INSTALL_DIR != INSTALL_ROOT:
        fail("provenance_mismatch")
    return module


def validate_artifacts(stage, manifest, module):
    artifact = decode_json(read_private(stage / "bundle/manifest.json"))
    if not isinstance(artifact, dict) or type(artifact.get("schema")) is not int or artifact.get("schema") != 1 or artifact.get("upstream") != UPSTREAM:
        fail("provenance_mismatch")
    if artifact.get("protocol") != PROTOCOL or artifact.get("version_text") != VERSION:
        fail("provenance_mismatch")
    hashes = artifact.get("files")
    if not isinstance(hashes, dict):
        fail("provenance_mismatch")
    for name in ("openflux-linux-amd64", "openflux-source.tar.gz"):
        if hashes.get(name) != manifest["files"]["bundle/" + name]["sha256"]:
            fail("provenance_mismatch")
    try:
        module.read_config(stage / "private/server.json")
        module.validate_elf(stage / "bundle/openflux-linux-amd64")
    except module.DeploymentError:
        fail("config_invalid")


def command(arguments, timeout):
    environment = {name: value for name, value in os.environ.items()
                   if not name.startswith(("DOCKER_", "BUILDX_", "BUILDKIT_", "PYTHON"))}
    try:
        # Never retain manager output, build output, document errors or credentials.
        process = subprocess.Popen(arguments, stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL,
                                   stderr=subprocess.DEVNULL, env=environment, start_new_session=True)
        try:
            code = process.wait(timeout=timeout)
        except subprocess.TimeoutExpired:
            # Terminate only this newly created command group, never host services.
            os.killpg(process.pid, signal.SIGTERM)
            try:
                process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                os.killpg(process.pid, signal.SIGKILL)
                process.wait(timeout=10)
            fail("command_timeout")
    except subprocess.TimeoutExpired:
        fail("command_timeout")
    except OSError:
        fail("command_failed")
    if code:
        fail("command_failed")


def manager_arguments(phase, stage, manifest, request):
    arguments = [sys.executable, str(stage / "bundle/manage.py"), "check" if phase == "preflight" else phase]
    if phase in ("install", "run", "stop"):
        arguments.append("--apply")
    if phase in ("preflight", "install"):
        arguments.extend(("--binary", str(stage / "bundle/openflux-linux-amd64"),
                          "--binary-sha256", manifest["files"]["bundle/openflux-linux-amd64"]["sha256"],
                          "--source-archive", str(stage / "bundle/openflux-source.tar.gz"),
                          "--source-sha256", manifest["files"]["bundle/openflux-source.tar.gz"]["sha256"],
                          "--licenses-dir", str(stage / "bundle/licenses"),
                          "--config", str(stage / "private/server.json"),
                          "--runtime-image", validate_runtime(request["runtime_image"]),
                          "--subnet", validate_subnet(request["subnet"])))
    return arguments


def bind_installation(stage, manifest, manager):
    if not INSTALL_ROOT.exists():
        return
    state = manager.load_state()
    record = {"schema": SCHEMA, "owner": state["owner"], "manifest_sha256": hash_bytes(json_bytes(manifest))}
    write_exclusive(stage / "installation.json", json_bytes(record))


def require_installation(stage, manifest, manager):
    record = decode_json(read_private(stage / "installation.json"))
    if not isinstance(record, dict) or set(record) != {"schema", "owner", "manifest_sha256"}:
        fail("installation_mismatch")
    if record["schema"] != SCHEMA or record["manifest_sha256"] != hash_bytes(json_bytes(manifest)):
        fail("installation_mismatch")
    state = manager.load_state()
    if not valid_hash(record["owner"]) or state["owner"] != record["owner"]:
        fail("installation_mismatch")


def runtime_build(request, stage, module, manager):
    base = manager.inspect("image", request["alpine_image"])
    if base is None:
        fail("runtime_missing")
    if base.get("Os") != "linux" or base.get("Architecture") != "amd64" or not base.get("RepoTags"):
        fail("runtime_unsafe")
    validate_runtime(base.get("Id"))
    config = base.get("Config") or {}
    if config.get("OnBuild") or config.get("Volumes"):
        fail("runtime_unsafe")
    alias = "unifiedvpn-openflux-base:" + request["stage"]
    tag = "unifiedvpn-openflux-runtime:" + request["stage"]
    context = stage / "runtime-context"
    if context.exists() or context.is_symlink() or manager.inspect("image", alias) is not None or manager.inspect("image", tag) is not None:
        fail("runtime_exists")
    context.mkdir(mode=0o700)
    write_exclusive(context / "Dockerfile", read_private(stage / "bundle/runtime.Dockerfile"))
    manager.docker("image", "tag", base["Id"], alias)
    try:
        arguments = ["docker", "--host", "unix:///var/run/docker.sock", "build", "--builder", "default",
                     "--pull=false", "--network=default", "--build-arg", "ALPINE_IMAGE=" + alias,
                     "--label", "org.unifiedvpn.openflux.stage=" + request["stage"], "--tag", tag, str(context)]
        command(arguments, timeout=600)
    finally:
        current = manager.inspect("image", alias)
        if current is not None and current.get("Id") == base["Id"] and len(current.get("RepoTags") or []) >= 2:
            manager.docker("image", "rm", alias)
    image = manager.inspect("image", tag)
    if image is None or (image.get("Config") or {}).get("Labels", {}).get("org.unifiedvpn.openflux.stage") != request["stage"]:
        fail("runtime_unsafe")
    result = {"runtime_image_id": validate_runtime(image.get("Id"))}
    write_exclusive(stage / "runtime-result.json", json_bytes(result))
    return result


def runtime_pull(request, stage, manager):
    tag = "unifiedvpn-openflux-alpine:" + request["stage"]
    record = stage / "runtime-base.json"
    if record.exists() or record.is_symlink() or manager.inspect("image", tag) is not None:
        fail("runtime_exists")
    command(["docker", "--host", "unix:///var/run/docker.sock", "pull", "--platform", "linux/amd64",
             validate_alpine(request["alpine_image"])], timeout=600)
    image = manager.inspect("image", request["alpine_image"])
    if image is None or image.get("Os") != "linux" or image.get("Architecture") != "amd64":
        fail("runtime_unsafe")
    image_id = validate_runtime(image.get("Id"))
    manager.docker("image", "tag", image_id, tag)
    write_exclusive(record, json_bytes({"image_id": image_id, "alpine_image": request["alpine_image"]}))
    return {"pinned_base_available": True}


def perform(request, stream):
    validate_request(request)
    require_host()
    phase = request["phase"]
    if phase == "upload":
        return upload(request, stream)
    stage, manifest = verify_stage(request)
    module = load_manager(stage)
    validate_artifacts(stage, manifest, module)
    manager = module.Manager()
    try:
        manager.preflight()
        if phase == "runtime-pull":
            return runtime_pull(request, stage, manager)
        if phase == "runtime-build":
            return runtime_build(request, stage, module, manager)
        if phase in ("preflight", "install"):
            if INSTALL_ROOT.exists() or INSTALL_ROOT.is_symlink():
                fail("installation_exists")
        else:
            require_installation(stage, manifest, manager)
        if phase == "install":
            try:
                command(manager_arguments(phase, stage, manifest, request), timeout=840)
            finally:
                bind_installation(stage, manifest, manager)
        else:
            command(manager_arguments(phase, stage, manifest, request), timeout=240)
    except module.DeploymentError:
        fail("command_failed")
    return {"configuration_only": phase in ("check", "preflight")}


def main(stream=None):
    stream = stream or sys.stdin.buffer
    os.umask(0o077)
    phase = "unknown"
    try:
        request = read_request(stream)
        phase = request["phase"]
        result = perform(request, stream)
        response = dict(result, schema=SCHEMA, phase=phase, status="completed", encrypted_peer_verified=False)
        code = 0
    except DeploymentError as error:
        response = {"schema": SCHEMA, "phase": phase, "status": "failed", "error": error.code}
        code = 1
    except (OSError, EOFError, KeyboardInterrupt):
        response = {"schema": SCHEMA, "phase": phase, "status": "failed", "error": "filesystem_failed"}
        code = 1
    except Exception:
        response = {"schema": SCHEMA, "phase": phase, "status": "failed", "error": "unexpected_failure"}
        code = 1
    print(json.dumps(response, sort_keys=True))
    return code


if __name__ == "__main__":
    raise SystemExit(main())
