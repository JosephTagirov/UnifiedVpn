#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Explicit, receipt-bound legacy-to-L4 upgrade; old files and resources are retained."""

import argparse
from contextlib import contextmanager
import importlib.util
import json
import os
from pathlib import Path
import re
import secrets
import signal
import stat
import sys
import time
from types import SimpleNamespace


HERE = Path(__file__).resolve().parent


def load_manager():
    path = HERE / "manage.py"
    if __name__ == "__main__":
        for candidate in (Path(__file__).absolute(), path):
            for parent in (candidate.parent, *candidate.parent.parents):
                info = parent.lstat()
                if not stat.S_ISDIR(info.st_mode) or info.st_uid != 0 or info.st_mode & 0o022:
                    raise RuntimeError("Unsafe upgrade helper directory.")
            info = candidate.lstat()
            if not stat.S_ISREG(info.st_mode) or info.st_uid != 0 or info.st_nlink != 1 or info.st_mode & 0o022:
                raise RuntimeError("Unsafe upgrade helper source.")
    spec = importlib.util.spec_from_file_location("openflux_upgrade_manager", path)
    module = importlib.util.module_from_spec(spec)
    source = path.read_bytes()
    if len(source) > 262144:
        raise RuntimeError("Oversized upgrade helper source.")
    exec(compile(source, str(path), "exec", dont_inherit=True), module.__dict__)
    return module


try:
    manage = load_manager()
except Exception:
    if __name__ != "__main__":
        raise
    print("Upgrade helper validation failed; private details suppressed.", file=sys.stderr)
    raise SystemExit(1)

SCHEMA = "unifiedvpn-openflux-upgrade-v1"
PROOF_SCHEMA = "unifiedvpn-openflux-upgrade-traffic-v1"
LEGACY_UPSTREAM = "4f1bdb554c262f3ae9adbfe317a092c6b929ba7d"
LEGACY_VERSIONS = {f"unified-openflux {version} upstream={LEGACY_UPSTREAM} protocol={manage.PROTOCOL}" for version in (1, 2, 3)}
SNAPSHOT_FILES = ("manage.py", "Dockerfile", "entrypoint.sh", "openflux", "source.tar.gz", "LICENSE", "NOTICE", "COPYRIGHT", "provenance.json", "server.json", "state.json")
PHASES = {"preparing", "prepared", "prepare_failed", "checking_document", "document_failed", "creating_candidate",
          "switching", "testing", "committed", "rolling_back", "rolled_back", "inspection_required"}
LOCK_ROOT = Path("/run/unifiedvpn-openflux-upgrade-locks")


class PrivateParser(argparse.ArgumentParser):
    def error(self, message):
        self.exit(2, "Invalid upgrade arguments; supplied values were not printed.\n")


def read_json(path, *, private=True):
    info = manage.require_secure_file(path, private=private)
    if not 0 < info.st_size <= 65536:
        manage.fail("Private upgrade metadata has an invalid size.")
    value = manage.decode_json(path.read_bytes())
    if not isinstance(value, dict):
        manage.fail("Private upgrade metadata is invalid.")
    return value


def policy_argument(policy):
    if not isinstance(policy, dict) or set(policy) != {"Name", "MaximumRetryCount"}:
        manage.fail("Unsupported original restart policy.")
    name, count = policy["Name"], policy["MaximumRetryCount"]
    if name not in ("no", "always", "unless-stopped", "on-failure") or type(count) is not int or not 0 <= count <= 100000:
        manage.fail("Unsupported original restart policy.")
    if name != "on-failure" and count:
        manage.fail("Unsupported original restart policy.")
    return name + (":" + str(count) if name == "on-failure" and count else "")


def file_hashes(root, names):
    result = {}
    for name in names:
        manage.require_secure_file(root / name, private=name in ("server.json", "state.json"))
        result[name] = manage.sha256_file(root / name)
    return result


def private_directory(path):
    manage.secure_directory(path)
    if path.stat().st_mode & 0o077:
        manage.fail("The private upgrade directory must have mode 0700.")


@contextmanager
def mutation_lock(instance):
    import fcntl

    manage.instance_name(instance)
    manage.secure_directory(LOCK_ROOT.parent)
    try:
        LOCK_ROOT.mkdir(mode=0o700)
    except FileExistsError:
        pass
    private_directory(LOCK_ROOT)
    descriptor = os.open(LOCK_ROOT / (instance + ".lock"), os.O_WRONLY | os.O_CREAT | getattr(os, "O_NOFOLLOW", 0), 0o600)
    try:
        info = os.fstat(descriptor)
        if (not stat.S_ISREG(info.st_mode) or info.st_uid != 0 or info.st_nlink != 1
                or info.st_mode & 0o077 or info.st_size != 0):
            manage.fail("The per-instance upgrade lock is unsafe.")
        try:
            fcntl.flock(descriptor, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except OSError:
            manage.fail("Another controller owns this original instance's upgrade lock.")
        yield
    finally:
        os.close(descriptor)


class Upgrade:
    def __init__(self, instance, directory, command=manage.run_command, install_parent=Path("/opt"), sleep=time.sleep, clock=time.monotonic):
        self.instance = instance
        self.directory = Path(directory)
        self.install_parent = Path(install_parent)
        self.command = command
        self.sleep, self.clock = sleep, clock
        self.deadline = None
        if not self.directory.is_absolute() or any(part in (".", "..") for part in self.directory.parts):
            manage.fail("Use an absolute canonical private receipt path without traversal components.")
        canonical = self.directory.resolve(strict=False)
        install_parent = self.install_parent.resolve(strict=False)
        if canonical != self.directory or canonical == install_parent or install_parent in canonical.parents:
            manage.fail("Use an absolute private receipt directory outside managed installation trees.")
        name = manage.instance_name(instance)
        self.original = manage.Manager(root=self.install_parent / name, command=self.bounded_command, instance=instance)
        self.record_path = self.directory / "upgrade.json"
        self.proof_path = self.directory / "traffic-proof.json"

    def bounded_command(self, argv, timeout=30, allow_failure=False):
        if self.deadline is not None:
            remaining = self.deadline - self.clock()
            if remaining <= 0:
                manage.fail("The bounded traffic trial expired.")
            timeout = min(timeout, remaining)
        return self.command(argv, timeout=timeout, allow_failure=allow_failure)

    def save(self, record, *, new=False):
        temporary = self.directory / "upgrade.new"
        if temporary.exists() or temporary.is_symlink() or new and (self.record_path.exists() or self.record_path.is_symlink()):
            manage.fail("An interrupted or existing receipt requires read-only inspection.")
        data = json.dumps(record, sort_keys=True, indent=2).encode("ascii") + b"\n"
        descriptor = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(descriptor, "wb") as stream:
            os.fchmod(stream.fileno(), 0o600)
            stream.write(data)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, self.record_path)
        if os.name == 'posix':
            descriptor = os.open(self.directory, os.O_RDONLY | os.O_DIRECTORY)
            try:
                os.fsync(descriptor)
            finally:
                os.close(descriptor)

    def load(self):
        private_directory(self.directory)
        record = read_json(self.record_path)
        fields = {"schema", "upgrade_id", "instance", "candidate_instance", "candidate_owner", "phase", "original",
                  "candidate_files", "candidate_id", "probe_id", "document_checked", "traffic_verified", "trial_started_ns", "trial_deadline_ns"}
        if (set(record) != fields or record["schema"] != SCHEMA or record["instance"] != self.instance
                or record["phase"] not in PHASES or not manage.HASH_RE.fullmatch(str(record["upgrade_id"]))
                or not re.fullmatch(r"browser[0-9a-f]{8}", str(record["candidate_instance"]))
                or not manage.HASH_RE.fullmatch(str(record["candidate_owner"]))
                or type(record["document_checked"]) is not bool or type(record["traffic_verified"]) is not bool
                or type(record["trial_started_ns"]) is not int or type(record["trial_deadline_ns"]) is not int):
            manage.fail("The upgrade receipt is invalid or belongs to another target.")
        for field in ("candidate_id", "probe_id"):
            if record[field] is not None and not manage.HASH_RE.fullmatch(str(record[field])):
                manage.fail("The upgrade receipt contains an invalid resource identity.")
        return record

    def candidate(self, record):
        instance = record["candidate_instance"]
        return manage.Manager(root=self.install_parent / manage.instance_name(instance), command=self.bounded_command, instance=instance)

    def resource(self, manager, state, name, identity, network_id):
        container = manager.owned("container", name, dict(state, container_id=identity))
        if container is None or container.get("Id") != identity or container.get("Image") != state["image_id"]:
            manage.fail("A bound container is missing or changed; no replacement was adopted.")
        host = container.get("HostConfig") or {}
        mount = [item for item in container.get("Mounts", []) if item.get("Type") == "bind"]
        networks = (container.get("NetworkSettings") or {}).get("Networks") or {}
        if (host.get("Privileged") or host.get("PidMode") or host.get("PortBindings") or not host.get("ReadonlyRootfs")
                or len(mount) != 1 or mount[0].get("Source") != str(manager.root / "server.json")
                or mount[0].get("Destination") != "/run/secrets/server.json" or mount[0].get("RW") is not False
                or set(networks) != {self.original.network} or next(iter(networks.values())).get("NetworkID") not in (network_id, "")
                or host.get("NetworkMode") not in (self.original.network, network_id)):
            manage.fail("A bound container's isolation or private config mount changed.")
        status = container.get("State") or {}
        if (status.get("Status") not in ("running", "exited", "created") or status.get("Paused") or status.get("Restarting")
                or type(status.get("Running")) is not bool):
            manage.fail("A bound container is in an unsupported state.")
        policy_argument(host.get("RestartPolicy"))
        return container

    def snapshot_original(self, allowed=()):
        root = self.original.root
        hashes = file_hashes(root, SNAPSHOT_FILES)
        state, provenance = read_json(root / "state.json"), read_json(root / "provenance.json", private=False)
        if (type(state.get("format")) is not int or state.get("format") != 1 or state.get("upstream") != LEGACY_UPSTREAM
                or state.get("instance", "default") != self.instance
                or not manage.HASH_RE.fullmatch(str(state.get("owner")))
                or provenance.get("upstream_commit") != LEGACY_UPSTREAM
                or provenance.get("protocol") != manage.PROTOCOL or provenance.get("wrapper_version") not in LEGACY_VERSIONS
                or provenance.get("binary_sha256") != hashes["openflux"] or provenance.get("source_sha256") != hashes["source.tar.gz"]):
            manage.fail("Original package provenance or artifact hashes are not a supported legacy installation.")
        for key, pattern in (("image_id", manage.IMAGE_RE), ("runtime_id", manage.IMAGE_RE), ("container_id", manage.HASH_RE), ("network_id", manage.HASH_RE)):
            if not pattern.fullmatch(str(state.get(key))):
                manage.fail("Original installation identities are incomplete.")
        manage.validate_subnet(state.get("subnet"))
        if manage.read_config(root / "server.json").get("codec", "legacy") != "legacy":
            manage.fail("Legacy upgrades must preserve the legacy codec.")
        image = self.original.owned("image", state["image_id"], state)
        network = self.original.owned("network", self.original.network, state)
        if image is None or network is None or network.get("Driver") != "bridge":
            manage.fail("Original image or isolated bridge is missing.")
        if (image.get("Config") or {}).get("Labels", {}).get(manage.LABEL + ".upstream") != LEGACY_UPSTREAM:
            manage.fail("Original image provenance label does not match.")
        subnets = [entry.get("Subnet") for entry in (network.get("IPAM") or {}).get("Config", [])]
        if subnets != [state["subnet"]] or set(network.get("Containers") or {}) - {state["container_id"], *allowed}:
            manage.fail("The original bridge subnet or attached endpoints changed.")
        container = self.resource(self.original, state, self.original.name, state["container_id"], state["network_id"])
        return {"files": hashes, "state": state, "running": container["State"]["Running"],
                "restart_policy": container["HostConfig"]["RestartPolicy"]}

    def verify(self, record):
        allowed = [record[key] for key in ("candidate_id", "probe_id") if record[key]]
        current = self.snapshot_original(allowed)
        expected = record["original"]
        if not isinstance(expected, dict) or set(expected) != {"files", "state", "running", "restart_policy"}:
            manage.fail("Original rollback metadata is invalid.")
        if current["files"] != expected["files"] or current["state"] != expected["state"]:
            manage.fail("Original files or ownership changed; nothing was switched.")
        if type(expected["running"]) is not bool:
            manage.fail("Original running-state metadata is invalid.")
        policy_argument(expected["restart_policy"])
        candidate = self.candidate(record)
        state = candidate.load_state()
        provenance = read_json(candidate.root / "provenance.json", private=False)
        if (state["owner"] != record["candidate_owner"] or state.get("upgrade_staged") is not True
                or state["container_id"] != record["candidate_id"] or state["network_id"] is not None
                or candidate.owned("image", state["image_id"], state) is None
                or file_hashes(candidate.root, SNAPSHOT_FILES[:-1]) != record["candidate_files"]
                or record["candidate_files"].get("server.json") != expected["files"]["server.json"]
                or provenance.get("wrapper_version") != manage.VERSION or provenance.get("upstream_commit") != manage.UPSTREAM
                or provenance.get("binary_sha256") != record["candidate_files"].get("openflux")
                or provenance.get("source_sha256") != record["candidate_files"].get("source.tar.gz")):
            manage.fail("Candidate package, profile or ownership changed.")
        if record["candidate_id"]:
            self.resource(candidate, state, candidate.name, record["candidate_id"], expected["state"]["network_id"])
        elif candidate.inspect("container", candidate.name) is not None:
            manage.fail("An unrecorded candidate exists; inspect it instead of retrying a mutation.")
        return candidate, state, current

    def prepare(self, args):
        self.original.preflight()
        snapshot = self.snapshot_original()
        manage.secure_directory(self.directory.absolute().parent)
        if self.directory.exists() or self.directory.is_symlink():
            manage.fail("The private upgrade directory already exists; it was not overwritten.")
        self.directory.mkdir(mode=0o700)
        record = {"schema": SCHEMA, "upgrade_id": secrets.token_hex(32), "instance": self.instance,
                  "candidate_instance": "browser" + secrets.token_hex(4), "candidate_owner": secrets.token_hex(32),
                  "phase": "preparing", "original": snapshot, "candidate_files": {}, "candidate_id": None,
                  "probe_id": None, "document_checked": False, "traffic_verified": False, "trial_started_ns": 0, "trial_deadline_ns": 0}
        self.save(record, new=True)
        candidate = self.candidate(record)
        values = vars(args).copy()
        values.update(config=self.original.root / "server.json", subnet=snapshot["state"]["subnet"])
        try:
            candidate.install(SimpleNamespace(**values), owner=record["candidate_owner"], stage_only=True)
            record["candidate_files"] = file_hashes(candidate.root, SNAPSHOT_FILES[:-1])
            if record["candidate_files"]["server.json"] != snapshot["files"]["server.json"]:
                manage.fail("The copied profile is not byte-identical.")
            record["phase"] = "prepared"
            self.save(record)
        except BaseException:
            record["phase"] = "prepare_failed"
            self.save(record)
            raise

    def document_check(self, record, candidate, state):
        record["phase"] = "checking_document"
        self.save(record)
        name = candidate.name + "-document-check"
        if candidate.inspect("container", name) is not None:
            manage.fail("A previous document-check container requires inspection.")
        args = candidate.container_arguments(state, network_id=record["original"]["state"]["network_id"])
        args[args.index("--name") + 1] = name
        args = args[:-1] + ["--entrypoint", "/usr/local/bin/openflux", state["image_id"],
                           "--config", "/run/secrets/server.json", "--check-document"]
        identity = candidate.docker(*args).stdout.strip()
        if not manage.HASH_RE.fullmatch(identity):
            manage.fail("Document-check creation returned no valid identity.")
        record["probe_id"] = identity
        self.save(record)
        try:
            self.resource(candidate, state, name, identity, record["original"]["state"]["network_id"])
            candidate.docker("container", "start", identity)
            result = candidate.docker("container", "wait", identity, timeout=65)
            resource = self.resource(candidate, state, name, identity, record["original"]["state"]["network_id"])
            if result.stdout.strip() != "0" or resource["State"].get("ExitCode") != 0 or resource["State"]["Running"]:
                manage.fail("Document access failed; browser verification may be required. Original instance was not stopped.")
            record["document_checked"] = True
        finally:
            resource = self.resource(candidate, state, name, identity, record["original"]["state"]["network_id"])
            if resource["State"]["Running"]:
                candidate.docker("container", "stop", "--time", "5", identity, timeout=10)
            candidate.docker("container", "rm", identity)
            record["probe_id"] = None
            self.save(record)

    def healthy(self, record, candidate, state, duration=10):
        deadline = self.clock() + duration
        count = None
        while True:
            resource = self.resource(candidate, state, candidate.name, record["candidate_id"], record["original"]["state"]["network_id"])
            current = resource.get("RestartCount")
            if (not resource["State"]["Running"] or resource["State"].get("OOMKilled")
                    or type(current) is not int or count is not None and current != count):
                manage.fail("Candidate startup health failed; rollback is required.")
            count = current
            if self.clock() >= deadline:
                return count
            self.sleep(0.2)

    def traffic_proof(self, record):
        if not self.proof_path.exists() and not self.proof_path.is_symlink():
            return False
        proof = read_json(self.proof_path)
        fields = {"schema", "upgrade_id", "container_id", "protocol", "authenticated_peer", "https_successes", "dns_over_tunnel", "tested_at_ns"}
        timestamp = proof.get("tested_at_ns")
        if (set(proof) != fields or proof.get("schema") != PROOF_SCHEMA or proof.get("upgrade_id") != record["upgrade_id"]
                or proof.get("container_id") != record["candidate_id"] or proof.get("protocol") != manage.PROTOCOL
                or proof.get("authenticated_peer") is not True or proof.get("dns_over_tunnel") is not True
                or type(proof.get("https_successes")) is not int or not 1 <= proof["https_successes"] <= 100
                or type(timestamp) is not int or not record["trial_started_ns"] <= timestamp <= min(time.time_ns(), record["trial_deadline_ns"])
                or self.proof_path.stat().st_mtime_ns < record["trial_started_ns"]):
            manage.fail("Traffic proof is missing required results or does not belong to this trial.")
        return True

    def rollback(self, record=None, *, network_namespace=None):
        self.deadline = None
        self.original.preflight(network_namespace=network_namespace)
        record = record or self.load()
        candidate, state, _ = self.verify(record)
        if record["phase"] not in ("switching", "testing", "committed", "rolling_back", "inspection_required"):
            manage.fail("This phase has not switched the original; no rollback mutation was performed.")
        record["phase"] = "rolling_back"
        receipt_failed = False
        try:
            self.save(record)
        except (OSError, manage.DeploymentError):
            # A full filesystem must not prevent restoring already-verified IDs.
            receipt_failed = True
        try:
            if record["candidate_id"]:
                resource = self.resource(candidate, state, candidate.name, record["candidate_id"], record["original"]["state"]["network_id"])
                candidate.docker("container", "update", "--restart", "no", record["candidate_id"])
                if resource["State"]["Running"]:
                    candidate.docker("container", "stop", "--time", "15", record["candidate_id"])
                stopped = self.resource(candidate, state, candidate.name, record["candidate_id"], record["original"]["state"]["network_id"])
                if stopped["State"]["Running"]:
                    manage.fail("Candidate did not stop; the original was not started concurrently.")
            original = record["original"]
            identity = original["state"]["container_id"]
            self.original.docker("container", "update", "--restart", policy_argument(original["restart_policy"]), identity)
            current = self.snapshot_original([record["candidate_id"]] if record["candidate_id"] else ())
            if original["running"] and not current["running"]:
                self.original.docker("container", "start", identity)
            elif not original["running"] and current["running"]:
                self.original.docker("container", "stop", "--time", "15", identity)
            restored = self.snapshot_original([record["candidate_id"]] if record["candidate_id"] else ())
            if restored["running"] != original["running"] or restored["restart_policy"] != original["restart_policy"]:
                manage.fail("Original running state or restart policy was not restored.")
            record["phase"] = "rolled_back"
            try:
                self.save(record)
            except (OSError, manage.DeploymentError):
                receipt_failed = True
            if receipt_failed:
                manage.fail("Original state was restored, but the private receipt needs filesystem recovery.")
        except BaseException:
            record["phase"] = "inspection_required"
            try:
                self.save(record)
            except (OSError, manage.DeploymentError):
                pass
            raise

    def check_document(self):
        self.original.preflight()
        record = self.load()
        if record["phase"] != "prepared":
            manage.fail("Document validation requires a fresh prepared receipt; inspect interrupted phases first.")
        candidate, state, current = self.verify(record)
        if current != record["original"]:
            manage.fail("Original state changed since preparation; no document check was started.")
        try:
            self.document_check(record, candidate, state)
            self.verify(record)
            record["phase"] = "prepared"
            self.save(record)
        except BaseException:
            record["phase"] = "document_failed"
            self.save(record)
            raise

    def switch(self, timeout=180):
        if type(timeout) is not int or not 15 <= timeout <= 180:
            manage.fail("Traffic trial must be bounded to 15 through 180 seconds.")
        self.original.preflight()
        record = self.load()
        if record["phase"] != "prepared" or self.proof_path.exists() or self.proof_path.is_symlink():
            manage.fail("Only a fresh prepared receipt without prior proof can switch; inspect interrupted operations first.")
        candidate, state, current = self.verify(record)
        if current != record["original"]:
            manage.fail("Original state changed since preparation; nothing was switched.")
        try:
            self.document_check(record, candidate, state)
        except BaseException:
            record["phase"] = "document_failed"
            self.save(record)
            raise
        candidate, state, current = self.verify(record)
        if current != record["original"]:
            manage.fail("Original state changed during document validation; nothing was switched.")
        record["phase"] = "creating_candidate"
        self.save(record)
        identity = candidate.docker(*candidate.container_arguments(state, network_id=current["state"]["network_id"])).stdout.strip()
        if not manage.HASH_RE.fullmatch(identity):
            manage.fail("Candidate creation returned no valid identity; inspect before retrying.")
        record["candidate_id"], state["container_id"] = identity, identity
        candidate.write_state(state)
        record["phase"] = "switching"
        self.save(record)
        try:
            self.verify(record)
            original_id = current["state"]["container_id"]
            self.original.docker("container", "update", "--restart", "no", original_id)
            if current["running"]:
                self.original.docker("container", "stop", "--time", "15", original_id)
            stopped = self.snapshot_original([identity])
            if stopped["running"] or stopped["restart_policy"]["Name"] != "no":
                manage.fail("Original process did not stop; replacement was not started.")
            self.deadline = self.clock() + timeout
            candidate.docker("container", "start", identity)
            record["trial_started_ns"] = time.time_ns()
            record["trial_deadline_ns"] = record["trial_started_ns"] + timeout * 1000000000
            record["phase"] = "testing"
            self.save(record)
            print("OPENFLUX_UPGRADE_AWAITING_TRAFFIC", flush=True)
            self.healthy(record, candidate, state)
            while not self.traffic_proof(record):
                if self.clock() >= self.deadline:
                    manage.fail("Authenticated traffic proof timed out; restoring the original instance.")
                self.healthy(record, candidate, state, duration=0)
                self.sleep(0.2)
            _, _, original_status = self.verify(record)
            if original_status["running"]:
                manage.fail("Original process unexpectedly restarted during the traffic trial.")
            stable_restarts = self.healthy(record, candidate, state, duration=0)
            candidate.docker("container", "update", "--restart", policy_argument(current["restart_policy"]), identity)
            if not current["running"]:
                candidate.docker("container", "stop", "--time", "15", identity)
            final = self.resource(candidate, state, candidate.name, identity, current["state"]["network_id"])
            if (final["State"]["Running"] != current["running"] or final["State"].get("OOMKilled")
                    or final["HostConfig"]["RestartPolicy"] != current["restart_policy"]
                    or final.get("RestartCount") != stable_restarts):
                manage.fail("Candidate final state or restart policy did not match; restoring the original.")
            record["traffic_verified"], record["phase"] = True, "committed"
            self.save(record)
        except BaseException:
            self.rollback(record)
            raise
        finally:
            self.deadline = None

    def check(self):
        self.original.preflight()
        record = self.load()
        candidate, state, current = self.verify(record)
        running = False
        if record["candidate_id"]:
            resource = self.resource(candidate, state, candidate.name, record["candidate_id"], current["state"]["network_id"])
            running = resource["State"]["Running"]
        return {"phase": record["phase"], "original_running": current["running"], "candidate_running": running,
                "document_checked": record["document_checked"], "traffic_verified": record["traffic_verified"]}


def parser():
    result = PrivateParser(description=__doc__)
    result.add_argument("command", choices=("prepare", "check", "check-document", "switch", "rollback"))
    result.add_argument("--instance", required=True)
    result.add_argument("--receipt-directory", type=Path, required=True)
    result.add_argument("--apply", action="store_true")
    result.add_argument("--binary", type=Path)
    result.add_argument("--binary-sha256")
    result.add_argument("--source-archive", type=Path)
    result.add_argument("--source-sha256")
    result.add_argument("--licenses-dir", type=Path)
    result.add_argument("--runtime-image")
    result.add_argument("--trial-seconds", type=int, default=180)
    return result


def main(argv=None):
    args = parser().parse_args(argv)
    os.umask(0o077)
    if args.command != "check" and not args.apply:
        print("No changes made. Review the upgrade procedure before adding --apply.", file=sys.stderr)
        return 1
    try:
        for name in ("SIGTERM", "SIGHUP"):
            if hasattr(signal, name):
                signal.signal(getattr(signal, name), lambda *_: (_ for _ in ()).throw(KeyboardInterrupt()))
        upgrade = Upgrade(args.instance, args.receipt_directory)
        if args.command == "check":
            print(json.dumps(upgrade.check(), sort_keys=True))
        else:
            upgrade.original.preflight()
            with mutation_lock(args.instance):
                if args.command == "prepare":
                    upgrade.prepare(args)
                    print("Upgrade image prepared; original instance and profile unchanged. No candidate started.")
                elif args.command == "check-document":
                    upgrade.check_document()
                    print("HTTPS document validation passed; no exit-room join or original-container mutation was performed.")
                elif args.command == "switch":
                    upgrade.switch(args.trial_seconds)
                    print("Bound traffic trial passed; original package and container remain available for rollback.")
                else:
                    upgrade.rollback()
                    print("Original container running state and restart policy restored; candidate retained stopped.")
    except (Exception, KeyboardInterrupt):
        print("Upgrade operation failed or was interrupted. Inspect the private receipt before any retry; private details suppressed.", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
