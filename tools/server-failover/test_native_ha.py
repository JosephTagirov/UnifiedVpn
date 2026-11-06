"""Native replica authority, drift, rollback and fresh-acknowledgment regressions."""
import copy
import json
import os
from pathlib import Path
import re
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

import native_ha
import native_ha_sync


BASE = b"""[Interface]
PrivateKey = nonsecret-test-server-key
Address = 192.0.2.1/24
ListenPort = 43559
Jc = 4
S1 = 32
S2 = 48
S3 = 16
S4 = 12
H1 = 100-200
HeaderProtectionKey = nonsecret-test-header-key
ContentPaddingAddition = 10-100
RekeyAfterTime = 100-120
RekeyTimeout = 3-7
RejectAfterTime = 150-180
KeepaliveTimeout = 5-15
MaxHandshakeAttempts = 15-20
RandomTrailers = on
DisableCookies = on

[Peer]
PublicKey = test-peer-one
PresharedKey = nonsecret-test-psk-one
AllowedIPs = 192.0.2.2/32
"""
ADDED = BASE + b"\n[Peer]\nPublicKey = test-peer-two\nPresharedKey = nonsecret-test-psk-two\nAllowedIPs = 192.0.2.3/32\n"


class NativeReplicaTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        root_patch = patch.object(native_ha, "ROOT", self.root)
        root_patch.start()
        self.addCleanup(root_patch.stop)
        if not hasattr(os, "fchmod"):
            mode_patch = patch.object(os, "fchmod", lambda descriptor, mode: None, create=True)
            mode_patch.start()
            self.addCleanup(mode_patch.stop)
        self.origin = "primary-native"
        self.name = "unifiedvpn-native-primary-ha"
        self.active = self.root / "active" / self.origin
        self.incoming = self.root / "incoming" / self.origin
        self.request = {"origin": self.origin, "sourceId": "a" * 64, "serverPublicKeySha256": "b" * 64}
        self.old_files = {
            "awg0.conf": BASE, "clientsTable": b"[]", "wireguard_server_private_key.key": b"nonsecret-test-server-key",
            "wireguard_server_public_key.key": b"nonsecret-test-public-key", "wireguard_psk.key": b"nonsecret-test-default-psk",
        }
        self.new_files = {**self.old_files, "awg0.conf": ADDED, "clientsTable": b'[{"clientId":"test-peer-two"}]'}
        for name, value in self.old_files.items():
            native_ha.write(self.active / name, value)
        native_ha.write(self.active / "runtime.conf", native_ha.runtime_configuration(BASE))
        self.previous = self.manifest(self.old_files, 1)
        self.previous.update(targetName=self.name, targetId="f" * 64, activeDirectory=str(self.active))
        native_ha.document(self.root / "replicas" / (self.origin + ".json"), self.previous)
        self.next = self.manifest(self.new_files, 2)
        self.stage(self.next, self.new_files)
        self.runtime = BASE

    def manifest(self, files, revision):
        hashes = {name: native_ha.sha(value) for name, value in files.items()}
        return {
            **self.request, "epoch": 1, "revision": revision, "files": hashes,
            "payloadSha256": native_ha.sha(json.dumps(hashes, sort_keys=True).encode()),
            "identitySha256": native_ha.identity(files["awg0.conf"]), "imageId": "sha256:" + "1" * 64,
            "address": "192.0.2.1/24", "mtu": 1420, "peerCount": len(native_ha.parse(files["awg0.conf"])[1]),
        }

    def stage(self, manifest, files):
        for name, value in files.items():
            native_ha.write(self.incoming / name, value)
        native_ha.document(self.incoming / "manifest.json", manifest)

    def live(self, name):
        self.assertEqual(name, self.name)
        raw = (self.active / "awg0.conf").read_bytes()
        if native_ha.canonical(raw) != native_ha.canonical(self.runtime):
            raise RuntimeError("Test runtime and persisted configuration disagree")
        return {"containerId": "f" * 64, "startedAt": "2026-01-01T00:00:00Z", "restartCount": 0,
                "identitySha256": native_ha.identity(raw), "peerCount": len(native_ha.parse(raw)[1])}

    def inspected(self, name):
        self.assertEqual(name, self.name)
        return {"Id": "f" * 64, "Config": {"Labels": {native_ha.LABEL: self.origin}}}

    def read(self, name, filename):
        self.assertEqual(name, self.name)
        return (self.active / filename).read_bytes()

    def apply(self, command):
        with patch.object(native_ha, "inspect", self.inspected), patch.object(native_ha, "invariant", self.live), \
             patch.object(native_ha, "read", self.read), patch.object(native_ha, "command", command):
            return native_ha.apply_snapshot(self.request)

    def test_all_v31_header_and_timing_parameters_affect_identity(self):
        for field in ("HeaderProtectionKey", "ContentPaddingAddition", "RekeyAfterTime", "RekeyTimeout", "RejectAfterTime",
                      "KeepaliveTimeout", "MaxHandshakeAttempts", "RandomTrailers", "DisableCookies"):
            with self.subTest(field=field):
                lines = [line + "-changed" if line.startswith(field + " = ") else line for line in BASE.decode().splitlines()]
                self.assertNotEqual(native_ha.identity(BASE), native_ha.identity("\n".join(lines).encode()))

    def test_unknown_peer_is_part_of_complete_identity(self):
        self.assertNotEqual(native_ha.identity(BASE), native_ha.identity(ADDED))

    def test_stale_generation_is_rejected_before_any_mutation(self):
        self.next["revision"] = 0
        self.stage(self.next, self.new_files)
        with self.assertRaisesRegex(RuntimeError, "Stale native generation"):
            self.apply(lambda *args, **kwargs: self.fail("Stale generation invoked a command"))
        self.assertEqual((self.active / "awg0.conf").read_bytes(), BASE)

    def test_source_authority_change_is_rejected_before_any_mutation(self):
        self.next["sourceId"] = "c" * 64
        self.stage(self.next, self.new_files)
        with self.assertRaisesRegex(RuntimeError, "fixed source authority"):
            self.apply(lambda *args, **kwargs: self.fail("Foreign source invoked a command"))
        self.assertEqual((self.active / "awg0.conf").read_bytes(), BASE)

    def test_independent_owner_peer_is_preserved_and_blocks_synchronization(self):
        native_ha.write(self.active / "awg0.conf", ADDED)
        self.runtime = ADDED
        with self.assertRaisesRegex(RuntimeError, "independent peer changes"):
            self.apply(lambda *args, **kwargs: self.fail("Owner peer triggered a mutating command"))
        self.assertEqual((self.active / "awg0.conf").read_bytes(), ADDED)
        self.assertFalse((self.root / "replica-backups").exists())

    def test_independent_owner_metadata_is_preserved_and_blocks_synchronization(self):
        changed = b'[{"clientId":"test-peer-one","userData":{"clientName":"Owner changed label"}}]'
        native_ha.write(self.active / "clientsTable", changed)
        with self.assertRaisesRegex(RuntimeError, "independent file changes"):
            self.apply(lambda *args, **kwargs: self.fail("Owner metadata triggered a mutating command"))
        self.assertEqual((self.active / "clientsTable").read_bytes(), changed)

    def test_corrupt_transfer_is_rejected_before_runtime_write(self):
        self.next["files"]["clientsTable"] = "0" * 64
        self.stage(self.next, self.new_files)
        with self.assertRaisesRegex(RuntimeError, "configuration digest mismatch"):
            self.apply(lambda *args, **kwargs: self.fail("Corrupt transfer invoked a command"))

    def test_protocol_identity_change_requires_explicit_maintenance(self):
        changed_files = {**self.new_files, "awg0.conf": ADDED.replace(b"ListenPort = 43559", b"ListenPort = 43560")}
        self.stage(self.manifest(changed_files, 2), changed_files)
        with self.assertRaisesRegex(RuntimeError, "explicit maintenance"):
            self.apply(lambda *args, **kwargs: self.fail("Protocol change invoked a command"))
        self.assertEqual((self.active / "awg0.conf").read_bytes(), BASE)

    def test_new_generation_syncs_only_its_replica_without_restart(self):
        calls = []
        def command(arguments, data=None, check=True):
            calls.append(arguments)
            self.assertEqual(arguments, ["docker", "exec", "-i", self.name, "awg", "syncconf", "awg0", "/dev/stdin"])
            self.runtime = data
            return SimpleNamespace(returncode=0, stdout=b"", stderr=b"")
        result = self.apply(command)
        self.assertTrue(result["inPlaceSync"])
        self.assertEqual(result["manifest"]["revision"], 2)
        self.assertEqual(result["live"]["peerCount"], 2)
        self.assertEqual(len(calls), 1)
        self.assertEqual((self.active / "awg0.conf").read_bytes(), ADDED)
        self.assertFalse((self.root / "replicas" / (self.origin + ".pending.json")).exists())

    def test_failed_runtime_apply_restores_previous_files_and_generation(self):
        attempts = []
        def command(arguments, data=None, check=True):
            attempts.append(arguments)
            if len(attempts) == 1:
                raise RuntimeError("Simulated owned-replica apply failure")
            self.runtime = data
            return SimpleNamespace(returncode=0, stdout=b"", stderr=b"")
        with self.assertRaisesRegex(RuntimeError, "Simulated owned-replica"):
            self.apply(command)
        self.assertEqual((self.active / "awg0.conf").read_bytes(), BASE)
        self.assertEqual((self.active / "clientsTable").read_bytes(), self.old_files["clientsTable"])
        state = json.loads((self.root / "replicas" / (self.origin + ".json")).read_text())
        self.assertEqual(state["revision"], 1)
        self.assertEqual(len(attempts), 2)

    def journal(self):
        backup = self.root / "replica-backups" / self.origin / "before-test"
        for filename, value in self.old_files.items():
            native_ha.write(backup / filename, value)
        path = self.root / "replicas" / (self.origin + ".pending.json")
        native_ha.document(path, {"before": self.previous, "next": self.next, "backup": str(backup),
                                   "runtimeSha256": {"before": native_ha.sha(native_ha.runtime_configuration(BASE)),
                                                     "next": native_ha.sha(native_ha.runtime_configuration(ADDED))}})
        return path

    def test_completed_pending_generation_is_acknowledged_after_process_interruption(self):
        for filename, value in self.new_files.items():
            native_ha.write(self.active / filename, value)
        self.runtime = ADDED
        journal = self.journal()
        startup_inode = (self.active / "runtime.conf").stat().st_ino
        self.assertEqual((self.active / "runtime.conf").read_bytes(), native_ha.runtime_configuration(BASE))
        calls = []
        def command(arguments, data=None, check=True):
            calls.append(arguments)
            self.assertIsNone(data, "Completed generation must not be applied again")
            return SimpleNamespace(returncode=0, stdout=self.runtime, stderr=b"")
        result = self.apply(command)
        self.assertTrue(result["alreadyApplied"])
        self.assertEqual(result["manifest"]["revision"], 2)
        self.assertTrue(result["manifest"]["journalRecovered"])
        self.assertFalse(journal.exists())
        self.assertEqual(len(calls), 1)
        self.assertEqual((self.active / "runtime.conf").read_bytes(), native_ha.runtime_configuration(ADDED))
        self.assertEqual((self.active / "runtime.conf").stat().st_ino, startup_inode)

    def test_mixed_pending_generation_rolls_back_owned_files_then_retries(self):
        native_ha.write(self.active / "awg0.conf", ADDED)
        self.runtime = ADDED
        journal = self.journal()
        writes = []
        def command(arguments, data=None, check=True):
            if data is None:
                return SimpleNamespace(returncode=0, stdout=self.runtime, stderr=b"")
            writes.append(data)
            self.runtime = data
            return SimpleNamespace(returncode=0, stdout=b"", stderr=b"")
        result = self.apply(command)
        self.assertTrue(result["inPlaceSync"])
        self.assertEqual(result["manifest"]["revision"], 2)
        self.assertEqual(len(writes), 2)
        self.assertEqual(native_ha.identity(writes[0]), native_ha.identity(BASE))
        self.assertEqual(native_ha.identity(writes[1]), native_ha.identity(ADDED))
        self.assertFalse(journal.exists())

    def test_pending_recovery_preserves_independent_runtime_peer(self):
        self.runtime = ADDED + b"\n[Peer]\nPublicKey = independent-owner-peer\nAllowedIPs = 192.0.2.9/32\n"
        original_runtime = self.runtime
        journal = self.journal()
        def command(arguments, data=None, check=True):
            self.assertIsNone(data, "Independent runtime peer must never be overwritten")
            return SimpleNamespace(returncode=0, stdout=self.runtime, stderr=b"")
        with self.assertRaisesRegex(RuntimeError, "independent runtime changes"):
            self.apply(command)
        self.assertEqual(self.runtime, original_runtime)
        self.assertEqual((self.active / "awg0.conf").read_bytes(), BASE)
        self.assertTrue(journal.exists())

    def test_pending_recovery_preserves_independent_metadata(self):
        owner_metadata = b'[{"clientId":"independent-owner-peer"}]'
        native_ha.write(self.active / "clientsTable", owner_metadata)
        self.journal()
        with self.assertRaisesRegex(RuntimeError, "independent file changes"):
            self.apply(lambda *args, **kwargs: self.fail("Independent metadata invoked a command"))
        self.assertEqual((self.active / "clientsTable").read_bytes(), owner_metadata)

    def test_pending_journal_source_change_is_rejected(self):
        journal = self.journal()
        value = json.loads(journal.read_text())
        value["next"]["sourceId"] = "c" * 64
        native_ha.document(journal, value)
        with self.assertRaisesRegex(RuntimeError, "fixed source authority"):
            self.apply(lambda *args, **kwargs: self.fail("Foreign journal invoked a command"))
        self.assertTrue(journal.exists())

    def test_pending_recovery_preserves_independent_bound_startup_file(self):
        self.journal()
        owner_startup = b"independent owner startup configuration"
        (self.active / "runtime.conf").write_bytes(owner_startup)
        with self.assertRaisesRegex(RuntimeError, "independent startup-file changes"):
            self.apply(lambda *args, **kwargs: self.fail("Independent startup invoked a command"))
        self.assertEqual((self.active / "runtime.conf").read_bytes(), owner_startup)

    def test_initial_staging_retry_reuses_same_manifest_and_bound_runtime_inode(self):
        root = self.root / "initial"
        active = root / "active" / self.origin
        runtime = native_ha.runtime_configuration(BASE)
        entry = b"#!/bin/sh\nexit 0\n"
        with patch.object(native_ha, "ROOT", root):
            native_ha.prepare_initial_stage(self.origin, self.previous, active, self.old_files, runtime, entry)
            inode = (active / "runtime.conf").stat().st_ino
            (active / "clientsTable").unlink()
            native_ha.prepare_initial_stage(self.origin, self.previous, active, self.old_files, runtime, entry)
        self.assertEqual((active / "clientsTable").read_bytes(), self.old_files["clientsTable"])
        self.assertEqual((active / "runtime.conf").stat().st_ino, inode)

    def test_initial_staging_never_overwrites_foreign_artifacts(self):
        root = self.root / "initial"
        active = root / "active" / self.origin
        runtime = native_ha.runtime_configuration(BASE)
        entry = b"#!/bin/sh\nexit 0\n"
        with patch.object(native_ha, "ROOT", root):
            native_ha.prepare_initial_stage(self.origin, self.previous, active, self.old_files, runtime, entry)
            native_ha.write(active / "independent-owner-file", b"owner data")
            with patch.object(native_ha, "write", side_effect=AssertionError("Foreign staging must not be changed")):
                with self.assertRaisesRegex(RuntimeError, "independent artifacts"):
                    native_ha.prepare_initial_stage(self.origin, self.previous, active, self.old_files, runtime, entry)
        self.assertEqual((active / "independent-owner-file").read_bytes(), b"owner data")

    def test_initial_staging_different_revision_is_preserved(self):
        root = self.root / "initial"
        active = root / "active" / self.origin
        runtime = native_ha.runtime_configuration(BASE)
        entry = b"#!/bin/sh\nexit 0\n"
        with patch.object(native_ha, "ROOT", root):
            native_ha.prepare_initial_stage(self.origin, self.previous, active, self.old_files, runtime, entry)
            with self.assertRaisesRegex(RuntimeError, "different ownership or generation"):
                native_ha.prepare_initial_stage(self.origin, self.next, active, self.new_files, native_ha.runtime_configuration(ADDED), entry)
        self.assertEqual((active / "awg0.conf").read_bytes(), BASE)


class NativeAcknowledgmentTests(unittest.TestCase):
    def test_same_revision_healthy_compare_refreshes_ack_on_both_hosts(self):
        with tempfile.TemporaryDirectory() as directory:
            if not hasattr(os, "fchmod"):
                permission_patch = patch.object(os, "fchmod", lambda descriptor, mode: None, create=True)
                permission_patch.start()
                self.addCleanup(permission_patch.stop)
            manifest = {"origin": "primary-native", "sourceId": "a" * 64, "serverPublicKeySha256": "b" * 64,
                        "epoch": 1, "revision": 3, "payloadSha256": "c" * 64, "imageId": "sha256:" + "d" * 64,
                        "scope": "primary-native-v1", "scopeSha256": "e" * 64, "identitySha256": "f" * 64,
                        "peerCount": 2, "targetName": "unifiedvpn-native-primary-ha", "targetId": "1" * 64}
            config = {"authority": {key: manifest[key] for key in ("origin", "sourceId", "serverPublicKeySha256")},
                      "peer_host": "198.51.100.20", "identity_file": "/etc/example/identity", "known_hosts_file": "/etc/example/known_hosts",
                      "host_key_alias": "example-standby", "worker": "/opt/example/native_ha.py", "request": "/etc/example/request.json",
                      "data_root": directory, "desired_root": directory, "direction": "push"}
            calls = []
            def run(arguments, data=None):
                calls.append((arguments, data))
                if len(calls) == 1:
                    return json.dumps(manifest).encode()
                if len(calls) == 2:
                    return json.dumps({"manifest": manifest}).encode()
                return b""
            with patch.object(native_ha_sync, "run", run), patch.object(native_ha_sync.time, "time", return_value=20000):
                result = native_ha_sync.synchronize(config)
            desired = json.loads((Path(directory) / "primary-native-v1.json").read_text())
            self.assertEqual(result["revision"], 3)
            self.assertEqual(desired["synchronized_at"], 20000)
            self.assertEqual(desired["revision"], 3)
            self.assertEqual(json.loads(calls[-1][1]), desired)
            self.assertFalse(any(arguments[0] == "scp" for arguments, _ in calls))


class AmneziaDiscoveryCompatibilityTests(unittest.TestCase):
    def test_released_client_misidentifies_a_prefixed_legacy_container(self):
        expression = re.compile(r"(amnezia[-a-z0-9]*).*?:([0-9]*)->[0-9]*/(udp|tcp).*")
        result = expression.search("olcbox-amnezia-awg 0.0.0.0:55424->55424/udp")
        self.assertEqual(result.group(1), "amnezia-awg")
        actual_names = {"olcbox-amnezia-awg", "amnezia-awg2", "amnezia-awg2-protocol2-preserved"}
        self.assertNotIn(result.group(1), actual_names)

    def test_compatible_legacy_name_is_hidden_and_real_native_name_is_preserved(self):
        expression = re.compile(r"(amnezia[-a-z0-9]*).*?:([0-9]*)->[0-9]*/(udp|tcp).*")
        self.assertIsNone(expression.search("unifiedvpn-legacy-olcbox-awg 0.0.0.0:55424->55424/udp"))
        result = expression.search("amnezia-awg2 0.0.0.0:49312->49312/udp")
        self.assertEqual(result.group(1), "amnezia-awg2")


if __name__ == "__main__":
    unittest.main()
