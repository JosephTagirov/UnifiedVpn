"""Private, bounded standby HA configuration snapshot. It never restarts services."""
import argparse
from contextlib import closing
import io
import json
import os
from pathlib import Path
import re
import shutil
import sqlite3
import subprocess
import sys
import tarfile
import tempfile
import time


ROOTS = [
    "/etc/caddy", "/etc/mtproxy", "/etc/tproxy-server", "/etc/unifiedvpn-tls",
    "/etc/unifiedvpn-failover", "/opt/unifiedvpn-failover", "/var/lib/unifiedvpn-failover",
    "/etc/unifiedvpn-native-ha", "/opt/unifiedvpn-native-ha", "/var/lib/unifiedvpn-native-ha",
    "/etc/unifiedvpn-control.env", "/opt/unifiedvpn-control", "/var/lib/unifiedvpn-control", "/etc/x-ui",
    "/etc/unifiedvpn-frontdoor", "/opt/unifiedvpn-frontdoor", "/etc/unifiedvpn-management-relay",
]
CONTAINERS = re.compile(r"^(?:amnezia-awg2(?:-protocol2-preserved)?|unifiedvpn-native-[A-Za-z0-9_.-]+|unifiedvpn-legacy-olcbox-awg|unifiedvpn-awg3)$")


def command(arguments):
    result = subprocess.run(arguments, capture_output=True, timeout=30)
    if result.returncode:
        raise RuntimeError("Private standby snapshot read failed")
    return result.stdout


def member_filter(member):
    excluded = [
        "var/lib/unifiedvpn-native-ha/images", "opt/unifiedvpn-failover/bin", "var/lib/unifiedvpn-failover/backups",
        "var/lib/unifiedvpn-control/downloads", "var/lib/unifiedvpn-control/cabinet-backups", "var/lib/unifiedvpn-control/initial-backups",
    ]
    if any(member.name == prefix or member.name.startswith(prefix + "/") for prefix in excluded):
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


def create(destination):
    if Path("/run/archiso").exists():
        raise RuntimeError("Rescue OS cannot attest the standby configuration")
    with tempfile.TemporaryDirectory(prefix="unifiedvpn-ha-backup-", dir="/var/tmp") as directory:
        stage = Path(directory)
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
        for name in names:
            folder = native / name
            folder.mkdir(mode=0o700)
            (folder / "container.private.json").write_bytes(command(["docker", "inspect", name]))
            if name == "unifiedvpn-legacy-olcbox-awg":
                (folder / "wg0.conf").write_bytes(container_file(name, "/opt/amnezia/awg/wg0.conf"))
                image = json.loads((folder / "container.private.json").read_bytes())[0]["Image"]
                image_info = json.loads(command(["docker", "image", "inspect", image]))[0]
                if image_info["Size"] > 128 * 1024 * 1024:
                    raise RuntimeError("Legacy native image exceeds its private snapshot bound")
                command(["docker", "image", "save", "-o", str(folder / "image.private.tar"), image])
            elif name != "unifiedvpn-awg3":
                for filename in ["awg0.conf", "clientsTable"]:
                    (folder / filename).write_bytes(container_file(name, "/opt/amnezia/awg/" + filename))
            else:
                (folder / "server.conf").write_bytes(container_file(name, "/run/secrets/server.conf"))
        manifest = {"scope": "standby-owned-ha-v1", "createdAt": int(time.time()), "databaseIntegrity": integrity, "nativeContainers": names, "otherNativeImagesExcluded": True, "imageRecoverySource": "primary-native-cache"}
        (stage / "manifest.json").write_text(json.dumps(manifest))
        archive_path = stage / "standby-owned.tar.gz"
        with tarfile.open(archive_path, "w:gz", compresslevel=1) as archive:
            for source in ROOTS:
                path = Path(source)
                if path.exists():
                    archive.add(path, arcname=path.relative_to("/").as_posix(), filter=member_filter)
            units = Path("/etc/systemd/system")
            for pattern in ["unifiedvpn*", "mtg.service*", "mtproxy*.service*", "tproxy*.service*"]:
                for path in units.glob(pattern):
                    archive.add(path, arcname=path.relative_to("/").as_posix(), filter=member_filter)
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
