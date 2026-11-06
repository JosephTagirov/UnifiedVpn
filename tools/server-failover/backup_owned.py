"""Private, bounded standby HA configuration snapshot. It never restarts services."""
import argparse
from contextlib import closing
import io
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import stat
import sqlite3
import subprocess
import sys
import tarfile
import tempfile
import time

from scope_verify import awg_configuration


ROOTS = [
    "/etc/caddy", "/etc/mtproxy", "/etc/tproxy-server", "/etc/unifiedvpn-tls",
    "/etc/unifiedvpn-failover", "/opt/unifiedvpn-failover", "/var/lib/unifiedvpn-failover",
    "/etc/unifiedvpn-native-ha", "/opt/unifiedvpn-native-ha", "/var/lib/unifiedvpn-native-ha",
    "/etc/unifiedvpn-control.env", "/opt/unifiedvpn-control", "/var/lib/unifiedvpn-control", "/etc/x-ui",
    "/etc/unifiedvpn-frontdoor", "/opt/unifiedvpn-frontdoor", "/etc/unifiedvpn-management-relay",
    "/etc/unifiedvpn-subscription-compat", "/opt/unifiedvpn-subscription-compat", "/srv/unifiedvpn-ha-source",
    "/etc/unifiedvpn-legacy-ha", "/var/lib/unifiedvpn-legacy-ha",
    "/etc/unifiedvpn-classic-ha", "/var/lib/unifiedvpn-classic-ha",
    "/etc/unifiedvpn-openflux-ha", "/opt/unifiedvpn-openflux-ha", "/var/lib/unifiedvpn-openflux-ha",
    "/etc/chrony", "/etc/default/chrony", "/var/lib/chrony", "/lib/systemd/system/chrony.service",
    "/etc/systemd/system/chrony.service.d", "/etc/systemd/system/chronyd.service",
    "/etc/systemd/system/multi-user.target.wants/chrony.service",
]
CONTAINERS = re.compile(r"^(?:amnezia-awg2(?:-protocol2-preserved)?|unifiedvpn-native-[A-Za-z0-9_.-]+|unifiedvpn-legacy-(?:olcbox-awg|[ab]-replica)|unifiedvpn-classic-old-ha|unifiedvpn-awg3)$")


def command(arguments):
    result = subprocess.run(arguments, capture_output=True, timeout=30)
    if result.returncode:
        raise RuntimeError("Private standby snapshot read failed")
    return result.stdout


def member_filter(member, excluded_files=()):
    excluded = [
        "var/lib/unifiedvpn-native-ha/images", "opt/unifiedvpn-failover/bin", "var/lib/unifiedvpn-failover/backups",
        "var/lib/unifiedvpn-control/downloads", "var/lib/unifiedvpn-control/cabinet-backups", "var/lib/unifiedvpn-control/initial-backups",
    ]
    if member.name in excluded_files or any(member.name == prefix or member.name.startswith(prefix + "/") for prefix in excluded):
        return None
    if "__pycache__" in member.name.split("/") or member.name.endswith((".log", ".lock", ".new")):
        return None
    if member.name.startswith(("etc/x-ui/", "var/lib/unifiedvpn-control/")) and member.name.endswith((".db", ".db-wal", ".db-shm")):
        return None
    return member if member.isfile() or member.isdir() or member.issym() or member.islnk() else None


def container_file(container, source):
    encoded = command(["docker", "cp", "-L", container + ":" + source, "-"])
    if len(encoded) > 4 * 1024 * 1024:
        raise RuntimeError("Native container file exceeds its snapshot limit")
    with tarfile.open(fileobj=io.BytesIO(encoded), mode="r:") as archive:
        members = [member for member in archive if member.isfile()]
        if len(members) != 1 or members[0].size > 2 * 1024 * 1024:
            raise RuntimeError("Native container file archive is invalid")
        return archive.extractfile(members[0]).read()


def read_extra(path):
    path = Path(path)
    if not path.exists():
        return {}
    info = path.lstat()
    if not stat.S_ISREG(info.st_mode) or info.st_uid != os.geteuid() or info.st_mode & 0o077 or info.st_size > 512 * 1024:
        raise ValueError("Extra snapshot configuration must be protected and bounded")
    value = json.loads(path.read_text())
    if not isinstance(value, dict):
        raise ValueError("Extra snapshot configuration must be an object")
    for source in value.get("extra_roots", []):
        path = Path(source)
        if not path.is_absolute() or ".." in path.parts:
            raise ValueError("Extra snapshot path must be absolute")
    return value


def create(destination, config_path="/etc/unifiedvpn-failover/backup-owned.private.json"):
    if Path("/run/archiso").exists():
        raise RuntimeError("Rescue OS cannot attest the standby configuration")
    with tempfile.TemporaryDirectory(prefix="unifiedvpn-ha-backup-", dir="/var/tmp") as directory:
        stage = Path(directory)
        extra = read_extra(config_path)
        integrity = {}
        for source, name in [("/etc/x-ui/x-ui.db", "x-ui.db"), ("/var/lib/unifiedvpn-control/control.db", "control.db")]:
            with closing(sqlite3.connect("file:" + source + "?mode=ro", uri=True)) as incoming, closing(sqlite3.connect(stage / name)) as snapshot:
                incoming.backup(snapshot)
                integrity[name] = snapshot.execute("pragma integrity_check").fetchone()[0]
                if integrity[name] != "ok":
                    raise RuntimeError("Standby database integrity check failed")
            (stage / name).chmod(0o600)
        native = stage / "native"
        native.mkdir(mode=0o700)
        names = [name for name in command(["docker", "ps", "--all", "--format", "{{.Names}}"]).decode().splitlines() if CONTAINERS.fullmatch(name)]
        runtime_matches = {}
        for name in names:
            folder = native / name
            folder.mkdir(mode=0o700)
            (folder / "container.private.json").write_bytes(command(["docker", "inspect", name]))
            if name in {"unifiedvpn-legacy-olcbox-awg", "unifiedvpn-classic-old-ha"}:
                (folder / "wg0.conf").write_bytes(container_file(name, "/opt/amnezia/awg/wg0.conf"))
                configuration = folder / "wg0.conf"
                binary, interface = "wg", "wg0"
                image = json.loads((folder / "container.private.json").read_bytes())[0]["Image"]
                image_info = json.loads(command(["docker", "image", "inspect", image]))[0]
                if image_info["Size"] > 128 * 1024 * 1024:
                    raise RuntimeError("Legacy native image exceeds its private snapshot bound")
                command(["docker", "image", "save", "-o", str(folder / "image.private.tar"), image])
            elif name not in {"unifiedvpn-awg3", "unifiedvpn-legacy-a-replica", "unifiedvpn-legacy-b-replica"}:
                for filename in ["awg0.conf", "clientsTable"]:
                    (folder / filename).write_bytes(container_file(name, "/opt/amnezia/awg/" + filename))
                configuration = folder / "awg0.conf"
                json.loads((folder / "clientsTable").read_bytes())
                binary, interface = "awg", "awg0"
            else:
                (folder / "server.conf").write_bytes(container_file(name, "/run/secrets/server.conf"))
                configuration = folder / "server.conf"
                binary, interface = "awg", "awg0"
            live = command(["docker", "exec", name, binary, "showconf", interface]).decode()
            (folder / "runtime.conf").write_text(live)
            runtime_matches[name] = awg_configuration(configuration.read_text()) == awg_configuration(live)
            if name not in {"unifiedvpn-legacy-olcbox-awg", "unifiedvpn-classic-old-ha"} and not runtime_matches[name]:
                raise RuntimeError("Captured native configuration does not match the live interface")
        recovery = stage / "rtc-openflux"
        recovery.mkdir(mode=0o700)
        containers = []
        networks = set()
        existing = set(command(["docker", "ps", "--all", "--format", "{{.Names}}"]).decode().splitlines())
        for item in extra.get("active_openflux_containers", []):
            name = item["name"]
            if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_.-]{0,127}", name):
                raise ValueError("Invalid owned container name")
            if name not in existing:
                containers.append({"name": name, "missing": True})
                continue
            inspected = json.loads(command(["docker", "inspect", name]))
            (recovery / (name + ".container.private.json")).write_text(json.dumps(inspected))
            image = inspected[0]["Image"]
            containers.append({"name": name, "id": inspected[0]["Id"], "image_id": image})
            networks.update(inspected[0].get("NetworkSettings", {}).get("Networks", {}))
        for name in sorted(networks):
            if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_.-]{0,127}", name):
                raise ValueError("Invalid owned network name")
            (recovery / (name + ".network.private.json")).write_bytes(command(["docker", "network", "inspect", name]))
        pins = []
        for item in extra.get("deduplicated_public_binary_pins", []):
            path = Path(item["path"])
            if not path.is_absolute() or ".." in path.parts:
                raise ValueError("Invalid owned binary path")
            included = any(Path(source) == path or Path(source) in path.parents for source in [*ROOTS, *extra.get("extra_roots", [])])
            pin = {"path": str(path), "included": included, "missing": not path.is_file()}
            if path.is_file():
                digest = hashlib.sha256()
                with path.open("rb") as stream:
                    for block in iter(lambda: stream.read(1024 * 1024), b""):
                        digest.update(block)
                pin.update(bytes=path.stat().st_size, sha256=digest.hexdigest())
            pins.append(pin)
        unit_states = {}
        for name in extra.get("record_unit_states", {}):
            if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_.@-]{0,127}\.(?:service|timer)", name):
                raise ValueError("Invalid owned unit name")
            result = subprocess.run(["systemctl", "show", name, "--property=ActiveState,UnitFileState,FragmentPath,DropInPaths,Requires,Wants,After"], capture_output=True, text=True, timeout=5)
            unit_states[name] = dict(line.split("=", 1) for line in result.stdout.splitlines() if "=" in line)
        (recovery / "unit-states.private.json").write_text(json.dumps(unit_states))
        (recovery / "binary-pins.private.json").write_text(json.dumps(pins))
        manifest = {"scope": "standby-owned-ha-v1", "createdAt": int(time.time()), "databaseIntegrity": integrity, "nativeContainers": names, "nativeRuntimeMatches": runtime_matches, "otherNativeImagesExcluded": True, "imageRecoverySource": "primary-native-cache and legacy-ha image-cache", "rtcOpenfluxContainers": containers, "rtcOpenfluxImagesIncluded": False, "rtcOpenfluxBinaryRecovery": "included flag per binary pin"}
        (stage / "manifest.json").write_text(json.dumps(manifest))
        archive_path = stage / "standby-owned.tar.gz"
        with tarfile.open(archive_path, "w:gz", compresslevel=1) as archive:
            excluded_files = {str(Path(source, name)).lstrip("/") for source in extra.get("extra_roots", []) if source.startswith("/opt/unifiedvpn-openflux-browser") for name in extra.get("exclude_basenames_from_new_openflux_browser_roots", [])}
            filter_member = lambda member: member_filter(member, excluded_files)
            for source in dict.fromkeys([*ROOTS, *extra.get("extra_roots", [])]):
                path = Path(source)
                if path.exists():
                    archive.add(path, arcname=path.relative_to("/").as_posix(), filter=filter_member)
            units = Path("/etc/systemd/system")
            for pattern in ["unifiedvpn*", "mtg.service*", "mtproxy*.service*", "tproxy*.service*", *extra.get("extra_unit_patterns", [])]:
                for path in units.glob(pattern):
                    archive.add(path, arcname=path.relative_to("/").as_posix(), filter=filter_member)
            for path in stage.iterdir():
                if path != archive_path:
                    archive.add(path, arcname="consistent/" + path.name)
        archive_path.chmod(0o600)
        if archive_path.stat().st_size > 128 * 1024 * 1024:
            raise RuntimeError("Private standby snapshot exceeds its compressed limit")
        with archive_path.open("rb") as stream:
            shutil.copyfileobj(stream, destination, length=1024 * 1024)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--stdout", action="store_true", required=True)
    parser.parse_args()
    os.umask(0o077)
    os.nice(10)
    create(sys.stdout.buffer)


if __name__ == "__main__":
    main()
