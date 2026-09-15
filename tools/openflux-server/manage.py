#!/usr/bin/env python3
"""Opt-in, local Docker deployment for the pinned encrypted OpenFlux wrapper."""

import argparse
import getpass
import hashlib
import ipaddress
import json
import os
from pathlib import Path
import platform
import re
import secrets
import shutil
import stat
import struct
import subprocess
import sys
import warnings
from urllib.parse import urlsplit


UPSTREAM = "4f1bdb554c262f3ae9adbfe317a092c6b929ba7d"
PROTOCOL = "unified-openflux-aesgcm-v1"
VERSION = f"unified-openflux 1 upstream={UPSTREAM} protocol={PROTOCOL}"
INSTALL_DIR = Path("/opt/unifiedvpn-openflux")
NAME = "unifiedvpn-openflux"
NETWORK = NAME + "-net"
LABEL = "org.unifiedvpn.openflux"
DOCKER_SOCKET = Path("/var/run/docker.sock")
SCRIPT_DIR = Path(__file__).resolve().parent
HASH_RE = re.compile(r"[0-9a-f]{64}\Z")
IMAGE_RE = re.compile(r"sha256:[0-9a-f]{64}\Z")
FILES = (
    "manage.py", "Dockerfile", "entrypoint.sh", "openflux", "source.tar.gz",
    "LICENSE", "NOTICE", "COPYRIGHT", "provenance.json", "server.json", "state.json", "state.new",
)
CONTEXT_FILES = ("Dockerfile", "entrypoint.sh", "openflux", "source.tar.gz", "LICENSE", "NOTICE", "COPYRIGHT", "provenance.json")


class DeploymentError(Exception):
    pass


def fail(message):
    raise DeploymentError(message)


def decode_json(value):
    def unique(pairs):
        result = {}
        for key, item in pairs:
            if key in result:
                fail("Duplicate JSON fields are not allowed.")
            result[key] = item
        return result

    try:
        return json.loads(value, object_pairs_hook=unique)
    except (ValueError, UnicodeError):
        fail("Invalid JSON; its contents were not printed.")


def require_secure_file(path, private=False):
    secure_directory(path.absolute().parent)
    try:
        info = path.lstat()
    except OSError:
        fail("A required file is missing or inaccessible.")
    if not stat.S_ISREG(info.st_mode) or info.st_nlink != 1:
        fail("Inputs must be regular files, without symlinks or hard links.")
    forbidden = 0o077 if private else 0o022
    if info.st_uid != 0 or info.st_mode & forbidden:
        fail("Files must be root-owned and not writable by others; secrets need mode 0600.")
    return info


def secure_directory(path):
    for parent in (path, *path.parents):
        try:
            info = parent.lstat()
        except OSError:
            fail("A required parent directory is missing.")
        if not stat.S_ISDIR(info.st_mode) or info.st_uid != 0 or info.st_mode & 0o022:
            fail("Directories must be root-owned, without writable parents or symlinks.")


def validate_document_url(value):
    if not isinstance(value, str) or not value or len(value) > 8192:
        fail("A Yandex document URL is required (maximum 8192 characters).")
    if any(ord(char) <= 32 or ord(char) == 127 for char in value):
        fail("The document URL contains whitespace or control characters.")
    try:
        url = urlsplit(value)
        valid = (
            url.scheme == "https" and url.hostname in ("docs.yandex.ru", "disk.yandex.ru")
            and url.username is None and url.password is None and not url.fragment
            and url.port in (None, 443) and bool(url.path.strip("/"))
        )
    except ValueError:
        valid = False
    if not valid:
        fail("Use an HTTPS docs.yandex.ru or disk.yandex.ru link without userinfo or fragment.")


def validate_config(config):
    fields = {"version", "mode", "transport", "document_url", "encryption_key", "handshake_timeout_seconds"}
    if not isinstance(config, dict) or set(config) != fields:
        fail("Server JSON must contain exactly the documented server fields.")
    if type(config["version"]) is not int or config["version"] != 1:
        fail("Only server configuration version 1 is supported.")
    if config["mode"] != "server" or config["transport"] != "yandex":
        fail("This deployment supports only encrypted Yandex server mode.")
    validate_document_url(config["document_url"])
    key = config["encryption_key"]
    if not isinstance(key, str) or not re.fullmatch(r"[0-9a-fA-F]{64}", key):
        fail("The encryption key must contain 64 hexadecimal characters (32 random bytes).")
    timeout = config["handshake_timeout_seconds"]
    if type(timeout) is not int or not 5 <= timeout <= 180:
        fail("handshake_timeout_seconds must be an integer from 5 through 180.")
    return config


def read_config(path):
    info = require_secure_file(path, private=True)
    if info.st_size > 16384:
        fail("Server configuration is too large.")
    return validate_config(decode_json(path.read_bytes()))


def sha256_file(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def validate_elf(path):
    with path.open("rb") as stream:
        header = stream.read(64)
        if len(header) != 64 or header[:6] != b"\x7fELF\x02\x01" or struct.unpack_from("<H", header, 18)[0] != 62:
            fail("The binary must be a Linux amd64 ELF executable.")
        offset = struct.unpack_from("<Q", header, 32)[0]
        entry_size, count = struct.unpack_from("<HH", header, 54)
        if not count or count > 1024 or entry_size < 56:
            fail("The executable has invalid ELF program headers.")
        for index in range(count):
            stream.seek(offset + index * entry_size)
            entry = stream.read(56)
            if len(entry) != 56 or struct.unpack_from("<I", entry)[0] == 3:
                fail("Use the statically linked Linux amd64 build (CGO_ENABLED=0).")


def validate_subnet(value):
    try:
        subnet = ipaddress.IPv4Network(value, strict=True)
    except (ValueError, TypeError):
        fail("Choose a canonical private IPv4 subnet, for example an unused /28.")
    private = [ipaddress.IPv4Network(item) for item in ("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16")]
    if not 24 <= subnet.prefixlen <= 29 or not any(subnet.subnet_of(item) for item in private):
        fail("The isolated bridge subnet must be an RFC1918 /24 through /29.")
    if subnet.overlaps(ipaddress.IPv4Network("10.10.10.0/24")):
        fail("The bridge must not overlap OpenFlux's internal virtual network.")
    return subnet


def run_command(argv, timeout=30, allow_failure=False):
    env = {key: value for key, value in os.environ.items() if not key.startswith(("DOCKER_", "BUILDX_", "BUILDKIT_"))}
    try:
        result = subprocess.run(argv, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE,
                                stderr=subprocess.PIPE, text=True, timeout=timeout, env=env, check=False)
    except (OSError, subprocess.TimeoutExpired):
        fail("A required local command failed or timed out; no command output was printed.")
    if result.returncode and not allow_failure:
        fail("A local command failed; output was suppressed to protect private data.")
    return result


class Manager:
    def __init__(self, root=INSTALL_DIR, command=run_command):
        self.root = Path(root)
        self.command = command

    def docker(self, *args, timeout=30, allow_failure=False):
        return self.command(["docker", "--host", f"unix://{DOCKER_SOCKET.as_posix()}", *args],
                            timeout=timeout, allow_failure=allow_failure)

    def preflight(self):
        if platform.system() != "Linux" or platform.machine() not in ("x86_64", "amd64"):
            fail("Deployment commands require a Linux amd64 host; unit tests are platform-independent.")
        if os.geteuid() != 0:
            fail("Run deployment commands as root on the intended host.")
        if Path("/.dockerenv").exists() or Path("/run/.containerenv").exists():
            fail("Run the manager on the host, not inside a container.")
        if os.readlink("/proc/self/ns/net") != os.readlink("/proc/1/ns/net"):
            fail("Run the manager in the host's initial network namespace.")
        for utility in ("docker", "ip"):
            if shutil.which(utility) is None:
                fail("Docker and iproute2 must already be installed; this tool installs neither.")
        try:
            info = DOCKER_SOCKET.stat()
        except OSError:
            fail("An already-running local Docker daemon is required.")
        if not stat.S_ISSOCK(info.st_mode) or info.st_uid != 0 or info.st_mode & 0o002:
            fail("The local Docker socket must be root-owned and not world-writable.")
        info = decode_json(self.docker("info", "--format", "{{json .}}").stdout)
        if info.get("OSType") != "linux" or any("rootless" in str(item) for item in info.get("SecurityOptions", [])):
            fail("Only a local rootful Linux Docker daemon is supported.")

    def secure_directory(self, path):
        secure_directory(path)

    def load_state(self):
        self.secure_directory(self.root)
        path = self.root / "state.json"
        require_secure_file(path, private=True)
        state = decode_json(path.read_bytes())
        if not isinstance(state, dict) or type(state.get("format")) is not int or state.get("format") != 1 or state.get("upstream") != UPSTREAM:
            fail("Installation state does not belong to this deployment version.")
        if not isinstance(state.get("owner"), str) or not HASH_RE.fullmatch(state["owner"]):
            fail("Installation ownership record is invalid.")
        for field in ("image_id", "runtime_id"):
            if state.get(field) is not None and not IMAGE_RE.fullmatch(str(state[field])):
                fail("Installation image identity is invalid.")
        for field in ("container_id", "network_id"):
            if state.get(field) is not None and not HASH_RE.fullmatch(str(state[field])):
                fail("Installation resource identity is invalid.")
        validate_subnet(state.get("subnet"))
        return state

    def write_state(self, state):
        path = self.root / "state.json"
        temporary = self.root / "state.new"
        if temporary.exists() or temporary.is_symlink():
            require_secure_file(temporary, private=True)
            temporary.unlink()
        with temporary.open("x", encoding="utf-8") as stream:
            os.chmod(temporary, 0o600)
            json.dump(state, stream, indent=2)
            stream.write("\n")
        os.replace(temporary, path)

    def inspect(self, kind, name):
        result = self.docker(kind, "inspect", name, allow_failure=True)
        if result.returncode:
            return None
        entries = decode_json(result.stdout)
        if not isinstance(entries, list) or len(entries) != 1:
            fail("Docker returned an unexpected resource description.")
        if not isinstance(entries[0], dict):
            fail("Docker returned an invalid resource description.")
        return entries[0]

    def owned(self, kind, name, state):
        resource = self.inspect(kind, name)
        if resource is None:
            return None
        labels = (resource.get("Config") or {}).get("Labels") if kind in ("container", "image") else resource.get("Labels")
        labels = labels or {}
        if labels.get(LABEL + ".owner") != state["owner"] or labels.get(LABEL + ".managed") != "1":
            fail("A resource name is already owned by something else; nothing was changed.")
        expected = state.get(kind + "_id")
        if expected and resource.get("Id") != expected:
            fail("A resource identity changed; refusing to operate on its replacement.")
        return resource

    def ensure_subnet_free(self, subnet):
        routes = decode_json(self.command(["ip", "-j", "-4", "route", "show", "table", "all"], timeout=30).stdout)
        for route in routes:
            destination = route.get("dst", "default")
            if destination in ("default", "0.0.0.0/0"):
                continue
            try:
                network = ipaddress.IPv4Network(destination, strict=False)
            except ValueError:
                fail("An existing route could not be checked; refusing to allocate the subnet.")
            if subnet.overlaps(network):
                fail("The requested bridge subnet overlaps an existing host route.")
        ids = self.docker("network", "ls", "--quiet").stdout.split()
        if ids:
            networks = decode_json(self.docker("network", "inspect", *ids).stdout)
            for network in networks:
                for config in network.get("IPAM", {}).get("Config", []) or []:
                    if config.get("Subnet"):
                        existing = ipaddress.ip_network(config["Subnet"], strict=False)
                        if existing.version == 4 and subnet.overlaps(existing):
                            fail("The requested bridge subnet overlaps an existing Docker network.")

    def validate_inputs(self, args):
        for field in ("binary", "binary_sha256", "source_archive", "source_sha256", "licenses_dir", "runtime_image", "config", "subnet"):
            if not getattr(args, field, None):
                fail("Check/install requires binary, source, licenses, runtime image, config, subnet and expected SHA256 values.")
        for value in (args.binary_sha256, args.source_sha256):
            if not HASH_RE.fullmatch(value):
                fail("Expected SHA256 values must contain 64 lowercase hexadecimal characters.")
        require_secure_file(args.binary)
        require_secure_file(args.source_archive)
        if sha256_file(args.binary) != args.binary_sha256 or sha256_file(args.source_archive) != args.source_sha256:
            fail("An input SHA256 does not match; nothing was installed.")
        validate_elf(args.binary)
        with args.source_archive.open("rb") as archive:
            if archive.read(2) != b"\x1f\x8b":
                fail("The matching patched source must be supplied as a .tar.gz archive.")
        for name in ("LICENSE", "NOTICE", "COPYRIGHT"):
            require_secure_file(args.licenses_dir / name)
        read_config(args.config)
        if not (IMAGE_RE.fullmatch(args.runtime_image) or re.fullmatch(r"[^\s@]+@sha256:[0-9a-f]{64}", args.runtime_image)):
            fail("Specify an immutable local runtime image ID or repository@sha256 digest, not a tag.")
        image = self.inspect("image", args.runtime_image)
        if image is None or not IMAGE_RE.fullmatch(image.get("Id", "")):
            fail("The pinned runtime image is not present locally; no image was pulled.")
        if image.get("Os") != "linux" or image.get("Architecture") != "amd64":
            fail("The runtime image must target Linux amd64.")
        if not image.get("RepoTags"):
            fail("Keep an original tag on the reviewed runtime image before installing by its immutable ID.")
        config = image.get("Config") or {}
        if config.get("OnBuild") or config.get("Volumes"):
            fail("The runtime image must not contain ONBUILD triggers or declared volumes.")
        subnet = validate_subnet(args.subnet)
        self.ensure_subnet_free(subnet)
        for kind, name in (("container", NAME), ("network", NETWORK)):
            if self.inspect(kind, name) is not None:
                fail("The deployment's fixed container or network name is already in use.")
        return image["Id"]

    def remove_runtime_alias(self, state):
        alias = NAME + "-runtime:" + state["owner"]
        image = self.inspect("image", alias)
        if image is not None:
            if image.get("Id") != state["runtime_id"] or len(image.get("RepoTags") or []) < 2:
                fail("The runtime alias changed or is its image's last tag; it was not removed.")
            self.docker("image", "rm", alias)

    def clean_context(self):
        context = self.root / "image-context"
        if not context.exists() and not context.is_symlink():
            return
        self.secure_directory(context)
        children = list(context.iterdir())
        if any(child.name not in CONTEXT_FILES for child in children):
            fail("Unexpected build-context files were not removed.")
        for child in children:
            require_secure_file(child)
        for child in children:
            child.unlink()
        context.rmdir()

    def check(self, args):
        self.preflight()
        if self.root.exists() or self.root.is_symlink():
            state = self.load_state()
            read_config(self.root / "server.json")
            image = self.owned("image", state.get("image_id") or NAME + ":" + state["owner"], state)
            if image is None:
                fail("Installation is incomplete: its image is missing. Remove it before reinstalling.")
            self.owned("network", NETWORK, state)
            container = self.owned("container", NAME, state)
            status = "not running" if container is None else container.get("State", {}).get("Status", "unknown")
            print("Owned installation verified. Container state: " + status + ".")
            print("This checks local configuration/ownership only, not transport connectivity.")
        else:
            self.validate_inputs(args)
            self.secure_directory(self.root.parent)
            print("Preflight passed. No files, images, containers, networks, routes or rules were created.")
            print("The wrapper version and runtime utilities will be checked during the opt-in image build.")

    def install(self, args):
        self.preflight()
        if self.root.exists() or self.root.is_symlink():
            fail("An installation path already exists; refusing to overwrite it.")
        self.secure_directory(self.root.parent)
        runtime_id = self.validate_inputs(args)
        owner = secrets.token_hex(32)
        state = {"format": 1, "owner": owner, "upstream": UPSTREAM, "subnet": args.subnet,
                 "runtime_id": runtime_id, "image_id": None, "container_id": None, "network_id": None}
        self.root.mkdir(mode=0o700)
        self.write_state(state)
        sources = {"openflux": args.binary, "source.tar.gz": args.source_archive, "server.json": args.config}
        sources.update({name: args.licenses_dir / name for name in ("LICENSE", "NOTICE", "COPYRIGHT")})
        sources.update({name: SCRIPT_DIR / name for name in ("manage.py", "Dockerfile", "entrypoint.sh")})
        try:
            for name, source in sources.items():
                destination = self.root / name
                with source.open("rb") as incoming, destination.open("xb") as outgoing:
                    shutil.copyfileobj(incoming, outgoing)
                os.chmod(destination, 0o600 if name == "server.json" else 0o644)
            provenance = {"upstream_repository": "https://github.com/p1neappleXpress/OpenFlux", "upstream_commit": UPSTREAM,
                          "wrapper_version": VERSION, "protocol": PROTOCOL, "binary_sha256": args.binary_sha256,
                          "source_sha256": args.source_sha256, "runtime_image_id": runtime_id}
            (self.root / "provenance.json").write_text(json.dumps(provenance, indent=2) + "\n", encoding="utf-8")
            # The build context excludes server.json and state.json, even on build failure.
            context = self.root / "image-context"
            context.mkdir(mode=0o700)
            try:
                for name in CONTEXT_FILES:
                    shutil.copyfile(self.root / name, context / name)
                # BuildKit cannot reliably resolve raw image IDs in FROM. A unique
                # local alias is tied to the inspected ID, then removed after build.
                runtime_alias = NAME + "-runtime:" + owner
                if self.inspect("image", runtime_alias) is not None:
                    fail("The temporary runtime-image alias is already in use.")
                self.docker("image", "tag", runtime_id, runtime_alias)
                self.docker("build", "--builder", "default", "--pull=false", "--network=none", "--build-arg", "RUNTIME_IMAGE=" + runtime_alias,
                            "--build-arg", "EXPECTED_VERSION=" + VERSION,
                            "--label", LABEL + ".owner=" + owner, "--label", LABEL + ".managed=1",
                            "--label", LABEL + ".upstream=" + UPSTREAM, "--tag", NAME + ":" + owner, str(context), timeout=600)
            finally:
                self.clean_context()
            image = self.owned("image", NAME + ":" + owner, state)
            if image is None:
                fail("The expected image was not produced.")
            state["image_id"] = image["Id"]
            self.write_state(state)
            self.remove_runtime_alias(state)
        except (OSError, DeploymentError):
            fail("Installation did not finish. No exit node was started. Use remove --apply to clean up owned files/image.")
        print("Installed the verified encrypted wrapper. No container or network was started.")

    def run(self):
        self.preflight()
        state = self.load_state()
        read_config(self.root / "server.json")
        if not state.get("image_id") or self.owned("image", state["image_id"], state) is None:
            fail("A complete verified image is required.")
        if self.owned("container", NAME, state) is not None or self.owned("network", NETWORK, state) is not None:
            fail("Owned runtime resources already exist. Use stop --apply before running again.")
        self.ensure_subnet_free(validate_subnet(state["subnet"]))
        try:
            result = self.docker("network", "create", "--driver", "bridge", "--subnet", state["subnet"],
                                 "--opt", "com.docker.network.bridge.enable_icc=false", "--label", LABEL + ".owner=" + state["owner"],
                                 "--label", LABEL + ".managed=1", NETWORK)
            state["network_id"] = result.stdout.strip()
            if not HASH_RE.fullmatch(state["network_id"]):
                fail("Docker did not return the expected network identity.")
            self.write_state(state)
            namespace = os.readlink("/proc/self/ns/net")
            if not re.fullmatch(r"net:\[[0-9]+\]", namespace):
                fail("The host network namespace could not be identified.")
            result = self.docker("container", "create", "--pull=never", "--name", NAME, "--network", NETWORK,
                                 "--label", LABEL + ".owner=" + state["owner"], "--label", LABEL + ".managed=1",
                                 "--read-only", "--cap-drop", "ALL", "--cap-add", "NET_RAW", "--cap-add", "NET_ADMIN",
                                 "--security-opt", "no-new-privileges:true", "--user", "0:0", "--restart", "no",
                                 "--pids-limit", "128", "--memory", "512m", "--memory-swap", "512m", "--cpus", "1.0",
                                 "--ulimit", "nofile=8192:8192", "--stop-timeout", "15", "--log-driver", "none",
                                 "--env", "GOMEMLIMIT=256MiB", "--env", "OPENFLUX_HOST_NETNS=" + namespace,
                                 "--tmpfs", "/run:rw,noexec,nosuid,size=1048576,mode=0755",
                                 "--mount", f"type=bind,src={self.root / 'server.json'},dst=/run/secrets/server.json,readonly,bind-propagation=rprivate",
                                 state["image_id"])
            state["container_id"] = result.stdout.strip()
            if not HASH_RE.fullmatch(state["container_id"]):
                fail("Docker did not return the expected container identity.")
            self.write_state(state)
            self.docker("container", "start", state["container_id"])
        except (OSError, DeploymentError):
            fail("Start did not finish. Use stop --apply to remove only owned partial runtime resources.")
        print("Start requested. Verify the client authenticated handshake and existing VPNs before relying on this exit node.")

    def stop_owned(self, state):
        # Verify both identities before stopping anything. Never remove by a bare name.
        container = self.owned("container", NAME, state)
        network = self.owned("network", NETWORK, state)
        if container is not None:
            if container.get("State", {}).get("Running"):
                self.docker("container", "stop", "--time", "15", container["Id"], timeout=30)
            self.docker("container", "rm", container["Id"])
        state["container_id"] = None
        self.write_state(state)
        if network is not None:
            remaining = self.owned("network", NETWORK, state)
            if remaining and remaining.get("Containers"):
                fail("Another endpoint is attached to the owned network. It was not disconnected or removed.")
            if remaining:
                self.docker("network", "rm", remaining["Id"])
        state["network_id"] = None
        self.write_state(state)

    def stop(self):
        self.preflight()
        state = self.load_state()
        self.stop_owned(state)
        print("Owned container and bridge removed. Installed files and private configuration retained.")

    def remove(self):
        self.preflight()
        state = self.load_state()
        self.stop_owned(state)
        image = self.owned("image", state.get("image_id") or NAME + ":" + state["owner"], state)
        if image is not None:
            self.docker("image", "rm", image["Id"])
        self.remove_runtime_alias(state)
        self.clean_context()
        children = list(self.root.iterdir())
        if any(child.name not in FILES or child.is_symlink() or not child.is_file() for child in children):
            fail("Unknown files or directories remain in the installation; they were not deleted.")
        for child in children:
            require_secure_file(child)
        for child in children:
            if child.name != "state.json":
                child.unlink()
        (self.root / "state.json").unlink()
        self.root.rmdir()
        print("Owned installation and its private config copy removed. Original input files and runtime image retained.")


def configure(path):
    if platform.system() != "Linux" or os.geteuid() != 0:
        fail("Create the server secret file as root on Linux.")
    path = Path(path)
    Manager().secure_directory(path.parent)
    if not sys.stdin.isatty():
        fail("configure requires an interactive terminal with hidden input.")
    with warnings.catch_warnings():
        warnings.simplefilter("error", getpass.GetPassWarning)
        try:
            document_url = getpass.getpass("Private Yandex document URL (input hidden): ")
        except getpass.GetPassWarning:
            fail("Hidden terminal input is unavailable; no secret was requested in echo mode.")
    validate_document_url(document_url)
    config = {"version": 1, "mode": "server", "transport": "yandex", "document_url": document_url,
              "encryption_key": secrets.token_hex(32), "handshake_timeout_seconds": 60}
    with path.open("x", encoding="utf-8") as stream:
        os.chmod(path, 0o600)
        json.dump(config, stream, indent=2)
        stream.write("\n")
    print("Created a root-readable config file. Neither document URL nor key was printed.")


def parser():
    result = argparse.ArgumentParser(description=__doc__)
    result.add_argument("command", nargs="?", default="check", choices=("check", "configure", "install", "run", "stop", "remove"))
    result.add_argument("--apply", action="store_true", help="Explicitly allow changes for the selected command")
    result.add_argument("--binary", type=Path)
    result.add_argument("--binary-sha256")
    result.add_argument("--source-archive", type=Path)
    result.add_argument("--source-sha256")
    result.add_argument("--licenses-dir", type=Path)
    result.add_argument("--runtime-image")
    result.add_argument("--config", type=Path)
    result.add_argument("--subnet")
    result.add_argument("--output", type=Path, help="New private file for configure; existing files are never overwritten")
    return result


def main(argv=None):
    args = parser().parse_args(argv)
    os.umask(0o077)
    try:
        if args.command != "check" and not args.apply:
            fail("No changes made. Add --apply only after reviewing the deployment README.")
        if args.command == "configure":
            if args.output is None:
                fail("configure requires --output with a path in a root-owned directory.")
            configure(args.output)
        elif args.command in ("check", "install"):
            getattr(Manager(), args.command)(args)
        else:
            getattr(Manager(), args.command)()
    except (DeploymentError, OSError, KeyboardInterrupt, EOFError):
        error = sys.exc_info()[1]
        message = str(error) if isinstance(error, DeploymentError) else "Operation interrupted or filesystem access failed; private contents were not printed."
        print(message, file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
