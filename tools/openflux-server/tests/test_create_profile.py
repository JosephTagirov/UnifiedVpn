import base64
import contextlib
import copy
import importlib.util
import importlib.machinery
import io
import json
import os
from pathlib import Path
import stat
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch
from urllib.parse import unquote

import test_manage as fixtures


SPEC = importlib.util.spec_from_file_location("openflux_create_profile", Path(__file__).resolve().parents[1] / "create_profile.py")
wizard = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(wizard)
wizard.manage = fixtures.manage
manage = wizard.manage
DOCUMENT = "https://docs.yandex.ru/docs/view?url=public-new-document-fixture"


class ProfileWizardTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.directory = Path(temporary.name)
        self.installs = self.directory / "opt"
        self.installs.mkdir()
        self.private = self.directory / "private"
        self.private.mkdir()
        self.output = self.private / "new-phone2"
        self.fake = fixtures.FakeDocker()
        original_init = manage.Manager.__init__
        fake = self.fake

        def initialize(manager, root=None, command=None, instance="default"):
            original_init(manager, root=root, command=command or fake, instance=instance)

        for target in (patch.object(manage, "INSTALL_DIR", self.installs / manage.NAME),
                       patch.object(manage.Manager, "__init__", initialize),
                       patch.object(manage.Manager, "preflight"), patch.object(manage, "secure_directory"),
                       patch.object(manage, "require_secure_file", side_effect=lambda path, private=False: path.stat()),
                       patch.object(wizard, "private_directory"), patch.object(wizard, "require_host"),
                       patch.object(os, "readlink", return_value="net:[100]")):
            target.start()
            self.addCleanup(target.stop)
        self.stdout, self.stderr = io.StringIO(), io.StringIO()
        for stream in (contextlib.redirect_stdout(self.stdout), contextlib.redirect_stderr(self.stderr)):
            stream.__enter__()
            self.addCleanup(stream.__exit__, None, None, None)
        inputs = self.directory / "public-inputs"
        inputs.mkdir()
        (inputs / "binary").write_bytes(fixtures.elf())
        (inputs / "source.tar.gz").write_bytes(b"\x1f\x8bpublic-source-fixture")
        (inputs / "server.json").write_text(json.dumps(fixtures.config()), encoding="utf-8")
        for name in ("LICENSE", "NOTICE", "COPYRIGHT"):
            (inputs / name).write_text("public " + name, encoding="utf-8")
        original = manage.Manager()
        original.install(SimpleNamespace(binary=inputs / "binary", binary_sha256=manage.sha256_file(inputs / "binary"),
                                         source_archive=inputs / "source.tar.gz", source_sha256=manage.sha256_file(inputs / "source.tar.gz"),
                                         licenses_dir=inputs, runtime_image=fixtures.RUNTIME_ID, config=inputs / "server.json", subnet="172.30.251.0/28"))
        original.run()
        self.original = original
        self.original_files = {path.name: path.read_bytes() for path in original.root.iterdir()}
        self.original_container = copy.deepcopy(self.fake.containers[manage.NAME])
        self.original_network = copy.deepcopy(self.fake.networks[manage.NETWORK])
        self.document_file = self.private / "document.txt"
        self.document_file.write_text(DOCUMENT, encoding="utf-8")
        self.args = wizard.parser().parse_args(["--instance", "phone2", "--document-file", str(self.document_file),
                                                "--confirm-legacy-document", "--reuse-instance", "default",
                                                "--subnet", "172.30.252.0/28", "--output", str(self.output)])
        self.fake.commands.clear()
        self.stdout.truncate(0)
        self.stdout.seek(0)

    def assert_original_unchanged(self):
        self.assertEqual(self.original_files, {path.name: path.read_bytes() for path in self.original.root.iterdir()})
        self.assertEqual(self.fake.containers[manage.NAME], self.original_container)
        self.assertEqual(self.fake.networks[manage.NETWORK], self.original_network)

    def cli_args(self):
        return ["--instance", "phone2", "--document-file", str(self.document_file), "--confirm-legacy-document",
                "--reuse-instance", "default", "--subnet", "172.30.252.0/28", "--output", str(self.output)]

    def test_prepare_does_not_contact_docker_or_copy_existing_secrets(self):
        self.assertEqual(wizard.main(self.cli_args()), 0)
        self.assertEqual(self.fake.commands, [])
        self.assertEqual({path.name for path in self.output.iterdir()}, {"server.json", "profile.uri", "plan.json"})
        config = json.loads((self.output / "server.json").read_text(encoding="utf-8"))
        self.assertEqual(config["transport"], "yandex")
        self.assertEqual(config["document_url"], DOCUMENT)
        self.assertNotEqual(config["encryption_key"], fixtures.config()["encryption_key"])
        self.assertRegex(config["encryption_key"], r"^[0-9a-f]{64}$")
        for secret in (DOCUMENT, config["encryption_key"], "openflux://"):
            self.assertNotIn(secret, self.stdout.getvalue() + self.stderr.getvalue())
        self.assertIn(str(self.output / "profile.uri"), self.stdout.getvalue())
        self.assert_original_unchanged()

    def test_prepare_volga_requires_explicit_transport_and_matching_confirmation(self):
        arguments = [arg for arg in self.cli_args() if arg != "--confirm-legacy-document"]
        arguments += ["--transport", "vyandex", "--confirm-volga-document"]
        self.assertEqual(wizard.main(arguments), 0)
        self.assertEqual(self.fake.commands, [])
        server = json.loads((self.output / "server.json").read_text(encoding="utf-8"))
        self.assertEqual(server["transport"], "vyandex")
        encoded = (self.output / "profile.uri").read_text().strip().split("#")[0].removeprefix("openflux://")
        profile = json.loads(base64.urlsafe_b64decode(encoded + "=" * (-len(encoded) % 4)))
        self.assertEqual(profile, {field: server[field] for field in profile})
        self.assertEqual(profile["transport"], "vyandex")
        self.assertRegex(profile["encryption_key"], r"^[0-9a-f]{64}$")
        wizard.read_plan(self.output)
        self.assert_original_unchanged()

    def test_mismatched_or_missing_confirmation_refused_before_key_or_directory(self):
        for transport, legacy, volga in (("vyandex", True, False), ("vyandex", False, False),
                                         ("yandex", False, True), (None, False, True),
                                         ("vyandex", True, True), ("yandex", True, True)):
            self.args.transport = transport
            self.args.confirm_legacy_document = legacy
            self.args.confirm_volga_document = volga
            with self.subTest(transport=transport, legacy=legacy, volga=volga), \
                    patch.object(wizard.sys.stdin, "isatty", return_value=False), \
                    patch.object(wizard.secrets, "token_hex") as random, self.assertRaises(manage.DeploymentError):
                wizard.prepare(self.args)
            random.assert_not_called()
            self.assertFalse(self.output.exists())
            self.assertEqual(self.fake.commands, [])

    def test_unknown_transport_refused_without_output_or_key(self):
        for transport in ("", "auto", "volga", [], {}, True):
            self.args.transport = transport
            with self.subTest(transport=transport), patch.object(wizard.secrets, "token_hex") as random:
                with self.assertRaises(manage.DeploymentError):
                    wizard.prepare(self.args)
                random.assert_not_called()
                self.assertFalse(self.output.exists())
        self.assertEqual(self.fake.commands, [])

    def test_volga_apply_and_rollback_are_isolated_from_legacy_instance(self):
        self.args.transport = "vyandex"
        self.args.confirm_legacy_document = False
        self.args.confirm_volga_document = True
        wizard.prepare(self.args)
        wizard.apply_prepared(self.output)
        target = manage.Manager(instance="phone2")
        self.assertEqual(manage.read_config(target.root / "server.json")["transport"], "vyandex")
        self.assertTrue(self.fake.containers[target.name]["State"]["Running"])
        self.assert_original_unchanged()
        wizard.rollback_prepared(self.output)
        self.assertFalse(target.root.exists())
        self.assert_original_unchanged()

    def test_uri_roundtrip_matches_existing_app_format_and_name_encoding(self):
        wizard.prepare(self.args)
        uri = (self.output / "profile.uri").read_text(encoding="utf-8").strip()
        encoded, name = uri[len("openflux://"):].split("#")
        profile = json.loads(base64.urlsafe_b64decode(encoded + "=" * (-len(encoded) % 4)))
        server = json.loads((self.output / "server.json").read_text(encoding="utf-8"))
        self.assertEqual(set(profile), {"version", "transport", "document_url", "encryption_key"})
        self.assertEqual(profile, {key: server[key] for key in profile})
        self.assertEqual(unquote(name), "OpenFlux phone2")
        self.assertIn("%20", name)
        self.assertNotIn("+", name)
        unicode_uri = wizard.profile_uri(server, "\u0422\u0435\u043b\u0435\u0444\u043e\u043d 2").decode().strip()
        self.assertEqual(unquote(unicode_uri.split("#")[1]), "\u0422\u0435\u043b\u0435\u0444\u043e\u043d 2")

    def test_fresh_32_byte_key_and_owner_are_independently_generated(self):
        with patch.object(wizard.secrets, "token_hex", side_effect=["ab" * 32, "cd" * 32]) as random:
            wizard.prepare(self.args)
        self.assertEqual([call.args for call in random.call_args_list], [(32,), (32,)])
        self.assertEqual(wizard.read_plan(self.output)["owner"], "cd" * 32)

    def test_existing_output_is_never_overwritten(self):
        wizard.prepare(self.args)
        before = {path.name: path.read_bytes() for path in self.output.iterdir()}
        with patch.object(wizard.secrets, "token_hex") as random, self.assertRaises(manage.DeploymentError):
            wizard.prepare(self.args)
        random.assert_not_called()
        self.assertEqual(before, {path.name: path.read_bytes() for path in self.output.iterdir()})
        self.assertEqual(self.fake.commands, [])

    def test_default_and_existing_instance_are_refused(self):
        for instance in ("default", "../phone2", "Phone2", "phone-2"):
            self.args.instance = instance
            with self.subTest(instance=instance), self.assertRaises(manage.DeploymentError):
                wizard.prepare(self.args)
        self.args.instance = "phone2"
        manage.Manager(instance="phone2").root.mkdir()
        with self.assertRaises(manage.DeploymentError):
            wizard.prepare(self.args)
        self.assertFalse(self.output.exists())
        self.assertEqual(self.fake.commands, [])

    def test_duplicate_document_is_rejected_before_key_or_directory(self):
        self.document_file.write_text(fixtures.config()["document_url"], encoding="utf-8")
        with patch.object(wizard.secrets, "token_hex") as random, self.assertRaises(manage.DeploymentError) as error:
            wizard.prepare(self.args)
        random.assert_not_called()
        self.assertNotIn(fixtures.config()["document_url"], str(error.exception))
        self.assertFalse(self.output.exists())
        self.assertEqual(self.fake.commands, [])

    def test_duplicate_check_ignores_default_https_port_and_query_order(self):
        first = "https://docs.yandex.ru/docs/view?a=one&b=two"
        other = "https://docs.yandex.ru:443/docs/view?b=two&a=one"
        self.assertEqual(wizard.document_identity(first), wizard.document_identity(other))
        self.assertEqual(wizard.document_identity(first), wizard.document_identity(other + "&utm_source=test&yclid=123"))

    def test_output_must_stay_outside_all_managed_installation_trees(self):
        for output in (self.original.root / "private-new-profile", manage.Manager(instance="phone2").root,
                       manage.Manager(instance="phone2").root / "private"):
            self.args.output = output
            with self.subTest(output=output), self.assertRaises(manage.DeploymentError):
                wizard.prepare(self.args)
        self.assertEqual(self.fake.commands, [])
        self.assert_original_unchanged()

    def test_invalid_document_and_missing_legacy_confirmation_are_private(self):
        for url in ("http://docs.yandex.ru/private-secret", "https://secret@example.org/doc",
                    "https://docs.yandex.ru/view#", "https://docs.yandex.ru/view\u00a0secret"):
            self.document_file.write_text(url, encoding="utf-8")
            with self.subTest(url=url), self.assertRaises(manage.DeploymentError) as error:
                wizard.prepare(self.args)
            self.assertNotIn(url, str(error.exception))
        self.document_file.write_text(DOCUMENT, encoding="utf-8")
        self.args.confirm_legacy_document = False
        with patch.object(wizard.sys.stdin, "isatty", return_value=False), self.assertRaises(manage.DeploymentError):
            wizard.prepare(self.args)

    def test_no_terminal_never_falls_back_to_visible_secret_input(self):
        self.args.document_file = None
        with patch.object(wizard.sys.stdin, "isatty", return_value=False), patch.object(wizard.getpass, "getpass") as hidden:
            with self.assertRaises(manage.DeploymentError):
                wizard.read_document(self.args)
            hidden.assert_not_called()
        with patch.object(wizard.sys.stdin, "isatty", return_value=True), \
                patch.object(wizard.getpass, "getpass", side_effect=wizard.getpass.GetPassWarning("secret")):
            with self.assertRaises(manage.DeploymentError) as error:
                wizard.read_document(self.args)
            self.assertNotIn("secret", str(error.exception))

    def test_oversized_document_and_public_artifact_hash_mismatch_refuse_prepare(self):
        self.document_file.write_bytes(b"x" * 32769)
        with self.assertRaises(manage.DeploymentError):
            wizard.prepare(self.args)
        self.document_file.write_text(DOCUMENT, encoding="utf-8")
        (self.original.root / "openflux").write_bytes(b"not-the-reviewed-binary")
        with self.assertRaises(manage.DeploymentError):
            wizard.prepare(self.args)
        self.assertEqual(self.fake.commands, [])

    def test_provenance_pin_and_immutable_runtime_are_required(self):
        path = self.original.root / "provenance.json"
        original = json.loads(path.read_text(encoding="utf-8"))
        for field, value in (("upstream_commit", "0" * 40), ("protocol", "plaintext"),
                             ("wrapper_version", "other"), ("runtime_image_id", "alpine:latest")):
            path.write_text(json.dumps(dict(original, **{field: value})), encoding="utf-8")
            with self.subTest(field=field), self.assertRaises(manage.DeploymentError):
                wizard.prepare(self.args)
        self.assertEqual(self.fake.commands, [])

    def test_apply_and_owned_rollback_preserve_running_default(self):
        wizard.prepare(self.args)
        plan = wizard.read_plan(self.output)
        wizard.apply_prepared(self.output)
        target = manage.Manager(instance="phone2")
        self.assertEqual(target.load_state()["owner"], plan["owner"])
        self.assertTrue(self.fake.containers[target.name]["State"]["Running"])
        self.assert_original_unchanged()
        wizard.rollback_prepared(self.output)
        self.assertFalse(target.root.exists())
        self.assertNotIn(target.name, self.fake.containers)
        self.assertNotIn(target.network, self.fake.networks)
        self.assertTrue((self.output / "profile.uri").exists())
        self.assert_original_unchanged()

    def test_foreign_owner_rollback_refuses_before_any_docker_command(self):
        wizard.prepare(self.args)
        wizard.apply_prepared(self.output)
        target = manage.Manager(instance="phone2")
        state = target.load_state()
        state["owner"] = "ee" * 32
        target.write_state(state)
        self.fake.commands.clear()
        with self.assertRaises(manage.DeploymentError):
            wizard.rollback_prepared(self.output)
        self.assertEqual(self.fake.commands, [])
        self.assertTrue(target.root.exists())
        self.assert_original_unchanged()

    def test_repeat_apply_never_adopts_or_starts_existing_target(self):
        wizard.prepare(self.args)
        wizard.apply_prepared(self.output)
        self.fake.commands.clear()
        with self.assertRaises(manage.DeploymentError):
            wizard.apply_prepared(self.output)
        self.assertEqual(self.fake.commands, [])

    def test_partial_install_owner_is_bound_and_can_be_rolled_back(self):
        wizard.prepare(self.args)
        self.fake.fail_build = True
        with self.assertRaises(manage.DeploymentError):
            wizard.apply_prepared(self.output)
        target = manage.Manager(instance="phone2")
        self.assertEqual(target.load_state()["owner"], wizard.read_plan(self.output)["owner"])
        self.assertNotIn(target.name, self.fake.containers)
        wizard.rollback_prepared(self.output)
        self.assertFalse(target.root.exists())
        self.assert_original_unchanged()

    def test_route_overlap_blocks_all_new_docker_mutations(self):
        wizard.prepare(self.args)
        self.fake.routes.append({"dst": "172.30.252.0/24"})
        with self.assertRaises(manage.DeploymentError):
            wizard.apply_prepared(self.output)
        self.assertFalse(manage.Manager(instance="phone2").root.exists())
        self.assertFalse(any(command[3] == "build" or command[4] in ("create", "start", "stop", "rm", "tag")
                             for command in self.fake.commands if command[0] == "docker"))
        self.assert_original_unchanged()

    def test_private_file_change_is_rejected_before_apply(self):
        wizard.prepare(self.args)
        (self.output / "server.json").write_text("{}", encoding="utf-8")
        with self.assertRaises(manage.DeploymentError):
            wizard.apply_prepared(self.output)
        self.assertEqual(self.fake.commands, [])

    def test_rollback_requires_explicit_apply_and_never_accepts_new_profile_options(self):
        wizard.prepare(self.args)
        for args in (["--prepared", str(self.output), "--rollback"],
                     ["--prepared", str(self.output), "--apply", "--instance", "default"],
                     ["--prepared", str(self.output), "--apply", "--transport", "yandex"],
                     ["--prepared", str(self.output), "--apply", "--transport", "vyandex"],
                     ["--prepared", str(self.output), "--confirm-volga-document"]):
            self.assertEqual(wizard.main(args), 1)
        self.assertEqual(self.fake.commands, [])

    def test_private_writes_are_exclusive_and_create_mode_0600(self):
        path = self.private / "new-secret.txt"
        original_open = os.open
        with patch.object(os, "open", wraps=original_open) as opened:
            wizard.write_private(path, b"public test bytes")
        self.assertEqual(opened.call_args.args[1], os.O_WRONLY | os.O_CREAT | os.O_EXCL)
        self.assertEqual(opened.call_args.args[2], 0o600)
        with self.assertRaises(FileExistsError):
            wizard.write_private(path, b"replacement")
        self.assertEqual(path.read_bytes(), b"public test bytes")


class ProfileInputSecurityTests(unittest.TestCase):
    def test_cli_errors_do_not_echo_misplaced_private_arguments(self):
        output = io.StringIO()
        with contextlib.redirect_stderr(output), patch.object(wizard, "require_host") as host:
            self.assertEqual(wizard.main(["--document-url", "https://docs.yandex.ru/PRIVATE_SECRET"]), 1)
            self.assertEqual(wizard.main(["--private-key=PRIVATE_SECRET"]), 1)
            host.assert_not_called()
        self.assertNotIn("PRIVATE_SECRET", output.getvalue())

    def test_manager_load_compiles_checked_source_without_loading_bytecode_cache(self):
        with patch.object(wizard.platform, "system", return_value="Linux"), \
                patch.object(wizard.platform, "machine", return_value="x86_64"), \
                patch.object(os, "geteuid", return_value=0, create=True), \
                patch.object(wizard, "require_secure_tool_file"), patch.object(wizard, "manage", manage), \
                patch.object(importlib.machinery.SourceFileLoader, "exec_module") as cached_loader:
            wizard.require_host()
            self.assertEqual(wizard.manage.UPSTREAM, manage.UPSTREAM)
            cached_loader.assert_not_called()

    def test_unsafe_helper_is_rejected_before_dynamic_import(self):
        with patch.object(wizard.platform, "system", return_value="Linux"), \
                patch.object(wizard.platform, "machine", return_value="x86_64"), \
                patch.object(os, "geteuid", return_value=0, create=True), \
                patch.object(wizard, "require_secure_tool_file", side_effect=manage.DeploymentError("unsafe file")), \
                patch.object(wizard.importlib.util, "spec_from_file_location") as loader:
            with self.assertRaises(manage.DeploymentError):
                wizard.require_host()
            loader.assert_not_called()

    def test_private_file_rejects_links_permissive_mode_and_wrong_owner(self):
        for changes in ({"st_mode": stat.S_IFLNK | 0o600}, {"st_mode": stat.S_IFREG | 0o644},
                        {"st_nlink": 2}, {"st_uid": 1000}):
            fields = dict(st_mode=stat.S_IFREG | 0o600, st_nlink=1, st_uid=0, st_size=32)
            fields.update(changes)
            with self.subTest(changes=changes), patch.object(manage, "secure_directory"), \
                    patch.object(Path, "lstat", return_value=SimpleNamespace(**fields)), \
                    self.assertRaises(manage.DeploymentError):
                wizard.read_bounded(Path("document.txt"), 32768, private=True)

    def test_private_directory_requires_0700(self):
        with patch.object(manage, "secure_directory"), patch.object(Path, "lstat", return_value=SimpleNamespace(st_mode=0o755)):
            with self.assertRaises(manage.DeploymentError):
                wizard.private_directory(Path("/root/prepared"))

    def test_invalid_owner_is_rejected_before_manager_preflight(self):
        for owner in ("", "short", "x" * 64, True, "a" * 63):
            with self.subTest(owner=owner), patch.object(manage.Manager, "preflight") as preflight:
                with self.assertRaises(manage.DeploymentError):
                    manage.Manager().install(SimpleNamespace(), owner=owner)
                preflight.assert_not_called()


if __name__ == "__main__":
    unittest.main()
