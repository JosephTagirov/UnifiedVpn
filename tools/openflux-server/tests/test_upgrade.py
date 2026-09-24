import contextlib
import copy
import importlib.util
import io
import json
import os
from pathlib import Path
import stat
import sys
import time
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch

import test_manage

FakeDocker, manage = test_manage.FakeDocker, test_manage.manage


SPEC = importlib.util.spec_from_file_location("openflux_upgrade_test", Path(__file__).resolve().parents[1] / "upgrade.py")
upgrade = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(upgrade)


class UpgradeDocker(FakeDocker):
    def __init__(self):
        super().__init__()
        self.document_exit = 0
        self.fail_candidate_start = False
        self.ignore_stop_ids = set()
        self.ignore_stop_once = set()
        self.ignore_update_once = set()

    def __call__(self, argv, timeout=30, allow_failure=False):
        if argv[0] == "ip" or argv[3] == "build":
            return super().__call__(argv, timeout, allow_failure)
        args = argv[3:]
        kind, operation = args[:2]
        ignored = self.ignore_stop_once if operation == "stop" else self.ignore_update_once
        if kind == "container" and operation in ("stop", "update") and args[-1] in ignored:
            ignored.remove(args[-1])
            self.commands.append(argv)
            return self.result()
        if (kind, operation) == ("container", "stop") and args[-1] in self.ignore_stop_ids:
            self.commands.append(argv)
            return self.result()
        if (kind, operation) == ("container", "update"):
            self.commands.append(argv)
            _, resource = self.named_resource(self.containers, args[-1])
            value = args[args.index("--restart") + 1].split(":")
            resource["HostConfig"]["RestartPolicy"] = {"Name": value[0], "MaximumRetryCount": int(value[1]) if len(value) == 2 else 0}
            return self.result()
        if (kind, operation) == ("container", "wait"):
            self.commands.append(argv)
            _, resource = self.named_resource(self.containers, args[-1])
            return self.result(str(resource["State"]["ExitCode"]) + "\n")
        if (kind, operation) == ("container", "create"):
            original = list(argv)
            reference = args[args.index("--network") + 1]
            network_name, network = self.named_resource(self.networks, reference)
            copied = list(argv)
            copied[copied.index("--network") + 1] = network_name
            result = super().__call__(copied, timeout, allow_failure)
            self.commands[-1] = original
            name = args[args.index("--name") + 1]
            resource = self.containers[name]
            image = next(value for value in args if value in self.images)
            mount = dict(item.split("=", 1) for item in args[args.index("--mount") + 1].split(",") if "=" in item)
            resource.update(Image=image, RestartCount=0,
                            Mounts=[{"Type": "bind", "Source": mount["src"], "Destination": mount["dst"], "RW": False}],
                            HostConfig={"Privileged": False, "ReadonlyRootfs": True, "PidMode": "", "PortBindings": {},
                                        "NetworkMode": reference, "RestartPolicy": {"Name": "no", "MaximumRetryCount": 0}},
                            NetworkSettings={"Networks": {network_name: {"NetworkID": network["Id"]}}})
            resource["Config"]["Cmd"] = args[args.index(image) + 1:]
            return result
        result = super().__call__(argv, timeout, allow_failure)
        if (kind, operation) == ("network", "create"):
            self.networks[args[-1]]["Driver"] = "bridge"
        if (kind, operation) == ("container", "start"):
            name, resource = self.named_resource(self.containers, args[-1])
            if "--check-document" in resource["Config"]["Cmd"]:
                resource["State"].update(Running=False, Status="exited", ExitCode=self.document_exit)
            elif self.fail_candidate_start and "-browser" in name:
                resource["State"].update(Running=False, Status="exited", ExitCode=1)
        return result


class Clock:
    def __init__(self):
        self.now = 0.0

    def __call__(self):
        return self.now

    def sleep(self, duration):
        self.now += duration


class UpgradeTests(unittest.TestCase):
    def setUp(self):
        fixture = test_manage.LifecycleTests()
        fixture.setUp()
        self.addCleanup(fixture.doCleanups)
        self.directory, self.args = fixture.directory, fixture.args
        self.parent = self.directory / "opt"
        self.parent.mkdir()
        self.fake, self.clock = UpgradeDocker(), Clock()
        for guard in (patch.object(upgrade, "manage", manage), patch.object(upgrade, "private_directory"),
                      patch.object(os, "fchmod", create=True)):
            guard.start()
            self.addCleanup(guard.stop)
        self.up = upgrade.Upgrade("default", self.directory / "receipt", self.fake, self.parent, self.clock.sleep, self.clock)
        with patch.object(manage, "UPSTREAM", upgrade.LEGACY_UPSTREAM), \
                patch.object(manage, "VERSION", f"unified-openflux 1 upstream={upgrade.LEGACY_UPSTREAM} protocol={manage.PROTOCOL}"):
            self.up.original.install(self.args)
            self.up.original.run()
        self.old = self.fake.containers[self.up.original.name]
        self.old["HostConfig"]["RestartPolicy"] = {"Name": "unless-stopped", "MaximumRetryCount": 0}
        self.old_id = self.old["Id"]
        self.original_files = upgrade.file_hashes(self.up.original.root, upgrade.SNAPSHOT_FILES)
        self.original_networks = copy.deepcopy(self.fake.networks)
        self.fake.commands.clear()

    def prepare(self):
        self.up.prepare(self.args)
        return self.up.load()

    def proof(self, record):
        proof = {"schema": upgrade.PROOF_SCHEMA, "upgrade_id": record["upgrade_id"], "container_id": record["candidate_id"],
                 "protocol": manage.PROTOCOL, "authenticated_peer": True, "https_successes": 2,
                 "dns_over_tunnel": True, "tested_at_ns": time.time_ns()}
        self.up.proof_path.write_text(json.dumps(proof), encoding="ascii")
        # Windows fixtures may round filesystem timestamps below the trial's ns value.
        stamp = max(time.time_ns(), record["trial_started_ns"] + 1000000)
        os.utime(self.up.proof_path, ns=(stamp, stamp))
        return upgrade.Upgrade.traffic_proof(self.up, record)

    def mutation_commands(self):
        return [argv[3:] for argv in self.fake.commands if argv[0] == "docker" and
                (argv[3] == "build" or argv[4] in ("create", "start", "stop", "rm", "tag", "update"))]

    def old_mutations(self):
        return [args for args in self.mutation_commands() if self.old_id in args]

    def assert_original_files(self):
        self.assertEqual(upgrade.file_hashes(self.up.original.root, upgrade.SNAPSHOT_FILES), self.original_files)

    def test_prepare_only_builds_separate_image_and_preserves_profile(self):
        record = self.prepare()
        self.assertEqual(record["phase"], "prepared")
        self.assertEqual(self.old_mutations(), [])
        self.assertEqual(self.fake.networks, self.original_networks)
        self.assertEqual(len(self.fake.containers), 1)
        self.assert_original_files()
        candidate = self.up.candidate(record)
        self.assertEqual((candidate.root / "server.json").read_bytes(), (self.up.original.root / "server.json").read_bytes())
        self.assertEqual(candidate.load_state()["upstream"], manage.UPSTREAM)
        with self.assertRaises(manage.DeploymentError):
            candidate.run()

    def test_explicit_named_instance_keeps_other_instance_untouched(self):
        original = self.up.original
        self.up = upgrade.Upgrade("phone2", self.directory / "phone-receipt", self.fake, self.parent, self.clock.sleep, self.clock)
        self.args.subnet = "172.30.252.0/28"
        with patch.object(manage, "UPSTREAM", upgrade.LEGACY_UPSTREAM), \
                patch.object(manage, "VERSION", f"unified-openflux 1 upstream={upgrade.LEGACY_UPSTREAM} protocol={manage.PROTOCOL}"):
            self.up.original.install(self.args)
            self.up.original.run()
        self.fake.commands.clear()
        record = self.prepare()
        with patch.object(self.up, "traffic_proof", side_effect=self.proof):
            self.up.switch(15)
        self.assertEqual(record["instance"], "phone2")
        self.assertEqual(self.old_mutations(), [])
        self.assertTrue(self.fake.containers[original.name]["State"]["Running"])

    def test_check_is_read_only_and_reports_no_transport_proof(self):
        self.prepare()
        self.fake.commands.clear()
        report = self.up.check()
        self.assertEqual(self.mutation_commands(), [])
        self.assertFalse(report["document_checked"] or report["traffic_verified"] or report["candidate_running"])

    def test_browser_gate_failure_never_stops_or_updates_original(self):
        self.prepare()
        self.fake.document_exit = 1
        self.fake.commands.clear()
        with self.assertRaises(manage.DeploymentError):
            self.up.switch(15)
        self.assertTrue(self.old["State"]["Running"])
        self.assertEqual(self.old_mutations(), [])
        self.assertEqual(self.up.load()["phase"], "document_failed")
        self.assertEqual(len(self.fake.containers), 1)
        self.assert_original_files()

    def test_standalone_document_check_cannot_switch_original(self):
        self.prepare()
        self.fake.commands.clear()
        self.up.check_document()
        record = self.up.load()
        self.assertTrue(record["document_checked"])
        self.assertFalse(record["traffic_verified"])
        self.assertEqual(record["phase"], "prepared")
        self.assertIsNone(record["candidate_id"])
        self.assertEqual(self.old_mutations(), [])
        self.assertTrue(self.old["State"]["Running"])
        self.assertEqual(len(self.fake.containers), 1)
        creates = [args for args in self.mutation_commands() if args[:2] == ["container", "create"]]
        self.assertEqual(len(creates), 1)
        self.assertEqual(creates[0][-1], "--check-document")
        self.assert_original_files()

    def test_success_requires_real_proof_interface_then_bound_rollback(self):
        self.prepare()
        with patch.object(self.up, "traffic_proof", side_effect=self.proof):
            self.up.switch(15)
        record = self.up.load()
        self.assertEqual(record["phase"], "committed")
        self.assertTrue(record["document_checked"] and record["traffic_verified"])
        self.assertFalse(self.old["State"]["Running"])
        self.assertEqual(self.old["HostConfig"]["RestartPolicy"]["Name"], "no")
        new = self.fake.containers[self.up.candidate(record).name]
        self.assertTrue(new["State"]["Running"])
        self.assertEqual(new["HostConfig"]["RestartPolicy"]["Name"], "unless-stopped")
        self.assertEqual(len(self.fake.networks), 1)
        self.up.rollback()
        self.assertTrue(self.old["State"]["Running"])
        self.assertFalse(new["State"]["Running"])
        self.assertEqual(self.old["HostConfig"]["RestartPolicy"]["Name"], "unless-stopped")
        self.assertEqual(self.up.load()["phase"], "rolled_back")
        self.assert_original_files()

    def test_missing_traffic_proof_rolls_back_within_trial(self):
        self.prepare()
        with self.assertRaises(manage.DeploymentError):
            self.up.switch(15)
        self.assertLessEqual(self.clock.now, 15.21)
        self.assertEqual(self.up.load()["phase"], "rolled_back")
        self.assertTrue(self.old["State"]["Running"])
        self.assertEqual(self.old["HostConfig"]["RestartPolicy"]["Name"], "unless-stopped")

    def test_keyboard_interrupt_and_startup_failure_restore_original(self):
        self.prepare()
        with patch.object(self.up, "traffic_proof", side_effect=KeyboardInterrupt), self.assertRaises(KeyboardInterrupt):
            self.up.switch(15)
        self.assertEqual(self.up.load()["phase"], "rolled_back")
        self.assertTrue(self.old["State"]["Running"])

    def test_crashed_candidate_restores_original(self):
        self.prepare()
        self.fake.fail_candidate_start = True
        with self.assertRaises(manage.DeploymentError):
            self.up.switch(15)
        self.assertEqual(self.up.load()["phase"], "rolled_back")
        self.assertTrue(self.old["State"]["Running"])

    def test_replacement_never_starts_until_original_is_actually_stopped(self):
        self.prepare()
        self.fake.ignore_stop_ids.add(self.old_id)
        with self.assertRaises(manage.DeploymentError):
            self.up.switch(15)
        record = self.up.load()
        candidate = self.fake.containers[self.up.candidate(record).name]
        self.assertFalse(candidate["State"]["Running"])
        self.assertFalse(any(args[:2] == ["container", "start"] and args[-1] == record["candidate_id"] for args in self.mutation_commands()))
        self.assertTrue(self.old["State"]["Running"])

    def test_rollback_refuses_to_start_original_until_candidate_has_stopped(self):
        self.prepare()
        with patch.object(self.up, "traffic_proof", side_effect=self.proof):
            self.up.switch(15)
        record = self.up.load()
        self.fake.ignore_stop_ids.add(record["candidate_id"])
        with self.assertRaises(manage.DeploymentError):
            self.up.rollback()
        self.assertFalse(self.old["State"]["Running"])
        self.assertEqual(self.up.load()["phase"], "inspection_required")

    def test_receipt_write_failure_does_not_block_owned_rollback(self):
        self.prepare()
        save = self.up.save

        def fail_after_switch(record, **kwargs):
            if record["phase"] in ("testing", "rolling_back", "rolled_back", "inspection_required"):
                raise OSError("synthetic filesystem full")
            return save(record, **kwargs)

        with patch.object(self.up, "save", side_effect=fail_after_switch), self.assertRaises(manage.DeploymentError):
            self.up.switch(15)
        record = self.up.load()
        self.assertEqual(record["phase"], "switching")
        self.assertTrue(self.old["State"]["Running"])
        self.assertEqual(self.old["HostConfig"]["RestartPolicy"]["Name"], "unless-stopped")
        self.assertFalse(self.fake.containers[self.up.candidate(record).name]["State"]["Running"])

    def test_private_proof_cannot_commit_a_candidate_that_crashed_after_testing(self):
        self.prepare()

        def proof_then_crash(record):
            result = self.proof(record)
            self.fake.containers[self.up.candidate(record).name]["State"].update(Running=False, Status="exited")
            return result

        with patch.object(self.up, "traffic_proof", side_effect=proof_then_crash), self.assertRaises(manage.DeploymentError):
            self.up.switch(15)
        self.assertEqual(self.up.load()["phase"], "rolled_back")
        self.assertTrue(self.old["State"]["Running"])

    def test_ineffective_final_restart_policy_triggers_rollback(self):
        self.prepare()

        def proof_then_ignore_update(record):
            self.fake.ignore_update_once.add(record["candidate_id"])
            return self.proof(record)

        with patch.object(self.up, "traffic_proof", side_effect=proof_then_ignore_update), self.assertRaises(manage.DeploymentError):
            self.up.switch(15)
        self.assertEqual(self.up.load()["phase"], "rolled_back")
        self.assertTrue(self.old["State"]["Running"])
        self.assertEqual(self.old["HostConfig"]["RestartPolicy"]["Name"], "unless-stopped")

    def test_ineffective_final_stop_triggers_rollback_for_originally_stopped_profile(self):
        self.fake(["docker", "--host", "unix:///var/run/docker.sock", "container", "stop", self.old_id])
        self.prepare()

        def proof_then_ignore_stop(record):
            self.fake.ignore_stop_once.add(record["candidate_id"])
            return self.proof(record)

        with patch.object(self.up, "traffic_proof", side_effect=proof_then_ignore_stop), self.assertRaises(manage.DeploymentError):
            self.up.switch(15)
        record = self.up.load()
        self.assertEqual(record["phase"], "rolled_back")
        self.assertFalse(self.old["State"]["Running"])
        self.assertFalse(self.fake.containers[self.up.candidate(record).name]["State"]["Running"])
        self.assertEqual(self.old["HostConfig"]["RestartPolicy"]["Name"], "unless-stopped")

    def test_prior_stopped_state_is_not_silently_enabled(self):
        self.fake(["docker", "--host", "unix:///var/run/docker.sock", "container", "stop", self.old_id])
        self.prepare()
        with patch.object(self.up, "traffic_proof", side_effect=self.proof):
            self.up.switch(15)
        record = self.up.load()
        self.assertFalse(self.fake.containers[self.up.candidate(record).name]["State"]["Running"])
        self.up.rollback()
        self.assertFalse(self.old["State"]["Running"])
        self.assertEqual(self.old["HostConfig"]["RestartPolicy"]["Name"], "unless-stopped")

    def test_modified_profile_or_container_identity_fails_before_mutation(self):
        self.prepare()
        self.old["Id"] = "f" * 64
        self.fake.commands.clear()
        with self.assertRaises(manage.DeploymentError):
            self.up.switch(15)
        self.assertEqual(self.mutation_commands(), [])

    def test_modified_config_fails_before_mutation(self):
        self.prepare()
        path = self.up.original.root / "server.json"
        path.write_bytes(path.read_bytes() + b"\n")
        self.fake.commands.clear()
        with self.assertRaises(manage.DeploymentError):
            self.up.switch(15)
        self.assertEqual(self.mutation_commands(), [])

    def test_changed_mount_or_foreign_network_endpoint_is_rejected(self):
        self.prepare()
        self.old["Mounts"][0]["RW"] = True
        self.fake.commands.clear()
        with self.assertRaises(manage.DeploymentError):
            self.up.switch(15)
        self.assertEqual(self.mutation_commands(), [])

    def test_foreign_network_endpoint_is_rejected(self):
        self.prepare()
        self.fake.networks[self.up.original.network]["Containers"]["e" * 64] = {"Name": "unrelated"}
        self.fake.commands.clear()
        with self.assertRaises(manage.DeploymentError):
            self.up.switch(15)
        self.assertEqual(self.mutation_commands(), [])

    def test_preexisting_proof_and_interrupted_phase_cannot_retry_switch(self):
        record = self.prepare()
        for phase in ("checking_document", "creating_candidate", "switching", "testing", "inspection_required"):
            record["phase"] = phase
            self.up.save(record)
            self.fake.commands.clear()
            with self.assertRaises(manage.DeploymentError):
                self.up.switch(15)
            self.assertEqual(self.mutation_commands(), [])

    def test_mismatched_or_stale_traffic_proof_fails_closed(self):
        record = self.prepare()
        record.update(candidate_id="e" * 64, trial_started_ns=time.time_ns(), trial_deadline_ns=time.time_ns() + 1000000000)
        self.assertTrue(self.proof(record))
        original = json.loads(self.up.proof_path.read_text())
        for mutation in ({"container_id": "f" * 64}, {"upgrade_id": "f" * 64}, {"authenticated_peer": False},
                         {"https_successes": True}, {"dns_over_tunnel": False}, {"protocol": "plaintext"},
                         {"tested_at_ns": 1}, {"raw_url": "PRIVATE"}):
            self.up.proof_path.write_text(json.dumps(dict(original, **mutation)))
            with self.assertRaises(manage.DeploymentError):
                self.up.traffic_proof(record)

    def test_no_host_firewall_network_create_or_secret_arguments_during_switch(self):
        self.prepare()
        self.fake.commands.clear()
        with patch.object(self.up, "traffic_proof", side_effect=self.proof):
            self.up.switch(15)
        self.assertTrue(all(command[0] == "docker" for command in self.fake.commands))
        self.assertFalse(any(args[:2] in (["network", "create"], ["network", "rm"]) for args in self.mutation_commands()))
        self.assertTrue(all("--cap-add" not in args for args in self.mutation_commands()))
        raw = json.loads((self.up.original.root / "server.json").read_text())
        self.assertNotIn(raw["document_url"], repr(self.fake.commands))
        self.assertNotIn(raw["encryption_key"], repr(self.fake.commands))

    def test_apply_and_explicit_instance_are_mandatory_before_host_calls(self):
        with patch.object(upgrade, "Upgrade") as factory, contextlib.redirect_stderr(io.StringIO()):
            for command in ("prepare", "check-document", "switch", "rollback"):
                self.assertEqual(upgrade.main([command, "--instance", "default", "--receipt-directory", str(self.directory / "new")]), 1)
            factory.assert_not_called()
            with self.assertRaises(SystemExit):
                upgrade.parser().parse_args(["check", "--receipt-directory", str(self.directory / "new")])

    def test_existing_receipt_or_installation_tree_directory_is_never_overwritten(self):
        self.prepare()
        before = self.up.record_path.read_bytes()
        with self.assertRaises(manage.DeploymentError):
            self.up.prepare(self.args)
        self.assertEqual(self.up.record_path.read_bytes(), before)
        with self.assertRaises(manage.DeploymentError):
            upgrade.Upgrade("default", self.up.original.root / "receipt", self.fake, self.parent)

    def test_traversal_receipt_is_refused_before_any_mutation(self):
        stage = self.directory / "stage"
        stage.mkdir()
        self.fake.commands.clear()
        path = stage / ".." / "opt" / manage.NAME / "receipt"
        with self.assertRaises(manage.DeploymentError):
            upgrade.Upgrade("default", path, self.fake, self.parent)
        self.assertEqual(self.fake.commands, [])
        self.assertFalse((self.up.original.root / "receipt").exists())

    def test_lock_is_per_original_instance_and_nonblocking(self):
        lock_root = self.directory / "locks"
        flock = Mock()
        module = SimpleNamespace(LOCK_EX=2, LOCK_NB=4, flock=flock)
        safe = SimpleNamespace(st_mode=stat.S_IFREG | 0o600, st_uid=0, st_nlink=1, st_size=0)
        with patch.object(upgrade, "LOCK_ROOT", lock_root), patch.dict(sys.modules, {"fcntl": module}), \
                patch.object(os, "fstat", return_value=safe):
            with upgrade.mutation_lock("default"):
                self.assertTrue((lock_root / "default.lock").is_file())
            with upgrade.mutation_lock("phone2"):
                self.assertTrue((lock_root / "phone2.lock").is_file())
            self.assertEqual([call.args[1] for call in flock.call_args_list], [6, 6])
            flock.side_effect = BlockingIOError
            with self.assertRaises(manage.DeploymentError):
                with upgrade.mutation_lock("default"):
                    self.fail("busy per-instance lock was entered")

    def test_unsafe_lock_file_is_refused(self):
        module = SimpleNamespace(LOCK_EX=2, LOCK_NB=4, flock=Mock())
        for mutation in ({"st_uid": 1000}, {"st_nlink": 2}, {"st_mode": stat.S_IFREG | 0o644}, {"st_size": 1}):
            fields = dict(st_mode=stat.S_IFREG | 0o600, st_uid=0, st_nlink=1, st_size=0)
            fields.update(mutation)
            with patch.object(upgrade, "LOCK_ROOT", self.directory / "locks"), patch.dict(sys.modules, {"fcntl": module}), \
                    patch.object(os, "fstat", return_value=SimpleNamespace(**fields)), self.assertRaises(manage.DeploymentError):
                with upgrade.mutation_lock("default"):
                    self.fail("unsafe lock was accepted")
        module.flock.assert_not_called()


if __name__ == "__main__":
    unittest.main()
