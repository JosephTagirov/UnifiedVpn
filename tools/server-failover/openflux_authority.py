"""Fixed-registry DIRECT lifecycle replication with local cached expiry."""
import argparse
from contextlib import closing, contextmanager, ExitStack
import hashlib
import ipaddress
import json
import math
from pathlib import Path
import re
import shlex
import sqlite3
import subprocess
import sys
import time

from controller import atomic_json, exclusive_state, trusted_json
from openflux_expiry import observe


def digest(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(",", ":"), allow_nan=False).encode()).hexdigest()


def invoke(arguments, payload=None):
    for attempt in range(6):
        result = subprocess.run(arguments, input=json.dumps(payload).encode() if payload is not None else None,
                                capture_output=True, timeout=45)
        if len(result.stdout) > 1024 * 1024:
            raise RuntimeError("DIRECT authority operation is unbounded")
        response = json.loads(result.stdout)
        if not result.returncode:
            return response
        if not isinstance(response, dict) or response.get("error_type") != "BlockingIOError" or attempt == 5:
            raise RuntimeError("DIRECT authority operation failed")
        time.sleep(.3)


@contextmanager
def bounded_state_lock(path):
    with ExitStack() as stack:
        for attempt in range(10):
            try:
                stack.enter_context(exclusive_state(path))
                break
            except BlockingIOError:
                if attempt == 9:
                    raise
                time.sleep(.3)
        yield


def state_path(config, origin, suffix):
    if not re.fullmatch(r"[A-Za-z0-9_-]{1,80}", origin):
        raise ValueError("DIRECT authority origin is invalid")
    return Path(config["state_root"]) / (origin + "." + suffix + ".json")


def binding(spec, origin):
    return {"origin": origin, "source_machine_id": spec["source_machine_id"], "device_id": spec["device_id"],
            "authority_key_sha256": spec["canonical_key_sha256"], "port": spec["port"]}


def boot_identity():
    return Path("/proc/sys/kernel/random/boot_id").read_text().strip()


def synchronized_clock():
    try:
        result = subprocess.run(["timedatectl", "show", "-p", "NTPSynchronized", "--value"], capture_output=True, text=True, timeout=5)
    except (OSError, subprocess.TimeoutExpired):
        return False
    return result.returncode == 0 and result.stdout.strip() == "yes"


def clock_status(config, now=None):
    now = time.time() if now is None else now
    if type(now) not in (int, float) or not math.isfinite(now) or now <= 0:
        raise ValueError("DIRECT authority clock is invalid")
    path = Path(config["state_root"]) / "clock.json"
    previous = trusted_json(path, private=True) if path.exists() else {}
    boot = boot_identity()
    monotonic = time.monotonic()
    same_boot = previous.get("boot_id") == boot and monotonic >= previous.get("monotonic_at", monotonic)
    baseline = previous.get("checked_at", 0)
    if same_boot:
        baseline += monotonic - previous["monotonic_at"]
    trusted = bool(same_boot and previous.get("trusted") is True)
    if not previous.get("boot_id") and config.get("initial_boot_id") == boot:
        trusted = True
    if not trusted:
        trusted = synchronized_clock()
    return {"checked_at": max(now, baseline), "boot_id": boot, "monotonic_at": monotonic, "trusted": trusted}


def durable_clock(config, now=None, *, persist=True):
    state = clock_status(config, now)
    if persist:
        atomic_json(Path(config["state_root"]) / "clock.json", state)
    return state["checked_at"]


def validate(spec, origin, payload):
    if any(payload.get(key) != value for key, value in binding(spec, origin).items()):
        raise ValueError("DIRECT lifecycle violates its fixed authority")
    if type(payload.get("epoch")) is not int or payload["epoch"] != 1 or type(payload.get("revision")) is not int or payload["revision"] < 1:
        raise ValueError("DIRECT lifecycle generation is invalid")
    if type(payload.get("grant")) is not bool or type(payload.get("registry_known")) is not bool:
        raise ValueError("DIRECT lifecycle verdict is invalid")
    expiry = payload.get("expires")
    if type(expiry) not in (int, float) or not math.isfinite(expiry) or expiry < 0:
        raise ValueError("DIRECT lifecycle deadline is invalid")
    if payload["grant"] and spec.get("require_finite_deadline") is True and expiry <= 0:
        raise ValueError("DIRECT finite permission became unlimited")
    if payload["grant"] and payload.get("observed_key_sha256") != spec["canonical_key_sha256"]:
        raise ValueError("DIRECT grant changed its pinned key")
    body = {key: value for key, value in payload.items() if key not in {"epoch", "revision", "sha256", "observed_at"}}
    if payload.get("sha256") != digest(body):
        raise ValueError("DIRECT lifecycle digest changed")


def snapshot(config, origin, *, clock=time.time, persist=True):
    spec = config["sources"][origin]
    if spec["source_machine_id"] != config["machine_id"]:
        raise ValueError("DIRECT source is on another node")
    path = state_path(config, origin, "source")
    previous = trusted_json(path, private=True) if path.exists() else {}
    if previous:
        validate(spec, origin, previous)
    now = max(durable_clock(config, clock(), persist=persist), previous.get("observed_at", 0))
    with closing(sqlite3.connect("file:" + config["database"] + "?mode=ro", uri=True)) as database:
        database.row_factory = sqlite3.Row
        database.execute("begin")
        rows = [dict(row) for row in database.execute("select id,enabled,expires,protocols,openflux_key,openflux_port from devices")]
    active_ids = set()
    for row in rows:
        protocols = json.loads(row["protocols"] or '["awg","vless","vkcalls","openflux"]')
        if not isinstance(protocols, list) or any(not isinstance(item, str) for item in protocols):
            raise ValueError("DIRECT protocol membership is unknown")
        if type(row["enabled"]) is not int or row["enabled"] not in (0, 1) or type(row["expires"]) not in (int, float) or not math.isfinite(row["expires"]) or row["expires"] < 0:
            raise ValueError("DIRECT device lifecycle is malformed")
        if row["enabled"] and (row["expires"] == 0 or row["expires"] > now) and "openflux" in protocols:
            active_ids.add(row["id"])
    row = next((row for row in rows if row["id"] == spec["device_id"]), None)
    reason, grant, expires, key_hash = "deleted", False, 0, ""
    if row:
        expires = row["expires"]
        key_hash = hashlib.sha256((row["openflux_key"] or "").strip().lower().encode()).hexdigest()
        protocols = json.loads(row["protocols"] or '["awg","vless","vkcalls","openflux"]')
        reason = "enabled"
        if not row["enabled"]: reason = "disabled"
        elif "openflux" not in protocols: reason = "protocol_removed"
        elif key_hash != spec["canonical_key_sha256"]: reason = "key_changed"
        elif row["openflux_port"] != spec["port"]: reason = "port_changed"
        elif spec.get("require_finite_deadline") is True and expires <= 0: reason = "finite_deadline_removed"
        elif expires and expires <= now: reason = "expired"
        elif expires and not clock_status(config, clock())["trusted"]: reason = "clock_untrusted"
        else: grant = True
    if grant:
        resource = observe(spec["resource"])
        if resource.get("owned") is not True:
            raise RuntimeError("DIRECT active authority runtime is unverified")
    body = {**binding(spec, origin), "grant": grant, "expires": expires, "reason": reason,
            "observed_key_sha256": key_hash, "registry_known": active_ids <= set(config["known_device_ids"])}
    hashed = digest(body)
    revision = previous.get("revision", 0) + (previous.get("sha256") != hashed)
    result = {**body, "epoch": 1, "revision": revision, "sha256": hashed, "observed_at": now}
    if persist:
        atomic_json(path, result)
    elif not previous or any(previous[key] != result[key] for key in ("epoch", "revision", "sha256")):
        raise ValueError("DIRECT source startup awaits its current authority generation")
    return result


def effective_grant(payload, now):
    return payload["grant"] is True and (payload["expires"] == 0 or now < payload["expires"])


def enforce_resource(spec, payload, *, now=None, observer=observe, actor=None, allow_start=True):
    now = time.time() if now is None else now
    allowed = effective_grant(payload, now)
    current = observer(spec["resource"])
    if current.get("owned") is not True:
        raise RuntimeError("DIRECT receiver ownership is unknown")
    if allowed and not current["active"] and not allow_start:
        return {"owned": True, "active": False, "effective_grant": True, "unknown": True, "reason": "fresh_authority_required_for_start"}
    if current["active"] != allowed:
        action = "start" if allowed else "stop"
        if actor:
            actor(action, spec["resource"]["unit"])
        else:
            result = subprocess.run(["systemctl", action, spec["resource"]["unit"]], capture_output=True, timeout=25)
            if result.returncode:
                raise RuntimeError("DIRECT owned lifecycle transition failed")
        for _ in range(30):
            try:
                current = observer(spec["resource"])
                if current["active"] == allowed:
                    break
            except (OSError, RuntimeError):
                pass
            time.sleep(.1)
        else:
            raise RuntimeError("DIRECT owned lifecycle transition did not verify")
    return {"owned": True, "active": current["active"], "effective_grant": allowed}


def enforce_identity(config, origin, spec, payload, *, allow_start):
    now = durable_clock(config)
    path = state_path(config, origin, "local-expiry")
    denied = trusted_json(path, private=True) if path.exists() else {}
    version = (payload["epoch"], payload["revision"])
    if denied and any(denied.get(key) != value for key, value in binding(spec, origin).items()):
        raise ValueError("DIRECT local-expiry tombstone changed authority")
    if payload["expires"] > 0 and now >= payload["expires"]:
        denied = {**binding(spec, origin), "epoch": payload["epoch"], "revision": payload["revision"], "sha256": payload["sha256"], "expired_at": now}
        atomic_json(path, denied)
    effective = payload
    if (denied and version <= (denied["epoch"], denied["revision"])) or payload["expires"] > 0 and not clock_status(config)["trusted"]:
        effective = {**payload, "grant": False}
    return enforce_resource(spec, effective, now=now, allow_start=allow_start)


def apply(config, origin, payload):
    spec = config["targets"][origin]
    validate(spec, origin, payload)
    path = state_path(config, origin, "applied")
    previous = trusted_json(path, private=True) if path.exists() else {}
    if previous:
        validate(spec, origin, previous)
        if payload["revision"] < previous["revision"] or payload["revision"] == previous["revision"] and payload["sha256"] != previous["sha256"]:
            raise ValueError("DIRECT receiver generation would regress or be reused")
    if payload["grant"] and not payload["registry_known"]:
        raise ValueError("DIRECT fixed registry needs review for new profiles")
    # Commit permission intent before the unit action. A revoked generation is
    # never rolled back to an older grant after an interruption.
    atomic_json(path, payload)
    # Only a fresh coordinator application may authorize an inactive receiver
    # to start. Its boot gate consumes this short-lived, generation-bound ticket.
    if payload["grant"] and effective_grant(payload, durable_clock(config)):
        atomic_json(state_path(config, origin, "start-ticket"), {"epoch": payload["epoch"], "revision": payload["revision"], "sha256": payload["sha256"], "issued_at": time.time()})
    try:
        result = enforce_identity(config, origin, spec, payload, allow_start=True)
    finally:
        state_path(config, origin, "start-ticket").unlink(missing_ok=True)
    return {"manifest": payload, "passive_identity": result}


def startup_gate(config, origin):
    if origin in config.get("sources", {}):
        spec = config["sources"][origin]
        payload = snapshot(config, origin, persist=False)
    else:
        spec = config["targets"][origin]
        payload = trusted_json(state_path(config, origin, "applied"), private=True)
        validate(spec, origin, payload)
        ticket_path = state_path(config, origin, "start-ticket")
        ticket = trusted_json(ticket_path, private=True)
        ticket_path.unlink()
        if (any(ticket.get(key) != payload[key] for key in ("epoch", "revision", "sha256"))
                or not 0 <= time.time() - ticket.get("issued_at", 0) <= 25):
            raise ValueError("DIRECT receiver requires a fresh source start authorization")
    now = durable_clock(config, persist=False)
    denied_path = state_path(config, origin, "local-expiry")
    denied = trusted_json(denied_path, private=True) if denied_path.exists() else {}
    if (not payload["registry_known"] or not effective_grant(payload, now) or payload["expires"] > 0 and not clock_status(config)["trusted"]
            or denied and (payload["epoch"], payload["revision"]) <= (denied["epoch"], denied["revision"])):
        raise ValueError("DIRECT startup permission is revoked or expired")
    return {"startup_allowed": True, "origin": origin}


def acknowledge(config, origin, desired):
    if origin in config.get("sources", {}):
        spec = config["sources"][origin]
        current = snapshot(config, origin)
    else:
        spec = config["targets"][origin]
        current = trusted_json(state_path(config, origin, "applied"), private=True)
        validate(spec, origin, current)
    enforced = enforce_identity(config, origin, spec, current, allow_start=False)
    if enforced.get("unknown") or enforced["active"] != current["grant"] or enforced["effective_grant"] != current["grant"]:
        raise RuntimeError("DIRECT authority permission is not enforced locally")
    if any(desired.get(key) != current[key] for key in ("origin", "epoch", "revision", "sha256")) or not current["registry_known"]:
        raise ValueError("DIRECT paired acknowledgement changed or is unknown")
    at = desired.get("synchronized_at")
    if type(at) not in (int, float) or not math.isfinite(at) or not -5 <= time.time() - at <= 90:
        raise ValueError("DIRECT paired acknowledgement time is invalid")
    atomic_json(state_path(config, origin, "ack"), desired)
    return {"acknowledged": True}


def rpc(config, action, origin, payload=None, remote=False):
    arguments = [sys.executable, config["worker"], action, "--config", config["node_config"], "--origin", origin]
    if remote:
        peer = config["peer"]
        host = str(ipaddress.IPv4Address(peer["host"]))
        arguments = ["ssh", "-i", peer["identity_file"], "-o", "BatchMode=yes", "-o", "IdentitiesOnly=yes", "-o", "StrictHostKeyChecking=yes",
                     "-o", "UserKnownHostsFile=" + peer["known_hosts_file"], "-o", "HostKeyAlias=" + peer["host_key_alias"], "-o", "ConnectTimeout=6", "root@" + host, shlex.join(arguments)]
    return invoke(arguments, payload)


def synchronize(config):
    results = {}
    for origin, direction in config["jobs"].items():
        try:
            remote_source = direction == "pull"
            payload = rpc(config, "snapshot", origin, remote=remote_source)
            receiver = rpc(config, "apply", origin, payload, remote=not remote_source)
            current = rpc(config, "snapshot", origin, remote=remote_source)
            if current["sha256"] != payload["sha256"] or current["revision"] != payload["revision"] or receiver["manifest"]["sha256"] != payload["sha256"]:
                # A known newer revocation must survive even if its source
                # disappears before another full comparison can complete.
                if current.get("grant") is False and current["epoch"] == payload["epoch"] and current["revision"] > payload["revision"]:
                    rpc(config, "apply", origin, current, remote=not remote_source)
                raise RuntimeError("DIRECT authority changed before paired acknowledgement")
            desired = {key: current[key] for key in ("origin", "epoch", "revision", "sha256")}
            desired["synchronized_at"] = time.time()
            rpc(config, "ack", origin, desired, remote=True)
            rpc(config, "ack", origin, desired)
            results[origin] = {"healthy": True, **desired, "grant": current["grant"], "expires": current["expires"]}
        except (OSError, ValueError, TypeError, KeyError, RuntimeError):
            results[origin] = {"healthy": False, "reason": "authority_or_receiver_unknown"}
    return {"healthy": all(result["healthy"] for result in results.values()), "profiles": results, "checked_at": time.time()}


def enforce_cached(config):
    results = {}
    for origin, spec in config.get("sources", {}).items():
        # Local canonical DB state remains enforceable when the cabinet is
        # read-only and when the inter-node coordinator cannot reach this host.
        try:
            payload = snapshot(config, origin)
            results[origin] = enforce_identity(config, origin, spec, payload, allow_start=payload["registry_known"])
        except (OSError, ValueError, TypeError, KeyError, RuntimeError):
            results[origin] = {"unknown": True, "reason": "source_authority_unverified"}
    for origin, spec in config.get("targets", {}).items():
        path = state_path(config, origin, "applied")
        if not path.exists():
            results[origin] = {"unknown": True}
            continue
        payload = trusted_json(path, private=True)
        validate(spec, origin, payload)
        # Cached permission may close a unit when its known deadline passes,
        # but an outage never creates a new grant or fresh acknowledgement.
        results[origin] = enforce_identity(config, origin, spec, payload, allow_start=False)
    return {"profiles": results, "checked_at": time.time()}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("action", choices=["snapshot", "apply", "ack", "run", "enforce", "gate"])
    parser.add_argument("--config", required=True)
    parser.add_argument("--origin")
    args = parser.parse_args()
    config = trusted_json(args.config, private=True)
    if config["machine_id"] != Path("/etc/machine-id").read_text().strip():
        raise ValueError("DIRECT node authority changed")
    root = Path(config["state_root"])
    root.mkdir(mode=0o700, parents=True, exist_ok=True)
    try:
        # ExecStartPre runs while the initiating apply/enforce holds the node
        # lock. The gate only validates a generation-bound startup permission.
        if args.action == "gate":
            print(json.dumps(startup_gate(config, args.origin)))
            return
        with bounded_state_lock(root / ("coordinator" if args.action == "run" else "node")):
            if args.action in ("apply", "ack"):
                raw = sys.stdin.buffer.read(1024 * 1024 + 1)
                if len(raw) > 1024 * 1024:
                    raise ValueError("DIRECT authority RPC is unbounded")
                payload = json.loads(raw)
                result = apply(config, args.origin, payload) if args.action == "apply" else acknowledge(config, args.origin, payload)
            elif args.action == "snapshot": result = snapshot(config, args.origin)
            elif args.action == "enforce": result = enforce_cached(config)
            else: result = synchronize(config)
            if args.action in ("run", "enforce"):
                atomic_json(root / (args.action + ".json"), result)
        print(json.dumps(result))
    except Exception as error:
        failure = {"healthy": False, "reason": "direct_authority_held", "error_type": type(error).__name__, "checked_at": time.time()}
        if args.action in ("run", "enforce"):
            atomic_json(root / (args.action + ".json"), failure)
        print(json.dumps(failure))
        raise SystemExit(1) from None


if __name__ == "__main__":
    main()
