"""Failover monitor entry point. Run once per minute through a systemd timer.

It consumes locally produced authenticated protocol/replication evidence. It
does not create that evidence, alter identities, promote a database or stop bots.
"""
import argparse
import concurrent.futures
from contextlib import contextmanager
import json
import os
from pathlib import Path
import secrets
import time

from dns import DnsUnavailable, RegRu, read_private_credentials, reconcile
from policy import Settings, decide, observed_active, ready, regional
from probes import CheckHost, ProbeUnavailable, authoritative_dns, internet_origins, peer_ports


def load_json(path, maximum_bytes=2 * 1024 * 1024):
    source = Path(path)
    if source.is_symlink() or not source.is_file() or source.stat().st_size > maximum_bytes:
        raise ValueError("JSON source is not a bounded regular file")
    value = json.loads(source.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise ValueError("JSON source must be an object")
    return value


def trusted_json(path, *, private=False):
    source = Path(path)
    info = source.lstat()
    if os.name != "posix" or info.st_uid != os.geteuid() or info.st_mode & 0o022:
        raise ValueError("Live monitor inputs must be owned and protected by the service user")
    if private and info.st_mode & 0o077:
        raise ValueError("Private monitor inputs must be owner-only")
    return load_json(source)


def atomic_json(path, value):
    path = Path(path)
    temporary = path.with_name(path.name + "." + secrets.token_hex(6) + ".new")
    descriptor = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8") as stream:
            json.dump(value, stream, sort_keys=True)
            stream.write("\n")
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


@contextmanager
def exclusive_state(path):
    directory = Path(path).parent
    directory.mkdir(parents=True, exist_ok=True, mode=0o700)
    if directory.is_symlink():
        raise ValueError("Monitor state directory cannot be a symbolic link")
    if os.name == "posix":
        info = directory.stat()
        if info.st_uid != os.geteuid() or info.st_mode & 0o077:
            raise ValueError("Monitor state directory must be owner-only")
    descriptor = os.open(directory / (Path(path).name + ".lock"), os.O_RDWR | os.O_CREAT, 0o600)
    try:
        if os.name == "posix":
            import fcntl
            fcntl.flock(descriptor, fcntl.LOCK_EX | fcntl.LOCK_NB)
        else:
            import msvcrt
            msvcrt.locking(descriptor, msvcrt.LK_NBLCK, 1)
        yield
    finally:
        os.close(descriptor)


def settings_from(config):
    settings = config["policy"]
    return Settings(**{**settings, "records": tuple(settings["records"]), "protocols": tuple(settings["protocols"])})


def collect(config, settings, state, *, checker=None, clock=time.time):
    checker = checker or CheckHost()
    now = clock()
    evidence = {
        "round_id": secrets.token_hex(16), "checked_at": now,
        "authoritative": {}, "regional": {}, "probe_requests": {},
        "readiness": {}, "manifest": {},
        "publisher_health": {}, "peer_reachability": {},
    }
    try:
        evidence["authoritative"] = authoritative_dns(config["nameservers"], settings.records)
        evidence["manifest"] = trusted_json(config["manifest_file"])
        evidence["readiness"] = trusted_json(config["readiness_file"])
        if settings.mode == "regional_or_peer_dataplane":
            evidence["publisher_health"]["internet"] = internet_origins(config["internet_origins"])
            try:
                registrar = RegRu(config["zone"], settings.records, read_private_credentials(config["credentials_file"]))
                records = registrar.records_now()
                evidence["publisher_health"]["registrar"] = {"healthy": True, "records": records, "checked_at": clock()}
            except (DnsUnavailable, OSError, ValueError, TypeError, KeyError):
                evidence["publisher_health"]["registrar"] = {"healthy": False, "checked_at": clock()}
            for node, address in settings.addresses.items():
                if node != settings.publisher_node:
                    evidence["peer_reachability"][node] = peer_ports(address, settings.publisher_node, evidence["round_id"])
        nodes = state.get("probe_nodes", {})
        if not nodes or now - state.get("probe_nodes_at", 0) > 3600:
            try:
                nodes = checker.russian_nodes()
                state.update(probe_nodes=nodes, probe_nodes_at=now)
            except ProbeUnavailable:
                nodes = {}
        if not nodes:
            return evidence
        with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
            jobs = {node: pool.submit(checker.tcp, address, nodes, port=config.get("regional_port", 443)) for node, address in settings.addresses.items()}
            for node, job in jobs.items():
                try:
                    samples = job.result()
                    evidence["regional"][node] = samples
                    request_ids = {sample["request_id"] for sample in samples}
                    if len(request_ids) == 1:
                        evidence["probe_requests"][node] = request_ids.pop()
                except (ProbeUnavailable, KeyError, TypeError, ValueError):
                    evidence["regional"][node] = []
    except (OSError, ProbeUnavailable, KeyError, TypeError, ValueError, AttributeError):
        # Missing, malformed or inaccessible evidence is an unknown round.
        evidence["regional"] = {}
        evidence["probe_requests"] = {}
    evidence["checked_at"] = clock()
    return evidence


def run_once(config, state_path, *, apply=False, fixture=None, provider=None, clock=time.time):
    settings = settings_from(config)
    with exclusive_state(state_path):
        state = load_json(state_path) if Path(state_path).exists() else {}
        if fixture is None and state.get("simulation_state") is True:
            return {"action": "hold", "reason": "simulation_state_requires_new_path", "automatic_control_promotion": False}
        if fixture is None and clock() - state.get("last_collection_at", 0) < settings.round_interval_seconds:
            return {"action": "hold", "reason": "collection_cadence", "automatic_control_promotion": False}
        evidence = fixture if fixture is not None else collect(config, settings, state, clock=clock)
        now = clock()
        state["last_collection_at"] = now
        try:
            decision = decide(state, evidence, settings, now)
        except (KeyError, TypeError, ValueError, AttributeError):
            state.update(failures=0, successes=0, backup_successes={}, last_action="hold", last_reason="evidence_invalid")
            atomic_json(state_path, state)
            return {"action": "hold", "reason": "evidence_invalid", "automatic_control_promotion": False}
        next_state = decision.state
        if fixture is not None:
            next_state["simulation_state"] = True
        previous_pending = bool(state.get("pending"))
        next_state.update(checked_at=now, last_action=decision.action, last_reason=decision.reason)
        if fixture is None and config.get("health_file"):
            atomic_json(config["health_file"], {
                "checked_at": now,
                "active": observed_active(evidence.get("authoritative", {}), settings),
                "regional": {node: regional(evidence.get("regional", {}).get(node, []), settings, now) for node in settings.addresses},
                "dataplane_ready": {node: ready(node, evidence.get("readiness", {}), evidence.get("manifest", {}), settings, now) for node in settings.addresses},
                "backup_successes": next_state.get("backup_successes", {}),
            })
        # Commit the intent before any DNS write so a restart can safely resume.
        atomic_json(state_path, next_state)
        report = {
            "action": decision.action, "reason": decision.reason, "target": decision.target,
            "failures": next_state.get("failures", 0), "dry_run": not apply,
            "automatic_control_promotion": False,
        }
        if decision.action != "reconcile" or not apply:
            return report
        if config.get("enabled") is not True or fixture is not None or settings.publisher_node is None:
            report.update(action="hold", reason="live_writes_not_enabled")
            return report
        pending = next_state["pending"]
        pending["phase"] = "publishing"
        atomic_json(state_path, next_state)
        try:
            provider = provider or RegRu(
                config["zone"], settings.records, read_private_credentials(config["credentials_file"]), allow_writes=True,
            )
            reconcile(
                provider, settings.records, settings.addresses[pending["source"]], settings.addresses[pending["target"]],
                evidence["authoritative"], resuming=previous_pending,
            )
            report.update(action="await_authoritative_dns", reason="registrar_change_verified")
        except (DnsUnavailable, OSError, ValueError, TypeError, KeyError):
            report.update(action="hold", reason="dns_update_unverified")
        next_state.update(last_action=report["action"], last_reason=report["reason"])
        atomic_json(state_path, next_state)
        return report


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--config", required=True)
    parser.add_argument("--state", required=True)
    parser.add_argument("--fixture", help="Offline evidence. It always disables DNS mutations")
    parser.add_argument("--apply", action="store_true", help="Enable configured live DNS reconciliation")
    args = parser.parse_args()
    try:
        config = trusted_json(args.config) if args.apply or not args.fixture else load_json(args.config)
        fixture = load_json(args.fixture) if args.fixture else None
        print(json.dumps(run_once(config, args.state, apply=args.apply, fixture=fixture)))
    except BlockingIOError:
        print(json.dumps({"action": "hold", "reason": "monitor_already_running"}))
    except (OSError, ValueError, TypeError, KeyError, AttributeError):
        print(json.dumps({"action": "hold", "reason": "monitor_inputs_invalid"}))


if __name__ == "__main__":
    main()
