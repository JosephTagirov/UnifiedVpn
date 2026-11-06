import json
from pathlib import Path
import tempfile
import unittest
from dataclasses import replace
from unittest.mock import patch

from controller import atomic_json, load_json, run_once
import test_policy
from policy import configuration_id
from test_dns import MemoryProvider


class ControllerTests(unittest.TestCase):
    def setUp(self):
        self.fixtures = test_policy.FailoverPolicyTests()
        self.fixtures.setUp()
        self.config = {"enabled": True, "policy": self.fixtures.settings.__dict__}

    def test_offline_fixtures_cannot_invoke_registrar_writes(self):
        class ForbiddenProvider:
            def records_now(self):
                raise AssertionError("Offline fixture accessed registrar")
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory, "status.json")
            atomic_json(path, self.fixtures.state)
            for number in (1, 2, 3):
                evidence = self.fixtures.evidence(number)
                report = run_once(self.config, path, apply=True, fixture=evidence, provider=ForbiddenProvider(), clock=lambda: evidence["checked_at"])
            self.assertEqual(report["reason"], "live_writes_not_enabled")
            self.assertIs(load_json(path)["simulation_state"], True)
            self.assertIsNotNone(load_json(path)["pending"])

    def test_simulated_state_cannot_become_live_state(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory, "status.json")
            atomic_json(path, {"simulation_state": True})
            report = run_once(self.config, path, apply=True, clock=lambda: 10000)
            self.assertEqual(report["reason"], "simulation_state_requires_new_path")

    def test_invalid_evidence_holds_without_preserving_failure_streak(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory, "status.json")
            atomic_json(path, {**self.fixtures.state, "failures": 2})
            evidence = self.fixtures.evidence(1)
            evidence["readiness"] = None
            report = run_once(self.config, path, fixture=evidence, clock=lambda: 10000)
            self.assertEqual(report["reason"], "evidence_invalid")
            self.assertEqual(load_json(path)["failures"], 0)

    def test_atomic_state_is_json_and_has_no_leftover_temporary_file(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory, "status.json")
            atomic_json(path, {"active": "primary"})
            atomic_json(path, {"active": "standby"})
            self.assertEqual(json.loads(path.read_text()), {"active": "standby"})
            self.assertEqual([item.name for item in Path(directory).iterdir()], ["status.json"])

    def test_partial_registrar_state_resumes_after_ack_expiry_with_pinned_live_identity(self):
        self.fixtures.settings = replace(self.fixtures.settings, publisher_node="standby")
        self.fixtures.state["configuration_id"] = configuration_id(self.fixtures.settings)
        evidence = self.fixtures.expired_publication()
        config = {"enabled": True, "policy": self.fixtures.settings.__dict__}
        source, target = "192.0.2.10", "198.51.100.20"
        values = {name: [source] for name in self.fixtures.settings.records}
        values["panel.example.test"] = [source, target]
        provider = MemoryProvider(values)
        with tempfile.TemporaryDirectory() as directory:
            state_path = Path(directory, "status.json")
            atomic_json(state_path, self.fixtures.state)
            with patch("controller.collect", return_value=evidence):
                report = run_once(config, state_path, apply=True, provider=provider, clock=lambda: evidence["checked_at"])
            self.assertEqual(report["action"], "await_authoritative_dns")
            self.assertEqual(provider.records, {name: [target] for name in self.fixtures.settings.records})
            self.assertEqual(load_json(state_path)["pending"]["target_ack_at"], self.fixtures.state["pending"]["target_ack_at"])


if __name__ == "__main__":
    unittest.main()
