"""Replicate primary VLESS identities while retaining standby-only users and usage."""
import argparse
from contextlib import closing
import hashlib
import json
import os
from pathlib import Path
import shlex
import secrets
import sqlite3
import subprocess
import sys
import time

from controller import atomic_json, exclusive_state, trusted_json

SCOPE = "vless-primary-current-v1"
INBOUNDS = (6, 7, 11)
ACCOUNT_FIELDS = ("id", "email", "flow", "password", "security", "alterId", "method")
MAXIMUM_BYTES = 8 * 1024 * 1024


def digest(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode()).hexdigest()


def snapshot(connection, identifiers=None):
    connection.row_factory = sqlite3.Row
    transports = []
    identities = []
    for inbound in INBOUNDS:
        row = connection.execute("select * from inbounds where id=?", (inbound,)).fetchone()
        if row is None or row["protocol"] != "vless":
            raise ValueError("Expected VLESS transport is missing")
        transports.append({key: row[key] for key in ["id", "enable", "listen", "port", "protocol"]}
                          | {"stream_settings": json.loads(row["stream_settings"])})
        identities.extend({"inbound_id": inbound, "client": client}
                          for client in json.loads(row["settings"]).get("clients", [])
                          if identifiers is None or client.get("id") in identifiers)
    ids = [item["client"]["id"] for item in identities]
    emails = [item["client"].get("email") for item in identities]
    if len(ids) != len(set(ids)) or len(emails) != len(set(emails)) or not all(emails):
        raise ValueError("VLESS identities and emails must be unique")
    if identifiers is not None and set(ids) != set(identifiers):
        raise ValueError("Expected VLESS identity set is incomplete")
    return {"scope": SCOPE, "transports": sorted(transports, key=lambda item: item["id"]),
            "identities": sorted(identities, key=lambda item: item["client"]["id"])}


def read_snapshot(database):
    with closing(sqlite3.connect(Path(database).as_uri() + "?mode=ro", uri=True, timeout=15)) as connection:
        connection.execute("begin")
        return snapshot(connection)


def desired_files(config, request, synchronized_at):
    directory = Path(config["desired_directory"])
    directory.mkdir(mode=0o700, parents=True, exist_ok=True)
    identifiers = sorted(item["client"]["id"] for item in request["payload"]["identities"])
    atomic_json(directory / (SCOPE + ".clients.private.json"), {"client_ids": identifiers})
    manifest = {key: request[key] for key in ["scope", "epoch", "revision", "sha256"]}
    manifest["synchronized_at"] = synchronized_at
    atomic_json(directory / (SCOPE + ".json"), manifest)
    return manifest


class Runtime:
    def __init__(self, directory):
        sys.path.insert(0, directory)
        import existing
        self.existing = existing

    def present(self, row, client):
        result = self.existing.adapters.command([
            self.existing.adapters.XRAY, "api", "inbounduser", "--server=127.0.0.1:62789",
            "-tag=" + row["tag"], "-email=" + client["email"],
        ])
        return any(user.get("email") == client["email"] for user in json.loads(result.stdout).get("users", []))

    def set(self, row, client, active):
        self.existing.runtime_client(row, client, active)


def active(client, traffic, now):
    used = traffic.get("up", 0) + traffic.get("down", 0)
    return bool(client.get("enable", True)
                and not (client.get("expiryTime", 0) > 0 and client["expiryTime"] < now * 1000)
                and not (client.get("totalGB", 0) > 0 and used >= client["totalGB"]))


def validate_request(request, config, state, pending):
    if (request.get("scope") != SCOPE or request.get("origin") != "primary"
            or request.get("epoch") != config["epoch"] or request.get("sha256") != digest(request["payload"])):
        raise ValueError("VLESS source authority or digest is invalid")
    previous = pending or state
    if request["revision"] < previous.get("revision", 0):
        raise ValueError("VLESS generation cannot regress")
    if request["revision"] == previous.get("revision") and request["sha256"] != previous.get("sha256"):
        raise ValueError("VLESS generation cannot change its payload")
    payload = request["payload"]
    if payload["scope"] != SCOPE or [row["id"] for row in payload["transports"]] != list(INBOUNDS):
        raise ValueError("Unexpected VLESS transport scope")
    if len(payload["identities"]) > 5000:
        raise ValueError("VLESS identity snapshot exceeds its bound")
    ids = [item["client"]["id"] for item in payload["identities"]]
    emails = [item["client"].get("email") for item in payload["identities"]]
    if (len(ids) != len(set(ids)) or len(emails) != len(set(emails)) or not all(emails)
            or any(item["inbound_id"] not in INBOUNDS for item in payload["identities"])):
        raise ValueError("VLESS identity snapshot is ambiguous")


def apply_request(config, request, runtime, *, now=time.time):
    state_path = Path(config["state"])
    pending_path = state_path.with_name("vless-pending.private.json")
    state = trusted_json(state_path)
    pending = trusted_json(pending_path) if pending_path.exists() else None
    validate_request(request, config, state, pending)
    owned = set(state["managed_ids"])
    pending_identities = pending["payload"]["identities"] if pending else []
    owned.update(item["client"]["id"] for item in pending_identities)
    wanted = {item["client"]["id"]: item for item in request["payload"]["identities"]}
    removed = owned - set(wanted)
    changed_runtime = []
    with closing(sqlite3.connect(config["database"], timeout=20)) as connection:
        connection.row_factory = sqlite3.Row
        connection.execute("begin immediate")
        rows = {row["id"]: dict(row) for row in connection.execute("select * from inbounds where protocol='vless'")}
        before_settings = {key: json.loads(row["settings"]) for key, row in rows.items()}
        before_items = [{"inbound_id": key, "client": client}
                        for key, settings in before_settings.items() for client in settings.get("clients", [])]
        before = {item["client"]["id"]: item for item in before_items}
        if len(before) != len(before_items):
            raise ValueError("Standby VLESS identity appears in multiple locations")
        local_only = set(before) - owned
        if local_only & set(wanted):
            raise ValueError("VLESS source collides with a standby-owned identity")
        local_emails = {before[key]["client"]["email"] for key in local_only}
        if local_emails & {item["client"]["email"] for item in wanted.values()}:
            raise ValueError("VLESS source collides with a standby-owned email")
        actual_transports = snapshot(connection, set(before) & set(wanted))["transports"]
        if actual_transports != request["payload"]["transports"]:
            raise ValueError("VLESS transport changes require explicit maintenance")
        traffic_rows = [dict(row) for row in connection.execute("select * from client_traffics")]
        traffic = {}
        for row in traffic_rows:
            if row["email"] in traffic:
                raise ValueError("VLESS traffic ownership is ambiguous")
            traffic[row["email"]] = row
        identity_traffic = {identifier: traffic[before[identifier]["client"]["email"]]
                            for identifier in wanted if identifier in before
                            and before[identifier]["client"]["email"] in traffic}
        assigned_rows = {row["id"] for row in identity_traffic.values()}
        for identifier, item in wanted.items():
            previous_row = traffic.get(item["client"]["email"])
            if identifier not in identity_traffic and previous_row and previous_row["id"] not in assigned_rows:
                identity_traffic[identifier] = previous_row
                assigned_rows.add(previous_row["id"])
        known = {(item["inbound_id"], item["client"]["id"], item["client"]["email"]): item
                 for item in [*before.values(), *pending_identities, *wanted.values()]
                 if item["client"]["id"] in owned | set(wanted)}
        def expected_location(location):
            inbound, identifier, email = location
            target = wanted.get(identifier)
            return bool(target and target["inbound_id"] == inbound and target["client"]["email"] == email
                        and active(target["client"], identity_traffic.get(identifier, {}), now()))
        atomic_json(pending_path, request)
        try:
            # Remove old emails and locations before activating their replacements.
            for location in sorted(known, key=expected_location):
                inbound, identifier, email = location
                item = known[location]
                client = item["client"]
                target = wanted.get(identifier)
                expected = expected_location(location)
                old_item = before.get(identifier)
                account_changed = bool(target and (old_item is None or old_item["inbound_id"] != target["inbound_id"]
                                       or any(old_item["client"].get(key) != target["client"].get(key) for key in ACCOUNT_FIELDS)))
                if pending or account_changed or runtime.present(rows[inbound], client) != expected:
                    old_active = bool(old_item and old_item["inbound_id"] == inbound
                                      and old_item["client"]["email"] == email
                                      and active(old_item["client"], traffic.get(client["email"], {}), now()))
                    old_client = old_item["client"] if old_item and old_item["client"]["email"] == email else client
                    changed_runtime.append((rows[inbound], old_client, old_active))
                    runtime.set(rows[inbound], target["client"] if expected else client, expected)
            settings = {key: {**value, "clients": [client for client in value.get("clients", [])
                                                   if client["id"] not in owned | set(wanted)]}
                        for key, value in before_settings.items()}
            # Temporary names make email swaps safe even with a UNIQUE email index.
            for identifier, row in identity_traffic.items():
                if row["email"] != wanted[identifier]["client"]["email"]:
                    connection.execute("update client_traffics set email=? where id=?", ("__vless_rebind_" + secrets.token_hex(32), row["id"]))
            for identifier, item in wanted.items():
                inbound, client = item["inbound_id"], item["client"]
                settings[inbound]["clients"].append(client)
                status = int(active(client, identity_traffic.get(identifier, {}), now()))
                if identifier in identity_traffic:
                    connection.execute("update client_traffics set inbound_id=?,enable=?,expiry_time=?,total=?,email=? where id=?",
                                       (inbound, status, client.get("expiryTime", 0), client.get("totalGB", 0), client["email"], identity_traffic[identifier]["id"]))
                else:
                    connection.execute("insert into client_traffics(inbound_id,enable,email,up,down,all_time,expiry_time,total,reset,last_online) values(?,?,?,0,0,0,?,?,0,0)",
                                       (inbound, status, client["email"], client.get("expiryTime", 0), client.get("totalGB", 0)))
            for identifier in removed:
                previous = before.get(identifier)
                old_traffic = traffic.get(previous["client"]["email"]) if previous else None
                if old_traffic and old_traffic["id"] not in assigned_rows:
                    connection.execute("update client_traffics set enable=0 where email=?", (previous["client"]["email"],))
            for inbound, value in settings.items():
                if value != before_settings[inbound]:
                    connection.execute("update inbounds set settings=? where id=?", (json.dumps(value), inbound))
            actual = snapshot(connection, set(wanted))
            if digest(actual) != request["sha256"]:
                raise ValueError("VLESS replica digest did not match the source")
            for item in wanted.values():
                expected = active(item["client"], identity_traffic.get(item["client"]["id"], {}), now())
                if runtime.present(rows[item["inbound_id"]], item["client"]) != expected:
                    raise ValueError("VLESS runtime did not acknowledge an identity")
            for row in traffic_rows:
                after = connection.execute("select up,down,all_time from client_traffics where id=?", (row["id"],)).fetchone()
                if tuple(after) != tuple(row[key] for key in ["up", "down", "all_time"]):
                    raise ValueError("VLESS synchronization altered usage counters")
            connection.commit()
        except Exception:
            connection.rollback()
            for row, client, previous_active in reversed(changed_runtime):
                try:
                    runtime.set(row, client, previous_active)
                except Exception:
                    pass
            raise
    tombstones = dict(state.get("tombstones", {}))
    tombstones.update({identifier: now() for identifier in removed})
    state = {key: request[key] for key in ["epoch", "revision", "sha256"]} | {
        "managed_ids": sorted(wanted), "tombstones": tombstones, "applied_at": now(),
    }
    atomic_json(state_path, state)
    pending_path.unlink(missing_ok=True)
    return {"scope": SCOPE, **{key: state[key] for key in ["epoch", "revision", "sha256"]},
            "identity_count": len(wanted), "local_only_count": len(local_only), "removed_count": len(removed)}


def ssh(config, mode, value):
    options = ["-i", config["identity_file"], "-o", "BatchMode=yes", "-o", "IdentitiesOnly=yes",
               "-o", "StrictHostKeyChecking=yes", "-o", "UserKnownHostsFile=" + config["known_hosts_file"],
               "-o", "HostKeyAlias=" + config["host_key_alias"], "-o", "ConnectTimeout=6"]
    remote = "python3 " + shlex.quote(config["worker"]) + " " + mode + " --config " + shlex.quote(config["peer_config"])
    result = subprocess.run(["ssh", *options, "root@" + config["peer_host"], remote],
                            input=json.dumps(value).encode(), capture_output=True, timeout=150)
    if result.returncode or len(result.stdout) > MAXIMUM_BYTES:
        raise RuntimeError("VLESS replica did not acknowledge synchronization")
    return json.loads(result.stdout)


def synchronize(config):
    state_path = Path(config["state"])
    state = trusted_json(state_path) if state_path.exists() else {"revision": 0}
    payload = read_snapshot(config["database"])
    checksum = digest(payload)
    revision = state["revision"] + int(state.get("sha256") != checksum)
    request = {"scope": SCOPE, "origin": "primary", "epoch": config["epoch"], "revision": revision,
               "sha256": checksum, "payload": payload}
    atomic_json(state_path, {"revision": revision, "sha256": checksum})
    applied = ssh(config, "receive", request)
    if any(applied.get(key) != request[key] for key in ["scope", "epoch", "revision", "sha256"]):
        raise RuntimeError("VLESS replica acknowledged another generation")
    if digest(read_snapshot(config["database"])) != checksum:
        raise RuntimeError("VLESS authority changed while synchronization was running")
    acknowledged = ssh(config, "ack", request)
    if acknowledged.get("sha256") != checksum:
        raise RuntimeError("VLESS replica did not publish its acknowledged generation")
    manifest = desired_files(config, request, time.time())
    return {**manifest, "identity_count": applied["identity_count"], "local_only_count": applied["local_only_count"], "healthy": True}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=["run", "receive", "ack"])
    parser.add_argument("--config", required=True)
    args = parser.parse_args()
    os.umask(0o077)
    config = trusted_json(args.config)
    if hashlib.sha256(Path("/etc/machine-id").read_bytes()).hexdigest() != config["machine_id_sha256"]:
        raise RuntimeError("VLESS synchronization is running on another configured node")
    if (args.mode == "run") != (config["role"] == "primary"):
        raise RuntimeError("VLESS synchronization authority is fenced")
    Path(config["state"]).parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    with exclusive_state(config["state"]):
        if args.mode == "run":
            result = synchronize(config)
        else:
            raw = sys.stdin.buffer.read(MAXIMUM_BYTES + 1)
            if len(raw) > MAXIMUM_BYTES:
                raise ValueError("VLESS synchronization request exceeds its bound")
            request = json.loads(raw)
            if args.mode == "receive":
                result = apply_request(config, request, Runtime(config["control_directory"]))
            else:
                state = trusted_json(config["state"])
                validate_request(request, config, state, None)
                if request["revision"] != state["revision"] or request["sha256"] != state["sha256"]:
                    raise ValueError("VLESS applied generation is not the acknowledged generation")
                with closing(sqlite3.connect(Path(config["database"]).as_uri() + "?mode=ro", uri=True)) as connection:
                    if digest(snapshot(connection, set(state["managed_ids"]))) != state["sha256"]:
                        raise ValueError("VLESS replica changed before acknowledgment")
                result = desired_files(config, request, time.time())
        print(json.dumps(result))


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        print(json.dumps({"healthy": False, "reason": "vless_sync_held", "error_type": type(error).__name__}))
        raise SystemExit(1) from None
