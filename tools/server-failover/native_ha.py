"""Guarded native AWG replica worker. All artifacts remain root-private."""
import base64
import copy
import hashlib
import ipaddress
import json
import os
from pathlib import Path
import re
import shutil
import sqlite3
import subprocess
import sys
import tempfile
import tarfile
import time
import traceback

ROOT = Path("/var/lib/unifiedvpn-native-ha")
FILES = ("awg0.conf", "clientsTable", "wireguard_server_private_key.key", "wireguard_server_public_key.key", "wireguard_psk.key")
DIRECTORY = "/opt/amnezia/awg/"
LABEL = "org.unifiedvpn.native-ha.origin"
WG_FIELDS = {"PrivateKey", "ListenPort", "FwMark", "Jc", "Jmin", "Jmax", "S1", "S2", "S3", "S4", "H1", "H2", "H3", "H4", "I1", "I2", "I3", "I4", "I5", "HeaderProtectionKey", "ContentPaddingAddition", "RekeyAfterTime", "RekeyTimeout", "RejectAfterTime", "KeepaliveTimeout", "MaxHandshakeAttempts", "RandomTrailers", "DisableCookies"}


def command(args, data=None, check=True):
    result = subprocess.run(args, input=data, capture_output=True, timeout=180)
    if check and result.returncode:
        raise RuntimeError("Bounded native HA operation failed")
    return result


def sha(data):
    return hashlib.sha256(data).hexdigest()


def write(path, data, mode=0o600):
    path = Path(path)
    path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    descriptor, temporary = tempfile.mkstemp(prefix=".native-ha-", dir=path.parent)
    try:
        os.fchmod(descriptor, mode)
        with os.fdopen(descriptor, "wb") as stream:
            stream.write(data)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def document(path, value):
    write(path, json.dumps(value, sort_keys=True, separators=(",", ":")).encode())


def inspect(container):
    result = command(["docker", "container", "inspect", container], check=False)
    return json.loads(result.stdout)[0] if result.returncode == 0 else None


def read(container, name):
    if name not in FILES:
        raise RuntimeError("Native source file is outside allowlist")
    value = command(["docker", "exec", container, "cat", DIRECTORY + name]).stdout
    if len(value) > 2 * 1024 * 1024:
        raise RuntimeError("Native source file exceeds its bound")
    return value


def parse(raw):
    interface = {}
    peers = []
    current = interface
    for line in raw.decode().splitlines():
        line = line.strip()
        if line == "[Peer]":
            current = {}
            peers.append(current)
        elif line == "[Interface]":
            current = interface
        elif "=" in line and not line.startswith("#"):
            key, value = [part.strip() for part in line.split("=", 1)]
            current[key] = value
    return interface, peers


def canonical(raw):
    interface, peers = parse(raw)
    normalized = {key: value for key, value in interface.items() if key in WG_FIELDS}
    clients = [{key: ",".join(sorted(part.strip() for part in value.split(","))) if key == "AllowedIPs" else value
                for key, value in peer.items() if key in {"PublicKey", "PresharedKey", "AllowedIPs"}}
               for peer in peers]
    return {"interface": normalized, "peers": sorted(clients, key=lambda peer: peer["PublicKey"])}


def identity(raw):
    return sha(json.dumps(canonical(raw), sort_keys=True, separators=(",", ":")).encode())


def runtime_configuration(raw):
    normalized = canonical(raw)
    runtime = "[Interface]\n" + "".join(key + " = " + value + "\n" for key, value in normalized["interface"].items())
    for peer in normalized["peers"]:
        runtime += "\n[Peer]\n" + "".join(key + " = " + value + "\n" for key, value in peer.items())
    return runtime.encode()


def replace_bound_runtime(path, raw):
    with path.open("r+b") as stream:
        stream.write(raw)
        stream.truncate()
        stream.flush()
        os.fsync(stream.fileno())


def recover_pending(request, incoming, state_path, name):
    """Recover only a journal whose files and runtime still match its two generations."""
    origin = request["origin"]
    journal_path = ROOT / "replicas" / (origin + ".pending.json")
    if not journal_path.exists():
        return
    journal = json.loads(journal_path.read_text())
    before, after = journal["before"], journal["next"]
    for generation in (before, after):
        if any(generation[key] != request[key] for key in ("origin", "sourceId", "serverPublicKeySha256")):
            raise RuntimeError("Pending native journal violates fixed source authority")
    if (before["epoch"] != after["epoch"] or incoming["epoch"] != after["epoch"]
            or not before["revision"] < after["revision"] <= incoming["revision"]
            or any(before[key] != after[key] for key in ("imageId", "address", "mtu"))):
        raise RuntimeError("Pending native journal has an invalid generation lineage")
    if incoming["revision"] == after["revision"] and incoming["payloadSha256"] != after["payloadSha256"]:
        raise RuntimeError("Pending native revision changed its payload")
    current_state = json.loads(state_path.read_text())
    if not any(all(current_state[key] == generation[key] for key in ("epoch", "revision", "payloadSha256"))
               for generation in (before, after)):
        raise RuntimeError("Applied native state is outside the pending journal")
    active = Path(before["activeDirectory"])
    backup = Path(journal["backup"])
    if (active.resolve() != (ROOT / "active" / origin).resolve()
            or backup.resolve() != backup.absolute() or not backup.resolve().is_relative_to((ROOT / "replica-backups" / origin).resolve())):
        raise RuntimeError("Pending native journal references an unowned directory")
    item = inspect(name)
    if (not item or item["Id"] != before["targetId"] or before["targetName"] != name
            or item["Config"].get("Labels", {}).get(LABEL) != origin):
        raise RuntimeError("Pending native journal target ownership changed")
    old_files = {filename: (backup / filename).read_bytes() for filename in FILES}
    if any(sha(value) != before["files"][filename] for filename, value in old_files.items()):
        raise RuntimeError("Pending native rollback backup is unverified")
    current_files = {filename: read(name, filename) for filename in FILES}
    if any(sha(value) not in {before["files"][filename], after["files"][filename]} for filename, value in current_files.items()):
        raise RuntimeError("Pending native journal contains independent file changes")
    old_runtime = runtime_configuration(old_files["awg0.conf"])
    new_runtime = None
    for candidate in (current_files["awg0.conf"], (ROOT / "incoming" / origin / "awg0.conf").read_bytes()):
        if sha(candidate) == after["files"]["awg0.conf"]:
            new_runtime = runtime_configuration(candidate)
            break
    runtime_hashes = journal.get("runtimeSha256")
    if runtime_hashes is None:
        runtime_hashes = {"before": sha(old_runtime)}
        if new_runtime is not None:
            runtime_hashes["next"] = sha(new_runtime)
    elif (runtime_hashes.get("before") != sha(old_runtime)
          or new_runtime is not None and runtime_hashes.get("next") != sha(new_runtime)):
        raise RuntimeError("Pending native startup hashes do not match their verified generations")
    startup = active / "runtime.conf"
    if startup.is_symlink() or sha(startup.read_bytes()) not in set(runtime_hashes.values()):
        raise RuntimeError("Pending native journal contains independent startup-file changes")
    live_raw = command(["docker", "exec", name, "awg", "showconf", "awg0"]).stdout
    live_hash = identity(live_raw)
    if live_hash not in {before["identitySha256"], after["identitySha256"]}:
        raise RuntimeError("Pending native journal contains independent runtime changes")
    complete = live_hash == after["identitySha256"] and all(sha(value) == after["files"][filename] for filename, value in current_files.items())
    if complete:
        if new_runtime is None:
            raise RuntimeError("Pending native completed generation has no verified startup configuration")
        replace_bound_runtime(startup, new_runtime)
        applied = {**after, "targetName": name, "targetId": before["targetId"],
                   "activeDirectory": str(active), "appliedAt": int(time.time()), "journalRecovered": True}
    else:
        command(["docker", "exec", "-i", name, "awg", "syncconf", "awg0", "/dev/stdin"], old_runtime)
        for filename, value in old_files.items():
            write(active / filename, value)
        replace_bound_runtime(startup, old_runtime)
        applied = before
    live = invariant(name)
    if live["identitySha256"] != applied["identitySha256"] or live["containerId"] != before["targetId"]:
        raise RuntimeError("Pending native journal recovery did not verify")
    document(state_path, applied)
    journal_path.unlink()


def prepare_initial_stage(origin, manifest, active, files, runtime, entry):
    """Reuse only an explicitly owned staging directory for the identical manifest."""
    if origin != manifest["origin"] or active.resolve() != (ROOT / "active" / origin).resolve():
        raise RuntimeError("Initial native staging is outside its owned origin")
    claim = {key: manifest[key] for key in ("origin", "sourceId", "serverPublicKeySha256", "epoch", "revision", "payloadSha256", "imageId")}
    claim.update(runtimeSha256=sha(runtime), entrySha256=sha(entry))
    marker = active / ".native-ha-initial.json"
    if active.exists():
        if not marker.is_file() or marker.is_symlink() or json.loads(marker.read_text()) != claim:
            raise RuntimeError("Initial native staging has different ownership or generation")
        allowed = {*FILES, "runtime.conf", "entry.sh", marker.name}
        if any(path.name not in allowed or path.is_symlink() for path in active.iterdir()):
            raise RuntimeError("Initial native staging contains independent artifacts")
        for filename, value in files.items():
            path = active / filename
            if path.exists() and sha(path.read_bytes()) != sha(value):
                raise RuntimeError("Initial native staging contains independent file changes")
        for filename, expected in (("runtime.conf", claim["runtimeSha256"]), ("entry.sh", claim["entrySha256"])):
            path = active / filename
            if path.exists() and sha(path.read_bytes()) != expected:
                raise RuntimeError("Initial native startup artifacts changed")
    else:
        active.mkdir(mode=0o700, parents=True, exist_ok=False)
        document(marker, claim)
    for filename, value in files.items():
        write(active / filename, value)
    if (active / "runtime.conf").exists():
        replace_bound_runtime(active / "runtime.conf", runtime)
    else:
        write(active / "runtime.conf", runtime)
    if not (active / "entry.sh").exists():
        write(active / "entry.sh", entry, 0o700)


def invariant(container):
    item = inspect(container)
    if not item or not item["State"]["Running"]:
        raise RuntimeError("Expected native container is not running")
    raw = read(container, "awg0.conf")
    live = command(["docker", "exec", container, "awg", "showconf", "awg0"]).stdout
    if canonical(raw) != canonical(live):
        raise RuntimeError("Native persisted and runtime identities disagree")
    return {"containerId": item["Id"], "startedAt": item["State"]["StartedAt"], "restartCount": item["RestartCount"],
            "identitySha256": identity(raw), "peerCount": len(parse(raw)[1])}


def snapshot(request):
    origin, container = request["origin"], request["sourceId"]
    if not re.fullmatch("[a-z][a-z0-9-]*", origin) or not re.fullmatch("[a-f0-9]{64}", container):
        raise RuntimeError("Invalid fixed source authority")
    before = invariant(container)
    item = inspect(container)
    values = {name: read(container, name) for name in FILES}
    if invariant(container) != before or any(read(container, name) != value for name, value in values.items()):
        raise RuntimeError("Native source changed during snapshot")
    source = ROOT / "sources" / origin
    source.mkdir(mode=0o700, parents=True, exist_ok=True)
    state_path = source / "state.json"
    previous = json.loads(state_path.read_text()) if state_path.exists() else {}
    public_hash = sha(values["wireguard_server_public_key.key"].strip())
    if previous and (previous["sourceId"] != container or previous["serverPublicKeySha256"] != public_hash):
        raise RuntimeError("Native source authority changed")
    payload_hash = sha(json.dumps({name: sha(value) for name, value in values.items()}, sort_keys=True).encode())
    if previous.get("payloadSha256") == payload_hash and previous.get("imageConfigurationId") and previous.get("imageRuntimeConfigurationSha256") and previous.get("scopeSha256") and not request.get("forceNewGeneration"):
        return json.loads(Path(previous["manifestPath"]).read_text())
    revision = previous.get("revision", 0) + 1
    destination = source / str(revision)
    destination.mkdir(mode=0o700, parents=True, exist_ok=False)
    for name, value in values.items():
        write(destination / name, value)
    image_id = item["Image"]
    image = ROOT / "images" / (image_id.split(":", 1)[1] + ".tar")
    if not image.exists():
        image.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
        result = command(["docker", "image", "save", "--output", str(image) + ".new", image_id])
        temporary = Path(str(image) + ".new")
        temporary.chmod(0o600)
        if temporary.stat().st_size > 128 * 1024 * 1024:
            raise RuntimeError("Native image exceeds transfer bound")
        temporary.replace(image)
    with tarfile.open(image, "r:") as archive:
        image_manifests = json.load(archive.extractfile("manifest.json"))
        if len(image_manifests) != 1:
            raise RuntimeError("Native image archive contains multiple images")
        config_blob = archive.extractfile(image_manifests[0]["Config"]).read(2 * 1024 * 1024 + 1)
        if len(config_blob) > 2 * 1024 * 1024:
            raise RuntimeError("Native image configuration is oversized")
        configuration_id = "sha256:" + sha(config_blob)
    image_metadata = json.loads(command(["docker", "image", "inspect", image_id]).stdout)[0]
    interface, peers = parse(values["awg0.conf"])
    mtu = int(command(["docker", "exec", container, "cat", "/sys/class/net/awg0/mtu"]).stdout)
    manifest = {"schema": 1, "origin": origin, "sourceId": container, "epoch": 1, "revision": revision,
                "serverPublicKeySha256": public_hash, "payloadSha256": payload_hash,
                "identitySha256": identity(values["awg0.conf"]), "peerCount": len(peers),
                "scope": origin + "-v1", "scopeSha256": sha(json.dumps({"scope": origin + "-v1", **canonical(values["awg0.conf"])}, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode()),
                "imageId": image_id, "imagePath": str(image), "imageSha256": sha(image.read_bytes()),
                "imageConfigurationId": configuration_id, "imageRootFSLayers": image_metadata["RootFS"]["Layers"],
                "imageArchitecture": image_metadata["Architecture"], "imageOS": image_metadata["Os"],
                "imageRuntimeConfigurationSha256": sha(json.dumps(image_metadata["Config"], sort_keys=True, separators=(",", ":")).encode()),
                "files": {name: sha(value) for name, value in values.items()}, "directory": str(destination),
                "port": int(interface["ListenPort"]), "address": interface["Address"], "mtu": mtu,
                "createdAt": int(time.time()), "manifestPath": str(destination / "manifest.json")}
    document(destination / "container.private.json", item)
    document(destination / "manifest.json", manifest)
    document(state_path, manifest)
    return manifest


def health_peer(request):
    origin, container = request["origin"], request["sourceId"]
    before = invariant(container)
    path = ROOT / "health" / (origin + ".private.json")
    if path.exists():
        probe = json.loads(path.read_text())
        if probe["sourceId"] != container or not any(peer.get("PublicKey") == probe["publicKey"] for peer in parse(read(container, "awg0.conf"))[1]):
            raise RuntimeError("Previously created health identity has drifted")
        return {"origin": origin, "healthIdentityExists": True, "peerCount": before["peerCount"], "profilePath": str(path)}
    backup = snapshot(request)
    raw = read(container, "awg0.conf")
    table_raw = read(container, "clientsTable")
    interface, peers = parse(raw)
    network = ipaddress.ip_interface(interface["Address"]).network
    used = [ipaddress.ip_network(part.strip(), strict=False) for peer in peers for part in peer["AllowedIPs"].split(",")]
    address = next((str(value) for value in reversed(list(network.hosts()))
                    if value != ipaddress.ip_interface(interface["Address"]).ip and not any(value in item for item in used)), None)
    if address is None:
        raise RuntimeError("No unused native health address")
    private = command(["docker", "exec", container, "awg", "genkey"]).stdout.strip()
    public = command(["docker", "exec", "-i", container, "awg", "pubkey"], private + b"\n").stdout.strip()
    psk = command(["docker", "exec", container, "awg", "genpsk"]).stdout.strip()
    probe = {"origin": origin, "sourceId": container, "privateKey": private.decode(), "publicKey": public.decode(),
             "presharedKey": psk.decode(), "address": address + "/32", "serverPublicKey": read(container, "wireguard_server_public_key.key").decode().strip(),
             "interface": {key: value for key, value in interface.items() if key != "PrivateKey"}, "backupManifest": backup["manifestPath"]}
    document(path, probe)
    updated = raw.rstrip() + ("\n\n# Dedicated UnifiedVPN HA health peer " + origin + "\n[Peer]\nPublicKey = " + public.decode() + "\nPresharedKey = " + psk.decode() + "\nAllowedIPs = " + address + "/32\n").encode()
    table = json.loads(table_raw)
    table.append({"clientId": public.decode(), "userData": {"clientName": "UnifiedVPN HA health " + origin, "allowed_ips": address + "/32", "creationDate": time.strftime("%Y-%m-%d")}})
    try:
        command(["docker", "exec", "-i", container, "sh", "-c", "umask 077; cat > " + DIRECTORY + "awg0.conf"], updated)
        command(["docker", "exec", "-i", container, "sh", "-c", "umask 077; cat > " + DIRECTORY + "clientsTable"], json.dumps(table).encode())
        command(["docker", "exec", "-i", container, "awg", "set", "awg0", "peer", public.decode(), "preshared-key", "/dev/stdin", "allowed-ips", address + "/32"], psk + b"\n")
        after = invariant(container)
        if any(after[key] != before[key] for key in ("containerId", "startedAt", "restartCount")) or after["peerCount"] != before["peerCount"] + 1:
            raise RuntimeError("Native health addition violated preservation checks")
        if not set(peer["PublicKey"] for peer in peers) <= set(peer["PublicKey"] for peer in parse(updated)[1]):
            raise RuntimeError("Native original peer set changed")
    except Exception:
        command(["docker", "exec", container, "awg", "set", "awg0", "peer", public.decode(), "remove"], check=False)
        command(["docker", "exec", "-i", container, "sh", "-c", "cat > " + DIRECTORY + "awg0.conf"], raw)
        command(["docker", "exec", "-i", container, "sh", "-c", "cat > " + DIRECTORY + "clientsTable"], table_raw)
        raise
    return {"origin": origin, "healthIdentityExists": True, "peerCount": after["peerCount"], "profilePath": str(path), "snapshot": snapshot(request)}


def prepare_old(request):
    container = request["sourceId"]
    preserved = "amnezia-awg2-protocol2-preserved"
    marker = ROOT / "old-name-migration.json"
    if marker.exists():
        previous = json.loads(marker.read_text())
        if inspect(preserved)["Id"] != container:
            raise RuntimeError("Preserved native container identity drifted")
        return previous
    preserved_item = inspect(preserved)
    if inspect("amnezia-awg2") is None and preserved_item and preserved_item["Id"] == container:
        command(["docker", "rename", container, "amnezia-awg2"])
    current = inspect("amnezia-awg2")
    if current["Id"] != container:
        raise RuntimeError("Old official native container is outside rename authority")
    before = invariant(container)
    snapshot(request)
    backup = ROOT / ("old-name-migration-before-" + str(time.time_ns()))
    backup.mkdir(mode=0o700, parents=True, exist_ok=False)
    sources = {Path("/opt/unifiedvpn-control/existing.py"): None, Path("/opt/unifiedvpn-cascade/controller.py"): None}
    for source in sources:
        sources[source] = source.read_bytes()
        write(backup / source.name, sources[source])
    existing = sources[Path("/opt/unifiedvpn-control/existing.py")].decode()
    old_line = " 'amnezia-awg2':('awg','awg0','/opt/amnezia/awg/awg0.conf'),"
    if existing.count(old_line) != 1 or existing.count("if name=='amnezia-awg2':") != 1:
        raise RuntimeError("Existing native administration source changed")
    existing = existing.replace(old_line, old_line + "\n '" + preserved + "':('awg','awg0','/opt/amnezia/awg/awg0.conf'),")
    existing = existing.replace("if name=='amnezia-awg2':", "if name in ('amnezia-awg2','" + preserved + "'):")
    cascade = sources[Path("/opt/unifiedvpn-cascade/controller.py")].decode()
    old_loop = 'for name in ["amnezia-awg2", "unifiedvpn-awg3"]:'
    if cascade.count(old_loop) != 1:
        raise RuntimeError("Cascade classifier source changed")
    cascade = cascade.replace(old_loop, 'for name in ["amnezia-awg2", "amnezia-awg2-protocol2-preserved", "unifiedvpn-awg3"]:')
    compile(existing, "existing.py", "exec")
    compile(cascade, "controller.py", "exec")
    database = Path("/var/lib/unifiedvpn-control/control.db")
    with sqlite3.connect(database) as source, sqlite3.connect(backup / "control.db") as destination:
        source.backup(destination)
    (backup / "control.db").chmod(0o600)
    stopped = False
    renamed = False
    try:
        command(["systemctl", "stop", "unifiedvpn-control"])
        stopped = True
        changed = []
        with sqlite3.connect(database) as connection:
            connection.execute("begin immediate")
            for profile_id, locator, payload in connection.execute("select id,locator,payload from existing_profiles where kind='awg'").fetchall():
                value = json.loads(payload)
                if value.get("container") != "amnezia-awg2":
                    continue
                if not locator.startswith("amnezia-awg2:"):
                    raise RuntimeError("Native existing-profile locator is inconsistent")
                value["container"] = preserved
                connection.execute("update existing_profiles set locator=?,payload=? where id=?", (preserved + locator[len("amnezia-awg2"):], json.dumps(value), profile_id))
                changed.append(profile_id)
            if connection.execute("pragma integrity_check").fetchone()[0] != "ok":
                raise RuntimeError("Control database integrity check failed")
        write("/opt/unifiedvpn-control/existing.py", existing.encode(), os.stat("/opt/unifiedvpn-control/existing.py").st_mode & 0o777)
        write("/opt/unifiedvpn-cascade/controller.py", cascade.encode(), os.stat("/opt/unifiedvpn-cascade/controller.py").st_mode & 0o777)
        command(["docker", "rename", container, preserved])
        renamed = True
        if invariant(container) != before:
            raise RuntimeError("Old native rename changed its running identity")
        command(["systemctl", "start", "unifiedvpn-control"])
        stopped = False
        controller_loaded = command(["systemctl", "show", "unifiedvpn-cascade-controller.service", "--property=LoadState", "--value"]).stdout.strip() == b"loaded"
        if controller_loaded:
            command(["systemctl", "restart", "unifiedvpn-cascade-controller.service"])
        result = {"sourceId": container, "preservedName": preserved, "profilesMoved": len(changed), "before": before, "after": invariant(container), "backup": str(backup), "classifierRestarted": controller_loaded}
        document(marker, result)
        return result
    except Exception:
        if renamed and inspect("amnezia-awg2") is None:
            command(["docker", "rename", container, "amnezia-awg2"], check=False)
        for source, original in sources.items():
            write(source, original, os.stat(source).st_mode & 0o777)
        with sqlite3.connect(backup / "control.db") as source, sqlite3.connect(database) as destination:
            source.backup(destination)
        command(["systemctl", "start", "unifiedvpn-control"], check=False)
        if command(["systemctl", "show", "unifiedvpn-cascade-controller.service", "--property=LoadState", "--value"], check=False).stdout.strip() == b"loaded":
            command(["systemctl", "restart", "unifiedvpn-cascade-controller.service"], check=False)
        raise


def apply_snapshot(request):
    origin = request["origin"]
    incoming = ROOT / "incoming" / origin
    manifest = json.loads((incoming / "manifest.json").read_text())
    if manifest["origin"] != origin or manifest["sourceId"] != request["sourceId"] or manifest["serverPublicKeySha256"] != request["serverPublicKeySha256"]:
        raise RuntimeError("Replica snapshot violates fixed source authority")
    defaults = {"primary-native": "unifiedvpn-native-primary-ha", "old-native": "unifiedvpn-native-old-ha", "old-native-v31": "unifiedvpn-native-old-v31-ha"}
    if origin not in defaults:
        raise RuntimeError("Unknown native origin")
    name = request.get("targetName", defaults[origin])
    if name not in {"amnezia-awg2", "unifiedvpn-native-primary-ha", "unifiedvpn-native-old-ha", "unifiedvpn-native-old-v31-ha"}:
        raise RuntimeError("Native target name is outside its owned allowlist")
    network = "unifiedvpn-native-" + origin + "-ha-net"
    state_path = ROOT / "replicas" / (origin + ".json")
    previous = json.loads(state_path.read_text()) if state_path.exists() else None
    item = inspect(name)
    if item and (item["Config"].get("Labels") or {}).get(LABEL) != origin:
        raise RuntimeError("Target native container name is already independently owned")
    if previous:
        recover_pending(request, manifest, state_path, name)
        previous = json.loads(state_path.read_text())
    if previous:
        if manifest["epoch"] != previous["epoch"] or manifest["revision"] < previous["revision"]:
            raise RuntimeError("Stale native generation rejected")
        if invariant(name)["identitySha256"] != previous["identitySha256"]:
            raise RuntimeError("Replica has independent peer changes; synchronization held")
        if any(sha(read(name, filename)) != checksum for filename, checksum in previous["files"].items()):
            raise RuntimeError("Replica has independent file changes; synchronization held")
        if manifest["revision"] == previous["revision"]:
            if manifest["payloadSha256"] != previous["payloadSha256"]:
                raise RuntimeError("Same native revision has a different digest")
            return {"origin": origin, "alreadyApplied": True, "manifest": previous, "live": invariant(name)}
    files = {name: (incoming / name).read_bytes() for name in FILES}
    if any(sha(value) != manifest["files"][name] for name, value in files.items()):
        raise RuntimeError("Native transferred configuration digest mismatch")
    if identity(files["awg0.conf"]) != manifest["identitySha256"]:
        raise RuntimeError("Native transferred identity digest mismatch")
    if previous:
        active = Path(previous["activeDirectory"])
        old_files = {filename: (active / filename).read_bytes() for filename in FILES}
        old_runtime = runtime_configuration(old_files["awg0.conf"])
        if (canonical(old_files["awg0.conf"])["interface"] != canonical(files["awg0.conf"])["interface"]
                or previous["imageId"] != manifest["imageId"] or previous["address"] != manifest["address"]
                or previous["mtu"] != manifest["mtu"]):
            raise RuntimeError("Native protocol or interface change needs explicit maintenance")
        before = invariant(name)
        backup = ROOT / "replica-backups" / origin / (str(previous["revision"]) + "-" + str(time.time_ns()))
        for filename, value in old_files.items():
            write(backup / filename, value)
        document(backup / "manifest.json", previous)
        journal = ROOT / "replicas" / (origin + ".pending.json")
        document(journal, {"before": previous, "next": manifest, "backup": str(backup),
                           "runtimeSha256": {"before": sha(old_runtime), "next": sha(runtime_configuration(files["awg0.conf"]))}})
        try:
            command(["docker", "exec", "-i", name, "awg", "syncconf", "awg0", "/dev/stdin"], runtime_configuration(files["awg0.conf"]))
            for filename, value in files.items():
                write(active / filename, value)
            replace_bound_runtime(active / "runtime.conf", runtime_configuration(files["awg0.conf"]))
            live = invariant(name)
            if live["identitySha256"] != manifest["identitySha256"] or any(live[key] != before[key] for key in ("containerId", "startedAt", "restartCount")):
                raise RuntimeError("In-place native synchronization failed its invariants")
            applied = {**manifest, "targetName": name, "targetId": live["containerId"], "appliedAt": int(time.time()), "activeDirectory": str(active)}
            document(state_path, applied)
            journal.unlink()
            return {"origin": origin, "manifest": applied, "live": live, "inPlaceSync": True, "originalContainerLifecyclePreserved": True}
        except Exception:
            command(["docker", "exec", "-i", name, "awg", "syncconf", "awg0", "/dev/stdin"], old_runtime, check=False)
            for filename, value in old_files.items():
                write(active / filename, value)
            replace_bound_runtime(active / "runtime.conf", old_runtime)
            if invariant(name)["identitySha256"] == previous["identitySha256"]:
                journal.unlink()
            raise
    image = ROOT / "images" / (manifest["imageId"].split(":", 1)[1] + ".tar")
    def image_matches(value):
        return (value["RootFS"]["Layers"] == manifest["imageRootFSLayers"]
                and value["Architecture"] == manifest["imageArchitecture"] and value["Os"] == manifest["imageOS"]
                and sha(json.dumps(value["Config"], sort_keys=True, separators=(",", ":")).encode()) == manifest["imageRuntimeConfigurationSha256"])
    launch_image = None
    for candidate in (manifest["imageId"], manifest["imageConfigurationId"]):
        result = command(["docker", "image", "inspect", candidate], check=False)
        if result.returncode == 0 and image_matches(json.loads(result.stdout)[0]):
            launch_image = candidate
            break
    if launch_image is None:
        if not image.is_file() or sha(image.read_bytes()) != manifest["imageSha256"] or image.stat().st_size > 128 * 1024 * 1024:
            raise RuntimeError("Native transferred image is unverified")
        command(["docker", "image", "load", "-i", str(image)])
        candidates = command(["docker", "image", "ls", "--no-trunc", "-aq"]).stdout.decode().split()
        matching = [value["Id"] for value in json.loads(command(["docker", "image", "inspect", *sorted(set(candidates))]).stdout) if image_matches(value)]
        if not matching:
            raise RuntimeError("Loaded native image has no content-equivalent reference")
        launch_image = sorted(matching)[0]
    loaded_image = json.loads(command(["docker", "image", "inspect", launch_image]).stdout)[0]
    if (loaded_image["RootFS"]["Layers"] != manifest["imageRootFSLayers"]
            or loaded_image["Architecture"] != manifest["imageArchitecture"] or loaded_image["Os"] != manifest["imageOS"]):
        raise RuntimeError("Native image content differs across Docker backends")
    active = ROOT / "active" / origin
    subnet = str(ipaddress.ip_interface(manifest["address"]).network)
    script = ("#!/bin/sh\nset -eu\numask 077\namneziawg-go awg0\n"
              "awg setconf awg0 /run/secrets/runtime.conf\n"
              f"ip address add {manifest['address']} dev awg0\nip link set mtu {manifest['mtu']} up dev awg0\n"
              "iptables -t nat -N UVPN_NATIVE_HA_NAT\n"
              f"iptables -t nat -A UVPN_NATIVE_HA_NAT -s {subnet} -o eth0 -j MASQUERADE\n"
              "iptables -t nat -A POSTROUTING -j UVPN_NATIVE_HA_NAT\n"
              "iptables -N UVPN_NATIVE_HA_FWD\niptables -A UVPN_NATIVE_HA_FWD -i awg0 -j ACCEPT\n"
              "iptables -A UVPN_NATIVE_HA_FWD -o awg0 -m conntrack --ctstate RELATED,ESTABLISHED -j ACCEPT\n"
              "iptables -A FORWARD -j UVPN_NATIVE_HA_FWD\nexec tail -f /dev/null\n")
    prepare_initial_stage(origin, manifest, active, files, runtime_configuration(files["awg0.conf"]), script.encode())
    if item:
        if not item["State"]["Running"]:
            mounts = item.get("Mounts") or item.get("HostConfig", {}).get("Mounts") or []
            mounted = {mount.get("Destination", mount.get("Target")): mount["Source"] for mount in mounts}
            if item["Image"] != loaded_image["Id"] or mounted.get("/opt/amnezia/awg") != str(active):
                raise RuntimeError("Inactive initial native container has different ownership")
            command(["docker", "rm", item["Id"]])
            item = None
        else:
            live = invariant(name)
            if live["identitySha256"] != manifest["identitySha256"]:
                raise RuntimeError("Initial native container has independent runtime changes")
            applied = {**manifest, "targetName": name, "targetId": live["containerId"],
                       "appliedAt": int(time.time()), "activeDirectory": str(active), "initialStageRecovered": True}
            document(state_path, applied)
            return {"origin": origin, "manifest": applied, "live": live, "originalContainerLifecyclePreserved": True}
    all_ids = command(["docker", "ps", "-q"]).stdout.decode().split()
    originals = {container: inspect(container) for container in all_ids}
    before = {container: {"id": value["Id"], "started": value["State"]["StartedAt"], "restarts": value["RestartCount"]} for container, value in originals.items()}
    network_created = False
    container_created = False
    try:
        bridge_subnet = ipaddress.IPv4Network(request.get("networkSubnet", "172.30.201.0/28"))
        if not bridge_subnet.is_private or bridge_subnet.prefixlen != 28:
            raise RuntimeError("Native replica network must be a private /28")
        existing_network = command(["docker", "network", "inspect", network], check=False)
        if existing_network.returncode == 0:
            value = json.loads(existing_network.stdout)[0]
            subnets = [entry.get("Subnet") for entry in (value.get("IPAM", {}).get("Config") or [])]
            if (value.get("Labels") or {}).get(LABEL) != origin or str(bridge_subnet) not in subnets or value.get("Containers"):
                raise RuntimeError("Initial native network has different ownership or attachments")
        else:
            command(["docker", "network", "create", "--label", LABEL + "=" + origin, "--subnet", str(bridge_subnet), network])
            network_created = True
        command(["docker", "run", "-d", "--name", name, "--label", LABEL + "=" + origin, "--restart", "unless-stopped",
                 "--memory", "160m", "--pids-limit", "64", "--cap-add", "NET_ADMIN", "--device", "/dev/net/tun",
                 "--sysctl", "net.ipv4.ip_forward=1", "--network", network,
                 "-p", f"{manifest['port']}:{manifest['port']}/udp",
                 "--mount", "type=bind,source=" + str(active) + ",target=/opt/amnezia/awg",
                 "--mount", "type=bind,source=" + str(active / "runtime.conf") + ",target=/run/secrets/runtime.conf,readonly",
                 "--mount", "type=bind,source=" + str(active / "entry.sh") + ",target=/entry.sh,readonly",
                 "--entrypoint", "/bin/sh", launch_image, "/entry.sh"])
        container_created = True
        deadline = time.monotonic() + 20
        while True:
            try:
                live = invariant(name)
                if live["identitySha256"] != manifest["identitySha256"]:
                    raise RuntimeError("Native clone identity mismatch")
                break
            except RuntimeError:
                if time.monotonic() >= deadline:
                    raise
                time.sleep(0.5)
        for container, expected in before.items():
            value = inspect(container)
            actual = {"id": value["Id"], "started": value["State"]["StartedAt"], "restarts": value["RestartCount"]}
            if actual != expected:
                raise RuntimeError("Original container lifecycle changed during replica deployment")
        applied = {**manifest, "targetName": name, "targetId": live["containerId"], "appliedAt": int(time.time()), "activeDirectory": str(active)}
        document(state_path, applied)
        return {"origin": origin, "manifest": applied, "live": live, "originalContainerLifecyclePreserved": True}
    except Exception:
        own = inspect(name)
        if container_created and own and own["Config"].get("Labels", {}).get(LABEL) == origin:
            command(["docker", "rm", "-f", own["Id"]], check=False)
        if network_created:
            command(["docker", "network", "rm", network], check=False)
        raise


def replica_status(request):
    path = ROOT / "replicas" / (request["origin"] + ".json")
    manifest = json.loads(path.read_text())
    if manifest["sourceId"] != request["sourceId"] or manifest["serverPublicKeySha256"] != request["serverPublicKeySha256"]:
        raise RuntimeError("Replica status violates fixed source authority")
    live = invariant(manifest["targetName"])
    if live["identitySha256"] != manifest["identitySha256"] or live["containerId"] != manifest["targetId"]:
        raise RuntimeError("Replica has independent peer or container changes; synchronization held")
    if any(sha(read(manifest["targetName"], filename)) != checksum for filename, checksum in manifest["files"].items()):
        raise RuntimeError("Replica has independent file changes; synchronization held")
    return {"manifest": manifest, "live": live}


def main():
    import fcntl
    os.umask(0o077)
    ROOT.mkdir(mode=0o700, parents=True, exist_ok=True)
    request = json.loads(Path(sys.argv[2]).read_text())
    action = sys.argv[1]
    with (ROOT / "worker.lock").open("a") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        result = {"snapshot": snapshot, "health": health_peer, "prepare-old": prepare_old, "apply": apply_snapshot, "status": replica_status}[action](request)
    print(json.dumps(result))


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        write(ROOT / "last-error.private.txt", traceback.format_exc().encode())
        print(json.dumps({"ok": False, "reason": "native_ha_guard_rejected", "errorType": type(error).__name__,
                          "guardReason": str(error) if isinstance(error, RuntimeError) else "private_diagnostics_saved"}))
        raise SystemExit(1) from None
