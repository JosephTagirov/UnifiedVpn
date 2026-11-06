import copy
import hashlib
import json
from pathlib import Path
import unittest
from unittest.mock import Mock, patch

import openflux_direct_endpoint as endpoint
import openflux_expiry as expiry


class DirectTests(unittest.TestCase):
    def setUp(self):
        self.session = {"name": "example", "secret": "test-secret", "context": "unchanged-context", "codec": "batched",
                        "transports": [{"type": "direct", "dial": "192.0.2.1:50011", "priority": 50}]}
        self.settings = {"machine_id": "source-node", "profiles": [{"device_id": "profile-a", "canonical_key_sha256": hashlib.sha256(b"test-secret").hexdigest(), "port": 50011, "host": "vpn.example.test"}]}
        self.policy = {"machine_id": "source-node", "epoch": 1, "revision": 1, "profiles": [{"id": "profile-a", "unit": "example-direct.service", "expires": 1100}]}

    def test_only_direct_dial_changes(self):
        original = copy.deepcopy(self.session)
        actual = endpoint.select(self.session, "profile-a", self.settings, "source-node")
        expected = copy.deepcopy(original)
        expected["transports"][0]["dial"] = "vpn.example.test:50011"
        self.assertEqual(actual, expected)
        self.assertEqual(self.session, original)

    def test_unknown_device_and_key_keep_the_original(self):
        self.assertEqual(endpoint.select(self.session, "unknown", self.settings, "source-node"), self.session)
        self.session["secret"] = "different-secret"
        self.assertEqual(endpoint.select(self.session, "profile-a", self.settings, "source-node"), self.session)

    def test_machine_port_and_transport_mismatch_are_rejected(self):
        for change in ["machine", "port", "transport", "duplicate"]:
            session, settings = copy.deepcopy(self.session), copy.deepcopy(self.settings)
            machine = "source-node"
            if change == "machine": machine = "foreign-node"
            elif change == "port": session["transports"][0]["dial"] = "192.0.2.1:50012"
            elif change == "transport": session["transports"][0]["type"] = "provider"
            else: settings["profiles"].append(copy.deepcopy(settings["profiles"][0]))
            with self.assertRaises(ValueError):
                endpoint.select(session, "profile-a", settings, machine)

    def test_future_deadline_never_stops_or_restarts_owner(self):
        stopper = Mock()
        with patch.object(expiry.Path, "read_text", return_value="source-node"):
            result = expiry.enforce(self.policy, {}, now=1000, observer=lambda p: {"owned": True, "active": True}, stopper=stopper)
        stopper.assert_not_called()
        self.assertTrue(result["healthy"])
        self.assertFalse(result["profiles"]["profile-a"]["expired"])

    def test_expired_cached_deadline_stops_only_its_owned_unit(self):
        observer = Mock(side_effect=[{"owned": True, "active": True}, {"owned": True, "active": False}])
        stopper = Mock()
        with patch.object(expiry.Path, "read_text", return_value="source-node"):
            result = expiry.enforce(self.policy, {}, now=1101, observer=observer, stopper=stopper)
        stopper.assert_called_once_with("example-direct.service")
        self.assertTrue(result["profiles"]["profile-a"]["deadline_enforced"])

    def test_unknown_ownership_prevents_stop(self):
        stopper = Mock()
        with patch.object(expiry.Path, "read_text", return_value="source-node"):
            with self.assertRaises(RuntimeError):
                expiry.enforce(self.policy, {}, now=1200, observer=lambda p: {"owned": False, "active": True}, stopper=stopper)
        stopper.assert_not_called()

    def test_infinite_deadline_and_reused_generation_are_rejected(self):
        with patch.object(expiry.Path, "read_text", return_value="source-node"):
            result = expiry.enforce(self.policy, {}, now=1000, observer=lambda p: {"owned": True, "active": True})
            for deadline in [0, True, float("inf"), float("nan")]:
                changed = copy.deepcopy(self.policy)
                changed["profiles"][0]["expires"] = deadline
                with self.assertRaises(ValueError): expiry.validate_policy(changed)
            changed = copy.deepcopy(self.policy)
            changed["profiles"][0]["expires"] = 1200
            with self.assertRaises(ValueError): expiry.enforce(changed, result, now=1001, observer=lambda p: {"owned": True, "active": True})

    def test_clock_rollback_does_not_restore_an_expired_permission(self):
        with patch.object(expiry.Path, "read_text", return_value="source-node"):
            previous = expiry.enforce(self.policy, {}, now=1200, observer=lambda p: {"owned": True, "active": False})
            stopper = Mock()
            result = expiry.enforce(self.policy, previous, now=1000, observer=Mock(side_effect=[{"owned": True, "active": True}, {"owned": True, "active": False}]), stopper=stopper)
        stopper.assert_called_once()
        self.assertTrue(result["profiles"]["profile-a"]["deadline_enforced"])

    def test_port_reservation_survives_absent_listeners_and_database_rows(self):
        settings = {"machine_id": "source-node", "reserved_ports": [50011]}
        self.assertEqual(endpoint.reservation_set(settings, "source-node"), {50011})
        for values in ([True], [50009], [50110], [50011, 50011], "50011"):
            with self.assertRaises(ValueError): endpoint.reservation_set({**settings, "reserved_ports": values}, "source-node")
        with self.assertRaises(ValueError): endpoint.reservation_set(settings, "other-node")


if __name__ == "__main__":
    unittest.main()
