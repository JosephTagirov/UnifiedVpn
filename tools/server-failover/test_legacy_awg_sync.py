import base64
import copy
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import legacy_awg_sync as worker


RAW = b"[Interface]\nPrivateKey = server-secret\nListenPort = 8443\nJc = 4\n\n[Peer]\nPublicKey = client-a\nPresharedKey = psk-a\nAllowedIPs = 10.0.0.2/32\n"


def fixture(raw=RAW, revision=1):
    spec = {"scope": "legacy-awg-primary-v1", "origin": "primary", "source_machine_id": "machine-a",
            "source_container_id": "original-a", "server_public_key_sha256": "public-sha",
            "interface_sha256": worker.digest(worker.configuration(raw)["interface"])}
    manifest = {key: spec[key] for key in spec}
    manifest.update(epoch=1, revision=revision, payload_sha256=worker.sha(raw),
                    sha256=worker.scope_hash(spec["scope"], raw), peer_count=len(worker.configuration(raw)["peers"]))
    return spec, {"manifest": manifest, "configuration": base64.b64encode(raw).decode()}


class LegacyTests(unittest.TestCase):
    def test_scope_preserves_internal_port_and_isolates_origins(self):
        self.assertNotEqual(worker.scope_hash("legacy-awg-primary-v1", RAW), worker.scope_hash("legacy-awg-old-v1", RAW))
        self.assertEqual(worker.configuration(RAW)["interface"]["ListenPort"], "8443")

    def test_origin_is_not_selected_by_shared_server_key(self):
        spec, payload = fixture()
        for field in ("source_machine_id", "source_container_id", "origin", "scope"):
            altered = copy.deepcopy(payload)
            altered["manifest"][field] = "other-origin"
            with self.assertRaises(RuntimeError):
                worker.validate_payload(spec, altered)

    def test_source_deletion_removes_peer_and_changes_scope(self):
        raw = RAW.split(b"[Peer]")[0]
        spec, payload = fixture(raw, 2)
        self.assertEqual(worker.validate_payload(spec, payload), raw)
        self.assertEqual(payload["manifest"]["peer_count"], 0)
        self.assertNotEqual(worker.scope_hash(spec["scope"], RAW), payload["manifest"]["sha256"])

    def test_duplicate_peer_or_allowed_ip_is_rejected(self):
        for public in (b"client-a", b"client-b"):
            duplicated = RAW + b"\n[Peer]\nPublicKey = " + public + b"\nAllowedIPs = 10.0.0.2/32\n"
            with self.assertRaises(RuntimeError):
                worker.configuration(duplicated)

    def test_interface_drift_is_rejected(self):
        spec, payload = fixture()
        raw = RAW.replace(b"Jc = 4", b"Jc = 5")
        _, altered = fixture(raw)
        altered["manifest"]["interface_sha256"] = spec["interface_sha256"]
        with self.assertRaises(RuntimeError):
            worker.validate_payload(spec, altered)

    def test_unscoped_interface_field_requires_maintenance(self):
        for field in (b"FwMark = 1234\n", b"UnexpectedTransportSetting = 1\n"):
            with self.assertRaises(RuntimeError):
                worker.configuration(RAW.replace(b"Jc = 4\n", b"Jc = 4\n" + field))

    def test_inode_is_preserved(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "server.conf"
            path.write_bytes(RAW)
            original = path.stat().st_ino
            worker.replace_bound(path, RAW + b"# update\n")
            self.assertEqual(path.stat().st_ino, original)
            self.assertEqual(path.read_bytes(), RAW + b"# update\n")

    def test_same_revision_changed_payload_is_rejected(self):
        spec, payload = fixture()
        config = {"replicas": {"primary": spec}, "state_root": tempfile.gettempdir()}
        changed = copy.deepcopy(payload)
        changed["manifest"]["payload_sha256"] = "different"
        with patch.object(worker, "receiver_status", return_value={"manifest": changed["manifest"]}):
            with self.assertRaises(RuntimeError):
                worker.receive(config, "primary", payload)

    def test_ack_does_not_refresh_when_source_changed(self):
        spec, payload = fixture()
        config = {"sources": {"primary": spec}}
        desired = {key: payload["manifest"][key] for key in ("scope", "epoch", "revision", "sha256")}
        desired["sha256"] = "stale"
        with patch.object(worker, "source_snapshot", return_value=payload):
            with self.assertRaises(RuntimeError):
                worker.acknowledge(config, "primary", desired)

    def test_pending_recovery_checks_applied_lineage_before_writes(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            spec, before = fixture()
            _, after = fixture(RAW + b"# generation two\n", 2)
            journal = root / "pending.json"
            state = root / "applied.json"
            journal.write_text(json.dumps({"before": before, "after": after}))
            unrelated = copy.deepcopy(before["manifest"])
            unrelated["revision"] = 3
            state.write_text(json.dumps(unrelated))
            with patch.object(worker, "trusted_json", side_effect=lambda path, **kw: json.loads(Path(path).read_text())), patch.object(worker, "runtime_apply") as apply:
                with self.assertRaises(RuntimeError):
                    worker.recover_pending({}, spec, journal, state)
                apply.assert_not_called()

    def test_pending_recovery_completes_or_restores_owned_generation(self):
        for saved_after, live_after in [(True, True), (True, False), (False, True)]:
            with self.subTest(saved_after=saved_after, live_after=live_after), tempfile.TemporaryDirectory() as folder:
                root = Path(folder)
                spec, before = fixture()
                next_raw = RAW + b"\n[Peer]\nPublicKey = client-b\nAllowedIPs = 10.0.0.3/32\n"
                _, after = fixture(next_raw, 2)
                path = root / "server.conf"
                path.write_bytes(next_raw if saved_after else RAW)
                spec.update(config_path=str(path), container="owned-replica")
                journal, state = root / "pending.json", root / "applied.json"
                journal.write_text(json.dumps({"before": before, "after": after}))
                state.write_text(json.dumps(before["manifest"]))
                with patch.object(worker, "trusted_json", side_effect=lambda source, **kw: json.loads(Path(source).read_text())), patch.object(worker, "identity_guard"), patch.object(worker, "command", return_value=next_raw if live_after else RAW), patch.object(worker, "runtime_apply") as apply, patch.object(worker, "read_pair"):
                    worker.recover_pending({}, spec, journal, state)
                completed = saved_after and live_after
                self.assertEqual(path.read_bytes(), next_raw if completed else RAW)
                self.assertEqual(json.loads(state.read_text())["revision"], 2 if completed else 1)
                self.assertFalse(journal.exists())
                apply.assert_called_once()

    def test_empty_aliases_are_rejected_before_iptables(self):
        with patch.object(worker, "command") as command:
            with self.assertRaises(RuntimeError):
                worker.port_aliases({"sources": {}})
            command.assert_not_called()

    def test_foreign_alias_rule_is_rejected_before_flush(self):
        spec = {"public_port": 18443}
        item = {"NetworkSettings": {"Networks": {"bridge": {"IPAddress": "172.17.0.2"}}}}
        with tempfile.TemporaryDirectory() as folder:
            config = {"sources": {"primary": spec}, "state_root": folder}
            foreign = b"-N UVPN_LEGACY_ALIAS\n-A UVPN_LEGACY_ALIAS -p tcp --dport 18443 -j DNAT --to-destination 192.0.2.1:8443\n"
            with patch.object(worker, "read_pair", return_value=(item, RAW)), patch.object(worker.subprocess, "run") as process, patch.object(worker, "command", return_value=foreign) as command:
                process.return_value.returncode = 0
                with self.assertRaises(RuntimeError):
                    worker.port_aliases(config)
                self.assertEqual(command.call_count, 1)


if __name__ == "__main__":
    unittest.main()
