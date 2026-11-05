"""Conservative per-connection decisions; established flows are never rebalanced."""

from dataclasses import dataclass


@dataclass
class Decision:
    enabled: bool
    reason: str
    rate_mbit: int


def decide(local, remote, *, reachable=True, forced_failure=False):
    if forced_failure:
        return Decision(False, "temporary health-test override", 32)
    if not reachable or not remote:
        return Decision(False, "exit link unavailable", 32)
    if not remote.get("internetOk", False):
        return Decision(False, "exit HTTPS probes unavailable", 32)
    if local["availableMiB"] < 384 or remote["availableMiB"] < 640:
        return Decision(False, "memory reserve protected", 32)
    if local["freeDiskMiB"] < 512 or remote["freeDiskMiB"] < 1024:
        return Decision(False, "disk reserve protected", 32)
    if local["cpuBusyPct"] >= 88 or remote["cpuBusyPct"] >= 92:
        return Decision(False, "CPU reserve protected", 32)
    # The ingress has only one CPU; cap just its extra encrypted hop, not the NIC.
    rate = 120 if local["cpuBusyPct"] < 55 and remote["cpuBusyPct"] < 75 else 72
    return Decision(True, "exit healthy; resource reserves available", rate)
