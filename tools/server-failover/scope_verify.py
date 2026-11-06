"""Revalidate applied identity scopes from live local configuration, read-only."""
import argparse
from contextlib import closing
import hashlib
import json
from pathlib import Path
import re
import sqlite3
import subprocess
import socket
import secrets
import time

from controller import trusted_json


def digest(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode()).hexdigest()


def vless(spec):
    identifiers = set(trusted_json(spec["client_ids_file"])["client_ids"] if "client_ids_file" in spec else spec["client_ids"])
    transports = []
    identities = []
    source = Path(spec.get("database", "/etc/x-ui/x-ui.db"))
    with closing(sqlite3.connect(source.as_uri() + "?mode=ro", uri=True, timeout=10)) as connection:
        connection.row_factory = sqlite3.Row
        connection.execute("begin")
        for inbound in spec.get("inbounds", [6, 7, 11]):
            row = connection.execute("select * from inbounds where id=?", (inbound,)).fetchone()
            if row is None:
                raise ValueError("Expected VLESS inbound is missing")
            transports.append({key: row[key] for key in ["id", "enable", "listen", "port", "protocol"]} | {"stream_settings": json.loads(row["stream_settings"])})
            identities.extend(
                {"inbound_id": inbound, "client": client}
                for client in json.loads(row["settings"]).get("clients", [])
                if spec.get("authority") is True or client.get("id") in identifiers
            )
    actual = [item["client"]["id"] for item in identities]
    if len(actual) != len(set(actual)) or (spec.get("authority") is not True and set(actual) != identifiers):
        raise ValueError("Expected VLESS identity set is incomplete or duplicated")
    payload = {
        "scope": spec["scope"], "transports": sorted(transports, key=lambda item: item["id"]),
        "identities": sorted(identities, key=lambda item: item["client"]["id"]),
    }
    return digest(payload)


def awg_configuration(text, *, peer_keys=None):
    interface = {}
    peers = []
    current = interface
    interface_keys = {
        "PrivateKey", "ListenPort", "Jc", "Jmin", "Jmax", "S1", "S2", "S3", "S4", "H1", "H2", "H3", "H4", "I1", "I2", "I3", "I4", "I5",
        "HeaderProtectionKey", "ContentPaddingAddition", "RekeyAfterTime", "RekeyTimeout", "RejectAfterTime", "KeepaliveTimeout", "MaxHandshakeAttempts", "RandomTrailers", "DisableCookies",
    }
    peer_fields = {"PublicKey", "PresharedKey", "AllowedIPs"}
    for line in text.splitlines():
        line = line.strip()
        if line == "[Interface]":
            current = interface
        elif line == "[Peer]":
            current = {}
            peers.append(current)
        elif "=" in line and not line.startswith("#"):
            key, value = [part.strip() for part in line.split("=", 1)]
            if (current is interface and key in interface_keys) or (current is not interface and key in peer_fields):
                current[key] = ",".join(sorted(item.strip() for item in value.split(","))) if key == "AllowedIPs" else value
    if not interface.get("PrivateKey") or not interface.get("ListenPort"):
        raise ValueError("AWG interface identity is incomplete")
    if peer_keys is not None:
        peers = [peer for peer in peers if peer.get("PublicKey") in peer_keys]
        if {peer.get("PublicKey") for peer in peers} != set(peer_keys):
            raise ValueError("AWG expected peer identity set is incomplete")
    if any(not peer.get("PublicKey") or not peer.get("AllowedIPs") for peer in peers):
        raise ValueError("AWG peer identity is incomplete")
    return {"interface": interface, "peers": sorted(peers, key=lambda peer: peer["PublicKey"])}


def awg(spec, command=subprocess.run):
    container = spec["container"]
    interface = spec.get("interface", "awg0")
    config_path = spec["config_path"]
    if not re.fullmatch(r"[a-zA-Z0-9_.-]+", container) or not re.fullmatch(r"[a-zA-Z0-9_-]+", interface):
        raise ValueError("AWG container or interface name is invalid")
    if config_path not in {"/opt/amnezia/awg/awg0.conf", "/run/secrets/server.conf"}:
        raise ValueError("AWG configuration path is outside the read-only allowlist")
    def run(arguments):
        result = command(arguments, capture_output=True, text=True, timeout=15)
        if result.returncode or len(result.stdout) > 1024 * 1024:
            raise ValueError("AWG read-only configuration check failed")
        return result.stdout
    saved = awg_configuration(run(["docker", "exec", container, "cat", config_path]), peer_keys=spec.get("peer_public_keys"))
    live = awg_configuration(run(["docker", "exec", container, "awg", "showconf", interface]), peer_keys=spec.get("peer_public_keys"))
    if saved != live:
        raise ValueError("AWG runtime and persisted identity differ")
    return digest({"scope": spec["scope"], **saved})


def files(spec):
    hashes = {}
    for name, source in spec["files"].items():
        path = Path(source)
        if path.is_symlink() or not path.is_file() or path.stat().st_size > 2 * 1024 * 1024:
            raise ValueError("Identity file is not a bounded regular file")
        data = path.read_bytes()
        hashes[name] = digest(json.loads(data)) if name in spec.get("canonical_json", []) else hashlib.sha256(data).hexdigest()
    return digest({"scope": spec["scope"], "files": hashes})


def classic(spec):
    from classic_awg_ha import verify_classic
    try:
        return verify_classic(spec)
    except RuntimeError:
        raise ValueError("Classic identity scope is unverified") from None


def local_services(config, *, clock=time.time, command=subprocess.run):
    observations = {}
    for spec in config.get("services", []):
        value = {"status": "unknown", "checked_at": clock(), "receipt_id": secrets.token_hex(16)}
        try:
            if "unit" in spec:
                if not re.fullmatch(r"[A-Za-z0-9_.@-]+\.service", spec["unit"]):
                    raise ValueError("Invalid service name")
                result = command(["systemctl", "show", spec["unit"], "--property=LoadState,ActiveState"], capture_output=True, text=True, timeout=5)
                fields = dict(line.split("=", 1) for line in result.stdout.splitlines() if "=" in line)
                if result.returncode == 0 and fields.get("LoadState") == "loaded":
                    if fields.get("ActiveState") in {"inactive", "failed"}:
                        value.update(status="confirmed_failed", failure_class="required_service_inactive")
                    elif fields.get("ActiveState") == "active":
                        value["status"] = "responsive"
                if value["status"] == "responsive" and "loopback_port" in spec:
                    try:
                        with socket.create_connection(("127.0.0.1", spec["loopback_port"]), timeout=2):
                            pass
                    except ConnectionRefusedError:
                        value.update(status="confirmed_failed", failure_class="required_listener_refused")
                    except OSError:
                        value["status"] = "unknown"
            elif "container" in spec:
                if not re.fullmatch(r"[A-Za-z0-9_.-]+", spec["container"]):
                    raise ValueError("Invalid container name")
                interface = spec.get("interface")
                if interface is not None and not re.fullmatch(r"[A-Za-z0-9_-]{1,15}", interface):
                    raise ValueError("Invalid required container interface")
                result = command(["docker", "inspect", "--format", "{{json .State}}", spec["container"]], capture_output=True, text=True, timeout=5)
                if result.returncode == 0:
                    state = json.loads(result.stdout)
                    if state.get("Running") is False:
                        value.update(status="confirmed_failed", failure_class="required_container_stopped")
                    elif state.get("Running") is True:
                        value["status"] = "responsive"
                        if interface is not None:
                            parent = command(["docker", "exec", spec["container"], "test", "-d", "/sys/class/net"], capture_output=True, text=True, timeout=5)
                            if parent.returncode != 0:
                                value["status"] = "unknown"
                            else:
                                device = command(["docker", "exec", spec["container"], "test", "-e", "/sys/class/net/" + interface], capture_output=True, text=True, timeout=5)
                                if device.returncode == 1:
                                    value.update(status="confirmed_failed", failure_class="required_interface_missing")
                                elif device.returncode != 0:
                                    value["status"] = "unknown"
        except (OSError, ValueError, TypeError, KeyError, subprocess.SubprocessError):
            pass
        observations[spec["protocol"]] = value
    return observations


def verify(config, *, clock=time.time):
    results = {}
    for spec in config["scopes"]:
        spec = dict(spec)
        if "manifest_file" in spec:
            desired = trusted_json(spec["manifest_file"])
            if desired.get("scope") != spec["scope"]:
                raise ValueError("Identity manifest scope changed")
            spec.update({key: desired[key] for key in ["epoch", "revision", "sha256"]})
        scope = spec["scope"]
        result = {key: spec[key] for key in ["scope", "epoch", "revision"]}
        if "manifest_file" in spec:
            result["synchronized_at"] = desired.get("synchronized_at", 0)
        result.update(checked_at=clock(), applied=False, sha256=None)
        try:
            actual = {"vless": vless, "awg": awg, "files": files, "classic": classic}[spec["kind"]](spec)
            result.update(sha256=actual, applied=(actual == spec["sha256"]))
        except (OSError, ValueError, TypeError, KeyError, sqlite3.Error, subprocess.SubprocessError):
            result["reason"] = "identity_scope_unverified"
        results[scope] = result
    services = local_services(config, clock=clock)
    dataplane = {}
    try:
        if "readiness_file" in config and "node_id" in config:
            snapshot = trusted_json(config["readiness_file"])
            local = snapshot.get(config["node_id"], {})
            if local.get("address") == config["address"]:
                dataplane = local.get("dataplane", {})
                for protocol, result in dataplane.items():
                    if result.get("confirmed_failed") and services.get(protocol, {}).get("status") != "confirmed_failed":
                        result["confirmed_failed"] = False
    except (OSError, ValueError, TypeError, KeyError):
        pass
    return {"address": config["address"], "observed_at": clock(), "scopes": results, "local_services": services, "dataplane": dataplane}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--config", required=True)
    args = parser.parse_args()
    try:
        print(json.dumps(verify(trusted_json(args.config, private=True))))
    except (OSError, ValueError, KeyError, TypeError):
        print(json.dumps({"scopes": {}, "reason": "identity_configuration_unavailable"}))


if __name__ == "__main__":
    main()
