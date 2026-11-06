import hashlib
import json
from pathlib import Path
import sqlite3
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

from scope_verify import awg_configuration, digest, files, local_services, verify, vless


class ScopeTests(unittest.TestCase):
    def test_classic_verifier_failure_becomes_an_unverified_scope(self):
        config = {"address": "192.0.2.1", "scopes": [{"scope": "classic-old-v1", "kind": "classic", "epoch": 1, "revision": 1, "sha256": "a" * 64}]}
        with patch('classic_awg_ha.verify_classic',side_effect=RuntimeError('unverified-runtime')):
            result = verify(config)
        self.assertFalse(result["scopes"]["classic-old-v1"]["applied"])
        self.assertEqual(result["scopes"]["classic-old-v1"]["reason"], "identity_scope_unverified")

    def test_missing_declared_interface_in_a_running_container_confirms_failure(self):
        def command(arguments, **keywords):
            if arguments[1] == "inspect":
                return SimpleNamespace(returncode=0, stdout='{"Running":true}')
            return SimpleNamespace(returncode=1 if arguments[-2] == "-e" else 0, stdout="")
        result = local_services({"services": [{"protocol": "awg", "container": "native-health", "interface": "awg0"}]}, command=command)
        self.assertEqual(result["awg"]["failure_class"], "required_interface_missing")

    def test_inaccessible_interface_inventory_is_unknown(self):
        def command(arguments, **keywords):
            if arguments[1] == "inspect":
                return SimpleNamespace(returncode=0, stdout='{"Running":true}')
            return SimpleNamespace(returncode=2, stdout="")
        result = local_services({"services": [{"protocol": "awg", "container": "native-health", "interface": "awg0"}]}, command=command)
        self.assertEqual(result["awg"]["status"], "unknown")

    def test_json_file_whitespace_does_not_change_the_identity_scope(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory, "profiles.json")
            value = {"profiles": [{"name": "health", "secret": "test-only"}]}
            spec = {"scope": "web-v1", "files": {"profiles": str(path)}, "canonical_json": ["profiles"]}
            path.write_text(json.dumps(value, indent=2))
            first = files(spec)
            path.write_text(json.dumps(value, separators=(",", ":")))
            self.assertEqual(files(spec), first)
            value["profiles"][0]["secret"] = "different-test-only"
            path.write_text(json.dumps(value))
            self.assertNotEqual(files(spec), first)

    def test_awg31_header_protection_and_rekey_options_are_identity_fields(self):
        text = "[Interface]\nPrivateKey = test-private\nListenPort = 12345\nHeaderProtectionKey = test-header\nRekeyAfterTime = 120\n[Peer]\nPublicKey = test-peer\nPresharedKey = test-psk\nAllowedIPs = 10.0.0.2/32\n"
        baseline = awg_configuration(text)
        self.assertNotEqual(awg_configuration(text.replace("test-header", "other-header")), baseline)
        self.assertNotEqual(awg_configuration(text.replace("RekeyAfterTime = 120", "RekeyAfterTime = 121")), baseline)
        self.assertEqual(awg_configuration("# I1 = old-comment\n" + text), baseline)

    def test_vless_canonical_scope_matches_operational_format_and_ignores_old_only_identity(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory, "x-ui.db")
            client = {"id": "identity-one", "enable": True, "subId": "subscription-one"}
            stream = {"network": "ws"}
            with sqlite3.connect(path) as connection:
                connection.execute("create table inbounds(id integer,enable integer,listen text,port integer,protocol text,stream_settings text,settings text)")
                connection.execute("insert into inbounds values(11,1,'127.0.0.1',10086,'vless',?,?)", (json.dumps(stream), json.dumps({"clients": [client, {"id": "old-only"}]})))
            connection.close()
            expected = digest({"scope": "vless-primary-current-v1", "transports": [{"id": 11, "enable": 1, "listen": "127.0.0.1", "port": 10086, "protocol": "vless", "stream_settings": stream}], "identities": [{"inbound_id": 11, "client": client}]})
            spec = {"scope": "vless-primary-current-v1", "database": str(path), "inbounds": [11], "client_ids": ["identity-one"]}
            self.assertEqual(vless(spec), expected)
            spec["client_ids"].append("missing")
            with self.assertRaises(ValueError):
                vless(spec)

    def test_only_loaded_inactive_service_and_explicit_stopped_container_confirm_failure(self):
        def stopped(arguments, **kwargs):
            if arguments[0] == "systemctl":
                return SimpleNamespace(returncode=0, stdout="LoadState=loaded\nActiveState=failed\n")
            return SimpleNamespace(returncode=0, stdout='{"Running":false}')
        config = {"services": [{"protocol": "vless", "unit": "x-ui.service"}, {"protocol": "awg", "container": "native-health"}]}
        result = local_services(config, command=stopped, clock=lambda: 10000)
        self.assertEqual(result["vless"]["status"], "confirmed_failed")
        self.assertEqual(result["awg"]["failure_class"], "required_container_stopped")
        def unknown(arguments, **kwargs):
            return SimpleNamespace(returncode=1, stdout="")
        self.assertTrue(all(value["status"] == "unknown" for value in local_services(config, command=unknown).values()))


if __name__ == "__main__":
    unittest.main()
