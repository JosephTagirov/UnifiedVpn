import unittest
from unittest.mock import Mock, patch
from types import SimpleNamespace
import hashlib
import json

import awg_export_endpoint as export


class ExportTests(unittest.TestCase):
    def configured(self, settings, *, machine="source-a", mode=0o600, container="legacy", public="same-server"):
        file = Mock()
        file.lstat.return_value = SimpleNamespace(st_uid=0, st_mode=mode, st_size=1024)
        file.is_symlink.return_value = False
        file.is_file.return_value = True
        file.read_text.return_value = json.dumps(settings)
        machine_file = Mock()
        machine_file.read_text.return_value = machine
        with patch.dict(export.os.environ, {"UNIFIEDVPN_AWG_ENDPOINTS_FILE": "/private.json"}), patch.object(export, "Path", side_effect=lambda path: machine_file if str(path) == "/etc/machine-id" else file):
            return export.target(container, public)

    def settings(self, origin="primary"):
        return {"machine_id": "source-a", "endpoints": [{"origin": origin, "container": "legacy", "server_public_key_sha256": hashlib.sha256(b"same-server").hexdigest(), "host": "origin.example", "port": {"primary": 18443, "old": 28443, "classic-old": 55424}[origin]}]}

    def test_actual_target_selects_each_fixed_origin(self):
        self.assertEqual(self.configured(self.settings()), "origin.example:18443")
        self.assertEqual(self.configured(self.settings("old")), "origin.example:28443")
        self.assertEqual(self.configured(self.settings("classic-old")), "origin.example:55424")

    def test_same_key_other_container_and_other_key_are_unmatched(self):
        self.assertIsNone(self.configured(self.settings(), container="native-container"))
        self.assertIsNone(self.configured(self.settings(), public="other-server"))

    def test_wrong_machine_and_duplicate_mapping_are_rejected(self):
        with self.assertRaises(ValueError):
            self.configured(self.settings(), machine="source-b")
        duplicate = self.settings()
        duplicate["endpoints"] *= 2
        with self.assertRaises(ValueError):
            self.configured(duplicate)

    def test_nonprivate_file_and_wrong_origin_port_are_rejected(self):
        with self.assertRaises(ValueError):
            self.configured(self.settings(), mode=0o644)
        incorrect = self.settings()
        incorrect["endpoints"][0]["port"] = 28443
        with self.assertRaises(ValueError):
            self.configured(incorrect)

    def test_unmatched_native_and_unknown_keys_retain_original(self):
        text = "[Interface]\nPrivateKey = original\n[Peer]\nPublicKey = server\nEndpoint = original.example:49311\n"
        with patch.object(export, "target", return_value=None):
            self.assertEqual(export.portable(text, "native-container"), text)
            self.assertEqual(export.endpoint("native-container", "server", "original.example:49311"), "original.example:49311")

    def test_only_endpoint_changes_and_crlf_comments_survive(self):
        text = "[Interface]\r\nPrivateKey = original\r\nS1 = 15\r\n[Peer]\r\nPublicKey = server\r\nPresharedKey = original-psk\r\nEndpoint = old.example:8443 # keep\r\nAllowedIPs = 0.0.0.0/0\r\n"
        with patch.object(export, "target", return_value="origin-a.example:18443"):
            updated = export.portable(text, "legacy-container")
        self.assertEqual(updated, text.replace("old.example:8443", "origin-a.example:18443"))

    def test_classic_requires_its_own_fixed_port_and_container(self):
        classic = self.settings("classic-old")
        classic["endpoints"][0]["container"] = "classic-source"
        self.assertIsNone(self.configured(classic, container="legacy"))
        self.assertEqual(self.configured(classic, container="classic-source"), "origin.example:55424")
        classic["endpoints"][0]["port"] = 28443
        with self.assertRaises(ValueError):
            self.configured(classic, container="classic-source")

    def test_managed_default_changes_only_for_matched_origin(self):
        with patch.object(export, "target", return_value="origin-b.example:28443") as target:
            self.assertEqual(export.endpoint("legacy-container", "server-key", "fallback.example:8443"), "origin-b.example:28443")
            target.assert_called_once_with("legacy-container", "server-key")

    def test_unconfigured_behavior_is_unchanged(self):
        with patch.dict(export.os.environ, {}, clear=True):
            self.assertEqual(export.endpoint("container", "server", "default"), "default")


if __name__ == "__main__":
    unittest.main()
