"""Ingress selection policy. Control/database ownership never changes here."""
from dataclasses import dataclass
import copy
import hashlib
import ipaddress
import json
import re


@dataclass(frozen=True)
class Settings:
    addresses: dict
    records: tuple
    protocols: tuple
    quorum: int = 2
    failure_rounds: int = 3
    recovery_rounds: int = 2
    hold_down_seconds: int = 900
    round_interval_seconds: int = 60
    probe_max_age_seconds: int = 120
    sync_max_age_seconds: int = 600
    dataplane_max_age_seconds: int = 120
    publisher_node: str | None = None
    mode: str = "regional"

    def __post_init__(self):
        if len(self.addresses) != 2 or len(set(self.addresses.values())) != 2:
            raise ValueError("Exactly two independent nodes are required")
        for address in self.addresses.values():
            ipaddress.IPv4Address(address)
        if self.publisher_node is not None and self.publisher_node not in self.addresses:
            raise ValueError("Publisher must be one of the configured nodes")
        if self.mode not in {"regional", "regional_or_peer_dataplane"}:
            raise ValueError("Unknown ingress failure decision mode")
        if not self.records or len(set(self.records)) != len(self.records):
            raise ValueError("Managed DNS names must be unique and nonempty")
        if not self.protocols or len(set(self.protocols)) != len(self.protocols):
            raise ValueError("Explicit data-plane protocol coverage is required")
        if self.quorum < 2 or self.failure_rounds < 3 or self.recovery_rounds < 2:
            raise ValueError("Regional quorum and hysteresis are below their safety floors")
        if self.round_interval_seconds < 60 or self.hold_down_seconds < 900:
            raise ValueError("Probe cadence or hold-down is below its safety floor")
        windows = [self.hold_down_seconds, self.round_interval_seconds, self.probe_max_age_seconds, self.sync_max_age_seconds, self.dataplane_max_age_seconds]
        if any(type(value) is not int or value <= 0 for value in windows):
            raise ValueError("Freshness windows must be positive integer seconds")


@dataclass(frozen=True)
class Decision:
    state: dict
    action: str
    reason: str
    target: str | None = None


def configuration_id(settings):
    """Changing names, addresses or coverage invalidates earlier observations."""
    return hashlib.sha256(json.dumps(settings.__dict__, sort_keys=True).encode()).hexdigest()


def fresh(timestamp, now, maximum_age):
    return isinstance(timestamp, (int, float)) and 0 <= now - timestamp <= maximum_age


def regional(samples, settings, now):
    """Count only explicit fresh TCP outcomes from distinct Russian probe ASNs."""
    votes = {}
    for sample in samples:
        if not isinstance(sample, dict) or sample.get("country") != "ru":
            continue
        if not fresh(sample.get("checked_at"), now, settings.probe_max_age_seconds):
            continue
        node = sample.get("node")
        asn = sample.get("asn")
        outcome = sample.get("status")
        if not isinstance(node, str) or not isinstance(asn, str) or not asn:
            continue
        if outcome not in {"healthy", "failed"}:
            continue
        # Conflicting duplicate observations cannot manufacture a vote.
        previous = votes.get(node)
        votes[node] = (asn, outcome) if previous in (None, (asn, outcome)) else (asn, "unknown")
    healthy = {asn for asn, status in votes.values() if status == "healthy"}
    failed = {asn for asn, status in votes.values() if status == "failed"}
    if len(healthy) >= settings.quorum:
        return "healthy"
    # A positive observation means this is a partial regional outage. Hold ingress.
    if not healthy and len(failed) >= settings.quorum:
        return "failed"
    return "unknown"


def ready(node, readiness, manifest, settings, now, *, require_fresh_ack=True):
    value = readiness.get(node, {})
    if not isinstance(value, dict) or value.get("address") != settings.addresses[node]:
        return False
    sync = value.get("identity_sync", {})
    expected = (manifest.get("epoch"), manifest.get("revision"), manifest.get("sha256"))
    actual = (sync.get("epoch"), sync.get("revision"), sync.get("sha256"))
    valid_manifest = (
        isinstance(expected[0], int) and expected[0] > 0
        and isinstance(expected[1], int) and expected[1] >= 0
        and isinstance(expected[2], str) and re.fullmatch(r"[a-f0-9]{64}", expected[2])
    )
    if not valid_manifest or actual != expected or sync.get("applied") is not True:
        return False
    acknowledged_at = sync.get("checked_at")
    if type(acknowledged_at) not in (int, float) or not 0 < acknowledged_at <= now:
        return False
    if require_fresh_ack and not fresh(acknowledged_at, now, settings.sync_max_age_seconds):
        return False
    probes = value.get("dataplane", {})
    return all(
        probes.get(protocol, {}).get("authenticated") is True
        and probes[protocol].get("healthy") is True
        and probes[protocol].get("address") == settings.addresses[node]
        and fresh(probes[protocol].get("checked_at"), now, settings.dataplane_max_age_seconds)
        for protocol in settings.protocols
    )


def publishing_ready(pending, readiness, manifest, settings, now):
    """Finish one pinned publication after source loss, bounded to one hour."""
    target = pending["target"]
    value = readiness.get(target, {})
    sync = value.get("identity_sync", {})
    pinned_ack = pending.get("target_ack_at")
    current_ack = sync.get("checked_at")
    scopes = manifest.get("scopes")
    checks = value.get("scope_checks", {})
    return (
        fresh(pending.get("started_at"), now, 3600)
        and type(pinned_ack) in (int, float) and 0 < pinned_ack <= now
        and type(current_ack) in (int, float) and pinned_ack <= current_ack <= now
        and fresh(sync.get("verified_at"), now, settings.dataplane_max_age_seconds)
        and isinstance(scopes, dict) and bool(scopes) and set(checks) == set(scopes)
        and all(
            proof.get("applied") is True and proof.get("fresh") is True and proof.get("matches") is True
            for proof in checks.values()
        )
        and ready(target, readiness, manifest, settings, now, require_fresh_ack=False)
    )


def observed_active(authoritative, settings):
    """Both authoritative nameservers must agree on every managed singleton A."""
    if len(authoritative) < 2:
        return None
    active = set()
    for records in authoritative.values():
        if set(records) != set(settings.records):
            return None
        for values in records.values():
            if len(values) != 1:
                return None
            matches = [node for node, address in settings.addresses.items() if values == [address]]
            if not matches:
                return None
            active.add(matches[0])
    return active.pop() if len(active) == 1 else None


def pending_dns_safe(authoritative, pending, settings):
    """A partial add-before-remove update may contain only the two planned IPs."""
    if len(authoritative) < 2:
        return False
    allowed = {settings.addresses[pending["source"]], settings.addresses[pending["target"]]}
    return all(
        set(records) == set(settings.records)
        and all(values and set(values) <= allowed for values in records.values())
        for records in authoritative.values()
    )


def publisher_healthy(evidence, settings, now):
    health = evidence.get("publisher_health", {})
    registrar = health.get("registrar", {})
    if registrar.get("healthy") is not True or not fresh(registrar.get("checked_at"), now, settings.probe_max_age_seconds):
        return False
    authoritative = evidence.get("authoritative", {})
    if len(authoritative) < 2 or any(records != registrar.get("records") for records in authoritative.values()):
        return False
    origins = {
        origin["provider"] for origin in health.get("internet", [])
        if isinstance(origin, dict) and isinstance(origin.get("provider"), str)
        and origin.get("healthy") is True and origin.get("authenticated") is True
        and fresh(origin.get("checked_at"), now, settings.probe_max_age_seconds)
    }
    return len(origins) >= 2


def peer_dataplane_failure(source, evidence, settings, now):
    """One publisher vantage, explicitly accepted as a passive-data-plane mode."""
    if settings.mode != "regional_or_peer_dataplane" or settings.publisher_node in (None, source):
        return False
    peer = evidence.get("peer_reachability", {}).get(source, {})
    if (
        peer.get("address") != settings.addresses[source]
        or peer.get("observer") != settings.publisher_node
        or peer.get("round_id") != evidence.get("round_id")
        or not fresh(peer.get("checked_at"), now, settings.probe_max_age_seconds)
        or peer.get("ports") != {"22": "unreachable", "443": "unreachable"}
    ):
        return False
    # A fresh positive remote observation disproves total ingress loss.
    if any(
        sample.get("status") == "healthy" and fresh(sample.get("checked_at"), now, settings.probe_max_age_seconds)
        for sample in evidence.get("regional", {}).get(source, []) if isinstance(sample, dict)
    ):
        return False
    return publisher_healthy(evidence, settings, now)


def confirmed_protocol_failures(source, evidence, settings, now, seen):
    if settings.mode != "regional_or_peer_dataplane" or settings.publisher_node in (None, source):
        return []
    if not publisher_healthy(evidence, settings, now):
        return []
    value = evidence.get("readiness", {}).get(source, {})
    if value.get("address") != settings.addresses[source]:
        return []
    accepted = {"required_service_inactive", "required_listener_refused", "required_container_stopped", "required_interface_missing"}
    return [
        result["failure_receipt"] for protocol, result in value.get("dataplane", {}).items()
        if protocol in settings.protocols and result.get("address") == settings.addresses[source]
        and result.get("healthy") is False and result.get("confirmed_failed") is True
        and result.get("failure_class") in accepted
        and result.get("manifest_sha256") == evidence.get("manifest", {}).get("sha256")
        and isinstance(result.get("failure_receipt"), str) and result["failure_receipt"] not in seen
        and fresh(result.get("checked_at"), now, settings.dataplane_max_age_seconds)
        and fresh(result.get("failure_checked_at"), now, settings.dataplane_max_age_seconds)
    ]


def decide(state, evidence, settings, now):
    next_state = copy.deepcopy(state)
    next_state["control_writer_changed"] = False
    config_id = configuration_id(settings)
    if next_state.get("configuration_id", config_id) != config_id:
        return Decision(next_state, "hold", "configuration_changed")
    next_state["configuration_id"] = config_id
    if not fresh(evidence.get("checked_at"), now, settings.probe_max_age_seconds):
        next_state.update(failures=0, successes=0, backup_successes={})
        return Decision(next_state, "hold", "stale_round")
    authoritative = evidence.get("authoritative", {})
    active = observed_active(authoritative, settings)
    pending = next_state.get("pending")
    manifest = evidence.get("manifest", {})
    readiness = evidence.get("readiness", {})
    samples = evidence.get("regional", {})
    statuses = {node: regional(samples.get(node, []), settings, now) for node in settings.addresses}
    peer_failure = active is not None and peer_dataplane_failure(active, evidence, settings, now)
    failure_receipts = confirmed_protocol_failures(active, evidence, settings, now, set(next_state.get("seen_failure_receipts", []))) if active is not None else []
    basis = None
    if peer_failure:
        basis = "peer_dataplane"
    elif failure_receipts:
        basis = "confirmed_protocol_failure"
    elif active is not None and statuses[active] == "failed":
        basis = "regional"
    eligibility_basis = pending.get("basis") if pending else basis
    allow_unknown_target = eligibility_basis in {"peer_dataplane", "confirmed_protocol_failure"}
    regional_eligible = {
        node: (
            statuses[node] == "healthy"
            or (allow_unknown_target and settings.mode == "regional_or_peer_dataplane" and node == settings.publisher_node and statuses[node] != "failed" and publisher_healthy(evidence, settings, now))
        )
        for node in settings.addresses
    }
    target_ready = {node: ready(node, readiness, manifest, settings, now) and regional_eligible[node] for node in settings.addresses}
    if pending:
        target = pending["target"]
        if settings.publisher_node is not None and target != settings.publisher_node:
            return Decision(next_state, "hold", "pending_belongs_to_another_publisher")
        if active == target:
            next_state.update(active=target, pending=None, switched_at=now, failures=0, successes=0)
            return Decision(next_state, "hold", "dns_change_verified", target)
        source_failed = (
            statuses[pending["source"]] == "failed"
            or peer_dataplane_failure(pending["source"], evidence, settings, now)
            or bool(confirmed_protocol_failures(pending["source"], evidence, settings, now, set(next_state.get("seen_failure_receipts", []))))
        )
        if pending.get("phase", "publishing") == "planned" and not source_failed:
            next_state.update(pending=None, failures=0, successes=0, backup_successes={})
            return Decision(next_state, "hold", "source_recovered_or_unknown_before_publication")
        if not pending_dns_safe(authoritative, pending, settings):
            return Decision(next_state, "hold", "dns_drift_during_pending_change")
        current_generation = {key: manifest.get(key) for key in ("epoch", "revision", "sha256")}
        if pending.get("manifest") != current_generation:
            return Decision(next_state, "hold", "pending_generation_changed")
        publishing = pending.get("phase", "publishing") == "publishing"
        eligible = regional_eligible[target] and (publishing_ready(pending, readiness, manifest, settings, now) if publishing else target_ready[target])
        if not eligible:
            return Decision(next_state, "hold", "pending_target_not_ready")
        return Decision(next_state, "reconcile", "resume_pending_dns_change", target)
    if active is None:
        next_state.update(failures=0, successes=0)
        return Decision(next_state, "hold", "authoritative_dns_unknown")
    if next_state.get("active") != active:
        was_initialized = next_state.get("active") in settings.addresses
        next_state.update(active=active, failures=0, successes=0, switched_at=now if was_initialized else 0)
        return Decision(next_state, "hold", "adopt_observed_dns")
    round_id = evidence.get("round_id")
    if not isinstance(round_id, str) or not round_id or next_state.get("round_id") == round_id:
        return Decision(next_state, "hold", "round_already_processed_or_missing")
    if now - next_state.get("round_at", 0) < settings.round_interval_seconds:
        return Decision(next_state, "hold", "round_cadence")
    if now - next_state.get("round_at", now) > 2 * settings.round_interval_seconds:
        next_state.update(failures=0, successes=0, backup_successes={})
    next_state.update(round_id=round_id, round_at=now)
    requests = evidence.get("probe_requests", {})
    previous = set(next_state.get("seen_requests", []))
    independent = all(
        isinstance(requests.get(node), str) and requests[node]
        and requests[node] not in previous
        and all(sample.get("request_id") == requests[node] for sample in samples.get(node, []))
        for node in settings.addresses
    ) and len(set(requests.values())) == len(settings.addresses)
    if not independent and not peer_failure and not failure_receipts:
        next_state.update(failures=0, successes=0, backup_successes={})
        return Decision(next_state, "hold", "probe_requests_missing_or_reused")
    next_state["probe_requests"] = requests
    next_state["seen_requests"] = (next_state.get("seen_requests", []) + list(requests.values()))[-32:]
    next_state["seen_failure_receipts"] = (next_state.get("seen_failure_receipts", []) + failure_receipts)[-32:]
    next_state["backup_successes"] = {
        node: next_state.get("backup_successes", {}).get(node, 0) + 1 if target_ready[node] else 0
        for node in statuses
    }
    status = statuses[active]
    previous_failures = next_state.get("failures", 0) if next_state.get("failure_basis") in (None, basis) else 0
    next_state["failures"] = previous_failures + 1 if basis is not None else 0
    next_state["failure_basis"] = basis
    next_state["successes"] = next_state.get("successes", 0) + 1 if status == "healthy" else 0
    if basis is None:
        return Decision(next_state, "hold", "active_healthy" if status == "healthy" else "regional_evidence_unknown")
    target = next(node for node in settings.addresses if node != active)
    if settings.publisher_node is not None and target != settings.publisher_node:
        return Decision(next_state, "hold", "target_owned_by_another_publisher")
    if (
        not target_ready[target]
        or next_state["backup_successes"][target] < settings.recovery_rounds
    ):
        return Decision(next_state, "hold", "backup_not_ready")
    if next_state["failures"] < settings.failure_rounds:
        return Decision(next_state, "hold", "failure_hysteresis")
    if now - next_state.get("switched_at", 0) < settings.hold_down_seconds:
        return Decision(next_state, "hold", "sticky_hold_down")
    operation = hashlib.sha256(f"{config_id}:{round_id}:{active}:{target}".encode()).hexdigest()
    next_state["pending"] = {
        "id": operation, "source": active, "target": target, "started_at": now,
        "phase": "planned", "basis": basis,
        "manifest": {key: manifest.get(key) for key in ("epoch", "revision", "sha256")},
        "target_ack_at": readiness[target]["identity_sync"]["checked_at"],
    }
    reasons = {"regional": "regional_failure_confirmed", "peer_dataplane": "peer_dataplane_failure_confirmed", "confirmed_protocol_failure": "protected_protocol_failure_confirmed"}
    return Decision(next_state, "reconcile", reasons[basis], target)
