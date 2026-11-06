"""Replicate one fixed-authority classic AWG identity without changing its writer."""
import argparse
import base64
import hashlib
import ipaddress
import json
import math
import os
from pathlib import Path
import re
import secrets
import shlex
import subprocess
import sys
import time

from controller import atomic_json, exclusive_state, trusted_json
from scope_verify import awg_configuration, digest

RAW_LIMIT = 512 * 1024
RPC_LIMIT = 1024 * 1024
INTERFACE_FIELDS = {"PrivateKey", "ListenPort", "Jc", "Jmin", "Jmax", "S1", "S2", "H1", "H2", "H3", "H4"}
PEER_FIELDS = {"PublicKey", "PresharedKey", "AllowedIPs", "PersistentKeepalive", "Endpoint"}


def sha(raw):
    return hashlib.sha256(raw).hexdigest()


def command(arguments, raw=None):
    result = subprocess.run(arguments, input=raw, capture_output=True, timeout=45)
    if result.returncode or len(result.stdout) > RPC_LIMIT:
        raise RuntimeError("Classic bounded operation failed")
    return result.stdout


def classic_configuration(text):
    """Normalize only the default header spellings verified with dedicated QA."""
    peer = False
    for line in text.splitlines():
        line = line.strip()
        if line == "[Peer]":
            peer = True
        elif line == "[Interface]":
            peer = False
        elif "=" in line and not line.startswith("#"):
            key = line.split("=", 1)[0].strip()
            if key not in (PEER_FIELDS if peer else INTERFACE_FIELDS | {"Address"}):
                raise ValueError("Classic configuration has an unscoped field")
    value = awg_configuration(text)
    for number in range(1, 5):
        key = "H" + str(number)
        if value["interface"].get(key) in (None, "0", str(number)):
            value["interface"][key] = str(number)
    keys = [peer["PublicKey"] for peer in value["peers"]]
    networks = [ipaddress.ip_network(address.strip(), strict=False) for peer in value["peers"] for address in peer["AllowedIPs"].split(",")]
    if len(keys) != len(set(keys)) or any(a.version == b.version and a.overlaps(b) for i, a in enumerate(networks) for b in networks[i + 1:]):
        raise ValueError("Classic peer identities or routes overlap")
    return value


def runtime_configuration(raw):
    classic_configuration(raw.decode())
    peer = False
    lines = []
    for line in raw.decode().splitlines(keepends=True):
        if line.strip() == "[Peer]":
            peer = True
        if not peer and line.split("=", 1)[0].strip() == "Address":
            continue
        lines.append(line)
    return "".join(lines).encode()


def quick_address(text):
    header = text.split("[Peer]", 1)[0]
    values = re.findall(r"(?m)^\s*Address\s*=\s*([^\r\n]+)", header)
    if len(values) != 1:
        raise ValueError("Classic persisted tunnel address is ambiguous")
    address = ipaddress.ip_interface(values[0].strip())
    if address.version != 4:
        raise ValueError("Classic tunnel requires its fixed IPv4 address")
    return str(address)


def scope_hash(scope, raw, mtu):
    return digest({"scope": scope, "tunnel_address": quick_address(raw.decode()), "mtu": mtu, **classic_configuration(raw.decode())})


def route_guard(container, raw, address, mtu):
    if quick_address(raw.decode()) != address:
        raise RuntimeError("Classic persisted tunnel address changed")
    values = json.loads(command(["docker", "exec", container, "ip", "-j", "address", "show", "dev", "wg0"]))
    if len(values) != 1 or values[0]["mtu"] != mtu:
        raise RuntimeError("Classic tunnel MTU changed")
    actual = {str(ipaddress.ip_interface(str(item["local"]) + "/" + str(item["prefixlen"]))) for item in values[0].get("addr_info", []) if item.get("family") == "inet"}
    if actual != {address}:
        raise RuntimeError("Classic live tunnel address changed")


def verify_classic(spec):
    """Read-only verifier used by the readiness producer's classic scope."""
    container = spec["container"]
    if not re.fullmatch(r"[A-Za-z0-9_.-]+", container) or spec["config_path"] != "/opt/amnezia/awg/wg0.conf":
        raise ValueError("Classic verifier resource is outside its allowlist")
    saved = command(["docker", "exec", container, "cat", spec["config_path"]])
    live = command(["docker", "exec", container, "wg", "showconf", "wg0"])
    if classic_configuration(saved.decode()) != classic_configuration(live.decode()):
        raise ValueError("Classic persisted and runtime identities differ")
    route_guard(container, saved, spec["tunnel_address"], spec["mtu"])
    return scope_hash(spec["scope"], saved, spec["mtu"])


def replace_bound(path, raw):
    path = Path(path)
    if path.is_symlink() or not path.is_file():
        raise RuntimeError("Classic bound configuration is not a regular file")
    with path.open("r+b") as stream:
        stream.write(raw)
        stream.truncate()
        stream.flush()
        os.fsync(stream.fileno())


def authority(config):
    return {key: config[key] for key in ("scope", "source_machine_id", "source_container_id", "server_public_key_sha256", "interface_sha256", "tunnel_address", "mtu")}


def guard(config):
    if Path("/etc/machine-id").read_text().strip() != config["machine_id"]:
        raise RuntimeError("Classic node authority changed")
    spec = config["resource"]
    value = json.loads(command(["docker", "inspect", spec["container"]]))[0]
    if value["Id"] != spec["container_id"] or not value["State"]["Running"] or value["Image"] != spec["image_id"]:
        raise RuntimeError("Classic container authority changed")
    if set(value["NetworkSettings"]["Networks"]) != {spec["network"]}:
        raise RuntimeError("Classic container network attachments changed")
    mounts = value["Mounts"]
    mount = [item for item in mounts if item["Destination"] == "/opt/amnezia/awg"]
    if len(mount) != 1 or mount[0]["Source"] != spec["mount_source"] or mount[0]["Type"] != "bind" or mount[0]["RW"] is not config["source"]:
        raise RuntimeError("Classic configuration mount changed")
    if Path(spec["config_path"]) != Path(spec["mount_source"]) / "wg0.conf":
        raise RuntimeError("Classic configuration path changed")
    if config["source"]:
        if config["machine_id"] != config["source_machine_id"] or value["Id"] != config["source_container_id"]:
            raise RuntimeError("Classic fixed source changed")
    else:
        host = value["HostConfig"]
        if (len(mounts) != 1 or value["Config"].get("Labels", {}).get("org.unifiedvpn.classic-ha") != "classic-old"
                or host["NetworkMode"] != spec["network"] or host["Privileged"] or not host["ReadonlyRootfs"]
                or {v.removeprefix("CAP_") for v in host.get("CapAdd") or []} != {"NET_ADMIN", "NET_RAW"}
                or {v.removeprefix("CAP_") for v in host.get("CapDrop") or []} != {"ALL"}
                or host["Memory"] != 128 * 1024 * 1024 or host["PidsLimit"] != 64
                or host["NanoCpus"] != 500000000 or set(host.get("SecurityOpt") or []) != {"no-new-privileges:true"}
                or host["PortBindings"] != {"55424/udp": [{"HostIp": "0.0.0.0", "HostPort": "55424"}]}):
            raise RuntimeError("Classic replica sandbox changed")
    public = command(["docker", "exec", spec["container"], "wg", "show", "wg0", "public-key"]).strip()
    if sha(public) != config["server_public_key_sha256"]:
        raise RuntimeError("Classic server identity changed")
    return value


def read_pair(config):
    guard(config)
    spec = config["resource"]
    path = Path(spec["config_path"])
    if path.is_symlink() or not path.is_file() or path.stat().st_size > RAW_LIMIT:
        raise RuntimeError("Classic source configuration is unbounded")
    raw = path.read_bytes()
    parsed = classic_configuration(raw.decode())
    live = command(["docker", "exec", spec["container"], "wg", "showconf", "wg0"])
    if parsed != classic_configuration(live.decode()) or digest(parsed["interface"]) != config["interface_sha256"]:
        raise RuntimeError("Classic persisted, runtime or fixed interface differs")
    route_guard(spec["container"], raw, config["tunnel_address"], config["mtu"])
    return raw


def validate(config, payload):
    manifest = payload["manifest"]
    if any(manifest.get(key) != value for key, value in authority(config).items()) or type(manifest.get("epoch")) is not int or manifest["epoch"] != 1:
        raise RuntimeError("Classic source authority does not match")
    if type(manifest.get("revision")) is not int or manifest["revision"] < 1:
        raise RuntimeError("Classic generation is invalid")
    raw = base64.b64decode(payload["configuration"], validate=True)
    if len(raw) > RAW_LIMIT or sha(raw) != manifest["payload_sha256"] or scope_hash(config["scope"], raw, config["mtu"]) != manifest["sha256"]:
        raise RuntimeError("Classic payload digest changed")
    parsed = classic_configuration(raw.decode())
    if (digest(parsed["interface"]) != config["interface_sha256"] or len(parsed["peers"]) != manifest["peer_count"]
            or quick_address(raw.decode()) != config["tunnel_address"]):
        raise RuntimeError("Classic interface or peer identity changed")
    return raw


def snapshot(config):
    if config["source"] is not True:
        raise RuntimeError("Replica cannot publish classic generations")
    raw = read_pair(config)
    path = Path(config["state_root"]) / "source.json"
    old = trusted_json(path, private=True) if path.exists() else {}
    if old and (any(old.get(key) != value for key, value in authority(config).items())
                or type(old.get("epoch")) is not int or old["epoch"] != 1
                or type(old.get("revision")) is not int or old["revision"] < 1):
        raise RuntimeError("Classic source generation lineage changed")
    revision = old.get("revision", 0) + (old.get("payload_sha256") != sha(raw))
    manifest = {**authority(config), "epoch": 1, "revision": revision, "payload_sha256": sha(raw),
                "sha256": scope_hash(config["scope"], raw, config["mtu"]), "peer_count": len(classic_configuration(raw.decode())["peers"])}
    atomic_json(path, manifest)
    return {"manifest": manifest, "configuration": base64.b64encode(raw).decode()}


def status(config):
    if config["source"] is not False:
        raise RuntimeError("Source cannot impersonate a classic replica")
    raw = read_pair(config)
    manifest = trusted_json(Path(config["state_root"]) / "applied.json", private=True)
    validate(config, {"manifest": manifest, "configuration": base64.b64encode(raw).decode()})
    return {"manifest": manifest, "checked_at": time.time()}


def runtime_apply(config, raw):
    command(["docker", "exec", "-i", config["resource"]["container"], "wg", "syncconf", "wg0", "/dev/stdin"], runtime_configuration(raw))


def atomic_source_configuration(config, raw):
    path = Path(config["resource"]["config_path"])
    if not config["source"] or path.is_symlink() or not path.is_file():
        raise RuntimeError("Classic source file ownership changed")
    temporary = path.with_name(path.name + ".classic-" + secrets.token_hex(8))
    descriptor = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    try:
        with os.fdopen(descriptor, "wb") as stream:
            stream.write(raw)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
        if os.name == "posix":
            directory = os.open(path.parent, os.O_RDONLY | os.O_DIRECTORY)
            try:
                os.fsync(directory)
            finally:
                os.close(directory)
    finally:
        temporary.unlink(missing_ok=True)


def source_recover(config, journal_path):
    journal = trusted_json(journal_path, private=True)
    if config["source"] is not True or journal["authority"] != authority(config):
        raise RuntimeError("Classic source mutation journal changed authority")
    before = base64.b64decode(journal["before"], validate=True)
    after = base64.b64decode(journal["after"], validate=True)
    if sha(before) != journal["before_sha256"] or sha(after) != journal["after_sha256"]:
        raise RuntimeError("Classic source mutation journal changed content")
    for raw in (before, after):
        parsed = classic_configuration(raw.decode())
        if (len(raw) > RAW_LIMIT or digest(parsed["interface"]) != config["interface_sha256"]
                or quick_address(raw.decode()) != config["tunnel_address"]):
            raise RuntimeError("Classic source mutation changed its fixed interface")
    guard(config)
    saved = Path(config["resource"]["config_path"]).read_bytes()
    live = command(["docker", "exec", config["resource"]["container"], "wg", "showconf", "wg0"])
    if sha(saved) not in {sha(before), sha(after)} or classic_configuration(live.decode()) not in (classic_configuration(before.decode()), classic_configuration(after.decode())):
        raise RuntimeError("Classic source mutation contains independent changes")
    completed = sha(saved) == sha(after) and classic_configuration(live.decode()) == classic_configuration(after.decode())
    selected = after if completed else before
    runtime_apply(config, selected)
    atomic_source_configuration(config, selected)
    if read_pair(config) != selected:
        raise RuntimeError("Classic source recovery did not verify")
    journal_path.unlink()


def control_peer(config, payload):
    """Apply one old-control peer mutation using every pinned interface parameter."""
    if config["source"] is not True or payload["container"] != config["resource"]["container"] or type(payload["enabled"]) is not bool:
        raise RuntimeError("Classic control mutation is outside its fixed writer")
    journal = Path(config["state_root"]) / "source-peer.pending.json"
    if journal.exists():
        source_recover(config, journal)
    raw = read_pair(config)
    text = raw.decode()
    matches = list(re.finditer(r"(?m)^\s*\[Peer\][ \t]*\r?$", text))
    blocks = []
    for index, match in enumerate(matches):
        end = matches[index + 1].start() if index + 1 < len(matches) else len(text)
        block = text[match.start():end]
        public = re.findall(r"(?m)^\s*PublicKey\s*=\s*(\S+)\s*$", block)
        if public == [payload["public"]]:
            blocks.append((match.start(), end))
    if len(blocks) > 1:
        raise RuntimeError("Classic control peer identity is duplicated")
    if bool(blocks) == payload["enabled"]:
        return {"applied": True, "changed": False}
    if payload["enabled"]:
        candidate = raw.rstrip() + b"\n\n" + payload["block"].strip().encode() + b"\n"
        added = classic_configuration(candidate.decode())["peers"]
        previous = classic_configuration(text)["peers"]
        extra = [peer for peer in added if peer not in previous]
        if len(extra) != 1 or extra[0]["PublicKey"] != payload["public"] or len(added) != len(previous) + 1:
            raise RuntimeError("Classic control mutation must add exactly its requested peer")
    else:
        start, end = blocks[0]
        candidate = (text[:start] + text[end:]).encode()
    parsed = classic_configuration(candidate.decode())
    if digest(parsed["interface"]) != config["interface_sha256"] or quick_address(candidate.decode()) != config["tunnel_address"]:
        raise RuntimeError("Classic control mutation changed its fixed interface")
    atomic_json(journal, {"authority": authority(config), "before": base64.b64encode(raw).decode(), "after": base64.b64encode(candidate).decode(),
                          "before_sha256": sha(raw), "after_sha256": sha(candidate)})
    try:
        # This exact classic wg binary clears unspecified AWG options on `set peer`.
        # The complete configuration keeps J/S/H values present on every update.
        runtime_apply(config, candidate)
        atomic_source_configuration(config, candidate)
        if read_pair(config) != candidate:
            raise RuntimeError("Classic control mutation did not verify")
        journal.unlink()
    except Exception:
        source_recover(config, journal)
        raise
    return {"applied": True, "changed": True, "enabled": payload["enabled"], "peer_count": len(parsed["peers"])}


def recover(config, journal_path):
    journal = trusted_json(journal_path, private=True)
    before, after = validate(config, journal["before"]), validate(config, journal["after"])
    state_path = Path(config["state_root"]) / "applied.json"
    current = trusted_json(state_path, private=True)
    if journal["before"]["manifest"]["revision"] >= journal["after"]["manifest"]["revision"] or current not in (journal["before"]["manifest"], journal["after"]["manifest"]):
        raise RuntimeError("Classic pending generation lineage changed")
    guard(config)
    path = Path(config["resource"]["config_path"])
    saved = path.read_bytes()
    live = command(["docker", "exec", config["resource"]["container"], "wg", "showconf", "wg0"])
    if sha(saved) not in {sha(before), sha(after)} or classic_configuration(live.decode()) not in (classic_configuration(before.decode()), classic_configuration(after.decode())):
        raise RuntimeError("Classic pending operation contains independent changes")
    completed = sha(saved) == sha(after) and classic_configuration(live.decode()) == classic_configuration(after.decode())
    selected = journal["after"] if completed else journal["before"]
    raw = after if completed else before
    runtime_apply(config, raw)
    replace_bound(path, raw)
    read_pair(config)
    atomic_json(state_path, selected["manifest"])
    journal_path.unlink()


def receive(config, payload):
    if config["source"] is not False:
        raise RuntimeError("Classic authoritative source cannot receive replicas")
    raw = validate(config, payload)
    root = Path(config["state_root"])
    journal = root / "pending.json"
    if journal.exists():
        recover(config, journal)
    current = status(config)["manifest"]
    incoming = payload["manifest"]
    if incoming["revision"] < current["revision"] or (incoming["revision"] == current["revision"] and incoming != current):
        raise RuntimeError("Classic generation would regress or be reused")
    if incoming == current:
        return status(config)
    before = Path(config["resource"]["config_path"]).read_bytes()
    atomic_json(journal, {"before": {"manifest": current, "configuration": base64.b64encode(before).decode()}, "after": payload})
    try:
        runtime_apply(config, raw)
        replace_bound(config["resource"]["config_path"], raw)
        if read_pair(config) != raw:
            raise RuntimeError("Classic apply did not persist its generation")
        atomic_json(root / "applied.json", incoming)
        journal.unlink()
    except Exception:
        recover(config, journal)
        raise
    return status(config)


def acknowledge(config, desired):
    observed = snapshot(config)["manifest"] if config["source"] else status(config)["manifest"]
    if any(desired.get(key) != observed[key] for key in ("scope", "epoch", "revision", "sha256")):
        raise RuntimeError("Classic cross-node generation ACK is stale")
    at = desired.get("synchronized_at")
    if type(at) not in (int, float) or not math.isfinite(at) or not 0 <= time.time() - at <= 90:
        raise RuntimeError("Classic cross-node ACK time is invalid")
    atomic_json(Path(config["desired_root"]) / (config["scope"] + ".json"), desired)
    return {"acknowledged": True}


def rpc(config, action, payload=None, remote=False):
    arguments = ["python3", config["worker"], action, "--config", config["node_config"]]
    if remote:
        peer = config["peer"]
        host = str(ipaddress.IPv4Address(peer["host"]))
        arguments = ["ssh", "-i", peer["identity_file"], "-o", "BatchMode=yes", "-o", "IdentitiesOnly=yes", "-o", "StrictHostKeyChecking=yes",
                     "-o", "UserKnownHostsFile=" + peer["known_hosts_file"], "-o", "HostKeyAlias=" + peer["host_key_alias"], "-o", "ConnectTimeout=6",
                     "root@" + host, shlex.join(arguments)]
    return json.loads(command(arguments, json.dumps(payload).encode() if payload is not None else None))


def synchronize(config):
    if config["source"] is not False:
        raise RuntimeError("Classic coordinator must run on its fixed replica node")
    payload = rpc(config, "snapshot", remote=True)
    target = rpc(config, "receive", payload)
    observed = rpc(config, "snapshot", remote=True)["manifest"]
    if observed != payload["manifest"] or target["manifest"] != observed:
        raise RuntimeError("Classic source changed before the paired ACK")
    desired = {key: observed[key] for key in ("scope", "epoch", "revision", "sha256")}
    desired["synchronized_at"] = time.time()
    rpc(config, "ack", desired, remote=True)
    rpc(config, "ack", desired)
    return {"healthy": True, **desired, "peer_count": observed["peer_count"], "checked_at": time.time()}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("action", choices=["snapshot", "status", "receive", "ack", "run", "control-peer"])
    parser.add_argument("--config", required=True)
    args = parser.parse_args()
    os.umask(0o077)
    config = trusted_json(args.config, private=True)
    root = Path(config["state_root"])
    root.mkdir(mode=0o700, parents=True, exist_ok=True)
    try:
        with exclusive_state(root / ("coordinator" if args.action == "run" else "node")):
            source_journal = root / "source-peer.pending.json"
            if config["source"] and source_journal.exists():
                source_recover(config, source_journal)
            if args.action in ("receive", "ack", "control-peer"):
                raw = sys.stdin.buffer.read(RPC_LIMIT + 1)
                if len(raw) > RPC_LIMIT:
                    raise RuntimeError("Classic RPC is unbounded")
                payload = json.loads(raw)
                actions = {"receive": receive, "ack": acknowledge, "control-peer": control_peer}
                result = actions[args.action](config, payload)
            else:
                result = {"snapshot": snapshot, "status": status, "run": synchronize}[args.action](config)
                if args.action == "run":
                    atomic_json(root / "coordinator.json", result)
        print(json.dumps(result))
    except Exception as error:
        failure = {"healthy": False, "reason": "classic_sync_held", "error_type": type(error).__name__, "checked_at": time.time()}
        if args.action == "run":
            atomic_json(root / "coordinator.json", failure)
        print(json.dumps(failure))
        raise SystemExit(1) from None


if __name__ == "__main__":
    main()
