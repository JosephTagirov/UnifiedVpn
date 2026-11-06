"""Fixed-authority legacy AWG replication and narrowly owned UDP aliases."""
import argparse
import base64
import hashlib
import ipaddress
import json
import os
from pathlib import Path
import re
import shlex
import subprocess
import sys
import time

from controller import atomic_json, exclusive_state, trusted_json
from scope_verify import awg_configuration, digest

LABEL = "org.unifiedvpn.legacy-ha.origin"
LIMIT = 2 * 1024 * 1024


def command(arguments, data=None):
    result = subprocess.run(arguments, input=data, capture_output=True, timeout=90)
    if result.returncode or len(result.stdout) > LIMIT:
        raise RuntimeError("Bounded legacy HA operation failed")
    return result.stdout


def sha(data):
    return hashlib.sha256(data).hexdigest()


def configuration(raw):
    text = raw.decode("utf-8")
    parsed = awg_configuration(text)
    interface_fields = {line.split("=", 1)[0].strip() for line in text.split("[Peer]", 1)[0].splitlines()
                        if "=" in line and not line.lstrip().startswith("#")}
    if interface_fields - set(parsed["interface"]):
        raise RuntimeError("Legacy unscoped interface change requires explicit maintenance")
    keys = [peer["PublicKey"] for peer in parsed["peers"]]
    addresses = [address for peer in parsed["peers"] for address in peer["AllowedIPs"].split(",")]
    if len(keys) != len(set(keys)) or len(addresses) != len(set(addresses)):
        raise RuntimeError("Legacy peer identity or address is duplicated")
    return parsed


def scope_hash(scope, raw):
    return digest({"scope": scope, **configuration(raw)})


def replace_bound(path, raw):
    """Keep the existing inode visible through the container's read-only bind."""
    path = Path(path)
    if path.is_symlink() or not path.is_file():
        raise RuntimeError("Legacy bound configuration is not a regular file")
    with path.open("r+b") as stream:
        stream.write(raw)
        stream.truncate()
        stream.flush()
        os.fsync(stream.fileno())


def inspect(resource):
    return json.loads(command(["docker", "inspect", resource]))[0]


def identity_guard(config, spec, replica=False):
    if Path("/etc/machine-id").read_text().strip() != config["machine_id"]:
        raise RuntimeError("Legacy host authority changed")
    item = inspect(spec["container"])
    if item["Id"] != spec["container_id"] or not item["State"]["Running"]:
        raise RuntimeError("Legacy container identity or running state changed")
    mounts = [mount for mount in item["Mounts"] if mount["Destination"] == "/run/secrets/server.conf"]
    if (len(mounts) != 1 or mounts[0]["Source"] != spec["config_path"]
            or mounts[0]["Type"] != "bind" or mounts[0]["RW"]):
        raise RuntimeError("Legacy configuration mount ownership changed")
    public = command(["docker", "exec", spec["container"], "awg", "show", "awg0", "public-key"]).strip()
    if sha(public) != spec["server_public_key_sha256"]:
        raise RuntimeError("Legacy server identity changed")
    if replica:
        host = item["HostConfig"]
        if (item["Config"].get("Labels", {}).get(LABEL) != spec["origin"]
                or item["Image"] != spec["image_id"] or host["NetworkMode"] != spec["network"]
                or host["Privileged"] or not host["ReadonlyRootfs"]
                or {cap.removeprefix("CAP_") for cap in host.get("CapAdd") or []} != {"NET_ADMIN"}
                or {cap.removeprefix("CAP_") for cap in host.get("CapDrop") or []} != {"ALL"}
                or host["Memory"] != 128 * 1024 * 1024):
            raise RuntimeError("Legacy replica sandbox ownership changed")
        binding = host["PortBindings"].get("8443/udp", [])
        if len(binding) != 1 or binding[0]["HostPort"] != str(spec["public_port"]):
            raise RuntimeError("Legacy replica public port changed")
    return item


def read_pair(config, spec, replica=False):
    item = identity_guard(config, spec, replica)
    path = Path(spec["config_path"])
    if path.is_symlink() or path.stat().st_size > LIMIT:
        raise RuntimeError("Legacy source configuration is unbounded")
    saved = path.read_bytes()
    live = command(["docker", "exec", spec["container"], "awg", "showconf", "awg0"])
    parsed = configuration(saved)
    if parsed != configuration(live):
        raise RuntimeError("Legacy persisted and live configurations differ")
    if digest(parsed["interface"]) != spec["interface_sha256"]:
        raise RuntimeError("Legacy interface change requires explicit maintenance")
    return item, saved


def source_snapshot(config, origin):
    spec = config["sources"][origin]
    item, raw = read_pair(config, spec)
    path = Path(config["state_root"]) / (origin + ".source.json")
    previous = trusted_json(path, private=True) if path.exists() else {}
    payload_sha = sha(raw)
    revision = previous.get("revision", 0) + (previous.get("payload_sha256") != payload_sha)
    manifest = {"scope": spec["scope"], "origin": origin, "epoch": 1, "revision": revision,
                "sha256": scope_hash(spec["scope"], raw), "payload_sha256": payload_sha,
                "source_machine_id": config["machine_id"], "source_container_id": item["Id"],
                "server_public_key_sha256": spec["server_public_key_sha256"],
                "interface_sha256": spec["interface_sha256"], "peer_count": len(configuration(raw)["peers"])}
    atomic_json(path, manifest)
    return {"manifest": manifest, "configuration": base64.b64encode(raw).decode()}


def validate_payload(spec, payload):
    manifest = payload["manifest"]
    expected = {"scope": spec["scope"], "origin": spec["origin"], "epoch": 1,
                "source_machine_id": spec["source_machine_id"],
                "source_container_id": spec["source_container_id"],
                "server_public_key_sha256": spec["server_public_key_sha256"],
                "interface_sha256": spec["interface_sha256"]}
    if any(manifest.get(key) != value for key, value in expected.items()):
        raise RuntimeError("Legacy snapshot violates fixed origin authority")
    if type(manifest.get("epoch")) is not int:
        raise RuntimeError("Legacy epoch is not an integer")
    if type(manifest.get("revision")) is not int or manifest["revision"] < 1:
        raise RuntimeError("Legacy generation is not a positive integer")
    raw = base64.b64decode(payload["configuration"], validate=True)
    parsed = configuration(raw)
    if (len(raw) > LIMIT or sha(raw) != manifest["payload_sha256"]
            or scope_hash(spec["scope"], raw) != manifest["sha256"]
            or digest(parsed["interface"]) != spec["interface_sha256"]
            or len(parsed["peers"]) != manifest["peer_count"]):
        raise RuntimeError("Legacy payload identity did not verify")
    return raw


def runtime_apply(spec, raw):
    command(["docker", "exec", "-i", spec["container"], "awg", "syncconf", "awg0", "/dev/stdin"], raw)


def receiver_status(config, origin):
    spec = config["replicas"][origin]
    _, raw = read_pair(config, spec, True)
    path = Path(config["state_root"]) / (origin + ".applied.json")
    manifest = trusted_json(path, private=True)
    validate_payload(spec, {"manifest": manifest, "configuration": base64.b64encode(raw).decode()})
    if sha(raw) != manifest["payload_sha256"] or scope_hash(spec["scope"], raw) != manifest["sha256"]:
        raise RuntimeError("Legacy replica independently changed")
    return {"manifest": manifest, "verified_at": time.time()}


def recover_pending(config, spec, journal_path, state_path):
    journal = trusted_json(journal_path, private=True)
    before = validate_payload(spec, journal["before"])
    after = validate_payload(spec, journal["after"])
    before_manifest = journal["before"]["manifest"]
    after_manifest = journal["after"]["manifest"]
    current_manifest = trusted_json(state_path, private=True)
    if (before_manifest["revision"] >= after_manifest["revision"]
            or current_manifest not in (before_manifest, after_manifest)):
        raise RuntimeError("Legacy pending journal violates applied generation lineage")
    identity_guard(config, spec, True)
    saved = Path(spec["config_path"]).read_bytes()
    live = command(["docker", "exec", spec["container"], "awg", "showconf", "awg0"])
    if (sha(saved) not in {sha(before), sha(after)}
            or digest(configuration(live)) not in {digest(configuration(before)), digest(configuration(after))}):
        raise RuntimeError("Legacy pending journal contains independent changes")
    # Complete only the fully applied generation, otherwise restore the verified prior one.
    completed = sha(saved) == sha(after) and configuration(live) == configuration(after)
    selected = journal["after"] if completed else journal["before"]
    raw = after if completed else before
    runtime_apply(spec, raw)
    replace_bound(spec["config_path"], raw)
    read_pair(config, spec, True)
    atomic_json(state_path, selected["manifest"])
    journal_path.unlink()


def receive(config, origin, payload):
    spec = config["replicas"][origin]
    raw = validate_payload(spec, payload)
    root = Path(config["state_root"])
    state_path = root / (origin + ".applied.json")
    journal_path = root / (origin + ".pending.json")
    if journal_path.exists():
        recover_pending(config, spec, journal_path, state_path)
    current = receiver_status(config, origin)["manifest"]
    incoming = payload["manifest"]
    if incoming["revision"] < current["revision"]:
        raise RuntimeError("Legacy generation would regress")
    if incoming["revision"] == current["revision"]:
        if incoming != current:
            raise RuntimeError("Legacy same revision changed")
        return receiver_status(config, origin)
    before = Path(spec["config_path"]).read_bytes()
    journal = {"before": {"manifest": current, "configuration": base64.b64encode(before).decode()}, "after": payload}
    atomic_json(journal_path, journal)
    try:
        runtime_apply(spec, raw)
        replace_bound(spec["config_path"], raw)
        _, actual = read_pair(config, spec, True)
        if sha(actual) != incoming["payload_sha256"]:
            raise RuntimeError("Legacy persisted apply did not verify")
        atomic_json(state_path, incoming)
        journal_path.unlink()
    except Exception:
        recover_pending(config, spec, journal_path, state_path)
        raise
    return receiver_status(config, origin)


def acknowledge(config, origin, desired):
    spec = config.get("sources", {}).get(origin) or config["replicas"][origin]
    observed = source_snapshot(config, origin)["manifest"] if origin in config.get("sources", {}) else receiver_status(config, origin)["manifest"]
    if any(desired.get(key) != observed[key] for key in ("scope", "epoch", "revision", "sha256")):
        raise RuntimeError("Legacy cross-node ACK is stale")
    if desired["scope"] != spec["scope"]:
        raise RuntimeError("Legacy cross-node ACK scope changed")
    atomic_json(Path(config["desired_root"]) / (spec["scope"] + ".json"), desired)
    return {"acknowledged": True}


def rpc(config, action, origin, payload=None, remote=False):
    arguments = ["python3", config["worker"], action, "--config", config["node_config"], "--origin", origin]
    if remote:
        peer = config["peer"]
        host = str(ipaddress.IPv4Address(peer["host"]))
        options = ["-i", peer["identity_file"], "-o", "BatchMode=yes", "-o", "IdentitiesOnly=yes",
                   "-o", "StrictHostKeyChecking=yes", "-o", "UserKnownHostsFile=" + peer["known_hosts_file"],
                   "-o", "HostKeyAlias=" + peer["host_key_alias"], "-o", "ConnectTimeout=6"]
        arguments = ["ssh", *options, "root@" + host, shlex.join(arguments)]
    data = json.dumps(payload).encode() if payload is not None else None
    return json.loads(command(arguments, data))


def synchronize(config):
    results = {}
    for origin, job in config["jobs"].items():
        source_remote = job["direction"] == "pull"
        snapshot = rpc(config, "snapshot", origin, remote=source_remote)
        target = rpc(config, "receive", origin, snapshot, remote=not source_remote)
        # Re-read both authorities after apply, so source changes cannot receive a stale ACK.
        verified = rpc(config, "snapshot", origin, remote=source_remote)["manifest"]
        if verified != snapshot["manifest"] or target["manifest"] != verified:
            raise RuntimeError("Legacy source changed before cross-node ACK")
        desired = {key: verified[key] for key in ("scope", "epoch", "revision", "sha256")}
        desired["synchronized_at"] = time.time()
        rpc(config, "ack", origin, desired, remote=True)
        rpc(config, "ack", origin, desired)
        results[origin] = {**desired, "peer_count": verified["peer_count"]}
    return {"healthy": True, "jobs": results, "verified_at": time.time()}


def port_aliases(config):
    """Refresh only exact destination-local aliases after verifying source ownership."""
    chain = "UVPN_LEGACY_ALIAS"
    targets = []
    for origin, spec in config["sources"].items():
        if spec["public_port"] != {"primary": 18443, "old": 28443}.get(origin):
            raise RuntimeError("Legacy alias does not match its fixed origin port")
        item, _ = read_pair(config, spec)
        networks = list(item["NetworkSettings"]["Networks"].values())
        if len(networks) != 1:
            raise RuntimeError("Legacy source network is ambiguous")
        address = str(ipaddress.IPv4Address(networks[0]["IPAddress"]))
        targets.append((str(spec["public_port"]), address + ":8443", origin))
    if not targets or len({port for port, _, _ in targets}) != len(targets):
        raise RuntimeError("Legacy alias target set is empty or duplicated")
    check = subprocess.run(["iptables", "-w", "5", "-t", "nat", "-S", chain], capture_output=True)
    if check.returncode:
        command(["iptables", "-w", "5", "-t", "nat", "-N", chain])
    existing = command(["iptables", "-w", "5", "-t", "nat", "-S", chain]).decode().splitlines()
    actual = [shlex.split(line) for line in existing if line.startswith("-A ")]
    registry_path = Path(config["state_root"]) / "aliases.json"
    registry = trusted_json(registry_path, private=True) if registry_path.exists() else {"current": []}
    if actual != registry["current"] and actual != registry.get("pending"):
        raise RuntimeError("Legacy alias chain contains independent rules")
    desired = [["-A", chain, "-p", "udp", "-m", "udp", "--dport", port,
                "-m", "comment", "--comment", "unifiedvpn-legacy-ha-" + origin,
                "-j", "DNAT", "--to-destination", address] for port, address, origin in targets]
    atomic_json(registry_path, {"current": actual, "pending": desired})
    rules = ["*nat", "-F " + chain, *[shlex.join(rule) for rule in desired], "COMMIT"]
    command(["iptables-restore", "-w", "5", "--noflush"], ("\n".join(rules) + "\n").encode())
    atomic_json(registry_path, {"current": desired})
    for parent in ("PREROUTING", "OUTPUT"):
        jump = ["-p", "udp", "-m", "multiport", "--dports", ",".join(port for port, _, _ in targets),
                "-m", "addrtype", "--dst-type", "LOCAL", "-m", "comment", "--comment", "unifiedvpn-legacy-ha", "-j", chain]
        exists = subprocess.run(["iptables", "-w", "5", "-t", "nat", "-C", parent, *jump], capture_output=True)
        if exists.returncode:
            command(["iptables", "-w", "5", "-t", "nat", "-I", parent, "1", *jump])
    return {"healthy": True, "aliases": len(targets), "verified_at": time.time()}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=["snapshot", "receive", "status", "ack", "run", "aliases"])
    parser.add_argument("--config", required=True)
    parser.add_argument("--origin")
    args = parser.parse_args()
    os.umask(0o077)
    config = trusted_json(args.config, private=True)
    root = Path(config["state_root"])
    root.mkdir(mode=0o700, parents=True, exist_ok=True)
    try:
        lock_name = {"run": "coordinator", "aliases": "aliases"}.get(args.action, args.origin)
        if not lock_name or not re.fullmatch(r"[a-z][a-z0-9-]*", lock_name):
            raise RuntimeError("Legacy operation requires a valid fixed origin")
        with exclusive_state(root / lock_name):
            if args.action == "run":
                result = synchronize(config)
                atomic_json(root / "coordinator.json", result)
            elif args.action == "aliases":
                result = port_aliases(config)
            elif args.action == "snapshot":
                result = source_snapshot(config, args.origin)
            elif args.action == "status":
                result = receiver_status(config, args.origin)
            else:
                raw = sys.stdin.buffer.read(LIMIT + 1)
                if len(raw) > LIMIT:
                    raise RuntimeError("Legacy request exceeds its bound")
                payload = json.loads(raw)
                result = receive(config, args.origin, payload) if args.action == "receive" else acknowledge(config, args.origin, payload)
        print(json.dumps(result))
    except Exception as error:
        print(json.dumps({"healthy": False, "reason": "legacy_sync_held", "error_type": type(error).__name__}))
        raise SystemExit(1) from None


if __name__ == "__main__":
    main()
