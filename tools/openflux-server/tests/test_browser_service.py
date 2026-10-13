import base64
from contextlib import nullcontext
import copy
import hashlib
import importlib.util
import json
from pathlib import Path
import subprocess
from types import SimpleNamespace
import unittest
from unittest.mock import patch

import test_create_profile as profiles


SPEC = importlib.util.spec_from_file_location("profile_service_tests", Path(__file__).resolve().parents[1] / "browser_service.py")
service_module = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(service_module)
wizard = profiles.wizard
manage = profiles.manage
BROWSER_IMAGE = "sha256:" + "9" * 64


class LockPathTests(unittest.TestCase):
    def test_bootstrap_defaults_to_auto_and_rejects_implicit_modes(self):
        self.assertEqual(service_module.bootstrap_mode({}), "auto")
        self.assertEqual(service_module.bootstrap_mode({"bootstrap_mode": "manual-once"}), "manual-once")
        for value in (None, True, 1, "manual", "", [], {}):
            with self.subTest(value=value), self.assertRaises(service_module.manage.DeploymentError):
                service_module.bootstrap_mode({"bootstrap_mode": value})

    def test_manual_runtime_belongs_only_to_its_explicit_unit(self):
        with patch.object(service_module, "canonical_path", side_effect=Path):
            auto = service_module.unit_contents(Path("/synthetic-plan"), "browserphone")
            explicit_auto = service_module.unit_contents(Path("/synthetic-plan"), "browserphone", "auto")
            manual = service_module.unit_contents(Path("/synthetic-plan"), "browserphone", "manual-once")
        self.assertEqual(auto, explicit_auto)
        self.assertNotIn(b"RuntimeDirectory", auto)
        self.assertIn(b"RuntimeDirectory=unifiedvpn-openflux-bootstrap-browserphone\n", manual)
        self.assertIn(b"RuntimeDirectoryMode=0700\nRuntimeDirectoryPreserve=no\n", manual)
        self.assertNotIn(b"manual-once", auto)

    def test_supervisor_manual_mode_is_explicit_and_auto_call_unchanged(self):
        service = service_module.AdminService(Path("/synthetic-plan"))
        manifest = {"browser_image": BROWSER_IMAGE, "seccomp_sha256": "0" * 64}
        with patch.object(service_module.browser, "Supervisor") as supervisor:
            service.supervisor("manager", manifest)
            supervisor.assert_called_once_with("manager", BROWSER_IMAGE,
                                               Path("/synthetic-plan/runtime/browser-seccomp.json"), "0" * 64)
            supervisor.reset_mock()
            service.supervisor("manager", dict(manifest, bootstrap_mode="manual-once"))
            self.assertEqual(supervisor.call_args.kwargs, {"manual_bootstrap": True})

    def test_global_lock_uses_root_owned_run_not_shared_run_lock(self):
        self.assertEqual(service_module.admin_lock.__wrapped__.__defaults__[0], Path("/run/unifiedvpn-openflux-admin.lock"))

    def test_runtime_lock_uses_same_root_owned_parent_without_changing_permissions(self):
        service = service_module.AdminService(Path("/synthetic-plan"))
        with patch.object(service_module, "admin_lock", return_value=nullcontext()) as lock:
            service.runtime_lock({"instance": "browserphone"})
        lock.assert_called_once_with(Path("/run/unifiedvpn-openflux-browserphone.lock"))


class BrowserProfileTests(unittest.TestCase):
    assert_original_unchanged = profiles.ProfileWizardTests.assert_original_unchanged

    def setUp(self):
        profiles.ProfileWizardTests.setUp(self)
        self.unit_dir = self.directory / "systemd"
        self.unit_dir.mkdir()
        self.active = False
        self.fail_enable = False
        self.system_calls = []
        self.service = None
        self.output = self.private / "browserphone"
        self.seccomp = self.private / "seccomp.json"
        self.seccomp.write_text(json.dumps({"defaultAction": "SCMP_ACT_ERRNO", "syscalls": [{"names": ["read"], "action": "SCMP_ACT_ALLOW"}]}))
        service_module.manage = manage
        service_module.browser.manage = manage
        self.fake.images[BROWSER_IMAGE] = {"Id": BROWSER_IMAGE, "Os": "linux", "Architecture": "amd64", "RepoTags": ["public-browser:fixture"],
                                          "Config": {"User": "10001:10001", "Labels": {manage.LABEL + ".browser-contract": "3"}}}
        self.fake.tags["public-browser:fixture"] = BROWSER_IMAGE
        for item in (patch.object(wizard, "browser_backend", return_value=service_module),
                     patch.object(service_module, "profile_module", return_value=wizard),
                     patch.object(service_module, "canonical_path", side_effect=Path),
                     patch.object(service_module, "admin_lock", side_effect=lambda *args: nullcontext())):
            item.start()
            self.addCleanup(item.stop)
        artifacts = wizard.public_artifacts("default")
        self.manifest = {"schema": service_module.MANIFEST_SCHEMA, "tool_version": 1, "source_instance": "default",
                         "artifacts": {key: value for key, value in artifacts.items() if key != "source_instance"},
                         "browser_image": BROWSER_IMAGE, "seccomp_file": str(self.seccomp),
                         "seccomp_sha256": hashlib.sha256(self.seccomp.read_bytes()).hexdigest(),
                         "helper_sha256": {name: hashlib.sha256((service_module.HERE / name).read_bytes()).hexdigest() for name in service_module.HELPERS},
                         "subnet_pool": "172.30.240.0/20"}
        self.manifest_path = self.private / "manifest.json"
        self.manifest_path.write_text(json.dumps(self.manifest))

    def command(self, args, timeout=30, allow_failure=False):
        if args[0] in ("docker", "ip"):
            return self.fake(args, timeout=timeout, allow_failure=allow_failure)
        self.system_calls.append(tuple(args))
        if args[0] == "systemd-analyze":
            return subprocess.CompletedProcess(args, 0, "", "")
        self.assertEqual(args[:2], ["systemctl", "--no-pager"])
        operation = args[2]
        if operation == "show":
            name = args[3]
            path = self.unit_dir / name
            loaded = path.exists()
            fields = {"Id": name, "LoadState": "loaded" if loaded else "not-found", "FragmentPath": str(path) if loaded else "",
                      "Transient": "no", "DropInPaths": "", "ActiveState": "active" if self.active else "inactive", "SubState": "running" if self.active else "dead"}
            return subprocess.CompletedProcess(args, 0 if loaded else 1, "\n".join(key + "=" + value for key, value in fields.items()), "")
        if operation == "enable":
            if self.fail_enable:
                raise manage.DeploymentError("Simulated enable failure")
            plan = wizard.read_plan(self.output)
            manage.Manager(instance=plan["instance"], command=self.command).run(bootstrap_stdio=True)
            self.active = True
        elif operation == "disable":
            if self.active:
                self.service.stop_runtime()
            self.active = False
        else:
            self.assertEqual(operation, "daemon-reload")
        return subprocess.CompletedProcess(args, 0, "", "")

    def cli(self, transport="vyandex"):
        return ["--browser-companion", "--instance", "phone", "--admin-manifest", str(self.manifest_path),
                "--document-file", str(self.document_file), "--transport", transport,
                "--confirm-volga-document" if transport == "vyandex" else "--confirm-legacy-document", "--output", str(self.output)]

    def prepare(self, transport="vyandex"):
        self.assertEqual(wizard.main(self.cli(transport)), 0)
        self.service = service_module.AdminService(self.output, command=self.command, unit_dir=self.unit_dir)
        return wizard.read_plan(self.output)

    def install_args(self, plan):
        root = self.original.root
        return SimpleNamespace(binary=root / "openflux", binary_sha256=plan["binary_sha256"], source_archive=root / "source.tar.gz",
                               source_sha256=plan["source_sha256"], licenses_dir=root, runtime_image=plan["runtime_image"],
                               config=self.output / "server.json", subnet=None)

    def test_browser_prepare_is_offline_private_independent_and_freezes_helpers(self):
        plan = self.prepare()
        self.assertEqual(self.fake.commands, [])
        self.assertEqual(self.system_calls, [])
        self.assertEqual(plan["instance"], "browserphone")
        self.assertIsNone(plan["subnet"])
        self.assertEqual(set(path.name for path in (self.output / "runtime").iterdir()), set(service_module.HELPERS) | {"browser-seccomp.json"})
        config = json.loads((self.output / "server.json").read_bytes())
        self.assertEqual(config["transport"], "vyandex")
        self.assertNotEqual(config["encryption_key"], profiles.fixtures.config()["encryption_key"])
        link = (self.output / "profile.uri").read_text().strip()
        self.assertTrue(link.endswith("#OpenFlux%20phone"))
        self.assertNotIn(config["encryption_key"], self.stdout.getvalue() + self.stderr.getvalue())
        self.assertNotIn(config["document_url"], self.stdout.getvalue() + self.stderr.getvalue())
        self.assert_original_unchanged()

    def test_browser_supports_explicit_classic_without_changing_existing_profiles(self):
        self.prepare("yandex")
        self.assertEqual(json.loads((self.output / "server.json").read_bytes())["transport"], "yandex")
        self.assert_original_unchanged()

    def test_manual_mode_is_frozen_in_manifest_and_rejects_later_changes(self):
        self.manifest["bootstrap_mode"] = "manual-once"
        self.manifest_path.write_text(json.dumps(self.manifest))
        plan = self.prepare("yandex")
        frozen = self.output / "admin-runtime.json"
        self.assertEqual(json.loads(frozen.read_bytes())["bootstrap_mode"], "manual-once")
        self.assertEqual(service_module.validate_snapshot(self.output, plan)["bootstrap_mode"], "manual-once")
        altered = json.loads(frozen.read_bytes())
        altered["bootstrap_mode"] = "auto"
        frozen.write_text(json.dumps(altered))
        with self.assertRaises(manage.DeploymentError):
            service_module.validate_snapshot(self.output, plan)
        self.assert_original_unchanged()

    def test_manifest_rejects_unsupported_bootstrap_mode(self):
        for value in (None, True, "manual", [], {}):
            with self.subTest(value=value), self.assertRaises(manage.DeploymentError):
                service_module.validate_manifest(dict(self.manifest, bootstrap_mode=value))

    def test_manual_install_never_takes_over_existing_runtime_directory(self):
        self.manifest["bootstrap_mode"] = "manual-once"
        self.manifest_path.write_text(json.dumps(self.manifest))
        plan = self.prepare("yandex")
        runtime = self.private / "occupied-runtime"
        runtime.mkdir()
        with patch.object(service_module.browser, "manual_runtime_directory", return_value=runtime):
            with self.assertRaises(manage.DeploymentError):
                self.service.install(self.install_args(plan))
        self.assertTrue(runtime.is_dir())
        self.assertFalse(self.service.receipt_path.exists())
        self.assertFalse(manage.Manager(instance=plan["instance"]).root.exists())
        self.assert_original_unchanged()

    def test_manual_install_binding_and_rollback_keep_explicit_unit_mode(self):
        self.manifest["bootstrap_mode"] = "manual-once"
        self.manifest_path.write_text(json.dumps(self.manifest))
        plan = self.prepare("yandex")
        runtime = self.private / "new-runtime"
        with patch.object(service_module.browser, "manual_runtime_directory", return_value=runtime):
            self.service.install(self.install_args(plan))
        bound_plan, manifest, receipt = self.service.binding()
        self.assertEqual(bound_plan, plan)
        self.assertEqual(manifest["bootstrap_mode"], "manual-once")
        self.assertEqual(receipt["phase"], "enabled")
        unit = (self.unit_dir / receipt["unit"]).read_bytes()
        self.assertIn(b"RuntimeDirectory=unifiedvpn-openflux-bootstrap-browserphone\n", unit)
        self.assertEqual(hashlib.sha256(unit).hexdigest(), receipt["unit_sha256"])
        self.service.rollback()
        self.assert_original_unchanged()

    def test_manual_install_does_not_interpret_unreadable_runtime_as_absent(self):
        self.manifest["bootstrap_mode"] = "manual-once"
        self.manifest_path.write_text(json.dumps(self.manifest))
        plan = self.prepare("yandex")
        class UnreadableRuntime:
            def lstat(self):
                raise PermissionError("synthetic failure")
        with patch.object(service_module.browser, "manual_runtime_directory", return_value=UnreadableRuntime()):
            with self.assertRaises(PermissionError):
                self.service.install(self.install_args(plan))
        self.assertFalse(self.service.receipt_path.exists())
        self.assertFalse(manage.Manager(instance=plan["instance"]).root.exists())
        self.assert_original_unchanged()

    def test_browser_classic_batch_profile_preserves_codec_through_frozen_install_and_rollback(self):
        self.assertEqual(wizard.main(self.cli("yandex") + ["--wire-codec", "batch-zstd-v2"]), 0)
        self.service = service_module.AdminService(self.output, command=self.command, unit_dir=self.unit_dir)
        plan = wizard.read_plan(self.output)
        config = json.loads((self.output / "server.json").read_bytes())
        self.assertEqual(config["wire_codec"], "batch-zstd-v2")
        encoded = (self.output / "profile.uri").read_text().strip().split("#")[0].removeprefix("openflux://")
        self.assertEqual(json.loads(base64.urlsafe_b64decode(encoded + "=" * (-len(encoded) % 4)))["wire_codec"], "batch-zstd-v2")
        self.assertEqual(self.fake.commands, [])
        self.service.install(self.install_args(plan))
        installed = manage.Manager(instance=plan["instance"], command=self.command)
        self.assertEqual(manage.read_config(installed.root / "server.json")["wire_codec"], "batch-zstd-v2")
        self.assert_original_unchanged()
        self.service.rollback()
        self.assertFalse(installed.root.exists())
        self.assert_original_unchanged()

    def test_browser_volga_batch_rejected_before_freezing_or_generating_key(self):
        with patch.object(wizard.secrets, "token_hex") as random, patch.object(service_module, "freeze_runtime") as freeze:
            self.assertEqual(wizard.main(self.cli() + ["--wire-codec", "batch-zstd-v2"]), 1)
            random.assert_not_called()
            freeze.assert_not_called()
        self.assertFalse(self.output.exists())
        self.assertEqual(self.fake.commands, [])
        self.assertEqual(self.system_calls, [])
        self.assert_original_unchanged()

    def test_browser_apply_noninteractive_requires_additional_explicit_confirmation(self):
        with patch.object(wizard.sys.stdin, "isatty", return_value=False), patch.object(wizard, "apply_prepared") as apply:
            self.assertEqual(wizard.main(self.cli() + ["--apply"]), 1)
            apply.assert_not_called()
        self.assertTrue((self.output / "profile.uri").exists())
        self.assertEqual(self.fake.commands, [])

    def test_full_new_service_lifecycle_allocates_and_rolls_back_without_touching_original(self):
        plan = self.prepare()
        self.service.install(self.install_args(plan))
        manager = manage.Manager(instance=plan["instance"], command=self.command)
        state = manager.load_state()
        self.assertEqual(state["subnet"], "172.30.240.0/29")
        self.assertTrue(self.service.check()["service_active"])
        self.assertFalse(self.service.check()["real_traffic_verified"])
        self.assert_original_unchanged()
        self.service.rollback()
        self.assertFalse(manager.root.exists())
        self.assertEqual(list(self.unit_dir.iterdir()), [])
        self.assertTrue((self.output / "profile.uri").exists())
        self.assertEqual(json.loads((self.output / "browser-service.json").read_bytes())["phase"], "removed")
        self.assert_original_unchanged()

    def test_rollback_does_not_require_export_file(self):
        plan = self.prepare()
        self.service.install(self.install_args(plan))
        (self.output / "profile.uri").unlink()
        self.service.rollback()
        self.assert_original_unchanged()

    def test_failed_build_has_exact_owned_rollback_and_retains_private_plan(self):
        plan = self.prepare()
        self.fake.fail_build = True
        with self.assertRaises(manage.DeploymentError):
            self.service.install(self.install_args(plan))
        self.fake.fail_build = False
        self.service.rollback()
        self.assertFalse(manage.Manager(instance=plan["instance"]).root.exists())
        self.assertTrue((self.output / "server.json").exists())
        self.assert_original_unchanged()

    def test_failed_unit_enable_rolls_back_only_new_image_unit_and_files(self):
        plan = self.prepare()
        self.fail_enable = True
        with self.assertRaises(manage.DeploymentError):
            self.service.install(self.install_args(plan))
        self.service.rollback()
        self.assertEqual(list(self.unit_dir.iterdir()), [])
        self.assert_original_unchanged()

    def test_existing_new_unit_is_refused_before_docker_mutations(self):
        plan = self.prepare()
        (self.unit_dir / service_module.unit_name(plan["instance"])).write_text("foreign")
        with self.assertRaises(manage.DeploymentError):
            self.service.install(self.install_args(plan))
        self.assertEqual(self.fake.commands, [])
        self.assert_original_unchanged()

    def test_changed_frozen_helper_is_refused_before_install(self):
        plan = self.prepare()
        (self.output / "runtime/browser_worker.py").write_text("changed")
        with self.assertRaises(manage.DeploymentError):
            self.service.install(self.install_args(plan))
        self.assertEqual(self.fake.commands, [])
        self.assertEqual(self.system_calls, [])

    def test_stopped_profile_subnet_is_reserved_during_new_allocation(self):
        plan = self.prepare()
        original_state = self.original.load_state()
        self.original.stop()
        original_state.update(subnet="172.30.240.0/29", container_id=None, network_id=None)
        self.original.write_state(original_state)
        self.service.install(self.install_args(plan))
        state = manage.Manager(instance=plan["instance"]).load_state()
        self.assertEqual(state["subnet"], "172.30.240.8/29")

    def test_rollback_has_no_global_runtime_lock_inversion(self):
        plan = self.prepare()
        self.service.install(self.install_args(plan))
        events = []

        class Lock:
            def __init__(self, path=None):
                self.name = str(path) if path else "global"
            def __enter__(self):
                self_outer.assertNotIn(self.name, events)
                events.append(self.name)
            def __exit__(self, *args):
                events.remove(self.name)

        self_outer = self
        with patch.object(service_module, "admin_lock", side_effect=Lock):
            self.service.rollback()
        self.assertEqual(events, [])
        self.assert_original_unchanged()


if __name__ == "__main__":
    unittest.main()
