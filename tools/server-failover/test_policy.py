"""Offline ingress-policy regressions using documentation-only addresses."""

import copy
import unittest

from policy import Settings, configuration_id, decide, observed_active, ready, regional


class FailoverPolicyTests(unittest.TestCase):
    def setUp(self):
        self.settings = Settings(
            addresses={"primary": "192.0.2.10", "standby": "198.51.100.20"},
            records=("example.test", "panel.example.test", "www.example.test", "tgw.example.test"),
            protocols=("vless_ws", "vless_xhttp", "awg", "rtc", "openflux", "telegram_web", "mtg"),
        )
        self.manifest = {"epoch": 3, "revision": 17, "sha256": "a" * 64}
        self.state = {
            "active": "primary",
            "switched_at": 0,
            "configuration_id": configuration_id(self.settings),
        }

    def evidence(self, number, *, now=None, active="primary", primary="failed", standby="healthy"):
        now = 10000 + (number - 1) * 60 if now is None else now
        requests = {node: f"{node}-request-{number}" for node in self.settings.addresses}
        regional_samples = {}
        readiness = {}
        for node, status in (("primary", primary), ("standby", standby)):
            address = self.settings.addresses[node]
            regional_samples[node] = [
                {"node": "ru-one", "asn": "AS64500", "country": "ru", "status": status,
                 "checked_at": now, "request_id": requests[node]},
                {"node": "ru-two", "asn": "AS64501", "country": "ru", "status": status,
                 "checked_at": now, "request_id": requests[node]},
            ]
            readiness[node] = {
                "address": address,
                "identity_sync": {**self.manifest, "applied": True, "checked_at": now},
                "dataplane": {
                    protocol: {"address": address, "authenticated": True, "healthy": True, "checked_at": now}
                    for protocol in self.settings.protocols
                },
            }
        authoritative = {
            nameserver: {record: [self.settings.addresses[active]] for record in self.settings.records}
            for nameserver in ("ns1.example.test", "ns2.example.test")
        }
        return {
            "round_id": f"round-{number}",
            "checked_at": now,
            "probe_requests": requests,
            "regional": regional_samples,
            "authoritative": authoritative,
            "manifest": copy.deepcopy(self.manifest),
            "readiness": readiness,
        }

    def apply(self, evidence, *, now=None):
        now = evidence["checked_at"] if now is None else now
        decision = decide(self.state, evidence, self.settings, now)
        self.state = decision.state
        self.assertIs(self.state["control_writer_changed"], False)
        return decision

    def begin_switch(self):
        for number in (1, 2, 3):
            decision = self.apply(self.evidence(number))
        self.assertEqual(decision.action, "reconcile")
        return decision

    def verify_switch(self):
        self.begin_switch()
        evidence = self.evidence(4, active="standby", primary="healthy")
        decision = self.apply(evidence)
        self.assertEqual(decision.reason, "dns_change_verified")
        self.assertEqual(self.state["active"], "standby")
        self.assertIsNone(self.state["pending"])
        return evidence["checked_at"]

    def test_three_independent_failure_rounds_are_required(self):
        for number in (1, 2):
            decision = self.apply(self.evidence(number))
            self.assertEqual(decision.action, "hold")
        decision = self.apply(self.evidence(3))
        self.assertEqual(decision.action, "reconcile")
        self.assertEqual(decision.target, "standby")
        self.assertEqual(self.state["active"], "primary")
        self.assertEqual(self.state["pending"]["source"], "primary")
        self.assertEqual(self.state["pending"]["target"], "standby")

    def test_first_observation_adopts_dns_without_inventing_a_recent_switch(self):
        self.state = {}
        decision = self.apply(self.evidence(1))
        self.assertEqual(decision.reason, "adopt_observed_dns")
        self.assertEqual(self.state["switched_at"], 0)

    def test_all_managed_aliases_and_nameservers_must_agree(self):
        evidence = self.evidence(1)
        self.assertEqual(observed_active(evidence["authoritative"], self.settings), "primary")
        mutations = (
            lambda records: records.pop("www.example.test"),
            lambda records: records.update({"panel.example.test": ["198.51.100.20"]}),
            lambda records: records.update({"panel.example.test": ["192.0.2.10", "198.51.100.20"]}),
            lambda records: records.update({"unmanaged.example.test": ["192.0.2.10"]}),
        )
        for mutate in mutations:
            with self.subTest(mutation=mutate):
                changed = copy.deepcopy(evidence["authoritative"])
                mutate(changed["ns1.example.test"])
                self.assertIsNone(observed_active(changed, self.settings))
        self.assertIsNone(observed_active({"ns1.example.test": evidence["authoritative"]["ns1.example.test"]}, self.settings))

    def test_partial_regional_partition_holds_ingress(self):
        for number in (1, 2, 3, 4):
            evidence = self.evidence(number)
            evidence["regional"]["primary"].append(
                {"node": "ru-three", "asn": "AS64502", "country": "ru", "status": "healthy",
                 "checked_at": evidence["checked_at"], "request_id": evidence["probe_requests"]["primary"]}
            )
            decision = self.apply(evidence)
            self.assertEqual(decision.action, "hold")
            self.assertEqual(self.state["failures"], 0)

    def test_failure_votes_need_two_different_russian_asns(self):
        evidence = self.evidence(1)
        samples = evidence["regional"]["primary"]
        self.assertEqual(regional(samples, self.settings, evidence["checked_at"]), "failed")
        samples[1]["asn"] = samples[0]["asn"]
        self.assertEqual(regional(samples, self.settings, evidence["checked_at"]), "unknown")
        samples[1]["asn"] = "AS64501"
        samples[1]["country"] = "de"
        self.assertEqual(regional(samples, self.settings, evidence["checked_at"]), "unknown")

    def test_duplicate_observer_cannot_manufacture_an_independent_vote(self):
        evidence = self.evidence(1)
        samples = evidence["regional"]["primary"]
        samples[1]["node"] = samples[0]["node"]
        self.assertEqual(regional(samples, self.settings, evidence["checked_at"]), "unknown")

    def test_stale_regional_samples_do_not_vote(self):
        evidence = self.evidence(1)
        for sample in evidence["regional"]["primary"]:
            sample["checked_at"] -= self.settings.probe_max_age_seconds + 1
        self.assertEqual(regional(evidence["regional"]["primary"], self.settings, evidence["checked_at"]), "unknown")

    def test_unknown_and_api_errors_reset_failure_streak(self):
        for status in ("unknown", "api_error"):
            with self.subTest(status=status):
                self.setUp()
                self.apply(self.evidence(1))
                self.apply(self.evidence(2))
                decision = self.apply(self.evidence(3, primary=status))
                self.assertEqual(decision.action, "hold")
                self.assertEqual(self.state["failures"], 0)
                self.assertEqual(self.apply(self.evidence(4)).action, "hold")
                self.assertEqual(self.apply(self.evidence(5)).action, "hold")
                self.assertEqual(self.apply(self.evidence(6)).action, "reconcile")

    def test_stale_round_resets_failure_streak(self):
        self.apply(self.evidence(1))
        self.apply(self.evidence(2))
        evidence = self.evidence(3)
        decision = self.apply(evidence, now=evidence["checked_at"] + self.settings.probe_max_age_seconds + 1)
        self.assertEqual(decision.reason, "stale_round")
        self.assertEqual(self.state["failures"], 0)

    def test_unknown_authoritative_dns_resets_failure_streak(self):
        self.apply(self.evidence(1))
        self.apply(self.evidence(2))
        evidence = self.evidence(3)
        evidence["authoritative"] = {}
        decision = self.apply(evidence)
        self.assertEqual(decision.reason, "authoritative_dns_unknown")
        self.assertEqual(self.state["failures"], 0)

    def test_duplicate_round_does_not_increment_failure_streak(self):
        self.apply(self.evidence(1))
        evidence = self.evidence(2)
        evidence["round_id"] = "round-1"
        decision = self.apply(evidence)
        self.assertEqual(decision.action, "hold")
        self.assertEqual(self.state["failures"], 1)

    def test_immediately_reused_request_ids_reset_failure_streak(self):
        first = self.evidence(1)
        self.apply(first)
        evidence = self.evidence(2)
        evidence["probe_requests"] = first["probe_requests"]
        evidence["regional"] = first["regional"]
        decision = self.apply(evidence)
        self.assertEqual(decision.reason, "probe_requests_missing_or_reused")
        self.assertEqual(self.state["failures"], 0)

    def test_nonadjacent_request_replay_cannot_complete_three_round_quorum(self):
        first = self.evidence(1)
        self.apply(first)
        self.apply(self.evidence(2))
        evidence = self.evidence(3)
        evidence["probe_requests"] = first["probe_requests"]
        evidence["regional"] = first["regional"]
        decision = self.apply(evidence)
        self.assertEqual(decision.action, "hold")
        self.assertEqual(self.state["failures"], 0)

    def test_one_request_cannot_describe_both_target_addresses(self):
        evidence = self.evidence(1)
        shared_request = evidence["probe_requests"]["primary"]
        evidence["probe_requests"]["standby"] = shared_request
        for sample in evidence["regional"]["standby"]:
            sample["request_id"] = shared_request
        decision = self.apply(evidence)
        self.assertEqual(decision.action, "hold")
        self.assertEqual(self.state["failures"], 0)

    def test_sample_request_id_must_match_the_node_request(self):
        evidence = self.evidence(1)
        evidence["regional"]["primary"][0]["request_id"] = "another-request"
        decision = self.apply(evidence)
        self.assertEqual(decision.reason, "probe_requests_missing_or_reused")
        self.assertEqual(self.state["failures"], 0)

    def test_missing_request_identifiers_hold_and_reset(self):
        self.apply(self.evidence(1))
        evidence = self.evidence(2)
        evidence.pop("probe_requests")
        decision = self.apply(evidence)
        self.assertEqual(decision.reason, "probe_requests_missing_or_reused")
        self.assertEqual(self.state["failures"], 0)

    def test_identity_and_dataplane_readiness_are_bound_to_node_address(self):
        evidence = self.evidence(1)
        self.assertTrue(ready("standby", evidence["readiness"], evidence["manifest"], self.settings, evidence["checked_at"]))
        for address in (None, "192.0.2.10"):
            with self.subTest(readiness_address=address):
                changed = copy.deepcopy(evidence["readiness"])
                changed["standby"]["address"] = address
                self.assertFalse(ready("standby", changed, evidence["manifest"], self.settings, evidence["checked_at"]))
            with self.subTest(dataplane_address=address):
                changed = copy.deepcopy(evidence["readiness"])
                changed["standby"]["dataplane"]["awg"]["address"] = address
                self.assertFalse(ready("standby", changed, evidence["manifest"], self.settings, evidence["checked_at"]))

    def test_stale_or_mismatched_identity_readiness_is_rejected(self):
        evidence = self.evidence(1)
        mutations = {
            "epoch": 2,
            "revision": 16,
            "sha256": "b" * 64,
            "applied": False,
            "checked_at": evidence["checked_at"] - self.settings.sync_max_age_seconds - 1,
        }
        for field, value in mutations.items():
            with self.subTest(field=field):
                changed = copy.deepcopy(evidence["readiness"])
                changed["standby"]["identity_sync"][field] = value
                self.assertFalse(ready("standby", changed, evidence["manifest"], self.settings, evidence["checked_at"]))

    def test_each_protocol_needs_fresh_authenticated_healthy_dataplane(self):
        evidence = self.evidence(1)
        for protocol in self.settings.protocols:
            for field, value in (
                ("authenticated", False),
                ("healthy", False),
                ("checked_at", evidence["checked_at"] - self.settings.dataplane_max_age_seconds - 1),
            ):
                with self.subTest(protocol=protocol, field=field):
                    changed = copy.deepcopy(evidence["readiness"])
                    changed["standby"]["dataplane"][protocol][field] = value
                    self.assertFalse(ready("standby", changed, evidence["manifest"], self.settings, evidence["checked_at"]))
            changed = copy.deepcopy(evidence["readiness"])
            changed["standby"]["dataplane"].pop(protocol)
            self.assertFalse(ready("standby", changed, evidence["manifest"], self.settings, evidence["checked_at"]))

    def test_unready_backup_never_receives_ingress(self):
        for number in (1, 2, 3, 4):
            evidence = self.evidence(number)
            evidence["readiness"]["standby"]["identity_sync"]["revision"] -= 1
            self.assertEqual(self.apply(evidence).action, "hold")
        self.assertNotIn("pending", self.state)

    def test_partial_dns_update_can_resume_only_the_planned_two_addresses(self):
        self.begin_switch()
        evidence = self.evidence(4)
        evidence["authoritative"]["ns1.example.test"]["panel.example.test"] = ["192.0.2.10", "198.51.100.20"]
        self.assertEqual(self.apply(evidence).reason, "resume_pending_dns_change")
        evidence = self.evidence(5)
        evidence["authoritative"]["ns1.example.test"]["panel.example.test"] = ["203.0.113.99"]
        self.assertEqual(self.apply(evidence).reason, "dns_drift_during_pending_change")

    def test_pending_dns_change_waits_when_backup_readiness_expires(self):
        self.begin_switch()
        evidence = self.evidence(4)
        evidence["readiness"]["standby"]["dataplane"]["awg"]["authenticated"] = False
        self.assertEqual(self.apply(evidence).reason, "pending_target_not_ready")

    def test_verified_switch_starts_hold_down_and_preserves_writer(self):
        switched_at = self.verify_switch()
        self.assertEqual(self.state["switched_at"], switched_at)
        for number in (5, 6, 7):
            decision = self.apply(self.evidence(number, active="standby", primary="healthy", standby="failed"))
            self.assertEqual(decision.action, "hold")
        self.assertEqual(decision.reason, "sticky_hold_down")
        self.assertEqual(self.state["active"], "standby")

    def test_healthy_backup_remains_selected_after_primary_recovers(self):
        switched_at = self.verify_switch()
        evidence = self.evidence(5, now=switched_at + self.settings.hold_down_seconds + 60,
                                 active="standby", primary="healthy", standby="healthy")
        decision = self.apply(evidence)
        self.assertEqual(decision.action, "hold")
        self.assertEqual(self.state["active"], "standby")
        self.assertIsNone(self.state["pending"])

    def test_reverse_failover_is_allowed_after_hold_down_and_new_failure_quorum(self):
        switched_at = self.verify_switch()
        first = switched_at + self.settings.hold_down_seconds + 60
        for number in (5, 6, 7):
            evidence = self.evidence(number, now=first + (number - 5) * 60,
                                     active="standby", primary="healthy", standby="failed")
            decision = self.apply(evidence)
        self.assertEqual(decision.action, "reconcile")
        self.assertEqual(decision.target, "primary")

    def test_long_monitoring_gap_restarts_failure_quorum(self):
        self.apply(self.evidence(1))
        self.apply(self.evidence(2))
        restart = 10060 + 3600
        decision = self.apply(self.evidence(3, now=restart))
        self.assertEqual(decision.action, "hold")
        self.assertLessEqual(self.state["failures"], 1)
        self.assertEqual(self.apply(self.evidence(4, now=restart + 60)).action, "hold")
        self.assertEqual(self.apply(self.evidence(5, now=restart + 120)).action, "reconcile")

    def test_configuration_change_requires_explicit_state_reinitialization(self):
        self.state["configuration_id"] = "0" * 64
        self.assertEqual(self.apply(self.evidence(1)).reason, "configuration_changed")

    def test_decision_does_not_mutate_input_state(self):
        before = copy.deepcopy(self.state)
        decide(self.state, self.evidence(1), self.settings, 10000)
        self.assertEqual(self.state, before)


if __name__ == "__main__":
    unittest.main()
