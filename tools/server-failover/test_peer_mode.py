import copy
from dataclasses import replace
import unittest

from policy import configuration_id, decide
import test_policy


class PeerModeTests(unittest.TestCase):
    def setUp(self):
        self.fixtures = test_policy.FailoverPolicyTests()
        self.fixtures.setUp()
        self.settings = replace(self.fixtures.settings, mode="regional_or_peer_dataplane", publisher_node="standby")
        self.state = {"active": "primary", "switched_at": 0, "configuration_id": configuration_id(self.settings)}

    def evidence(self, number):
        evidence = self.fixtures.evidence(number, primary="unknown", standby="unknown")
        evidence["probe_requests"] = {}
        evidence["regional"] = {"primary": [], "standby": []}
        now = evidence["checked_at"]
        evidence["publisher_health"] = {
            "registrar": {"healthy": True, "checked_at": now, "records": copy.deepcopy(next(iter(evidence["authoritative"].values())))},
            "internet": [
                {"provider": provider, "healthy": True, "authenticated": True, "checked_at": now}
                for provider in ["provider-one", "provider-two"]
            ],
        }
        evidence["peer_reachability"] = {"primary": {
            "address": self.settings.addresses["primary"], "observer": "standby", "round_id": evidence["round_id"],
            "ports": {"22": "unreachable", "443": "unreachable"}, "checked_at": now,
        }}
        return evidence

    def apply(self, evidence):
        decision = decide(self.state, evidence, self.settings, evidence["checked_at"])
        self.state = decision.state
        return decision

    def test_three_peer_hard_failure_rounds_can_switch_with_regional_api_unknown(self):
        for number in (1, 2):
            self.assertEqual(self.apply(self.evidence(number)).action, "hold")
        decision = self.apply(self.evidence(3))
        self.assertEqual(decision.action, "reconcile")
        self.assertEqual(decision.reason, "peer_dataplane_failure_confirmed")
        self.assertEqual(decision.state["pending"]["basis"], "peer_dataplane")
        self.assertIs(decision.state["control_writer_changed"], False)

    def test_regional_failure_requires_positive_target_quorum_in_combined_mode(self):
        for number in (1, 2, 3):
            evidence = self.evidence(number)
            regional_evidence = self.fixtures.evidence(number, primary="failed", standby="unknown")
            evidence.update(regional=regional_evidence["regional"], probe_requests=regional_evidence["probe_requests"])
            evidence["peer_reachability"]["primary"]["ports"] = {"22": "responsive", "443": "responsive"}
            self.assertEqual(self.apply(evidence).action, "hold")
        self.assertNotIn("pending", self.state)

    def test_confirmed_hard_failure_can_use_unknown_target_despite_regional_source_failure(self):
        for number in (1, 2, 3):
            evidence = self.evidence(number)
            regional_evidence = self.fixtures.evidence(number, primary="failed", standby="unknown")
            evidence.update(regional=regional_evidence["regional"], probe_requests=regional_evidence["probe_requests"])
            decision = self.apply(evidence)
        self.assertEqual(decision.action, "reconcile")
        self.assertEqual(decision.reason, "peer_dataplane_failure_confirmed")

    def test_regional_unknown_alone_never_creates_a_peer_failure(self):
        for number in (1, 2, 3, 4):
            evidence = self.evidence(number)
            evidence["peer_reachability"] = {}
            self.assertEqual(self.apply(evidence).action, "hold")
            self.assertEqual(self.state["failures"], 0)

    def test_either_responsive_port_or_unknown_local_error_prevents_switch(self):
        for status in ["responsive", "unknown"]:
            for port in ["22", "443"]:
                with self.subTest(status=status, port=port):
                    self.setUp()
                    for number in (1, 2, 3):
                        evidence = self.evidence(number)
                        evidence["peer_reachability"]["primary"]["ports"][port] = status
                        self.assertEqual(self.apply(evidence).action, "hold")

    def test_two_independent_authenticated_internet_providers_are_required(self):
        for mutation in ["missing", "duplicate", "failed", "unauthenticated", "stale"]:
            with self.subTest(mutation=mutation):
                self.setUp()
                for number in (1, 2, 3):
                    evidence = self.evidence(number)
                    internet = evidence["publisher_health"]["internet"]
                    if mutation == "missing":
                        internet.pop()
                    elif mutation == "duplicate":
                        internet[1]["provider"] = internet[0]["provider"]
                    elif mutation == "failed":
                        internet[1]["healthy"] = False
                    elif mutation == "unauthenticated":
                        internet[1]["authenticated"] = False
                    else:
                        internet[1]["checked_at"] -= 121
                    self.assertEqual(self.apply(evidence).action, "hold")

    def test_registrar_failure_or_dns_drift_prevents_peer_publication(self):
        for mutation in ["failed", "stale", "drift"]:
            with self.subTest(mutation=mutation):
                self.setUp()
                for number in (1, 2, 3):
                    evidence = self.evidence(number)
                    registrar = evidence["publisher_health"]["registrar"]
                    if mutation == "failed":
                        registrar["healthy"] = False
                    elif mutation == "stale":
                        registrar["checked_at"] -= 121
                    else:
                        registrar["records"][self.settings.records[0]] = [self.settings.addresses["standby"]]
                    self.assertEqual(self.apply(evidence).action, "hold")

    def test_positive_regional_observation_prevents_route_partition_takeover(self):
        for number in (1, 2, 3):
            evidence = self.evidence(number)
            evidence["regional"]["primary"] = [{"status": "healthy", "checked_at": evidence["checked_at"], "country": "ru", "asn": "AS1", "node": "ru-one"}]
            self.assertEqual(self.apply(evidence).action, "hold")

    def test_stale_peer_or_reused_round_cannot_complete_quorum(self):
        for number in (1, 2, 3):
            evidence = self.evidence(number)
            evidence["peer_reachability"]["primary"]["round_id"] = "old-round"
            self.assertEqual(self.apply(evidence).action, "hold")

    def test_unready_own_protocol_blocks_peer_failover(self):
        for number in (1, 2, 3):
            evidence = self.evidence(number)
            evidence["readiness"]["standby"]["dataplane"][self.settings.protocols[0]]["healthy"] = False
            self.assertEqual(self.apply(evidence).action, "hold")

    def test_source_publisher_cannot_promote_itself_away_from_current_dns(self):
        self.settings = replace(self.settings, publisher_node="primary")
        self.state["configuration_id"] = configuration_id(self.settings)
        for number in (1, 2, 3):
            evidence = self.evidence(number)
            self.assertEqual(self.apply(evidence).action, "hold")

    def protocol_failure(self, number):
        evidence = self.evidence(number)
        evidence["peer_reachability"]["primary"]["ports"] = {"22": "responsive", "443": "responsive"}
        protocol = self.settings.protocols[0]
        evidence["readiness"]["primary"]["dataplane"][protocol].update(
            healthy=False, authenticated=False, confirmed_failed=True,
            failure_class="required_service_inactive", failure_receipt=f"receipt-{number}",
            failure_checked_at=evidence["checked_at"], manifest_sha256=evidence["manifest"]["sha256"],
        )
        return evidence

    def test_confirmed_local_service_failure_can_switch_with_ssh_responsive(self):
        for number in (1, 2, 3):
            decision = self.apply(self.protocol_failure(number))
        self.assertEqual(decision.action, "reconcile")
        self.assertEqual(decision.reason, "protected_protocol_failure_confirmed")

    def test_confirmed_service_failure_can_use_unknown_target_when_source_regional_failure_is_known(self):
        for number in (1, 2, 3):
            evidence = self.protocol_failure(number)
            regional_evidence = self.fixtures.evidence(number, primary="failed", standby="unknown")
            evidence.update(regional=regional_evidence["regional"], probe_requests=regional_evidence["probe_requests"])
            decision = self.apply(evidence)
        self.assertEqual(decision.action, "reconcile")
        self.assertEqual(decision.reason, "protected_protocol_failure_confirmed")

    def test_confirmed_missing_container_interface_can_switch_with_ssh_responsive(self):
        for number in (1, 2, 3):
            evidence = self.protocol_failure(number)
            evidence["readiness"]["primary"]["dataplane"][self.settings.protocols[0]]["failure_class"] = "required_interface_missing"
            decision = self.apply(evidence)
        self.assertEqual(decision.action, "reconcile")
        self.assertEqual(decision.reason, "protected_protocol_failure_confirmed")

    def test_generic_probe_exception_cannot_become_confirmed_service_failure(self):
        for number in (1, 2, 3):
            evidence = self.protocol_failure(number)
            evidence["readiness"]["primary"]["dataplane"][self.settings.protocols[0]]["failure_class"] = "socket_timeout"
            self.assertEqual(self.apply(evidence).action, "hold")
            self.assertEqual(self.state["failures"], 0)

    def test_replayed_service_receipt_cannot_complete_failure_hysteresis(self):
        for number in (1, 2, 3):
            evidence = self.protocol_failure(number)
            evidence["readiness"]["primary"]["dataplane"][self.settings.protocols[0]]["failure_receipt"] = "one-old-receipt"
            self.assertEqual(self.apply(evidence).action, "hold")

    def test_mismatched_service_revision_and_stale_service_proof_hold(self):
        for mutation in ["revision", "stale"]:
            with self.subTest(mutation=mutation):
                self.setUp()
                for number in (1, 2, 3):
                    evidence = self.protocol_failure(number)
                    result = evidence["readiness"]["primary"]["dataplane"][self.settings.protocols[0]]
                    if mutation == "revision":
                        result["manifest_sha256"] = "b" * 64
                    else:
                        result["failure_checked_at"] -= 121
                    self.assertEqual(self.apply(evidence).action, "hold")


if __name__ == "__main__":
    unittest.main()
