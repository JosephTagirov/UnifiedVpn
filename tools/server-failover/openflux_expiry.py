"""Enforce a cached, finite DIRECT deadline without recreating owner sessions."""
import argparse
import hashlib
import json
import math
import os
from pathlib import Path
import re
import subprocess
import time

from controller import atomic_json, exclusive_state, trusted_json


def digest(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(",", ":"), allow_nan=False).encode()).hexdigest()


def validate_policy(config):
    if type(config.get("epoch")) is not int or config["epoch"] < 1 or type(config.get("revision")) is not int or config["revision"] < 1:
        raise ValueError("DIRECT deadline policy generation is invalid")
    if config["machine_id"] != Path("/etc/machine-id").read_text().strip():
        raise ValueError("DIRECT deadline policy belongs to another node")
    profiles = config["profiles"]
    if not profiles or len({item["id"] for item in profiles}) != len(profiles) or len({item["unit"] for item in profiles}) != len(profiles):
        raise ValueError("DIRECT deadline policy is ambiguous")
    for item in profiles:
        if (not re.fullmatch(r"[A-Za-z0-9_.@-]+\.service", item["unit"])
                or type(item.get("expires")) not in (int, float) or not math.isfinite(item["expires"]) or item["expires"] <= 0):
            raise ValueError("DIRECT deadline must be explicit and finite")


def run(arguments):
    result = subprocess.run(arguments, capture_output=True, text=True, timeout=12)
    if result.returncode:
        raise RuntimeError("DIRECT deadline inspection failed")
    return result.stdout


def observe(profile):
    state = dict(line.split("=", 1) for line in run(["systemctl", "show", profile["unit"], "-p", "LoadState", "-p", "ActiveState", "-p", "MainPID", "-p", "FragmentPath", "-p", "DropInPaths"]).splitlines() if "=" in line)
    if state.get("LoadState") != "loaded" or state.get("FragmentPath") != profile["fragment_path"]:
        raise RuntimeError("DIRECT owned unit changed")
    if hashlib.sha256(Path(state["FragmentPath"]).read_bytes()).hexdigest() != profile["fragment_sha256"]:
        raise RuntimeError("DIRECT owned unit definition changed")
    dropins = profile.get("dropins", {})
    if set(state.get("DropInPaths", "").split()) != set(dropins) or any(hashlib.sha256(Path(path).read_bytes()).hexdigest() != expected for path, expected in dropins.items()):
        raise RuntimeError("DIRECT owned unit overrides changed")
    configuration = Path(profile["configuration_path"])
    if configuration.is_symlink() or not configuration.is_file() or configuration.stat().st_size > 65536:
        raise RuntimeError("DIRECT configuration ownership changed")
    raw = configuration.read_bytes()
    if hashlib.sha256(raw).hexdigest() != profile["configuration_sha256"]:
        raise RuntimeError("DIRECT configuration identity changed")
    values = dict((name.strip(), value.strip().strip('"')) for line in raw.decode().splitlines() for name, separator, value in [line.partition("=")] if separator)
    if (values.get("Role") != "exit" or values.get("Mode") != "l4" or values.get("Codec") != "batched"
            or values.get("Type") != "direct" or len(re.findall(r'(?m)^\s*\[Transport\s', raw.decode())) != 1
            or values.get("Listen") != profile["listen"]
            or hashlib.sha256(values.get("SessionContext", "").encode()).hexdigest() != profile["context_sha256"]):
        raise RuntimeError("DIRECT mode or context changed")
    key = Path(profile["persistent_key_path"]).read_text().strip().lower()
    if hashlib.sha256(key.encode()).hexdigest() != profile["canonical_key_sha256"]:
        raise RuntimeError("DIRECT persistent key changed")
    active = state.get("ActiveState") == "active"
    pid = int(state.get("MainPID") or 0)
    if active:
        if not pid or hashlib.sha256(Path("/proc", str(pid), "exe").read_bytes()).hexdigest() != profile["binary_sha256"]:
            raise RuntimeError("DIRECT process binary changed")
        arguments = Path("/proc", str(pid), "cmdline").read_bytes().decode().split("\0")
        if profile["runtime_configuration_path"] not in arguments:
            raise RuntimeError("DIRECT process configuration changed")
        runtime_config = Path(profile["runtime_configuration_path"])
        runtime_key = Path(profile["runtime_key_path"])
        if not runtime_config.exists():
            runtime_config = Path("/proc", str(pid), "root") / runtime_config.relative_to("/")
        if not runtime_key.exists():
            runtime_key = Path("/proc", str(pid), "root") / runtime_key.relative_to("/")
        loaded = runtime_config.read_bytes()
        loaded_key = runtime_key.read_text().strip().lower()
        if hashlib.sha256(loaded).hexdigest() != profile["configuration_sha256"] or hashlib.sha256(loaded_key.encode()).hexdigest() != profile["canonical_key_sha256"]:
            raise RuntimeError("DIRECT loaded credentials differ from their pinned files")
        listeners = run(["ss", "-H", "-lntp", "sport = :" + profile["listen"].rsplit(":", 1)[1]])
        if not re.search(r"pid=" + str(pid) + r"(?:,|\))", listeners):
            raise RuntimeError("DIRECT listener does not belong to its pinned process")
    return {"owned": True, "active": active, "pid": pid}


def enforce(config, previous, *, now=None, observer=observe, stopper=None):
    validate_policy(config)
    now = time.time() if now is None else now
    if type(now) not in (int, float) or not math.isfinite(now):
        raise ValueError("DIRECT deadline clock is invalid")
    policy_digest = digest(config)
    version = (config["epoch"], config["revision"])
    if previous:
        old_version = (previous["epoch"], previous["revision"])
        if version < old_version or version == old_version and previous["policy_digest"] != policy_digest:
            raise ValueError("DIRECT deadline generation would regress or be reused")
    effective_now = max(now, previous.get("checked_at", now))
    expired = set(previous.get("expired_profiles", []))
    results = {}
    for profile in config["profiles"]:
        expired_now = effective_now >= profile["expires"] or profile["id"] in expired
        observed = observer(profile)
        if observed.get("owned") is not True:
            raise RuntimeError("DIRECT deadline cannot act on an unowned unit")
        if expired_now:
            expired.add(profile["id"])
            if observed["active"]:
                (stopper or (lambda unit: run(["systemctl", "stop", unit])))(profile["unit"])
                observed = observer(profile)
                if observed["active"]:
                    raise RuntimeError("DIRECT expired listener did not stop")
        results[profile["id"]] = {"unit": profile["unit"], "expires": profile["expires"], "expired": expired_now,
                                   "active": observed["active"], "deadline_enforced": expired_now and not observed["active"]}
    return {"epoch": config["epoch"], "revision": config["revision"], "policy_digest": policy_digest,
            "policy": config, "expired_profiles": sorted(expired), "checked_at": effective_now,
            "healthy": all(item["deadline_enforced"] if item["expired"] else item["active"] for item in results.values()), "profiles": results}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--config", required=True)
    parser.add_argument("--state", required=True)
    args = parser.parse_args()
    os.umask(0o077)
    with exclusive_state(args.state):
        previous = trusted_json(args.state, private=True) if Path(args.state).exists() else {}
        configuration_valid = True
        try:
            config = trusted_json(args.config, private=True)
            validate_policy(config)
            if previous and ((config["epoch"], config["revision"]) < (previous["epoch"], previous["revision"])
                             or (config["epoch"], config["revision"]) == (previous["epoch"], previous["revision"]) and digest(config) != previous["policy_digest"]):
                raise ValueError("DIRECT deadline configuration changed generation")
        except (OSError, ValueError, TypeError, KeyError):
            if not previous.get("policy"):
                raise
            config = previous["policy"]
            configuration_valid = False
        result = enforce(config, previous)
        result["configuration_valid"] = configuration_valid
        result["healthy"] = result["healthy"] and configuration_valid
        atomic_json(args.state, result)
        print(json.dumps({key: value for key, value in result.items() if key != "policy"}))


if __name__ == "__main__":
    main()
