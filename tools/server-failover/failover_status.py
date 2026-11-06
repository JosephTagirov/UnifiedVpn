"""Bounded operational status for checkpoints and the owner metrics API."""
import json
from pathlib import Path
import subprocess
import time


TIMERS = ("unifiedvpn-failover.timer", "unifiedvpn-failover-readiness.timer")


def timer_states():
    result = subprocess.run(
        ["systemctl", "show", *TIMERS, "--property=Id,ActiveState,UnitFileState"],
        capture_output=True, text=True, timeout=3, check=True,
    )
    values = {}
    for block in result.stdout.strip().split("\n\n"):
        item = dict(line.split("=", 1) for line in block.splitlines() if "=" in line)
        values[item.get("Id")] = item
    return values


def bounded_json(path):
    source = Path(path)
    if source.is_symlink() or source.stat().st_size > 2 * 1024 * 1024:
        raise ValueError("Invalid status source")
    value = json.loads(source.read_text())
    if not isinstance(value, dict):
        raise ValueError("Invalid status object")
    return value


def snapshot(config_path="/etc/unifiedvpn-failover/config.json", *, units=None, now=None):
    """Policy activation and a healthy Russian backup are separate facts."""
    now = time.time() if now is None else now
    result = {
        "automaticTrafficFailover": False,
        "healthyBackupAvailable": False,
        "regionalCoverageDegraded": True,
        "failoverStatus": "unavailable",
    }
    try:
        config = bounded_json(config_path)
        policy = config["policy"]
        units = timer_states() if units is None else units
        running = all(
            units.get(name, {}).get("ActiveState") == "active"
            and units[name].get("UnitFileState") == "enabled"
            for name in TIMERS
        )
        enabled = config.get("enabled") is True and running
        result.update(
            primary=policy["addresses"]["primary"],
            standby=policy["addresses"]["standby"],
            automaticTrafficFailover=enabled,
            failoverPolicyEnabled=config.get("enabled") is True,
            failoverTimersActive=running,
            failoverCoverage=list(policy["protocols"]),
            controlWriter=config["control_writer"],
            automaticControlPromotion=False,
            failoverMode=policy["mode"],
            failoverStatus="enabled_degraded" if enabled else "disabled",
        )
        health = bounded_json(config["health_file"])
        maximum_age = policy.get("probe_max_age_seconds", 120)
        if not 0 <= now - health["checked_at"] <= maximum_age:
            return result
        active = health.get("active")
        regional = health["regional"]
        nodes = policy["addresses"]
        backup = next((name for name in nodes if name != active), None) if active in nodes else None
        healthy_backup = (
            backup is not None and regional.get(backup) == "healthy"
            and health.get("dataplane_ready", {}).get(backup) is True
            and health.get("backup_successes", {}).get(backup, 0) >= policy.get("recovery_rounds", 2)
        )
        degraded = any(regional.get(name) != "healthy" for name in nodes)
        result.update(
            healthyBackupAvailable=healthy_backup,
            regionalCoverageDegraded=degraded,
            regionalStatus={name: regional.get(name, "unknown") for name in nodes},
            activeIngress=active,
            failoverHealthCheckedAt=health["checked_at"],
            failoverStatus=("enabled_degraded" if degraded or not healthy_backup else "enabled_ready") if enabled else "disabled",
        )
    except (OSError, ValueError, TypeError, KeyError, subprocess.SubprocessError):
        pass
    return result
