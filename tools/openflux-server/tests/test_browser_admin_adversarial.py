"""Independent offline adversarial checks for the new-profile browser wizard."""

import contextlib
import copy
import hashlib
import importlib.util
import importlib.machinery
import inspect
import io
import ipaddress
import json
import os
from pathlib import Path, PurePosixPath
import posixpath
import stat
import subprocess
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch

import test_create_profile as wizard_fixtures
import test_manage as fixtures


manage = fixtures.manage
wizard = wizard_fixtures.wizard
PRIVATE_SENTINEL = "SYNTHETIC_PRIVATE_VALUE_MUST_NOT_APPEAR"
BACKEND_SPEC = importlib.util.spec_from_file_location(
    "openflux_browser_admin_adversarial_backend", Path(__file__).resolve().parents[1] / "browser_service.py",
)
backend = importlib.util.module_from_spec(BACKEND_SPEC)
sys.modules[BACKEND_SPEC.name] = backend
BACKEND_SPEC.loader.exec_module(backend)
backend.manage = manage
backend.browser.manage = manage


class SyntheticLinuxPath(PurePosixPath):
    def resolve(self, strict=False):
        return type(self)(posixpath.normpath(str(self)))


def manifest_fixture():
    return {
        "schema": backend.MANIFEST_SCHEMA, "tool_version": backend.TOOL_VERSION,
        "source_instance": "default",
        "artifacts": {"binary_sha256": "a" * 64, "source_sha256": "b" * 64,
                      "runtime_image": "sha256:" + "c" * 64,
                      "licenses_sha256": {name: "d" * 64 for name in ("LICENSE", "NOTICE", "COPYRIGHT")}},
        "browser_image": "sha256:" + "e" * 64,
        "seccomp_file": "/opt/reviewed/browser-seccomp.json", "seccomp_sha256": "f" * 64,
        "helper_sha256": {name: "1" * 64 for name in backend.HELPERS},
        "subnet_pool": "172.29.0.0/16",
    }


def unit_state_text(name, path, **changes):
    fields = {"Id": name, "LoadState": "loaded", "FragmentPath": str(path), "DropInPaths": "",
              "Transient": "no", "ActiveState": "inactive", "SubState": "dead"}
    fields.update(changes)
    return "\n".join(key + "=" + value for key, value in fields.items()) + "\n"


class StrictDockerInspectTests(unittest.TestCase):
    def inspect_with(self, kind, name, stdout="", stderr="", code=1):
        command = Mock(return_value=subprocess.CompletedProcess([], code, stdout, stderr))
        manager = manage.Manager(instance="browserguard", command=command)
        return manager, command

    def test_unknown_docker_errors_are_not_absent_resources(self):
        cases = (
            (1, "", ""),
            (1, "", "permission denied: " + PRIVATE_SENTINEL),
            (1, "", "Cannot connect to the Docker daemon"),
            (124, "", "timeout"),
            (125, "", "daemon unavailable"),
            (1, "[]", "Error: No such container: a-different-instance"),
            (1, "[]", "Error: No such container: unifiedvpn-openflux-browserguard\npermission denied"),
            (1, "[]", "untrusted prefix Error: No such container: unifiedvpn-openflux-browserguard"),
        )
        for code, stdout, stderr in cases:
            with self.subTest(code=code, category="unknown_docker_error"):
                manager, command = self.inspect_with("container", "unused", stdout, stderr, code)
                output = io.StringIO()
                with contextlib.redirect_stdout(output), contextlib.redirect_stderr(output):
                    with self.assertRaises(manage.DeploymentError) as error:
                        manager.inspect("container", manager.name)
                self.assertNotIn(PRIVATE_SENTINEL, str(error.exception) + output.getvalue())
                command.assert_called_once_with(
                    ["docker", "--host", "unix:///var/run/docker.sock", "container", "inspect", manager.name],
                    timeout=30, allow_failure=True,
                )

    def test_exact_kind_and_identity_missing_diagnostics_allow_absence(self):
        cases = (
            ("container", "unifiedvpn-openflux-browserguard", "Error: No such container: "),
            ("image", "sha256:" + "c" * 64, "Error response from daemon: No such image: "),
        )
        for kind, name, prefix in cases:
            with self.subTest(kind=kind):
                manager, _ = self.inspect_with(kind, name, "[]\n", prefix + name + "\n")
                self.assertIsNone(manager.inspect(kind, name))
        name = "unifiedvpn-openflux-browserguard-net"
        manager, _ = self.inspect_with("network", name, "[]\n", "Error response from daemon: network " + name + " not found\n")
        self.assertIsNone(manager.inspect("network", name))

    def test_wrong_kind_missing_diagnostic_fails_closed(self):
        manager, _ = self.inspect_with("container", "unused", "[]", "Error: No such image: unifiedvpn-openflux-browserguard")
        with self.assertRaises(manage.DeploymentError):
            manager.inspect("container", manager.name)

    def test_success_with_invalid_resource_description_is_never_adopted(self):
        for data in ("{}", "[]", "[null]", "[1]", "[{}, {}]", "not-json-" + PRIVATE_SENTINEL):
            with self.subTest(category="malformed_inspect"):
                manager, _ = self.inspect_with("container", "unused", data, "", 0)
                with self.assertRaises(manage.DeploymentError) as error:
                    manager.inspect("container", manager.name)
                self.assertNotIn(PRIVATE_SENTINEL, str(error.exception))


class DocumentAliasGuardTests(unittest.TestCase):
    def test_disk_share_identity_ignores_presentation_parameters(self):
        base = "https://disk.yandex.ru/i/public-duplicate-fixture"
        for suffix in ("?lang=ru", "?locale=en", "?utm_source=synthetic", "?download=0"):
            with self.subTest(category="share_presentation_alias"):
                self.assertEqual(wizard.document_identity(base), wizard.document_identity(base + suffix))

    def test_docs_authoritative_url_identity_ignores_presentation_parameters(self):
        base = "https://docs.yandex.ru/docs/view?url=ya-disk-public%3A%2F%2Fpublic-duplicate-fixture"
        for suffix in ("&lang=ru", "&locale=en", "&name=Synthetic%20Document", "&utm_source=synthetic"):
            with self.subTest(category="editor_presentation_alias"):
                self.assertEqual(wizard.document_identity(base), wizard.document_identity(base + suffix))

    def test_ambiguous_duplicate_authoritative_parameters_are_rejected(self):
        value = "https://docs.yandex.ru/docs/view?url=first-public-fixture&url=second-public-fixture"
        with self.assertRaises(manage.DeploymentError) as error:
            wizard.document_identity(value)
        self.assertNotIn(value, str(error.exception))

    def test_distinct_documents_remain_distinct(self):
        first = "https://disk.yandex.ru/i/public-first-fixture"
        second = "https://disk.yandex.ru/i/public-second-fixture"
        self.assertNotEqual(wizard.document_identity(first), wizard.document_identity(second))


class HelperCodeBoundaryTests(unittest.TestCase):
    def test_runtime_loader_compiles_source_without_executing_bytecode_cache(self):
        with patch.object(importlib.machinery.SourceFileLoader, "exec_module") as cached_loader:
            module = backend.load_source("synthetic_checked_manager", "manage.py")
        cached_loader.assert_not_called()
        self.assertEqual(module.UPSTREAM, manage.UPSTREAM)

    def test_root_owned_helper_also_requires_regular_single_link_nonwritable_code(self):
        for change in ({"st_mode": stat.S_IFLNK | 0o644}, {"st_nlink": 2},
                       {"st_uid": 1000}, {"st_mode": stat.S_IFREG | 0o664}):
            metadata = dict(st_mode=stat.S_IFREG | 0o644, st_uid=0, st_nlink=1)
            metadata.update(change)
            code = Path("/synthetic/root/tools/browser_service.py")
            directory = SimpleNamespace(st_mode=stat.S_IFDIR | 0o755, st_uid=0)

            def inspect(path):
                return SimpleNamespace(**metadata) if path == code else directory

            with self.subTest(category="unsafe_helper_metadata"), patch.object(Path, "lstat", inspect):
                with self.assertRaises(manage.DeploymentError):
                    wizard.require_secure_tool_file(code)

    def test_writable_or_symlink_ancestor_is_rejected(self):
        code = Path("/synthetic/root/tools/browser_service.py")
        for bad_mode in (stat.S_IFDIR | 0o777, stat.S_IFLNK | 0o755):
            def inspect(path):
                if path == code:
                    return SimpleNamespace(st_mode=stat.S_IFREG | 0o644, st_uid=0, st_nlink=1)
                mode = bad_mode if path == code.parent.parent else stat.S_IFDIR | 0o755
                return SimpleNamespace(st_mode=mode, st_uid=0)

            with self.subTest(category="unsafe_helper_parent"), patch.object(Path, "lstat", inspect):
                with self.assertRaises(manage.DeploymentError):
                    wizard.require_secure_tool_file(code)

    def test_unexpected_backend_exception_does_not_print_private_exception_text(self):
        output = io.StringIO()
        with contextlib.redirect_stdout(output), contextlib.redirect_stderr(output), \
                patch.object(wizard, "require_host", side_effect=RuntimeError(PRIVATE_SENTINEL)):
            self.assertEqual(wizard.main([]), 1)
        self.assertNotIn(PRIVATE_SENTINEL, output.getvalue())


class FrozenRuntimeContractTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.directory = Path(temporary.name)
        self.sources = self.directory / "helpers"
        self.sources.mkdir()
        self.prepared = self.directory / "prepared"
        self.prepared.mkdir()
        self.manifest = manifest_fixture()
        for name in backend.HELPERS:
            content = ("synthetic-reviewed-helper " + name).encode("ascii")
            (self.sources / name).write_bytes(content)
            self.manifest["helper_sha256"][name] = hashlib.sha256(content).hexdigest()
        self.seccomp = self.directory / "reviewed-seccomp.json"
        self.seccomp.write_bytes(b'{"defaultAction":"SCMP_ACT_ERRNO","syscalls":[]}')
        self.manifest["seccomp_file"] = str(self.seccomp)
        self.manifest["seccomp_sha256"] = hashlib.sha256(self.seccomp.read_bytes()).hexdigest()
        for target in (patch.object(backend, "HERE", self.sources),
                       patch.object(backend, "canonical_path", side_effect=lambda value: Path(value)),
                       patch.object(manage, "require_secure_file", side_effect=lambda path, private=False: path.stat()),
                       patch.object(backend, "profile_module", return_value=SimpleNamespace(write_private=wizard.write_private))):
            target.start()
            self.addCleanup(target.stop)

    def freeze(self):
        digest = backend.freeze_runtime(self.prepared, self.manifest)
        return dict(self.manifest["artifacts"], instance="browserguard", display_name="guard", subnet=None,
                    source_instance="default", admin_manifest_sha256=digest)

    def test_mismatched_source_helper_is_rejected_before_partial_runtime_directory(self):
        for name in backend.HELPERS:
            with self.subTest(helper=name):
                original = (self.sources / name).read_bytes()
                (self.sources / name).write_bytes(b"changed helper")
                with self.assertRaises(manage.DeploymentError):
                    backend.freeze_runtime(self.prepared, self.manifest)
                self.assertFalse((self.prepared / "runtime").exists())
                (self.sources / name).write_bytes(original)

    def test_changed_seccomp_is_rejected_before_frozen_runtime_creation(self):
        self.seccomp.write_bytes(b'{"defaultAction":"SCMP_ACT_ALLOW"}')
        with self.assertRaises(manage.DeploymentError):
            backend.freeze_runtime(self.prepared, self.manifest)
        self.assertFalse((self.prepared / "runtime").exists())

    def test_frozen_helper_mutations_are_detected_independently(self):
        plan = self.freeze()
        backend.validate_snapshot(self.prepared, plan)
        for name in backend.HELPERS:
            with self.subTest(helper=name):
                path = self.prepared / "runtime" / name
                original = path.read_bytes()
                path.write_bytes(b"changed helper")
                with self.assertRaises(manage.DeploymentError):
                    backend.validate_snapshot(self.prepared, plan)
                path.write_bytes(original)

    def test_frozen_manifest_or_sandbox_change_is_rejected(self):
        plan = self.freeze()
        for path in (self.prepared / "admin-runtime.json", self.prepared / "runtime/browser-seccomp.json"):
            original = path.read_bytes()
            path.write_bytes(b"{}")
            with self.subTest(category="frozen_runtime_change"), self.assertRaises(manage.DeploymentError):
                backend.validate_snapshot(self.prepared, plan)
            path.write_bytes(original)

    def test_plan_cannot_substitute_source_or_public_artifact_contract(self):
        plan = self.freeze()
        for field, value in (("source_instance", "browserother"), ("binary_sha256", "e" * 64),
                             ("source_sha256", "e" * 64), ("runtime_image", "sha256:" + "e" * 64),
                             ("licenses_sha256", {name: "e" * 64 for name in ("LICENSE", "NOTICE", "COPYRIGHT")})):
            with self.subTest(field=field), self.assertRaises(manage.DeploymentError):
                backend.validate_snapshot(self.prepared, dict(plan, **{field: value}))

    def test_existing_freeze_is_never_overwritten(self):
        plan = self.freeze()
        before = {path.name: path.read_bytes() for path in (self.prepared / "runtime").iterdir()}
        with self.assertRaises(FileExistsError):
            backend.freeze_runtime(self.prepared, self.manifest)
        self.assertEqual(before, {path.name: path.read_bytes() for path in (self.prepared / "runtime").iterdir()})
        backend.validate_snapshot(self.prepared, plan)

    def test_apply_loader_cannot_use_changed_current_code_with_intact_frozen_helpers(self):
        plan = dict(self.freeze(), schema=backend.PLAN_SCHEMA)
        module = SimpleNamespace(read_plan=lambda *args, **kwargs: plan)
        service = backend.AdminService(self.prepared)
        with patch.object(backend, "profile_module", return_value=module):
            service.plan()
            (self.sources / "browser_service.py").write_bytes(b"new unreviewed controller code")
            with self.assertRaises(manage.DeploymentError):
                service.plan()


class ManifestAndUnitInputTests(unittest.TestCase):
    def test_manifest_rejects_unpinned_images_incomplete_helpers_and_wrong_schema(self):
        base = manifest_fixture()
        mutations = [dict(base, tool_version=True), dict(base, schema="old"), dict(base, unexpected=True),
                     dict(base, browser_image="reviewed:latest"), dict(base, helper_sha256={}),
                     dict(base, source_instance="default\nExecStart=bad")]
        for field in ("binary_sha256", "source_sha256", "runtime_image"):
            item = copy.deepcopy(base)
            item["artifacts"][field] = "not-pinned"
            mutations.append(item)
        for pool in ("0.0.0.0/0", "203.0.113.0/24", "172.29.1.1/24", "172.29.0.0/25", True):
            mutations.append(dict(base, subnet_pool=pool))
        with patch.object(backend, "Path", SyntheticLinuxPath):
            backend.validate_manifest(base)
            for item in mutations:
                with self.subTest(category="invalid_manifest"), self.assertRaises(manage.DeploymentError):
                    backend.validate_manifest(item)

    def test_systemd_path_and_instance_cannot_inject_unit_content(self):
        with patch.object(backend, "Path", SyntheticLinuxPath):
            for value in ("/root/unsafe\nExecStart=bad", "/root/unsafe%h", "/root/space name",
                          "/root/prepared/../other", "relative/path", "/root/with\tcontrol"):
                with self.subTest(category="unit_path_injection"), self.assertRaises(manage.DeploymentError):
                    backend.unit_contents(value, "browserguard")
            for instance in ("default", "browser", "Browserguard", "browser../bad", "browserx\nRestart=no"):
                with self.subTest(category="unit_name_injection"), self.assertRaises(manage.DeploymentError):
                    backend.unit_contents("/root/prepared", instance)

    def test_systemd_runtime_uses_isolated_interpreter_and_fixed_commands(self):
        with patch.object(backend, "Path", SyntheticLinuxPath):
            text = backend.unit_contents("/root/prepared", "browserguard").decode("ascii")
        self.assertIn("/usr/bin/python3 -I -B /root/prepared/runtime/browser_service.py serve", text)
        self.assertIn("ExecStopPost=/usr/bin/python3 -I -B", text)
        self.assertIn("WantedBy=multi-user.target", text)
        self.assertIn("UMask=0077", text)
        for forbidden in ("openflux://", "document_url", "encryption_key", "--no-sandbox", "shell=True"):
            self.assertNotIn(forbidden, text)


class UnitOwnershipBoundaryTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.directory = Path(temporary.name)
        self.unit_name = backend.unit_name("browserguard")
        self.path = self.directory / self.unit_name
        self.contents = b"[Unit]\nDescription=Synthetic owned unit\n"
        self.path.write_bytes(self.contents)
        self.record = {"unit": self.unit_name, "unit_sha256": hashlib.sha256(self.contents).hexdigest()}
        self.command = Mock(return_value=subprocess.CompletedProcess([], 0, unit_state_text(self.unit_name, self.path), ""))
        self.service = backend.AdminService(self.directory, command=self.command, unit_dir=self.directory)
        target = patch.object(backend, "read_bytes", side_effect=lambda path, *args, **kwargs: path.read_bytes())
        target.start()
        self.addCleanup(target.stop)

    def test_owned_primary_file_is_not_enough_when_loaded_unit_is_different(self):
        for fields in ({"FragmentPath": "/run/foreign.service"}, {"DropInPaths": "/run/foreign.conf"},
                       {"Transient": "yes"}, {"Id": "foreign.service"}, {"LoadState": "error"}):
            self.command.return_value = subprocess.CompletedProcess([], 0, unit_state_text(self.unit_name, self.path, **fields), "")
            with self.subTest(category="foreign_loaded_unit"), self.assertRaises(manage.DeploymentError):
                self.service.owned_unit(self.record)
        self.assertTrue(all(call.args[0][2] == "show" for call in self.command.call_args_list))

    def test_tampered_unit_is_rejected_before_any_systemctl_call(self):
        self.path.write_bytes(self.contents + b"[Service]\nExecStart=/foreign\n")
        with self.assertRaises(manage.DeploymentError):
            self.service.owned_unit(self.record)
        self.command.assert_not_called()

    def test_missing_primary_with_loaded_unit_is_never_removed(self):
        self.path.unlink()
        with self.assertRaises(manage.DeploymentError):
            self.service.owned_unit(self.record)

    def test_missing_primary_and_exact_not_found_can_recover_partial_install(self):
        self.path.unlink()
        self.command.return_value = subprocess.CompletedProcess(
            [], 0, unit_state_text(self.unit_name, self.path, LoadState="not-found", FragmentPath=""), "",
        )
        _, _, exists = self.service.owned_unit(self.record)
        self.assertFalse(exists)

    def test_incomplete_or_duplicate_systemd_properties_are_rejected(self):
        original = unit_state_text(self.unit_name, self.path)
        cases = []
        for field in ("Id", "LoadState", "FragmentPath", "DropInPaths", "Transient", "ActiveState", "SubState"):
            cases.append("\n".join(line for line in original.splitlines() if not line.startswith(field + "=")) + "\n")
        cases += ["Id=foreign.service\n" + original, original + "DropInPaths=\n", original + "Unknown=value\n"]
        for data in cases:
            self.command.return_value = subprocess.CompletedProcess([], 0, data, "")
            with self.subTest(category="ambiguous_unit_description"), self.assertRaises(manage.DeploymentError):
                self.service.owned_unit(self.record)


class PartialRuntimeCleanupTests(unittest.TestCase):
    def fixture(self, state):
        service = backend.AdminService(Path("synthetic-prepared"))
        manager = Mock()
        manager.name = "unifiedvpn-openflux-browserguard"
        manager.network = manager.name + "-net"
        supervisor = Mock()
        service.binding = Mock(return_value=({"instance": "browserguard"}, manifest_fixture(), {}))
        service.native = Mock(return_value=(manager, state))
        service.supervisor = Mock(return_value=supervisor)
        service.owned_unit = Mock()
        service.runtime_lock = Mock(return_value=contextlib.nullcontext())
        return service, manager, supervisor

    def test_recorded_partial_bridge_after_interruption_can_be_cleaned_without_native_container(self):
        identity = "7" * 64
        service, manager, supervisor = self.fixture({"network_id": identity, "container_id": None})
        manager.owned.side_effect = [None, {"Id": identity, "Containers": {}}]
        supervisor.companion.return_value = None
        service.stop_runtime()
        manager.stop.assert_called_once_with()
        supervisor.cleanup.assert_not_called()

    def test_unrecorded_partial_bridge_is_not_adopted_for_cleanup(self):
        service, manager, supervisor = self.fixture({"network_id": None, "container_id": None})
        manager.owned.side_effect = [None, {"Id": "7" * 64, "Containers": {}}]
        supervisor.companion.return_value = None
        with self.assertRaises(manage.DeploymentError):
            service.stop_runtime()
        manager.stop.assert_not_called()
        supervisor.cleanup.assert_not_called()

    def test_stop_does_not_depend_on_retained_export_profile_uri(self):
        service, manager, supervisor = self.fixture({"browser_bootstrap_stdio": True})
        manager.owned.side_effect = [None, None]
        supervisor.companion.return_value = None
        service.stop_runtime()
        self.assertGreaterEqual(service.binding.call_count, 1)
        self.assertTrue(all(call.kwargs == {"with_inputs": False} for call in service.binding.call_args_list))
        supervisor.cleanup.assert_called_once_with()


class InstalledNativeBindingTests(unittest.TestCase):
    def test_changed_owner_subnet_or_image_is_not_adopted(self):
        service = backend.AdminService(Path("synthetic-prepared"))
        plan = {"owner": "a" * 64}
        record = {"subnet": "172.29.0.0/29", "image_id": "sha256:" + "b" * 64}
        original = {"owner": plan["owner"], "subnet": record["subnet"], "image_id": record["image_id"]}
        manager = Mock()
        service.manager = Mock(return_value=manager)
        for field, value in (("owner", "c" * 64), ("subnet", "172.29.0.8/29"), ("image_id", "sha256:" + "d" * 64)):
            manager.load_state.return_value = dict(original, **{field: value})
            with self.subTest(field=field), self.assertRaises(manage.DeploymentError):
                service.native(plan, record, complete=False)

    def test_installed_binary_and_private_configuration_remain_hash_bound(self):
        service = backend.AdminService(Path("synthetic-prepared"))
        contents = b"synthetic-private-config-bytes"
        plan = {"owner": "a" * 64, "binary_sha256": "b" * 64,
                "server_sha256": hashlib.sha256(contents).hexdigest()}
        record = {"subnet": "172.29.0.0/29", "image_id": "sha256:" + "c" * 64}
        manager = Mock()
        manager.root = Path("synthetic-installation")
        manager.load_state.return_value = {"owner": plan["owner"], "subnet": record["subnet"], "image_id": record["image_id"]}
        service.manager = Mock(return_value=manager)
        for binary, config in (("d" * 64, contents), (plan["binary_sha256"], b"changed private config")):
            with self.subTest(category="changed_installed_payload"), patch.object(manage, "require_secure_file"), \
                    patch.object(manage, "sha256_file", return_value=binary), patch.object(backend, "read_bytes", return_value=config):
                with self.assertRaises(manage.DeploymentError):
                    service.native(plan, record)


class AllocatorAndLockTests(unittest.TestCase):
    def test_default_and_runtime_locks_use_direct_root_owned_run_parent(self):
        default = inspect.signature(backend.admin_lock).parameters["path"].default
        self.assertEqual(default.as_posix(), "/run/unifiedvpn-openflux-admin.lock")
        self.assertEqual(default.parent.as_posix(), "/run")
        service = backend.AdminService(Path("synthetic-prepared"))
        with patch.object(backend, "admin_lock", return_value=contextlib.nullcontext()) as lock:
            with service.runtime_lock({"instance": "browserguard"}):
                pass
        self.assertEqual(lock.call_args.args[0].as_posix(), "/run/unifiedvpn-openflux-browserguard.lock")
        self.assertEqual(lock.call_args.args[0].parent, default.parent)

    def test_sticky_world_writable_parent_is_refused_before_lock_file_access(self):
        parent = Path("/run/lock")
        lock = SimpleNamespace(LOCK_EX=2, LOCK_NB=4, flock=Mock())

        def metadata(path):
            return SimpleNamespace(st_mode=stat.S_IFDIR | (0o1777 if path == parent else 0o755), st_uid=0)

        with patch.dict(sys.modules, {"fcntl": lock}), patch.object(Path, "lstat", metadata), \
                patch.object(os, "open") as opened, patch.object(os, "close") as closed:
            with self.assertRaises(manage.DeploymentError):
                with backend.admin_lock(parent / "synthetic-rejected.lock"):
                    self.fail("A sticky writable directory must not be trusted")
        opened.assert_not_called()
        closed.assert_not_called()
        lock.flock.assert_not_called()

    def test_root_owned_run_parent_still_checks_single_link_private_lock_and_closes_it(self):
        lock = SimpleNamespace(LOCK_EX=2, LOCK_NB=4, flock=Mock())
        directory = SimpleNamespace(st_mode=stat.S_IFDIR | 0o755, st_uid=0)
        file = SimpleNamespace(st_mode=stat.S_IFREG | 0o600, st_uid=0, st_nlink=1, st_ino=71)
        with patch.dict(sys.modules, {"fcntl": lock}), patch.object(Path, "lstat", return_value=directory), \
                patch.object(os, "O_NOFOLLOW", 0x100000, create=True), patch.object(os, "O_CLOEXEC", 0x200000, create=True), \
                patch.object(os, "open", return_value=43) as opened, patch.object(os, "fstat", return_value=file), \
                patch.object(Path, "stat", return_value=SimpleNamespace(st_ino=71)), patch.object(os, "close") as closed, \
                patch.object(manage, "secure_directory", wraps=manage.secure_directory) as checked:
            with backend.admin_lock():
                lock.flock.assert_called_once_with(43, lock.LOCK_EX | lock.LOCK_NB)
            checked.assert_called_once_with(inspect.signature(backend.admin_lock).parameters["path"].default.parent)
            self.assertEqual(opened.call_args.args[2], 0o600)
            self.assertEqual(opened.call_args.args[1] & 0x300000, 0x300000)
            closed.assert_called_once_with(43)

    def test_allocator_avoids_routes_docker_and_stopped_installations(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            installation = root / manage.NAME
            installation.mkdir()
            (installation / "state.json").write_text(json.dumps({"subnet": "172.29.0.16/29"}), encoding="ascii")
            command = Mock(return_value=subprocess.CompletedProcess([], 0, '[{"dst":"172.29.0.0/29"}]', ""))
            manager = Mock(command=command)
            manager.docker.side_effect = [subprocess.CompletedProcess([], 0, "synthetic-network", ""),
                                          subprocess.CompletedProcess([], 0, '[{"IPAM":{"Config":[{"Subnet":"172.29.0.8/29"}]}}]', "")]
            manifest = dict(manifest_fixture(), subnet_pool="172.29.0.0/24")
            with patch.object(manage, "INSTALL_DIR", root / manage.NAME), \
                    patch.object(backend, "read_bytes", side_effect=lambda path, *args, **kwargs: path.read_bytes()):
                selected = backend.unused_subnet(manager, manifest)
            self.assertEqual(selected, "172.29.0.24/29")
            manager.ensure_subnet_free.assert_called_once_with(ipaddress.IPv4Network(selected))
            self.assertEqual([call.args[:2] for call in manager.docker.call_args_list], [("network", "ls"), ("network", "inspect")])

    def test_allocator_never_uses_internal_tunnel_network_or_mutates_when_exhausted(self):
        with tempfile.TemporaryDirectory() as temporary:
            manager = Mock()
            manager.command.return_value = subprocess.CompletedProcess([], 0, "[]", "")
            manager.docker.return_value = subprocess.CompletedProcess([], 0, "", "")
            with patch.object(manage, "INSTALL_DIR", Path(temporary) / manage.NAME), self.assertRaises(manage.DeploymentError):
                backend.unused_subnet(manager, dict(manifest_fixture(), subnet_pool="10.10.10.0/24"))
            manager.ensure_subnet_free.assert_not_called()
            manager.docker.assert_called_once_with("network", "ls", "--quiet")

    def test_lock_rejects_unsafe_metadata_and_releases_descriptor(self):
        metadata = dict(st_mode=stat.S_IFREG | 0o600, st_uid=0, st_nlink=1, st_ino=71)
        cases = ({"st_mode": stat.S_IFLNK | 0o600}, {"st_uid": 1000}, {"st_nlink": 2},
                 {"st_mode": stat.S_IFREG | 0o644}, {"st_ino": 72})
        for changes in cases:
            lock = SimpleNamespace(LOCK_EX=2, LOCK_NB=4, flock=Mock())
            info = SimpleNamespace(**dict(metadata, **changes))
            with self.subTest(category="unsafe_allocation_lock"), \
                    patch.dict(sys.modules, {"fcntl": lock}), patch.object(manage, "secure_directory"), \
                    patch.object(os, "O_NOFOLLOW", 0x100000, create=True), patch.object(os, "O_CLOEXEC", 0x200000, create=True), \
                    patch.object(os, "open", return_value=43), patch.object(os, "fstat", return_value=info), \
                    patch.object(Path, "stat", return_value=SimpleNamespace(st_ino=71)), patch.object(os, "close") as close:
                with self.assertRaises(manage.DeploymentError):
                    with backend.admin_lock(Path("synthetic-admin.lock")):
                        self.fail("Unsafe lock metadata entered the mutation region")
                lock.flock.assert_not_called()
                close.assert_called_once_with(43)

    def test_competing_lock_is_nonblocking_and_never_enters_mutation_region(self):
        lock = SimpleNamespace(LOCK_EX=2, LOCK_NB=4, flock=Mock(side_effect=BlockingIOError(PRIVATE_SENTINEL)))
        info = SimpleNamespace(st_mode=stat.S_IFREG | 0o600, st_uid=0, st_nlink=1, st_ino=71)
        with patch.dict(sys.modules, {"fcntl": lock}), patch.object(manage, "secure_directory"), \
                patch.object(os, "O_NOFOLLOW", 0x100000, create=True), patch.object(os, "O_CLOEXEC", 0x200000, create=True), \
                patch.object(os, "open", return_value=43) as opened, patch.object(os, "fstat", return_value=info), \
                patch.object(Path, "stat", return_value=SimpleNamespace(st_ino=71)), patch.object(os, "close") as close:
            with self.assertRaises(manage.DeploymentError) as error:
                with backend.admin_lock(Path("synthetic-admin.lock")):
                    self.fail("A competing allocator must not enter the mutation region")
            self.assertNotIn(PRIVATE_SENTINEL, str(error.exception))
            lock.flock.assert_called_once_with(43, lock.LOCK_EX | lock.LOCK_NB)
            self.assertEqual(opened.call_args.args[1] & 0x300000, 0x300000)
            close.assert_called_once_with(43)


class RollbackOwnershipTests(unittest.TestCase):
    def fixture(self, loaded=False):
        service = backend.AdminService(Path("synthetic-prepared"))
        manager = Mock()
        manager.root.exists.return_value = True
        manager.root.is_symlink.return_value = False
        manager.name = "unifiedvpn-openflux-browserguard"
        manager.network = manager.name + "-net"
        manager.load_state.return_value = {"owner": "a" * 64, "container_id": None, "network_id": None}
        manager.owned.return_value = None
        supervisor = Mock()
        supervisor.companion.return_value = None
        plan = {"instance": "browserguard", "owner": "a" * 64}
        record = {"unit": backend.unit_name(plan["instance"]), "phase": "installing", "image_id": None}
        service.binding = Mock(return_value=(plan, manifest_fixture(), record))
        service.owned_unit = Mock(return_value=(Path("synthetic-unit"), {"LoadState": "loaded" if loaded else "not-found"}, False))
        service.native = Mock(return_value=(manager, manager.load_state.return_value))
        service.manager = Mock(return_value=manager)
        service.supervisor = Mock(return_value=supervisor)
        service.systemctl = Mock()
        service.save = Mock()
        service.runtime_lock = Mock(return_value=contextlib.nullcontext())
        return service, manager, supervisor

    def test_partial_install_without_unit_can_remove_only_owned_native_resources(self):
        service, manager, supervisor = self.fixture()
        with patch.object(backend, "admin_lock", return_value=contextlib.nullcontext()):
            service.rollback()
        self.assertGreaterEqual(service.binding.call_count, 1)
        self.assertTrue(all(call.kwargs == {"with_inputs": False} for call in service.binding.call_args_list))
        service.systemctl.assert_not_called()
        manager.remove.assert_called_once_with()
        supervisor.cleanup.assert_not_called()
        self.assertEqual(service.save.call_args.args[0]["phase"], "removed")

    def test_foreign_unit_or_native_identity_refuses_before_disable(self):
        for field in ("owned_unit", "native"):
            service, manager, _ = self.fixture(loaded=True)
            getattr(service, field).side_effect = manage.DeploymentError("identity changed")
            with self.subTest(field=field), patch.object(backend, "admin_lock", return_value=contextlib.nullcontext()):
                with self.assertRaises(manage.DeploymentError):
                    service.rollback()
            service.systemctl.assert_not_called()
            manager.remove.assert_not_called()
            service.save.assert_not_called()

    def test_foreign_network_endpoint_refuses_before_any_stop_or_disable(self):
        service, manager, supervisor = self.fixture(loaded=True)
        manager.owned.side_effect = [{"Id": "1" * 64}, {"Id": "2" * 64, "Containers": {"1" * 64: {}, "3" * 64: {}}}]
        with patch.object(backend, "admin_lock", return_value=contextlib.nullcontext()), self.assertRaises(manage.DeploymentError):
            service.rollback()
        service.systemctl.assert_not_called()
        manager.remove.assert_not_called()
        supervisor.cleanup.assert_not_called()

    def test_runtime_lock_is_acquired_after_stop_child_and_covers_final_remove(self):
        service, manager, _ = self.fixture(loaded=True)
        events = []
        locked = [False]

        @contextlib.contextmanager
        def runtime_lock(plan):
            events.append("runtime_lock")
            locked[0] = True
            try:
                yield
            finally:
                locked[0] = False
                events.append("runtime_unlock")

        def systemctl(*args):
            self.assertFalse(locked[0], "ExecStopPost must not wait on a lock held by its parent")
            self.assertEqual(args[:2], ("disable", "--now"))
            events.append("disable_and_stop_child_complete")

        def remove():
            self.assertTrue(locked[0], "A manual serve must not race final owned removal")
            events.append("remove_owned")

        service.runtime_lock = runtime_lock
        service.systemctl.side_effect = systemctl
        manager.remove.side_effect = remove
        with patch.object(backend, "admin_lock", return_value=contextlib.nullcontext()):
            service.rollback()
        self.assertEqual(events, ["disable_and_stop_child_complete", "runtime_lock", "remove_owned", "runtime_unlock"])


if __name__ == "__main__":
    unittest.main()
