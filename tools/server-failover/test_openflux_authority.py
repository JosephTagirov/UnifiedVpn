import copy
from contextlib import closing, nullcontext
import hashlib
import json
from pathlib import Path
import sqlite3
import tempfile
import unittest
from unittest.mock import Mock, patch

import openflux_authority as worker


class AuthorityTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        root = Path(self.directory.name)
        boot_patch = patch.object(worker, "boot_identity", return_value="test-boot")
        boot_patch.start()
        self.addCleanup(boot_patch.stop)
        self.key = "generated-test-secret"
        self.spec = {"source_machine_id": "source-node", "device_id": "device-a", "canonical_key_sha256": hashlib.sha256(self.key.encode()).hexdigest(),
                     "port": 50011, "resource": {"unit": "example-direct.service"}}
        self.config = {"machine_id": "source-node", "database": str(root / "devices.db"), "state_root": str(root), "initial_boot_id": "test-boot",
                       "sources": {"origin-a": self.spec}, "targets": {"origin-a": self.spec}, "known_device_ids": ["device-a"]}
        with closing(sqlite3.connect(self.config["database"])) as database, database:
            database.execute("create table devices(id text,enabled integer,expires real,protocols text,openflux_key text,openflux_port integer)")
            database.execute("insert into devices values(?,?,?,?,?,?)", ("device-a", 1, 0, '["openflux"]', self.key, 50011))

    def snapshot(self):
        with patch.object(worker, "observe", return_value={"owned": True, "active": True}), patch.object(worker, "trusted_json", side_effect=lambda p, **kw: json.loads(Path(p).read_text())):
            return worker.snapshot(self.config, "origin-a", clock=lambda: 1000)

    def test_disabled_deleted_protocol_removed_and_key_changed_are_tombstones(self):
        initial = self.snapshot()
        for mutation in ["disabled", "deleted", "protocol", "key"]:
            with self.subTest(mutation=mutation):
                with closing(sqlite3.connect(self.config["database"])) as database, database:
                    database.execute("delete from devices")
                    if mutation != "deleted":
                        database.execute("insert into devices values(?,?,?,?,?,?)", ("device-a", 0 if mutation == "disabled" else 1, 0, '[]' if mutation == "protocol" else '["openflux"]', "changed-key" if mutation == "key" else self.key, 50011))
                result = self.snapshot()
                self.assertFalse(result["grant"])
                self.assertGreater(result["revision"], initial["revision"])

    def test_finite_deadline_cannot_become_old_infinite_permission(self):
        self.spec["require_finite_deadline"] = True
        result = self.snapshot()
        self.assertFalse(result["grant"])
        self.assertEqual(result["reason"], "finite_deadline_removed")

    def test_source_unknown_runtime_never_publishes_fresh_permission(self):
        with patch.object(worker, "observe", side_effect=RuntimeError("unverified")):
            with self.assertRaises(RuntimeError):
                worker.snapshot(self.config, "origin-a", clock=lambda: 1000)
        self.assertFalse(worker.state_path(self.config, "origin-a", "source").exists())

    def test_unknown_new_profile_holds_grants_but_not_known_revocation(self):
        with closing(sqlite3.connect(self.config["database"])) as database, database:
            database.execute("insert into devices values(?,?,?,?,?,?)", ("device-new", 1, 0, '["openflux"]', "new-key", 50012))
        payload = self.snapshot()
        self.assertFalse(payload["registry_known"])
        with self.assertRaises(ValueError):
            worker.apply(self.config, "origin-a", payload)
        with closing(sqlite3.connect(self.config["database"])) as database, database:
            database.execute("update devices set enabled=0 where id='device-a'")
        revoked = self.snapshot()
        with patch.object(worker, "enforce_identity", return_value={"owned": True, "active": False}) as enforce:
            worker.apply(self.config, "origin-a", revoked)
            enforce.assert_called_once()

    def test_revoked_intent_is_durable_before_unit_transition(self):
        with closing(sqlite3.connect(self.config["database"])) as database, database:
            database.execute("update devices set enabled=0")
        payload = self.snapshot()
        with patch.object(worker, "enforce_identity", side_effect=RuntimeError("unit transition interrupted")):
            with self.assertRaises(RuntimeError):
                worker.apply(self.config, "origin-a", payload)
        self.assertFalse(json.loads(worker.state_path(self.config, "origin-a", "applied").read_text())["grant"])

    def test_source_binding_generation_replay_and_changed_same_revision_are_rejected(self):
        first = self.snapshot()
        with patch.object(worker, "enforce_identity", return_value={}), patch.object(worker, "trusted_json", side_effect=lambda p, **kw: json.loads(Path(p).read_text())):
            worker.apply(self.config, "origin-a", first)
            altered = copy.deepcopy(first)
            altered["grant"] = False
            body = {key: value for key, value in altered.items() if key not in {"epoch", "revision", "sha256", "observed_at"}}
            altered["sha256"] = worker.digest(body)
            with self.assertRaises(ValueError): worker.apply(self.config, "origin-a", altered)
            altered = copy.deepcopy(first)
            altered["source_machine_id"] = "foreign-node"
            with self.assertRaises(ValueError): worker.apply(self.config, "origin-a", altered)

    def test_cached_expiry_closes_without_a_source_query(self):
        payload = self.snapshot()
        payload.update(grant=True, expires=1050)
        actor = Mock()
        result = worker.enforce_resource(self.spec, payload, now=1100, observer=Mock(side_effect=[{"owned": True, "active": True}, {"owned": True, "active": False}]), actor=actor, allow_start=False)
        actor.assert_called_once_with("stop", "example-direct.service")
        self.assertFalse(result["effective_grant"])

    def test_cached_outage_cannot_start_an_inactive_receiver(self):
        payload = self.snapshot()
        actor = Mock()
        result = worker.enforce_resource(self.spec, payload, now=1100, observer=lambda p: {"owned": True, "active": False}, actor=actor, allow_start=False)
        actor.assert_not_called()
        self.assertTrue(result["unknown"])

    def test_foreign_owner_cannot_be_stopped(self):
        payload = self.snapshot()
        payload["grant"] = False
        actor = Mock()
        with self.assertRaises(RuntimeError):
            worker.enforce_resource(self.spec, payload, observer=lambda p: {"owned": False, "active": True}, actor=actor)
        actor.assert_not_called()

    def test_one_unknown_origin_does_not_block_other_origin_revocations(self):
        config = {"jobs": {"first": "push", "second": "pull"}}
        payload = {"origin": "second", "epoch": 1, "revision": 2, "sha256": "placeholder-hash", "grant": False, "expires": 0}
        def rpc(c, action, origin, *args, **kwargs):
            if origin == "first": raise RuntimeError("source unknown")
            return {"manifest": payload} if action == "apply" else payload if action == "snapshot" else {"acknowledged": True}
        with patch.object(worker, "rpc", side_effect=rpc):
            result = worker.synchronize(config)
        self.assertFalse(result["healthy"])
        self.assertTrue(result["profiles"]["second"]["healthy"])
        self.assertFalse(result["profiles"]["second"]["grant"])

    def test_observed_newer_revoke_is_applied_even_without_stable_ack(self):
        grant = {"origin": "first", "epoch": 1, "revision": 1, "sha256": "grant-hash", "grant": True, "expires": 0}
        revoke = {**grant, "revision": 2, "sha256": "revocation-hash", "grant": False}
        calls = []
        snapshots = iter([grant, revoke])
        def rpc(config, action, origin, payload=None, remote=False):
            calls.append((action, payload))
            if action == "snapshot": return next(snapshots)
            if action == "apply": return {"manifest": payload}
            self.fail("Unstable authority must not receive an acknowledgement")
        with patch.object(worker, "rpc", side_effect=rpc):
            result = worker.synchronize({"jobs": {"first": "push"}})
        self.assertFalse(result["healthy"])
        self.assertEqual([payload for action, payload in calls if action == "apply"], [grant, revoke])

    def test_local_source_revocation_is_enforced_without_coordinator(self):
        config = {**self.config, "targets": {}}
        payload = self.snapshot()
        payload["grant"] = False
        with patch.object(worker, "snapshot", return_value=payload), patch.object(worker, "enforce_identity", return_value={"active": False}) as enforce:
            result = worker.enforce_cached(config)
        enforce.assert_called_once_with(config, "origin-a", self.spec, payload, allow_start=True)
        self.assertFalse(result["profiles"]["origin-a"]["active"])

    def test_expired_receiver_has_durable_tombstone_after_clock_rollback(self):
        payload = self.snapshot()
        payload["expires"] = 1100
        with patch.object(worker, "trusted_json", side_effect=lambda p, **kw: json.loads(Path(p).read_text())), patch.object(worker.time, "time", return_value=1200), patch.object(worker, "enforce_resource", return_value={}):
            worker.enforce_identity(self.config, "origin-a", self.spec, payload, allow_start=False)
        self.assertTrue(worker.state_path(self.config, "origin-a", "local-expiry").exists())
        with patch.object(worker, "trusted_json", side_effect=lambda p, **kw: json.loads(Path(p).read_text())), patch.object(worker.time, "time", return_value=1000), patch.object(worker, "enforce_resource", return_value={}) as enforce:
            worker.enforce_identity(self.config, "origin-a", self.spec, payload, allow_start=False)
        self.assertFalse(enforce.call_args.args[1]["grant"])
        self.assertGreaterEqual(enforce.call_args.kwargs["now"], 1200)

    def test_acknowledgement_requires_enforced_source_and_receiver_permission(self):
        payload = self.snapshot()
        desired = {key: payload[key] for key in ("origin", "epoch", "revision", "sha256")}
        desired["synchronized_at"] = 1000
        for source in (True, False):
            config = {**self.config, "sources": self.config["sources"] if source else {}}
            with self.subTest(source=source), patch.object(worker, "snapshot", return_value=payload), patch.object(worker, "trusted_json", return_value=payload), patch.object(worker.time, "time", return_value=1000):
                for result in ({"active": False, "effective_grant": True, "unknown": True}, {"active": False, "effective_grant": False}):
                    with patch.object(worker, "enforce_identity", return_value=result):
                        with self.assertRaises(RuntimeError): worker.acknowledge(config, "origin-a", desired)
                with patch.object(worker, "enforce_identity", return_value={"active": True, "effective_grant": True}) as enforce:
                    self.assertTrue(worker.acknowledge(config, "origin-a", desired)["acknowledged"])
                    self.assertFalse(enforce.call_args.kwargs["allow_start"])

    def test_node_lock_contention_retries_only_that_explicit_error(self):
        busy = Mock(returncode=1, stdout=json.dumps({"error_type": "BlockingIOError"}).encode())
        good = Mock(returncode=0, stdout=b'{"verified": true}')
        with patch.object(worker.subprocess, "run", side_effect=[busy, good]) as run, patch.object(worker.time, "sleep"):
            self.assertTrue(worker.invoke(["example"]) ["verified"])
            self.assertEqual(run.call_count, 2)
        bad = Mock(returncode=1, stdout=json.dumps({"error_type": "ValueError"}).encode())
        with patch.object(worker.subprocess, "run", return_value=bad) as run:
            with self.assertRaises(RuntimeError): worker.invoke(["example"])
            self.assertEqual(run.call_count, 1)

    def test_local_timer_lock_contention_is_bounded_without_repeating_body(self):
        first, second = Mock(), nullcontext()
        first.__enter__ = Mock(side_effect=BlockingIOError())
        first.__exit__ = Mock()
        body = Mock()
        with patch.object(worker, "exclusive_state", side_effect=[first, second]), patch.object(worker.time, "sleep"):
            with worker.bounded_state_lock("example"):
                body()
        body.assert_called_once()
        with patch.object(worker, "exclusive_state", return_value=first) as lock, patch.object(worker.time, "sleep"):
            with self.assertRaises(BlockingIOError):
                with worker.bounded_state_lock("example"):
                    self.fail("A busy lock must never enter the body")
        self.assertEqual(lock.call_count, 10)

    def test_receiver_boot_requires_fresh_single_use_generation_ticket(self):
        config = {**self.config, "sources": {}}
        payload = self.snapshot()
        worker.atomic_json(worker.state_path(config, "origin-a", "applied"), payload)
        with patch.object(worker, "trusted_json", side_effect=lambda p, **kw: json.loads(Path(p).read_text())), patch.object(worker.time, "time", return_value=1000):
            with self.assertRaises(FileNotFoundError): worker.startup_gate(config, "origin-a")
            worker.atomic_json(worker.state_path(config, "origin-a", "start-ticket"), {"epoch": 1, "revision": payload["revision"], "sha256": payload["sha256"], "issued_at": 999})
            self.assertTrue(worker.startup_gate(config, "origin-a")["startup_allowed"])
            with self.assertRaises(FileNotFoundError): worker.startup_gate(config, "origin-a")

    def test_source_boot_rejects_deadline_passed_while_powered_off(self):
        payload = self.snapshot()
        payload["expires"] = 1100
        with patch.object(worker, "snapshot", return_value=payload), patch.object(worker, "durable_clock", return_value=1200):
            with self.assertRaises(ValueError): worker.startup_gate(self.config, "origin-a")

    def test_source_boot_gate_is_read_only_and_requires_published_current_generation(self):
        self.snapshot()
        root = Path(self.config["state_root"])
        before = {path.name: path.read_bytes() for path in root.glob("*.json")}
        with patch.object(worker, "observe", return_value={"owned": True, "active": False}), patch.object(worker, "trusted_json", side_effect=lambda p, **kw: json.loads(Path(p).read_text())):
            self.assertTrue(worker.startup_gate(self.config, "origin-a")["startup_allowed"])
            self.assertEqual({path.name: path.read_bytes() for path in root.glob("*.json")}, before)
            with closing(sqlite3.connect(self.config["database"])) as database, database:
                database.execute("update devices set enabled=0")
            with self.assertRaises(ValueError): worker.startup_gate(self.config, "origin-a")
            self.assertEqual({path.name: path.read_bytes() for path in root.glob("*.json")}, before)

    def test_same_boot_monotonic_elapsed_reaches_deadline_during_wall_rollback(self):
        with patch.object(worker.time, "monotonic", return_value=10):
            self.assertEqual(worker.durable_clock(self.config, 100), 100)
        with patch.object(worker.time, "monotonic", return_value=12), patch.object(worker, "trusted_json", side_effect=lambda p, **kw: json.loads(Path(p).read_text())):
            self.assertEqual(worker.durable_clock(self.config, 50), 102)

    def test_reboot_without_synchronized_time_denies_finite_permission(self):
        with patch.object(worker.time, "monotonic", return_value=10):
            worker.durable_clock(self.config, 100)
        with patch.object(worker, "boot_identity", return_value="next-boot"), patch.object(worker, "synchronized_clock", return_value=False), patch.object(worker.time, "monotonic", return_value=1), patch.object(worker, "trusted_json", side_effect=lambda p, **kw: json.loads(Path(p).read_text())):
            state = worker.clock_status(self.config, 101)
            self.assertFalse(state["trusted"])
            payload = {"epoch": 1, "revision": 1, "sha256": "test-hash", "grant": True, "expires": 200}
            with patch.object(worker.time, "time", return_value=101), patch.object(worker, "enforce_resource", return_value={}) as enforce:
                worker.enforce_identity(self.config, "origin-a", self.spec, payload, allow_start=False)
            self.assertFalse(enforce.call_args.args[1]["grant"])
        with patch.object(worker, "boot_identity", return_value="next-boot"), patch.object(worker, "synchronized_clock", return_value=True), patch.object(worker.time, "monotonic", return_value=2), patch.object(worker, "trusted_json", side_effect=lambda p, **kw: json.loads(Path(p).read_text())):
            self.assertTrue(worker.clock_status(self.config, 102)["trusted"])

    def test_time_trust_query_failure_remains_untrusted(self):
        for error in (OSError("unavailable"), worker.subprocess.TimeoutExpired("timedatectl", 5)):
            with patch.object(worker.subprocess, "run", side_effect=error):
                self.assertFalse(worker.synchronized_clock())


if __name__ == "__main__":
    unittest.main()
