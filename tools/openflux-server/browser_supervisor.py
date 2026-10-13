#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Opt-in supervisor for a new, nonproduction OpenFlux browser-check instance."""

import argparse
from collections import deque
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import secrets
import select
import signal
import socket
import stat
import struct
import subprocess
import sys
import threading
import time


HERE = Path(__file__).resolve().parent


def load(name, filename):
    path = HERE / filename
    if __name__ == "__main__":
        for candidate in (Path(__file__).absolute(), path):
            for directory in (candidate.parent, *candidate.parent.parents):
                info = directory.lstat()
                if not stat.S_ISDIR(info.st_mode) or info.st_uid != 0 or info.st_mode & 0o022:
                    raise RuntimeError("Unsafe helper directory; private details suppressed.")
            info = candidate.lstat()
            if not stat.S_ISREG(info.st_mode) or info.st_nlink != 1 or info.st_uid != 0 or info.st_mode & 0o022:
                raise RuntimeError("Unsafe helper source; private details suppressed.")
    specification = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(specification)
    with path.open("rb") as stream:
        source = stream.read(262145)
    if len(source) > 262144:
        raise RuntimeError("Oversized helper source; private details suppressed.")
    # Never execute an unchecked pre-existing bytecode cache with root privileges.
    exec(compile(source, str(path), "exec", dont_inherit=True), module.__dict__)
    return module


try:
    manage = load("browser_manager", "manage.py")
    worker = load("browser_contract", "browser_worker.py")
except Exception:
    if __name__ != "__main__":
        raise
    print("OPENFLUX_BROWSER_HELPER_INVALID: private details suppressed.", file=sys.stderr)
    raise SystemExit(1)
REQUEST = re.compile(rb"OPENFLUX_BROWSER_VERIFY ([0-9a-f]{32})\r?\n\Z")
SAFE_STATUS = frozenset((b"OPENFLUX_ENCRYPTION AES-256-GCM", b"OPENFLUX_SERVER_WAITING", b"OPENFLUX_READY",
                         b"OPENFLUX_BROWSER_UNAVAILABLE"))
RECEIPT = "browser-companion.json"
LABEL = manage.LABEL + ".browser"
MANUAL_SOCKET = "browser.sock"
MANUAL_TIMEOUT = 40
MANUAL_WRITE_TIMEOUT = 2


def manual_runtime_directory(instance):
    if not isinstance(instance, str) or re.fullmatch(r"browser[a-z0-9]{1,9}", instance) is None:
        manage.fail("Manual bootstrap requires a fresh browser-prefixed instance.")
    return Path("/run") / ("unifiedvpn-openflux-bootstrap-" + instance)


def manual_challenge(state, instance, request_id, document_url):
    worker.validate_request({"id": request_id, "document_url": document_url})
    manual_runtime_directory(instance)
    for field in ("owner", "container_id"):
        if not isinstance(state.get(field), str) or not manage.HASH_RE.fullmatch(state[field]):
            raise worker.VerificationError()
    return {"version": 1, "id": request_id, "challenge_token": secrets.token_hex(32),
            "instance": instance, "owner": state["owner"], "native_container_id": state["container_id"],
            "document_sha256": hashlib.sha256(document_url.encode("utf-8")).hexdigest()}


def manual_response(value, challenge, document_url):
    if not isinstance(value, dict) or not {"binding", "cookies"} <= set(value) <= {"binding", "cookies", "user_agent", "classic_document"}:
        raise worker.VerificationError()
    binding = value["binding"]
    if (not isinstance(binding, dict) or set(binding) != set(challenge)
            or any(type(binding[key]) is not type(expected) or binding[key] != expected
                   for key, expected in challenge.items())):
        raise worker.VerificationError()
    response = {"id": challenge["id"], "cookies": value["cookies"]}
    if "user_agent" in value:
        response["user_agent"] = value["user_agent"]
    if "classic_document" in value:
        response["classic_document"] = value["classic_document"]
    response = worker.validate_response(response, challenge["id"], document_url)
    for item in response["cookies"]:
        name = item["name"].lower()
        while name.startswith(("__secure-", "__host-")):
            name = name.split("-", 1)[1]
        if name in worker.AUTH_COOKIE_NAMES | {"sessionid"}:
            raise worker.VerificationError()
    return response


def manual_directory(instance):
    path = manual_runtime_directory(instance)
    for directory in (*reversed(path.parents), path):
        info = directory.lstat()
        if not stat.S_ISDIR(info.st_mode) or info.st_uid != 0 or info.st_mode & 0o022:
            manage.fail("Unsafe manual bootstrap directory; private details suppressed.")
    if stat.S_IMODE(info.st_mode) != 0o700:
        manage.fail("Manual bootstrap requires a private systemd runtime directory.")
    descriptor = os.open(path, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW | os.O_CLOEXEC)
    try:
        opened = os.fstat(descriptor)
        if (opened.st_dev, opened.st_ino) != (info.st_dev, info.st_ino):
            manage.fail("Manual bootstrap directory identity changed.")
    except BaseException:
        os.close(descriptor)
        raise
    return descriptor


def owned_manual_socket(directory, identity):
    info = os.stat(MANUAL_SOCKET, dir_fd=directory, follow_symlinks=False)
    if (not stat.S_ISSOCK(info.st_mode) or info.st_uid != 0 or info.st_nlink != 1
            or (info.st_dev, info.st_ino) != identity):
        manage.fail("Manual bootstrap socket identity changed; nothing was removed.")
    return info


class PrivateParser(argparse.ArgumentParser):
    def error(self, message):
        self.exit(2, "Invalid browser supervisor arguments; supplied values were not printed.\n")


def failure(request_id):
    return {"id": request_id, "error": "verification_failed"}


class RefreshBudget:
    def __init__(self, clock=time.monotonic):
        self.clock = clock
        self.attempts = deque()
        self.ids = deque(maxlen=64)

    def allow(self, request_id):
        now = self.clock()
        while self.attempts and now - self.attempts[0] >= 300:
            self.attempts.popleft()
        if request_id in self.ids or len(self.attempts) >= 2:
            return False
        self.ids.append(request_id)
        self.attempts.append(now)
        return True


def browser_arguments(state, name, image, seccomp):
    return [
        "container", "create", "--pull=never", "--interactive", "--name", name,
        "--network", "container:" + state["container_id"],
        "--label", manage.LABEL + ".owner=" + state["owner"],
        "--label", manage.LABEL + ".managed=1", "--label", LABEL + "=1",
        "--user", "10001:10001", "--cap-drop", "ALL",
        "--security-opt", "no-new-privileges:true", "--security-opt", "seccomp=" + str(seccomp),
        "--read-only", "--init", "--restart", "no", "--log-driver", "none", "--no-healthcheck",
        "--pids-limit", "256", "--memory", "768m", "--memory-swap", "768m", "--cpus", "0.75",
        "--shm-size", "128m", "--stop-timeout", "2", "--ulimit", "nofile=4096:4096",
        "--tmpfs", "/tmp:rw,nosuid,nodev,size=268435456,mode=1777",
        "--tmpfs", "/home/browser:rw,noexec,nosuid,nodev,size=16777216,uid=10001,gid=10001,mode=0700",
        "--env", "DEBUG=", "--env", "PWDEBUG=", "--entrypoint", "python3",
        image, "-B", "/opt/openflux-browser/browser_worker.py",
    ]


class Supervisor:
    def __init__(self, manager, image, seccomp, seccomp_hash, popen=subprocess.Popen, clock=time.monotonic,
                 *, manual_bootstrap=False):
        if type(manual_bootstrap) is not bool:
            raise ValueError("Manual bootstrap must be explicitly enabled or disabled.")
        self.manager = manager
        self.image = image
        self.seccomp = Path(seccomp)
        self.seccomp_hash = seccomp_hash
        self.popen = popen
        self.clock = clock
        self.name = manager.name + "-verify"
        self.budget = RefreshBudget(clock)
        self.cleanup_failed = False
        self.manual_bootstrap = manual_bootstrap
        self.manual_bootstrap_used = False

    def remaining(self, deadline, limit=None):
        remaining = deadline - self.clock()
        if remaining <= 0:
            raise TimeoutError("Manual browser bootstrap expired.")
        return min(remaining, limit) if limit is not None else remaining

    def manual_native(self, state, deadline):
        result = self.manager.docker("container", "inspect", state["container_id"],
                                     timeout=self.remaining(deadline, 3))
        entries = manage.decode_json(result.stdout)
        if not isinstance(entries, list) or len(entries) != 1 or not isinstance(entries[0], dict):
            raise worker.VerificationError()
        native = entries[0]
        labels = (native.get("Config") or {}).get("Labels") or {}
        if (native.get("Id") != state["container_id"] or native.get("Name") != "/" + self.manager.name
                or native.get("Image") != state.get("image_id")
                or labels.get(manage.LABEL + ".owner") != state["owner"]
                or labels.get(manage.LABEL + ".managed") != "1"
                or (native.get("State") or {}).get("Running") is not True):
            raise worker.VerificationError()
        self.remaining(deadline)

    def manual_read(self, connection, deadline):
        data = bytearray()
        while True:
            connection.settimeout(self.remaining(deadline))
            chunk = connection.recv(min(4096, worker.MAX_MESSAGE + 2 - len(data)))
            if not chunk:
                break
            data.extend(chunk)
            if len(data) > worker.MAX_MESSAGE + 1:
                raise worker.VerificationError()
        if not data.endswith(b"\n") or data.count(b"\n") != 1:
            raise worker.VerificationError()
        return worker.decode_message(bytes(data[:-1]))

    def verify_manual(self, state, document_url, request_id):
        deadline = self.clock() + MANUAL_TIMEOUT - MANUAL_WRITE_TIMEOUT
        response = failure(request_id)
        directory = None
        identity = None
        listener = None
        bound = False
        try:
            challenge = manual_challenge(state, self.manager.instance, request_id, document_url)
            if not hasattr(socket, "SO_PEERCRED"):
                raise worker.VerificationError()
            self.manual_native(state, deadline)
            directory = manual_directory(self.manager.instance)
            try:
                os.stat(MANUAL_SOCKET, dir_fd=directory, follow_symlinks=False)
            except FileNotFoundError:
                pass
            else:
                manage.fail("A manual bootstrap socket already exists; explicit recovery is required.")
            listener = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
            # Binding through the open parent descriptor avoids pathname replacement.
            listener.bind("/proc/self/fd/" + str(directory) + "/" + MANUAL_SOCKET)
            bound = True
            info = os.stat(MANUAL_SOCKET, dir_fd=directory, follow_symlinks=False)
            identity = (info.st_dev, info.st_ino)
            owned_manual_socket(directory, identity)
            os.chmod(MANUAL_SOCKET, 0o600, dir_fd=directory, follow_symlinks=False)
            if stat.S_IMODE(owned_manual_socket(directory, identity).st_mode) != 0o600:
                raise worker.VerificationError()
            listener.listen(1)
            listener.settimeout(self.remaining(deadline))
            connection, _ = listener.accept()
            with connection:
                credentials = connection.getsockopt(socket.SOL_SOCKET, socket.SO_PEERCRED, struct.calcsize("3i"))
                if len(credentials) != struct.calcsize("3i") or struct.unpack("3i", credentials)[1] != 0:
                    raise worker.VerificationError()
                connection.settimeout(self.remaining(deadline))
                connection.sendall(worker.encode_message(challenge))
                value = self.manual_read(connection, deadline)
                response = manual_response(value, challenge, document_url)
                self.manual_native(state, deadline)
                connection.settimeout(self.remaining(deadline))
                connection.sendall(worker.encode_message({"accepted": True}))
                self.remaining(deadline)
        except (OSError, ValueError, TypeError, AttributeError, struct.error, subprocess.TimeoutExpired,
                manage.DeploymentError, worker.VerificationError):
            response = failure(request_id)
        finally:
            try:
                if listener is not None:
                    listener.close()
                if identity is not None:
                    owned_manual_socket(directory, identity)
                    os.unlink(MANUAL_SOCKET, dir_fd=directory)
                elif bound:
                    manage.fail("Manual bootstrap socket identity could not be established.")
            except (OSError, manage.DeploymentError):
                self.cleanup_failed = True
                response = failure(request_id)
                print("OPENFLUX_BROWSER_CLEANUP_REQUIRED", flush=True)
            finally:
                if directory is not None:
                    os.close(directory)
        return response

    def write_manual_reply(self, destination, response):
        # The bridge remains the sole native stdin writer, without a blocked writer thread.
        deadline = self.clock() + MANUAL_WRITE_TIMEOUT
        descriptor = destination.fileno()
        blocking = os.get_blocking(descriptor)
        try:
            os.set_blocking(descriptor, False)
            pending = memoryview(worker.encode_message(response))
            while pending:
                _, ready, _ = select.select([], [descriptor], [], self.remaining(deadline))
                if not ready:
                    raise TimeoutError("The native bootstrap reply expired.")
                try:
                    count = os.write(descriptor, pending)
                except BlockingIOError:
                    continue
                if count <= 0:
                    raise BrokenPipeError("The native bootstrap input closed.")
                pending = pending[count:]
        finally:
            os.set_blocking(descriptor, blocking)

    def preflight(self):
        # This first release deliberately cannot target existing production names.
        if re.fullmatch(r"browser[a-z0-9]{1,9}", self.manager.instance) is None:
            manage.fail("The experimental companion requires a fresh browser-prefixed test instance.")
        self.manager.preflight()
        state = self.manager.load_state()
        if state.get("upgrade_staged"):
            manage.fail("Staged upgrades require a separately reviewed durable browser attachment.")
        config = manage.read_config(self.manager.root / "server.json")
        provenance = self.manager.root / "provenance.json"
        manage.require_secure_file(provenance)
        if provenance.stat().st_size > 16384 or manage.decode_json(provenance.read_bytes()).get("wrapper_version") != manage.VERSION:
            manage.fail("Browser bootstrap requires the exact current wrapper provenance, not an old installation.")
        if not state.get("image_id") or self.manager.owned("image", state["image_id"], state) is None:
            manage.fail("The owned native image is missing.")
        if not manage.IMAGE_RE.fullmatch(self.image):
            manage.fail("An immutable, already-installed browser image ID is required.")
        image = self.manager.inspect("image", self.image)
        image_config = (image or {}).get("Config") or {}
        if (not image or image.get("Id") != self.image or image.get("Os") != "linux"
                or image.get("Architecture") != "amd64" or not image.get("RepoTags")
                or image_config.get("OnBuild") or image_config.get("Volumes")
                or image_config.get("User") != "10001:10001"
                or (image_config.get("Labels") or {}).get(manage.LABEL + ".browser-contract") != "3"):
            manage.fail("The reviewed, unprivileged browser image is unavailable or incompatible.")
        manage.require_secure_file(self.seccomp)
        if (not manage.HASH_RE.fullmatch(self.seccomp_hash)
                or manage.sha256_file(self.seccomp) != self.seccomp_hash
                or self.seccomp.stat().st_size > 262144):
            manage.fail("The root-owned reviewed seccomp profile hash does not match.")
        profile = manage.decode_json(self.seccomp.read_bytes())
        if (not isinstance(profile, dict) or profile.get("defaultAction") not in ("SCMP_ACT_ERRNO", "SCMP_ACT_KILL", "SCMP_ACT_KILL_PROCESS")
                or not isinstance(profile.get("syscalls"), list) or not profile["syscalls"]):
            manage.fail("A reviewed default-deny seccomp profile is required; unconfined is unsupported.")
        return state, config

    def companion(self, state):
        resource = self.manager.inspect("container", self.name)
        path = self.manager.root / RECEIPT
        receipt = None
        if path.exists() or path.is_symlink():
            manage.require_secure_file(path, private=True)
            if path.stat().st_size > 16384:
                manage.fail("The private browser ownership receipt is oversized.")
            receipt = manage.decode_json(path.read_bytes())
            if (not isinstance(receipt, dict) or set(receipt) != {"owner", "container_id", "native_container_id", "image_id"}
                    or receipt["owner"] != state["owner"] or receipt["native_container_id"] != state["container_id"]
                    or receipt["image_id"] != self.image or not manage.HASH_RE.fullmatch(str(receipt["container_id"]))):
                manage.fail("The private browser ownership receipt changed; nothing was stopped.")
        if resource is None:
            return None
        if receipt is None:
            manage.fail("A browser container exists without its ID receipt; inspect it before manual recovery.")
        config = resource.get("Config") or {}
        host = resource.get("HostConfig") or {}
        labels = config.get("Labels") or {}
        if (labels.get(manage.LABEL + ".owner") != state["owner"] or labels.get(manage.LABEL + ".managed") != "1"
                or labels.get(LABEL) != "1" or resource.get("Image") != self.image
                or host.get("NetworkMode") != "container:" + state["container_id"]
                or not manage.HASH_RE.fullmatch(str(resource.get("Id")))
                or resource["Id"] != receipt["container_id"]):
            manage.fail("A browser resource identity changed; nothing was stopped.")
        return resource

    def cleanup_browser(self, state):
        companion = self.companion(state)
        if companion is not None:
            if (companion.get("State") or {}).get("Running"):
                self.manager.docker("container", "stop", "--time", "1", companion["Id"], timeout=2)
            self.manager.docker("container", "rm", companion["Id"], timeout=2)
        path = self.manager.root / RECEIPT
        if path.exists():
            manage.require_secure_file(path, private=True)
            path.unlink()

    def verify(self, state, document_url, request_id):
        if (not isinstance(request_id, str) or worker.ID_PATTERN.fullmatch(request_id) is None
                or self.cleanup_failed or not self.budget.allow(request_id)):
            return failure(request_id)
        if self.manual_bootstrap and not self.manual_bootstrap_used:
            self.manual_bootstrap_used = True
            return self.verify_manual(state, document_url, request_id)
        started = self.clock()
        process = None
        response = failure(request_id)
        try:
            if self.companion(state) is not None or (self.manager.root / RECEIPT).exists():
                manage.fail("A previous browser companion requires explicit cleanup.")
            native = self.manager.owned("container", self.manager.name, state)
            if native is None or not (native.get("State") or {}).get("Running"):
                manage.fail("The owned native container is not running.")
            result = self.manager.docker(*browser_arguments(state, self.name, self.image, self.seccomp), timeout=5)
            identity = result.stdout.strip()
            if not manage.HASH_RE.fullmatch(identity):
                manage.fail("Docker returned an invalid browser identity.")
            receipt = {"owner": state["owner"], "container_id": identity,
                       "native_container_id": state["container_id"], "image_id": self.image}
            with (self.manager.root / RECEIPT).open("x", encoding="ascii") as stream:
                os.chmod(stream.name, 0o600)
                json.dump(receipt, stream)
            self.companion(state)
            process = self.popen(
                ["docker", "--host", "unix:///var/run/docker.sock", "container", "start", "--attach", "--interactive", identity],
                stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                env={"PATH": "/usr/sbin:/usr/bin:/sbin:/bin", "LANG": "C"}, start_new_session=True,
            )
            output = []
            def read_bounded():
                output.append(process.stdout.read(worker.MAX_MESSAGE + 2))
            reader = threading.Thread(target=read_bounded, daemon=True)
            reader.start()
            process.stdin.write(worker.encode_message({"id": request_id, "document_url": document_url}))
            process.stdin.close()
            process.wait(timeout=max(0.01, 40 - (self.clock() - started)))
            reader.join(timeout=1)
            if process.returncode == 0 and not reader.is_alive() and len(output) == 1:
                response = worker.validate_response(worker.decode_message(output[0]), request_id, document_url)
        except (OSError, ValueError, subprocess.TimeoutExpired, manage.DeploymentError, worker.VerificationError):
            response = failure(request_id)
        finally:
            if process is not None and process.poll() is None:
                process.terminate()
                try:
                    process.wait(timeout=1)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait(timeout=1)
            # Failure removes only the ephemeral browser, never restarts native.
            try:
                self.cleanup_browser(state)
            except (OSError, manage.DeploymentError):
                # A cleanup identity failure must not restart a waiting native process.
                self.cleanup_failed = True
                response = failure(request_id)
                print("OPENFLUX_BROWSER_CLEANUP_REQUIRED", flush=True)
        return response

    def bridge(self, source, destination, state, document_url):
        while True:
            line = source.readline(worker.MAX_MESSAGE + 2)
            if not line:
                return
            if len(line) > worker.MAX_MESSAGE:
                manage.fail("The native protocol produced an oversized line; contents suppressed.")
            request = REQUEST.fullmatch(line)
            if request:
                request_id = request[1].decode("ascii")
                response = self.verify(state, document_url, request_id)
                if self.manual_bootstrap:
                    self.write_manual_reply(destination, response)
                else:
                    destination.write(worker.encode_message(response))
                    destination.flush()
                del response
            elif line.rstrip(b"\r\n") in SAFE_STATUS:
                print(line.decode("ascii").strip(), flush=True)
            # All other native text is discarded, including transport diagnostics.

    def run(self):
        state, config = self.preflight()
        if (self.manager.owned("container", self.manager.name, state) is not None
                or self.manager.owned("network", self.manager.network, state) is not None
                or self.companion(state) is not None or (self.manager.root / RECEIPT).exists()):
            manage.fail("Runtime resources already exist; no running instance was changed.")
        state = self.manager.run(bootstrap_stdio=True, start=False)
        process = None
        try:
            process = self.popen(
                ["docker", "--host", "unix:///var/run/docker.sock", "container", "start", "--attach", "--interactive", state["container_id"]],
                stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                env={"PATH": "/usr/sbin:/usr/bin:/sbin:/bin", "LANG": "C"}, start_new_session=True,
            )
            self.bridge(process.stdout, process.stdin, state, config["document_url"])
            process.wait(timeout=5)
        finally:
            self.cleanup_browser(state)
            if process is not None and process.poll() is None:
                process.terminate()
                try:
                    process.wait(timeout=3)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait(timeout=2)
            self.manager.stop()

    def cleanup(self):
        state, _ = self.preflight()
        if state.get("browser_bootstrap_stdio") is not True:
            manage.fail("This installation was not created by the browser supervisor.")
        self.manager.owned("container", self.manager.name, state)
        self.manager.owned("network", self.manager.network, state)
        self.companion(state)
        self.cleanup_browser(state)
        self.manager.stop()


def main(argv=None):
    parser = PrivateParser(description=__doc__)
    parser.add_argument("command", choices=("check", "run", "cleanup"))
    parser.add_argument("--instance", required=True)
    parser.add_argument("--browser-image", required=True)
    parser.add_argument("--seccomp", type=Path, required=True)
    parser.add_argument("--seccomp-sha256", required=True)
    parser.add_argument("--apply", action="store_true")
    args = parser.parse_args(argv)
    os.umask(0o077)
    if args.command != "check" and not args.apply:
        parser.error("Mutations require explicit --apply; use check first.")
    try:
        supervisor = Supervisor(manage.Manager(instance=args.instance), args.browser_image, args.seccomp, args.seccomp_sha256)
        if args.command == "check":
            supervisor.preflight()
            print("Browser prerequisites checked; Chromium sandbox and transport remain untested.")
        else:
            signal.signal(signal.SIGTERM, lambda *_: (_ for _ in ()).throw(KeyboardInterrupt()))
            getattr(supervisor, args.command)()
    except (Exception, KeyboardInterrupt):
        print("OPENFLUX_BROWSER_SUPERVISOR_FAILED: private details suppressed.", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
