#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Own a fresh browser-backed profile, never migrate an existing installation."""

import argparse
from contextlib import contextmanager
import hashlib
import importlib.util
import ipaddress
import json
import os
from pathlib import Path
import re
import signal
import stat
import sys
from types import SimpleNamespace


HERE = Path(__file__).resolve().parent
TOOL_VERSION = 1
MANIFEST_SCHEMA = "unifiedvpn-openflux-admin-runtime-v1"
PLAN_SCHEMA = "unifiedvpn-openflux-admin-profile-v1"
SERVICE_SCHEMA = "unifiedvpn-openflux-profile-service-v1"
HELPERS = ("create_profile.py", "browser_service.py", "browser_supervisor.py", "browser_worker.py",
           "manage.py", "Dockerfile", "entrypoint.sh")
MANIFEST_FIELDS = {"schema", "tool_version", "source_instance", "artifacts", "browser_image",
                   "seccomp_file", "seccomp_sha256", "helper_sha256", "subnet_pool"}
SERVICE_FIELDS = {"schema", "tool_version", "instance", "owner", "plan_sha256", "manifest_sha256",
                  "subnet", "unit", "unit_sha256", "image_id", "phase"}


def load_source(name, filename):
    path = HERE / filename
    if __name__ == "__main__":
        for candidate in (Path(__file__).absolute(), path):
            for parent in (candidate.parent, *candidate.parent.parents):
                info = parent.lstat()
                if not stat.S_ISDIR(info.st_mode) or info.st_uid != 0 or info.st_mode & 0o022:
                    raise RuntimeError("unsafe_helper_directory")
            info = candidate.lstat()
            if not stat.S_ISREG(info.st_mode) or info.st_uid != 0 or info.st_nlink != 1 or info.st_mode & 0o022:
                raise RuntimeError("unsafe_helper_source")
    with path.open("rb") as stream:
        data = stream.read(262145)
    if len(data) > 262144:
        raise RuntimeError("oversized_helper_source")
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    exec(compile(data, str(path), "exec", dont_inherit=True), module.__dict__)
    return module


manage = load_source("profile_service_manage", "manage.py")
if __name__ == "__main__":
    for helper in HELPERS:
        manage.require_secure_file(HERE / helper)
browser = load_source("profile_service_browser", "browser_supervisor.py")
browser.manage = manage


def profile_module():
    module = load_source("profile_service_wizard", "create_profile.py")
    module.manage = manage
    module.browser_backend = lambda: SimpleNamespace(PLAN_SCHEMA=PLAN_SCHEMA, validate_snapshot=validate_snapshot)
    return module


def read_bytes(path, maximum=262144, private=False):
    info = manage.require_secure_file(path, private=private)
    if not 0 < info.st_size <= maximum:
        manage.fail("An admin input is empty or exceeds its size limit.")
    with path.open("rb") as stream:
        data = stream.read(maximum + 1)
    if len(data) > maximum:
        manage.fail("An admin input exceeded its size limit while reading.")
    return data


def canonical_path(path):
    path = Path(path)
    if not path.is_absolute() or path.resolve(strict=False) != path or not re.fullmatch(r"/[A-Za-z0-9_./-]+", str(path)):
        manage.fail("Admin runtime paths must be canonical absolute ASCII paths without unit specifiers.")
    return path


def validate_manifest(value):
    if not isinstance(value, dict) or set(value) not in (MANIFEST_FIELDS, MANIFEST_FIELDS | {"bootstrap_mode"}) or value["schema"] != MANIFEST_SCHEMA or type(value["tool_version"]) is not int or value["tool_version"] != TOOL_VERSION:
        manage.fail("Unsupported admin runtime manifest.")
    bootstrap_mode(value)
    manage.instance_name(value["source_instance"])
    artifacts = value["artifacts"]
    if not isinstance(artifacts, dict) or set(artifacts) != {"binary_sha256", "source_sha256", "runtime_image", "licenses_sha256"}:
        manage.fail("Invalid public artifact contract.")
    licenses = artifacts["licenses_sha256"]
    if not isinstance(licenses, dict) or set(licenses) != {"LICENSE", "NOTICE", "COPYRIGHT"}:
        manage.fail("Invalid license contract.")
    hashes = [artifacts["binary_sha256"], artifacts["source_sha256"], value["seccomp_sha256"], *licenses.values()]
    helpers = value["helper_sha256"]
    if not isinstance(helpers, dict) or set(helpers) != set(HELPERS):
        manage.fail("The complete frozen helper set is required.")
    hashes.extend(helpers.values())
    if any(not isinstance(item, str) or not manage.HASH_RE.fullmatch(item) for item in hashes):
        manage.fail("Invalid admin SHA256 contract.")
    if any(not isinstance(item, str) or not manage.IMAGE_RE.fullmatch(item) for item in (artifacts["runtime_image"], value["browser_image"])):
        manage.fail("Immutable native and browser image IDs are required.")
    canonical_path(value["seccomp_file"])
    if not isinstance(value["subnet_pool"], str):
        manage.fail("Invalid automatic subnet pool.")
    try:
        pool = ipaddress.IPv4Network(value["subnet_pool"], strict=True)
    except (ValueError, TypeError):
        manage.fail("Invalid automatic subnet pool.")
    private = [ipaddress.IPv4Network(item) for item in ("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16")]
    if not 16 <= pool.prefixlen <= 24 or not any(pool.subnet_of(item) for item in private):
        manage.fail("The automatic subnet pool must be an RFC1918 /16 through /24.")
    return value


def bootstrap_mode(manifest):
    value = manifest.get("bootstrap_mode", "auto")
    if not isinstance(value, str) or value not in ("auto", "manual-once"):
        manage.fail("Select auto or the explicit manual-once browser bootstrap.")
    return value


def read_manifest(path):
    return validate_manifest(manage.decode_json(read_bytes(Path(path), 65536)))


def freeze_runtime(directory, manifest):
    validate_manifest(manifest)
    contents = {}
    for name, digest in manifest["helper_sha256"].items():
        contents[name] = read_bytes(HERE / name)
        if hashlib.sha256(contents[name]).hexdigest() != digest:
            manage.fail("An admin helper differs from the reviewed manifest.")
    seccomp = read_bytes(Path(manifest["seccomp_file"]))
    if hashlib.sha256(seccomp).hexdigest() != manifest["seccomp_sha256"]:
        manage.fail("The reviewed browser sandbox policy changed.")
    runtime = directory / "runtime"
    runtime.mkdir(mode=0o700)
    writer = profile_module().write_private
    for name, data in contents.items():
        writer(runtime / name, data)
    writer(runtime / "browser-seccomp.json", seccomp)
    raw = json.dumps(manifest, sort_keys=True, separators=(",", ":")).encode("ascii") + b"\n"
    writer(directory / "admin-runtime.json", raw)
    return hashlib.sha256(raw).hexdigest()


def validate_snapshot(directory, plan):
    canonical_path(directory)
    if not re.fullmatch(r"browser[a-z0-9]{1,9}", str(plan["instance"])) or plan["subnet"] is not None:
        manage.fail("The new browser profile requires an isolated automatic-subnet plan.")
    name = plan.get("display_name")
    if not isinstance(name, str) or not re.fullmatch(r"[a-z][a-z0-9]{0,8}", name) or plan["instance"] != "browser" + name:
        manage.fail("The browser name does not match the prepared profile.")
    raw = read_bytes(directory / "admin-runtime.json", 65536, private=True)
    if hashlib.sha256(raw).hexdigest() != plan.get("admin_manifest_sha256"):
        manage.fail("The prepared admin manifest changed.")
    manifest = validate_manifest(manage.decode_json(raw))
    if manifest["source_instance"] != plan["source_instance"] or any(plan[key] != value for key, value in manifest["artifacts"].items()):
        manage.fail("The profile and public artifact contract do not match.")
    for name, digest in manifest["helper_sha256"].items():
        if hashlib.sha256(read_bytes(directory / "runtime" / name)).hexdigest() != digest:
            manage.fail("A frozen profile helper changed.")
    if hashlib.sha256(read_bytes(directory / "runtime/browser-seccomp.json")).hexdigest() != manifest["seccomp_sha256"]:
        manage.fail("The frozen browser sandbox policy changed.")
    return manifest


@contextmanager
def admin_lock(path=Path("/run/unifiedvpn-openflux-admin.lock")):
    import fcntl
    manage.secure_directory(path.parent)
    descriptor = os.open(path, os.O_CREAT | os.O_RDWR | os.O_NOFOLLOW | os.O_CLOEXEC, 0o600)
    try:
        info = os.fstat(descriptor)
        if not stat.S_ISREG(info.st_mode) or info.st_uid != 0 or info.st_nlink != 1 or info.st_mode & 0o077 or path.stat().st_ino != info.st_ino:
            manage.fail("Unsafe admin mutation lock.")
        try:
            fcntl.flock(descriptor, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            manage.fail("Another admin operation is active; no concurrent change was made.")
        yield
    finally:
        os.close(descriptor)


def unused_subnet(manager, manifest):
    occupied = [ipaddress.IPv4Network("10.10.10.0/24")]
    routes = manage.decode_json(manager.command(["ip", "-j", "-4", "route", "show", "table", "all"], timeout=30).stdout)
    for route in routes:
        destination = route.get("dst", "default")
        if destination not in ("default", "0.0.0.0/0"):
            occupied.append(ipaddress.IPv4Network(destination, strict=False))
    ids = manager.docker("network", "ls", "--quiet").stdout.split()
    if ids:
        for network in manage.decode_json(manager.docker("network", "inspect", *ids).stdout):
            for config in (network.get("IPAM") or {}).get("Config") or []:
                if config.get("Subnet"):
                    subnet = ipaddress.ip_network(config["Subnet"], strict=False)
                    if subnet.version == 4:
                        occupied.append(subnet)
    # Installed but currently stopped profiles also reserve their bridge subnet.
    for root in manage.INSTALL_DIR.parent.iterdir():
        if root.name == manage.NAME or re.fullmatch(re.escape(manage.NAME) + r"-[a-z][a-z0-9]{0,15}", root.name):
            state = manage.decode_json(read_bytes(root / "state.json", 65536, private=True))
            occupied.append(manage.validate_subnet(state.get("subnet")))
    for candidate in ipaddress.IPv4Network(manifest["subnet_pool"]).subnets(new_prefix=29):
        if not any(candidate.overlaps(item) for item in occupied):
            manager.ensure_subnet_free(candidate)
            return str(candidate)
    manage.fail("No unused subnet remains in the reviewed admin pool.")


def unit_name(instance):
    if not re.fullmatch(r"browser[a-z0-9]{1,9}", instance):
        manage.fail("Only a fresh browser-prefixed profile can own an admin service.")
    return "unifiedvpn-openflux-profile-" + instance + ".service"


def unit_contents(directory, instance, mode="auto"):
    directory = canonical_path(directory)
    unit_name(instance)
    bootstrap_mode({"bootstrap_mode": mode})
    runtime = (f"RuntimeDirectory=unifiedvpn-openflux-bootstrap-{instance}\n"
               "RuntimeDirectoryMode=0700\nRuntimeDirectoryPreserve=no\n" if mode == "manual-once" else "")
    command = f"/usr/bin/python3 -I -B {directory}/runtime/browser_service.py"
    arguments = f" --plan-directory {directory} --apply"
    return (
        "[Unit]\nDescription=Unified VPN isolated OpenFlux profile\nRequires=docker.service\n"
        "After=docker.service network-online.target\nWants=network-online.target\nStartLimitIntervalSec=0\n\n"
        "[Service]\nType=exec\nUser=root\nUMask=0077\nEnvironment=PATH=/usr/sbin:/usr/bin:/sbin:/bin\n"
        "Environment=LANG=C\nEnvironment=HOME=/root\n"
        f"{runtime}"
        f"ExecStart={command} serve{arguments}\nExecStopPost={command} stop-runtime{arguments}\n"
        "Restart=always\nRestartSec=300\nTimeoutStartSec=120\nTimeoutStopSec=60\nKillMode=control-group\n"
        "NoNewPrivileges=yes\nPrivateTmp=yes\nMemoryMax=256M\nTasksMax=128\nCPUQuota=25%\n"
        "StandardOutput=journal\nStandardError=journal\n\n[Install]\nWantedBy=multi-user.target\n"
    ).encode("ascii")


class AdminService:
    def __init__(self, directory, *, command=manage.run_command, unit_dir=Path("/etc/systemd/system")):
        self.directory = Path(directory)
        self.command, self.unit_dir = command, Path(unit_dir)
        self.receipt_path = self.directory / "browser-service.json"

    def plan(self, with_inputs=True):
        plan = profile_module().read_plan(self.directory, with_inputs=with_inputs)
        if plan["schema"] != PLAN_SCHEMA:
            manage.fail("A new browser-profile plan is required; existing installations cannot be adopted.")
        manifest = validate_snapshot(self.directory, plan)
        for name, digest in manifest["helper_sha256"].items():
            if hashlib.sha256(read_bytes(HERE / name)).hexdigest() != digest:
                manage.fail("Current admin tools differ from this plan; use its frozen runtime command.")
        return plan, manifest

    def manager(self, plan):
        return manage.Manager(instance=plan["instance"], command=self.command)

    def save(self, receipt, new=False):
        profile_module().private_directory(self.directory)
        if self.receipt_path.exists() or self.receipt_path.is_symlink():
            manage.require_secure_file(self.receipt_path, private=True)
            if new:
                manage.fail("An admin service receipt already exists; inspect before retrying.")
        temporary = self.receipt_path.with_suffix(".new")
        profile_module().write_private(temporary, json.dumps(receipt, sort_keys=True).encode("ascii") + b"\n")
        os.replace(temporary, self.receipt_path)

    def binding(self, with_inputs=True):
        plan, manifest = self.plan(with_inputs)
        record = manage.decode_json(read_bytes(self.receipt_path, 65536, private=True))
        expected_unit = unit_contents(self.directory, plan["instance"], bootstrap_mode(manifest))
        if (not isinstance(record, dict) or set(record) != SERVICE_FIELDS or record["schema"] != SERVICE_SCHEMA
                or record["tool_version"] != TOOL_VERSION or record["instance"] != plan["instance"] or record["owner"] != plan["owner"]
                or record["plan_sha256"] != hashlib.sha256(read_bytes(self.directory / "plan.json", 65536, private=True)).hexdigest()
                or record["manifest_sha256"] != plan["admin_manifest_sha256"] or record["unit"] != unit_name(plan["instance"])
                or record["unit_sha256"] != hashlib.sha256(expected_unit).hexdigest()
                or record["phase"] not in ("installing", "installed", "enabled", "removing", "removed")
                or record["image_id"] is not None and not manage.IMAGE_RE.fullmatch(str(record["image_id"]))):
            manage.fail("Invalid browser-profile service ownership receipt.")
        subnet = manage.validate_subnet(record["subnet"])
        if subnet.prefixlen != 29 or not subnet.subnet_of(ipaddress.IPv4Network(manifest["subnet_pool"])):
            manage.fail("The service subnet is outside its reviewed allocation pool.")
        return plan, manifest, record

    def systemctl(self, *args, allow_failure=False):
        return self.command(["systemctl", "--no-pager", *args], timeout=90, allow_failure=allow_failure)

    def unit_state(self, name):
        result = self.systemctl("show", name, "--property=Id,LoadState,FragmentPath,DropInPaths,Transient,ActiveState,SubState", allow_failure=True)
        expected = {"Id", "LoadState", "FragmentPath", "DropInPaths", "Transient", "ActiveState", "SubState"}
        fields = {}
        if len(result.stdout) > 32768:
            manage.fail("Oversized systemd unit identity response.")
        for line in result.stdout.splitlines():
            if not line:
                continue
            if "=" not in line:
                manage.fail("Malformed systemd unit identity response.")
            key, value = line.split("=", 1)
            if key not in expected or key in fields:
                manage.fail("Ambiguous systemd unit identity response.")
            fields[key] = value
        if set(fields) != expected or fields["Id"] != name or fields["LoadState"] not in ("loaded", "not-found"):
            manage.fail("The service unit identity could not be verified.")
        if fields["LoadState"] == "not-found" and (fields["FragmentPath"] or fields["DropInPaths"] or fields["Transient"] != "no"
                or fields["ActiveState"] != "inactive" or fields["SubState"] != "dead"):
            manage.fail("Systemd did not unambiguously report an absent unit.")
        if result.returncode:
            missing_errors = ("", f"Unit {name} could not be found.", f"Failed to get properties: Unit {name} not found.")
            if (result.returncode != 1 or fields["LoadState"] != "not-found" or fields.get("FragmentPath")
                    or fields.get("DropInPaths") or fields.get("ActiveState", "inactive") != "inactive"
                    or result.stderr.strip() not in missing_errors):
                manage.fail("Systemd could not confirm unit absence; no mutation was made.")
        return fields

    def owned_unit(self, record):
        path = self.unit_dir / record["unit"]
        exists = path.exists() or path.is_symlink()
        if exists and hashlib.sha256(read_bytes(path, 65536)).hexdigest() != record["unit_sha256"]:
            manage.fail("The service unit file changed; nothing was stopped.")
        state = self.unit_state(record["unit"])
        if state.get("DropInPaths") or state.get("Transient") not in ("no", ""):
            manage.fail("Unbound systemd overrides or transient units are not accepted.")
        if state["LoadState"] == "loaded" and (not exists or state.get("FragmentPath") != str(path)):
            manage.fail("The loaded service is not the exact owned unit.")
        return path, state, exists

    def native(self, plan, record, complete=True):
        manager = self.manager(plan)
        state = manager.load_state()
        if state["owner"] != plan["owner"] or state["subnet"] != record["subnet"] or record["image_id"] is not None and state.get("image_id") != record["image_id"]:
            manage.fail("The native installation no longer belongs to this admin plan.")
        if complete:
            manage.require_secure_file(manager.root / "openflux")
            if (record["image_id"] is None or manage.sha256_file(manager.root / "openflux") != plan["binary_sha256"]
                    or hashlib.sha256(read_bytes(manager.root / "server.json", 16384, private=True)).hexdigest() != plan["server_sha256"]):
                manage.fail("The installed native binary or private config changed.")
        return manager, state

    def supervisor(self, manager, manifest):
        options = {"manual_bootstrap": True} if bootstrap_mode(manifest) == "manual-once" else {}
        return browser.Supervisor(manager, manifest["browser_image"], self.directory / "runtime/browser-seccomp.json", manifest["seccomp_sha256"], **options)

    def install(self, args):
        with admin_lock():
            plan, manifest = self.plan()
            manager = self.manager(plan)
            manager.preflight()
            name = unit_name(plan["instance"])
            path = self.unit_dir / name
            manage.secure_directory(self.unit_dir)
            if manager.root.exists() or manager.root.is_symlink() or self.receipt_path.exists() or self.receipt_path.is_symlink():
                manage.fail("The new instance or service receipt already exists; no replacement is allowed.")
            if path.exists() or path.is_symlink() or self.unit_state(name)["LoadState"] != "not-found":
                manage.fail("The new service name is already used.")
            mode = bootstrap_mode(manifest)
            if mode == "manual-once":
                runtime = browser.manual_runtime_directory(plan["instance"])
                try:
                    runtime.lstat()
                except FileNotFoundError:
                    pass
                else:
                    manage.fail("The manual bootstrap runtime directory already exists; nothing was replaced.")
            wizard = profile_module()
            artifacts = wizard.public_artifacts(plan["source_instance"])
            if any(plan[field] != value for field, value in artifacts.items()):
                manage.fail("The source artifacts changed after preparation.")
            wizard.require_new_document(manage.read_config(self.directory / "server.json")["document_url"])
            args.subnet = unused_subnet(manager, manifest)
            manager.check(args)
            contents = unit_contents(self.directory, plan["instance"], mode)
            record = {"schema": SERVICE_SCHEMA, "tool_version": TOOL_VERSION, "instance": plan["instance"], "owner": plan["owner"],
                      "plan_sha256": hashlib.sha256(read_bytes(self.directory / "plan.json", 65536, private=True)).hexdigest(),
                      "manifest_sha256": plan["admin_manifest_sha256"], "subnet": args.subnet, "unit": name,
                      "unit_sha256": hashlib.sha256(contents).hexdigest(), "image_id": None, "phase": "installing"}
            self.save(record, new=True)
            manager.install(args, owner=plan["owner"])
            state = manager.load_state()
            if state["owner"] != plan["owner"]:
                manage.fail("The new installation owner changed.")
            record.update(image_id=state["image_id"], phase="installed")
            self.save(record)
            self.supervisor(manager, manifest).preflight()
            wizard.write_private(path, contents)
            self.command(["systemd-analyze", "verify", str(path)], timeout=30)
            self.systemctl("daemon-reload")
            _, loaded, _ = self.owned_unit(record)
            if loaded["LoadState"] != "loaded":
                manage.fail("The new service unit did not load.")
            self.systemctl("enable", "--now", name)
            self.owned_unit(record)
            record["phase"] = "enabled"
            self.save(record)

    def runtime_lock(self, plan):
        return admin_lock(Path("/run") / ("unifiedvpn-openflux-" + plan["instance"] + ".lock"))

    def cleanup_runtime(self, plan, manifest, record):
        self.owned_unit(record)
        manager, state = self.native(plan, record)
        manager.preflight()
        supervisor = self.supervisor(manager, manifest)
        native = manager.owned("container", manager.name, state)
        network = manager.owned("network", manager.network, state)
        companion = supervisor.companion(state)
        if (native is not None and native["Id"] != state.get("container_id") or
                network is not None and network["Id"] != state.get("network_id")):
            manage.fail("Unreceipted runtime resources require manual inspection.")
        if set((network or {}).get("Containers") or {}) - ({native["Id"]} if native else set()):
            manage.fail("Foreign endpoints prevent runtime cleanup.")
        if not state.get("browser_bootstrap_stdio") and (native is not None or network is not None):
            if native is None and companion is None and state.get("container_id") is None and network is not None:
                manager.stop()
                return
            manage.fail("Unbound runtime resources were not stopped.")
        if state.get("browser_bootstrap_stdio"):
            supervisor.cleanup()

    def stop_runtime(self):
        plan, manifest, record = self.binding(with_inputs=False)
        with self.runtime_lock(plan):
            locked_instance = plan["instance"]
            plan, manifest, record = self.binding(with_inputs=False)
            if plan["instance"] != locked_instance:
                manage.fail("The profile identity changed while acquiring its runtime lock.")
            self.cleanup_runtime(plan, manifest, record)

    def serve(self):
        plan, manifest, record = self.binding(with_inputs=False)
        with self.runtime_lock(plan):
            locked_instance = plan["instance"]
            plan, manifest, record = self.binding(with_inputs=False)
            if plan["instance"] != locked_instance:
                manage.fail("The profile identity changed while acquiring its runtime lock.")
            self.owned_unit(record)
            if record["phase"] not in ("installed", "enabled"):
                manage.fail("The service is not authorized by an installed admin receipt.")
            manager, _ = self.native(plan, record)
            self.cleanup_runtime(plan, manifest, record)
            state = manager.load_state()
            state["browser_bootstrap_stdio"] = True
            manager.write_state(state)
            with (manager.root / "state.json").open("rb") as stream:
                os.fsync(stream.fileno())
            self.supervisor(manager, manifest).run()

    def check(self):
        plan, manifest, record = self.binding(with_inputs=False)
        manager, _ = self.native(plan, record)
        self.supervisor(manager, manifest).preflight()
        _, state, _ = self.owned_unit(record)
        return {"instance": plan["instance"], "service_active": state.get("ActiveState") == "active", "real_traffic_verified": False}

    def rollback(self):
        with admin_lock():
            plan, manifest, record = self.binding(with_inputs=False)
            path, unit, exists = self.owned_unit(record)
            manager = self.manager(plan)
            manager.preflight()
            if manager.root.exists() or manager.root.is_symlink():
                manager, state = self.native(plan, record, complete=False)
                native = manager.owned("container", manager.name, state)
                network = manager.owned("network", manager.network, state)
                self.supervisor(manager, manifest).companion(state)
                endpoints = (network or {}).get("Containers") or {}
                allowed = {native["Id"]} if native else set()
                if set(endpoints) - allowed:
                    manage.fail("Foreign network endpoints prevent owned rollback.")
            if unit["LoadState"] == "loaded":
                self.systemctl("disable", "--now", record["unit"])
                self.owned_unit(record)
            # Never hold this lock across systemctl stop: ExecStopPost needs it.
            with self.runtime_lock(plan):
                plan, manifest, record = self.binding(with_inputs=False)
                path, _, exists = self.owned_unit(record)
                manager = self.manager(plan)
                if manager.root.exists() or manager.root.is_symlink():
                    manager, state = self.native(plan, record, complete=False)
                    native = manager.owned("container", manager.name, state)
                    network = manager.owned("network", manager.network, state)
                    self.supervisor(manager, manifest).companion(state)
                    if set((network or {}).get("Containers") or {}) - ({native["Id"]} if native else set()):
                        manage.fail("Foreign endpoints appeared during rollback; removal refused.")
                record["phase"] = "removing"
                self.save(record)
                if manager.root.exists():
                    if state.get("browser_bootstrap_stdio"):
                        self.supervisor(manager, manifest).cleanup()
                    manager.remove()
                if exists:
                    self.owned_unit(record)
                    path.unlink()
                    self.systemctl("daemon-reload")
                record["phase"] = "removed"
                self.save(record)


def main(argv=None):
    parser = browser.PrivateParser(description=__doc__)
    parser.add_argument("command", choices=("serve", "stop-runtime", "check", "rollback"))
    parser.add_argument("--plan-directory", required=True, type=Path)
    parser.add_argument("--apply", action="store_true")
    args = parser.parse_args(argv)
    if args.command != "check" and not args.apply:
        parser.error("Mutation requires --apply.")
    os.umask(0o077)
    try:
        for signum in (signal.SIGINT, signal.SIGTERM):
            signal.signal(signum, lambda *_: (_ for _ in ()).throw(KeyboardInterrupt()))
        service = AdminService(args.plan_directory)
        result = getattr(service, args.command.replace("-", "_"))()
        if args.command == "check":
            print(json.dumps(result, sort_keys=True))
        return 0
    except KeyboardInterrupt:
        print("OPENFLUX_ADMIN_RUNTIME_STOPPED", flush=True)
        return 0 if args.command == "serve" else 1
    except Exception:
        print("OPENFLUX_ADMIN_FAILED: inspect owned state; private details suppressed.", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
