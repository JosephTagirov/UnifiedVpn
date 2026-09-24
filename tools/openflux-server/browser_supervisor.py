#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Opt-in supervisor for a new, nonproduction OpenFlux browser-check instance."""

import argparse
from collections import deque
import importlib.util
import json
import os
from pathlib import Path
import re
import signal
import stat
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
SAFE_STATUS = frozenset((b"OPENFLUX_ENCRYPTION AES-256-GCM", b"OPENFLUX_SERVER_WAITING", b"OPENFLUX_READY"))
RECEIPT = "browser-companion.json"
LABEL = manage.LABEL + ".browser"


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
    def __init__(self, manager, image, seccomp, seccomp_hash, popen=subprocess.Popen, clock=time.monotonic):
        self.manager = manager
        self.image = image
        self.seccomp = Path(seccomp)
        self.seccomp_hash = seccomp_hash
        self.popen = popen
        self.clock = clock
        self.name = manager.name + "-verify"
        self.budget = RefreshBudget(clock)
        self.cleanup_failed = False

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
                or (image_config.get("Labels") or {}).get(manage.LABEL + ".browser-contract") != "2"):
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
        if self.cleanup_failed or not self.budget.allow(request_id):
            return failure(request_id)
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
                destination.write(worker.encode_message(response))
                destination.flush()
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
