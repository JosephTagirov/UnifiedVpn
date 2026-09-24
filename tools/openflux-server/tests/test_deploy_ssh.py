import base64
import contextlib
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
from types import SimpleNamespace
import stat
import struct
import subprocess
import tempfile
import unittest
from unittest.mock import Mock, patch


SPEC = importlib.util.spec_from_file_location("test_openflux_deploy_ssh", Path(__file__).resolve().parents[1] / "deploy_ssh.py")
deploy = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(deploy)
remote = deploy.remote
STAGE = "ab" * 16
IMAGE = "sha256:" + "cd" * 32
ALPINE = "docker.io/library/alpine@sha256:" + "ef" * 32
BLOB = b"\x00\x00\x00\x0bssh-ed25519\x00\x00\x00\x20" + bytes(range(32))
KEY = base64.b64encode(BLOB).decode("ascii")
FINGERPRINT = "SHA256:" + base64.b64encode(hashlib.sha256(BLOB).digest()).decode("ascii").rstrip("=")
DOCUMENT = "https://docs.yandex.ru/docs/view?url=PRIVATE_DOCUMENT_NEVER_PRINT"
KEY_SECRET = "12" * 32


def access():
    return {"host": "192.0.2.1", "port": 22, "user": "root", "host_key_sha256": FINGERPRINT}


def fake_contents():
    return {name: ("FAKE_" + name).encode() for name in remote.FILES}


def manifest_for(contents=None):
    contents = contents or fake_contents()
    return {"schema": remote.SCHEMA, "upstream": remote.UPSTREAM,
            "files": {name: {"size": len(data), "sha256": remote.hash_bytes(data)} for name, data in contents.items()}}


def request(phase, **changes):
    manifest = manifest_for()
    value = {"schema": remote.SCHEMA, "phase": phase, "stage": STAGE,
             "manifest_sha256": remote.hash_bytes(remote.json_bytes(manifest)), "apply": phase in remote.MUTATING}
    if phase == "upload":
        value["manifest"] = manifest
    if phase in ("preflight", "install"):
        value.update(runtime_image=IMAGE, subnet="172.30.251.0/28")
    if phase in ("runtime-pull", "runtime-build"):
        value["alpine_image"] = ALPINE
        value["allow_network" if phase == "runtime-pull" else "allow_build_network"] = True
    value.update(changes)
    return value


def elf():
    header = bytearray(120)
    header[:6] = b"\x7fELF\x02\x01"
    struct.pack_into("<H", header, 18, 62)
    struct.pack_into("<Q", header, 32, 64)
    struct.pack_into("<HH", header, 54, 56, 1)
    struct.pack_into("<I", header, 64, 1)
    return bytes(header)


class LocalDeploymentTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.access_path = self.root / "access.json"
        self.access_path.write_text(json.dumps(access()), encoding="utf-8")
        os.chmod(self.access_path, 0o600)
        self.identity = self.root / "openflux-server-access"
        self.identity.write_bytes(b"FAKE_PRIVATE_SSH_KEY")
        os.chmod(self.identity, 0o600)

    def args(self, phase, *arguments):
        return deploy.parser().parse_args([phase, "--access-file", str(self.access_path),
                                          "--receipt-file", str(self.root / "receipt.json"), *arguments])

    def write_receipt(self, overrides=None):
        value = {"schema": deploy.RECEIPT_SCHEMA, "stage": STAGE,
                 "manifest_sha256": request("check")["manifest_sha256"], "target_sha256": deploy.target_hash(access())}
        value.update(overrides or {})
        path = self.root / "receipt.json"
        path.write_bytes(remote.json_bytes(value))
        os.chmod(path, 0o600)
        return path

    def bundle(self):
        bundle = self.root / "artifacts"
        bundle.mkdir()
        licenses = bundle / "licenses"
        licenses.mkdir()
        for name in ("LICENSE", "NOTICE", "COPYRIGHT"):
            (licenses / name).write_bytes(b"PUBLIC_LICENSE")
        (bundle / "openflux-linux-amd64").write_bytes(elf())
        (bundle / "openflux-source.tar.gz").write_bytes(b"\x1f\x8bFAKE_SOURCE")
        artifact = {"schema": 1, "upstream": remote.UPSTREAM, "protocol": remote.PROTOCOL, "version_text": remote.VERSION,
                    "files": {name: remote.hash_bytes((bundle / name).read_bytes())
                              for name in ("openflux-linux-amd64", "openflux-source.tar.gz")}}
        (bundle / "manifest.json").write_bytes(remote.json_bytes(artifact))
        (bundle / "IGNORE_PRIVATE_EXTRA").write_bytes(b"NEVER_UPLOAD_EXTRA")
        config = self.root / "server.json"
        config.write_bytes(remote.json_bytes({"version": 1, "mode": "server", "transport": "yandex",
                                             "document_url": DOCUMENT, "encryption_key": KEY_SECRET,
                                             "handshake_timeout_seconds": 60}))
        os.chmod(config, 0o600)
        return bundle, config

    def test_mutating_phases_require_consent_before_inputs_or_ssh(self):
        values = (("upload", ["--bundle-dir", "MISSING", "--server-config", "PRIVATE"]),
                  ("install", ["--runtime-image", IMAGE, "--subnet", "172.30.251.0/28"]),
                  ("runtime-pull", ["--alpine-image", ALPINE, "--allow-network"]),
                  ("runtime-build", ["--alpine-image", ALPINE, "--allow-build-network"]), ("run", []), ("stop", []))
        with patch.object(deploy.ssh, "native") as native:
            for phase, arguments in values:
                with self.subTest(phase=phase), self.assertRaisesRegex(deploy.DeploymentError, "apply_required"):
                    deploy.deploy(self.args(phase, *arguments))
            native.assert_not_called()
        self.assertFalse((self.root / "receipt.json").exists())

    def test_network_consent_is_separate_from_apply(self):
        for phase in ("runtime-pull", "runtime-build"):
            with self.subTest(phase=phase), self.assertRaisesRegex(deploy.DeploymentError, "network_consent"):
                deploy.deploy(self.args(phase, "--apply", "--alpine-image", ALPINE))

    def test_bundle_allowlist_and_artifact_hashes(self):
        bundle, config = self.bundle()
        manifest, contents = deploy.collect_bundle(bundle, config)
        self.assertEqual(set(contents), set(remote.FILES))
        self.assertNotIn(b"NEVER_UPLOAD_EXTRA", b"".join(contents.values()))
        self.assertEqual(contents["private/server.json"], config.read_bytes())
        self.assertNotIn(DOCUMENT, json.dumps(manifest))
        self.assertNotIn(KEY_SECRET, json.dumps(manifest))
        (bundle / "openflux-linux-amd64").write_bytes(elf() + b"ALTERED")
        with self.assertRaisesRegex(deploy.DeploymentError, "artifact_hash_mismatch"):
            deploy.collect_bundle(bundle, config)

    def test_new_packages_require_exact_wrapper_four_provenance(self):
        self.assertTrue(remote.VERSION.startswith("unified-openflux 5 "))
        self.assertEqual(deploy.manager.VERSION, remote.VERSION)
        bundle, config = self.bundle()
        manifest_path = bundle / "manifest.json"
        manifest = json.loads(manifest_path.read_bytes())
        for version in (1, 2, 3):
            with self.subTest(version=version):
                name, current, metadata = remote.VERSION.split(" ", 2)
                self.assertNotEqual(current, str(version))
                manifest["version_text"] = " ".join((name, str(version), metadata))
                manifest_path.write_bytes(remote.json_bytes(manifest))
                with self.assertRaises(deploy.DeploymentError):
                    deploy.collect_bundle(bundle, config)

    def test_receipt_is_private_exclusive_and_target_bound(self):
        path = self.write_receipt()
        self.assertEqual(deploy.read_receipt(path, access())["stage"], STAGE)
        with self.assertRaisesRegex(deploy.DeploymentError, "target_mismatch"):
            deploy.read_receipt(path, dict(access(), host="192.0.2.2"))
        with self.assertRaisesRegex(deploy.DeploymentError, "already_exists"):
            deploy.private_output(path, self.access_path, new=True)
        with self.assertRaises(deploy.DeploymentError):
            deploy.private_output(self.root.parent / "outside-private-receipt", self.access_path, new=True)

    def test_upload_refuses_existing_receipt_before_network(self):
        bundle, config = self.bundle()
        self.write_receipt()
        with patch.object(deploy.ssh, "native") as native, self.assertRaisesRegex(deploy.DeploymentError, "already_exists"):
            deploy.deploy(self.args("upload", "--apply", "--bundle-dir", str(bundle), "--server-config", str(config)))
        native.assert_not_called()

    def test_named_upload_binds_receipt_manifest_and_all_later_requests(self):
        bundle, config = self.bundle()
        response = {"schema": remote.SCHEMA, "phase": "upload", "status": "completed",
                    "files_uploaded": len(remote.FILES), "encrypted_peer_verified": False}
        with patch.object(deploy, "execute_ssh", return_value=response) as execute:
            deploy.deploy(self.args("upload", "--apply", "--instance", "phone2",
                                   "--bundle-dir", str(bundle), "--server-config", str(config)))
        sent = execute.call_args.args[3]
        self.assertEqual(sent["instance"], "phone2")
        self.assertEqual(sent["manifest"]["instance"], "phone2")
        receipt = deploy.read_receipt(self.root / "receipt.json", access())
        self.assertEqual(receipt["instance"], "phone2")
        for phase in ("run", "check", "stop"):
            later = deploy.request_for(self.args(phase, "--apply"), receipt)
            self.assertEqual(later["instance"], "phone2")
            self.assertEqual(later["manifest_sha256"], sent["manifest_sha256"])

    def test_invalid_instance_is_rejected_before_ssh_or_receipt_write(self):
        with patch.object(deploy.ssh, "native") as native:
            for value in ("", "../default", "Default", "a" * 17, "phone-2", "a;id", "0phone"):
                with self.subTest(value=value), self.assertRaises(remote.DeploymentError):
                    deploy.deploy(self.args("upload", "--apply", "--instance", value,
                                           "--bundle-dir", "MISSING", "--server-config", "PRIVATE"))
            native.assert_not_called()
            self.assertFalse((self.root / "receipt.json").exists())

    def test_reuse_upload_sends_only_small_files_and_does_not_persist_option(self):
        bundle, config = self.bundle()
        response = {"schema": remote.SCHEMA, "phase": "upload", "status": "completed",
                    "files_uploaded": len(remote.FILES), "encrypted_peer_verified": False}
        with patch.object(deploy, "execute_ssh", return_value=response) as execute:
            deploy.deploy(self.args("upload", "--apply", "--instance", "phone2", "--reuse-installed-artifacts",
                                    "--bundle-dir", str(bundle), "--server-config", str(config)))
        sent, contents = execute.call_args.args[3:5]
        self.assertIs(sent["reuse_installed_artifacts"], True)
        framed = io.BytesIO(deploy.framed_payload(sent, contents))
        framed.read(int(framed.readline()))
        self.assertEqual(remote.read_request(framed), sent)
        self.assertEqual(framed.read(), b"".join(contents[name] for name in remote.FILES if name not in remote.REUSABLE_ARTIFACTS))
        self.assertEqual(set(contents), set(remote.FILES))
        receipt = deploy.read_receipt(self.root / "receipt.json", access())
        self.assertNotIn("reuse_installed_artifacts", receipt)
        for phase in ("check", "run", "stop"):
            self.assertNotIn("reuse_installed_artifacts", deploy.request_for(self.args(phase, "--apply"), receipt))

    def test_reuse_is_opt_in_and_does_not_change_full_upload_framing(self):
        contents = fake_contents()
        framed = io.BytesIO(deploy.framed_payload(request("upload"), contents))
        framed.read(int(framed.readline()))
        parsed = remote.read_request(framed)
        self.assertNotIn("reuse_installed_artifacts", parsed)
        self.assertEqual(framed.read(), b"".join(contents.values()))

    def test_reuse_default_instance_is_refused_before_receipt_or_ssh(self):
        bundle, config = self.bundle()
        with patch.object(deploy, "execute_ssh") as execute, self.assertRaisesRegex(remote.DeploymentError, "invalid_request"):
            deploy.deploy(self.args("upload", "--apply", "--reuse-installed-artifacts",
                                    "--bundle-dir", str(bundle), "--server-config", str(config)))
        execute.assert_not_called()
        self.assertFalse((self.root / "receipt.json").exists())

    def test_reuse_still_rejects_changed_local_artifacts(self):
        bundle, config = self.bundle()
        (bundle / "openflux-source.tar.gz").write_bytes(b"\x1f\x8bCHANGED_SOURCE")
        with patch.object(deploy, "execute_ssh") as execute, self.assertRaisesRegex(deploy.DeploymentError, "artifact_hash_mismatch"):
            deploy.deploy(self.args("upload", "--apply", "--instance", "phone2", "--reuse-installed-artifacts",
                                    "--bundle-dir", str(bundle), "--server-config", str(config)))
        execute.assert_not_called()
        self.assertFalse((self.root / "receipt.json").exists())

    def test_reuse_cli_option_is_not_accepted_on_later_phases(self):
        for phase in ("check", "run", "stop"):
            with self.subTest(phase=phase), self.assertRaisesRegex(deploy.DeploymentError, "invalid_arguments"):
                self.args(phase, "--reuse-installed-artifacts")

    def test_non_root_access_refused_without_ssh(self):
        self.access_path.write_text(json.dumps(dict(access(), user="testuser")), encoding="utf-8")
        with patch.object(deploy.ssh, "native") as native, self.assertRaisesRegex(deploy.DeploymentError, "root_access"):
            deploy.deploy(self.args("check"))
        native.assert_not_called()

    def test_host_key_mismatch_prevents_authentication(self):
        scan = subprocess.CompletedProcess([], 0, ("host ssh-ed25519 " + KEY).encode(), b"PRIVATE_HOST_BANNER")
        with patch.object(deploy.ssh, "executable", side_effect=lambda value: Path(value)), patch.object(deploy.ssh, "native", return_value=scan) as native:
            with self.assertRaisesRegex(deploy.DeploymentError, "host_key_not_verified"):
                deploy.execute_ssh(self.access_path, dict(access(), host_key_sha256="SHA256:" + "a" * 43), access(), request("check"))
            self.assertEqual(native.call_count, 1)

    def test_verified_ssh_uses_fixed_bootstrap_and_stdin_for_secrets(self):
        scan = subprocess.CompletedProcess([], 0, ("host ssh-ed25519 " + KEY).encode(), b"PRIVATE_BANNER")
        response = {"schema": remote.SCHEMA, "phase": "upload", "status": "completed", "files_uploaded": len(remote.FILES), "encrypted_peer_verified": False}
        completed = subprocess.CompletedProcess([], 0, remote.json_bytes(response), b"PRIVATE_STDERR")
        contents = fake_contents()
        contents["private/server.json"] = (DOCUMENT + KEY_SECRET).encode()
        value = request("upload")
        value["manifest"] = manifest_for(contents)
        value["manifest_sha256"] = remote.hash_bytes(remote.json_bytes(value["manifest"]))
        with patch.object(deploy.ssh, "executable", side_effect=lambda item: Path(item)), patch.object(deploy.ssh, "native", side_effect=[scan, completed]) as native:
            result = deploy.execute_ssh(self.access_path, access(), access(), value, contents)
        arguments = native.call_args.args[0]
        self.assertIn("StrictHostKeyChecking=yes", arguments)
        self.assertIn("IdentityAgent=none", arguments)
        self.assertIn("PasswordAuthentication=no", arguments)
        self.assertIn("ClearAllForwardings=yes", arguments)
        self.assertTrue(arguments[-1].startswith("python3 -c "))
        self.assertNotIn(DOCUMENT, " ".join(arguments))
        self.assertNotIn(KEY_SECRET, " ".join(arguments))
        self.assertIn(DOCUMENT.encode(), native.call_args.kwargs["stdin"])
        self.assertNotIn("PRIVATE", json.dumps(result))

    def test_inspector_timeout_text_is_not_reused_after_mutation(self):
        scan = subprocess.CompletedProcess([], 0, ("host ssh-ed25519 " + KEY).encode(), b"")
        with patch.object(deploy.ssh, "executable", side_effect=lambda item: Path(item)), patch.object(deploy.ssh, "native", side_effect=[scan, deploy.ssh.InspectionError("PRIVATE no changes requested")]):
            with self.assertRaises(deploy.DeploymentError) as caught:
                deploy.execute_ssh(self.access_path, access(), access(), request("run"))
        self.assertIn("remote_state_unknown", str(caught.exception))
        self.assertNotIn("PRIVATE", str(caught.exception))
        self.assertNotIn("no changes", str(caught.exception))

    def test_runtime_image_id_only_in_private_report(self):
        self.write_receipt()
        response = {"schema": remote.SCHEMA, "phase": "runtime-build", "status": "completed", "runtime_image_id": IMAGE, "encrypted_peer_verified": False}
        with patch.object(deploy, "execute_ssh", return_value=response):
            result = deploy.deploy(self.args("runtime-build", "--apply", "--alpine-image", ALPINE, "--allow-build-network"))
        self.assertNotIn(IMAGE, json.dumps(result))
        self.assertTrue(result["runtime_image_recorded_privately"])
        self.assertEqual(json.loads((self.root / result["private_report"]).read_bytes())["runtime_image_id"], IMAGE)

    def test_remote_output_fields_cannot_smuggle_private_text(self):
        good = {"schema": remote.SCHEMA, "phase": "check", "status": "completed", "encrypted_peer_verified": False}
        for changes in ({"error": DOCUMENT}, {"configuration_only": DOCUMENT}, {"title": DOCUMENT}, {"encrypted_peer_verified": True}, {"phase": DOCUMENT}):
            with self.subTest(fields=list(changes)), self.assertRaises(deploy.DeploymentError) as caught:
                deploy.safe_response(remote.json_bytes(dict(good, **changes)), "check")
            self.assertNotIn(DOCUMENT, str(caught.exception))

    def test_cli_errors_never_echo_secret_arguments(self):
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            code = deploy.main(["upload", "--server-config", DOCUMENT])
        self.assertEqual(code, 1)
        self.assertNotIn(DOCUMENT, output.getvalue())


class RemoteDeploymentTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        staged = patch.object(remote, "STAGING_ROOT", self.root)
        staged.start()
        self.addCleanup(staged.stop)
        permissions = patch.object(remote.os, "fchmod", create=True)
        permissions.start()
        self.addCleanup(permissions.stop)

    def reuse_fixture(self):
        original = self.root / "original-installation"
        original.mkdir()
        contents = fake_contents()
        for name, filename in remote.REUSABLE_ARTIFACTS.items():
            (original / filename).write_bytes(contents[name])
        (original / "server.json").write_bytes(b"DO_NOT_READ_ORIGINAL_SERVER_CONFIG")
        (original / "state.json").write_bytes(b"DO_NOT_READ_ORIGINAL_STATE")
        installed = patch.object(remote, "INSTALL_ROOT", original)
        installed.start()
        self.addCleanup(installed.stop)
        manifest = dict(manifest_for(contents), instance="phone2")
        value = request("upload", instance="phone2", reuse_installed_artifacts=True,
                        manifest=manifest, manifest_sha256=remote.hash_bytes(remote.json_bytes(manifest)))
        payload = b"".join(contents[name] for name in remote.FILES if name not in remote.REUSABLE_ARTIFACTS)
        return original, contents, value, payload

    def test_reuse_has_fixed_sources_and_copies_without_modifying_original(self):
        self.assertEqual(remote.REUSABLE_ARTIFACTS, {"bundle/openflux-linux-amd64": "openflux",
                                                   "bundle/openflux-source.tar.gz": "source.tar.gz"})
        original, contents, value, payload = self.reuse_fixture()
        before = {path.name: (path.read_bytes(), path.stat().st_mtime_ns, path.stat().st_ino) for path in original.iterdir()}
        with patch.object(remote, "secure_file", side_effect=lambda path, maximum, private=False: path.stat()) as checked, \
                patch.object(remote.os, "open", wraps=os.open) as opened:
            result = remote.upload(value, io.BytesIO(payload))
        self.assertEqual(result["files_uploaded"], len(remote.FILES))
        self.assertEqual([call.args[0] for call in checked.call_args_list],
                         [original / filename for filename in remote.REUSABLE_ARTIFACTS.values()])
        original_opens = [call for call in opened.call_args_list if Path(call.args[0]).parent == original]
        self.assertEqual(len(original_opens), 2)
        for call in original_opens:
            self.assertEqual(call.args[1], os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0))
        after = {path.name: (path.read_bytes(), path.stat().st_mtime_ns, path.stat().st_ino) for path in original.iterdir()}
        self.assertEqual(after, before)
        stage = remote.stage_path(STAGE)
        for name, data in contents.items():
            self.assertEqual((stage / name).read_bytes(), data)
        with patch.object(remote, "secure_directory"), \
                patch.object(remote, "secure_file", side_effect=lambda path, maximum, private=False: path.stat()):
            remote.verify_stage(request("check", instance="phone2", manifest_sha256=value["manifest_sha256"]))

    def test_reuse_request_requires_named_instance_exact_true_and_upload_phase(self):
        manifest = dict(manifest_for(), instance="phone2")
        valid = request("upload", instance="phone2", manifest=manifest,
                        manifest_sha256=remote.hash_bytes(remote.json_bytes(manifest)), reuse_installed_artifacts=True)
        remote.validate_request(valid)
        for flag in (False, None, 0, 1, "true", [], {}):
            with self.subTest(flag=repr(flag)), self.assertRaisesRegex(remote.DeploymentError, "invalid_request"):
                remote.upload(dict(valid, reuse_installed_artifacts=flag), io.BytesIO())
        with self.assertRaisesRegex(remote.DeploymentError, "invalid_request"):
            remote.validate_request(request("upload", reuse_installed_artifacts=True))
        for phase in remote.PHASES:
            if phase != "upload":
                with self.subTest(phase=phase), self.assertRaisesRegex(remote.DeploymentError, "invalid_request"):
                    remote.validate_request(request(phase, instance="phone2", reuse_installed_artifacts=True))
        for extra in ({"reuse_source": "/etc"}, {"artifact_paths": ["/etc/passwd"]}):
            with self.assertRaisesRegex(remote.DeploymentError, "invalid_request"):
                remote.validate_request(dict(valid, **extra))
        self.assertFalse(remote.stage_path(STAGE).exists())

    def test_reuse_refuses_missing_short_larger_or_corrupt_sources(self):
        original, contents, value, payload = self.reuse_fixture()
        binary = original / "openflux"
        expected = contents["bundle/openflux-linux-amd64"]
        for index, data in enumerate((None, expected[:-1], expected + b"EXTRA", b"X" + expected[1:])):
            current = dict(value, stage=f"{index + 1:032x}")
            if data is None:
                binary.unlink()
            else:
                binary.write_bytes(data)
            with self.subTest(index=index), \
                    patch.object(remote, "secure_file", side_effect=lambda path, maximum, private=False: path.stat()), \
                    self.assertRaises((OSError, remote.DeploymentError)):
                remote.upload(current, io.BytesIO(payload))
            self.assertFalse((remote.stage_path(current["stage"]) / "upload-manifest.json").exists())
            if data is not None:
                self.assertEqual(binary.read_bytes(), data)

    def test_reuse_refuses_unsafe_source_metadata_before_opening(self):
        entry = manifest_for()["files"]["bundle/openflux-linux-amd64"]
        for mode, owner, links in ((stat.S_IFLNK | 0o600, 0, 1), (stat.S_IFREG | 0o666, 0, 1),
                                   (stat.S_IFREG | 0o644, 1000, 1), (stat.S_IFREG | 0o644, 0, 2)):
            info = SimpleNamespace(st_mode=mode, st_uid=owner, st_nlink=links, st_size=entry["size"])
            with self.subTest(mode=mode, owner=owner, links=links), patch.object(remote, "secure_directory"), \
                    patch.object(Path, "lstat", return_value=info), patch.object(remote.os, "open") as opened:
                with self.assertRaisesRegex(remote.DeploymentError, "unsafe_path"):
                    remote.copy_installed_artifact("bundle/openflux-linux-amd64", self.root / "output", entry)
                opened.assert_not_called()
        with patch.object(remote.os, "open") as opened, self.assertRaisesRegex(remote.DeploymentError, "invalid_request"):
            remote.copy_installed_artifact("private/server.json", self.root / "output", entry)
        opened.assert_not_called()

    def test_reuse_refuses_source_replaced_between_check_and_open(self):
        original, contents, value, payload = self.reuse_fixture()
        details = (original / "openflux").stat()
        changed = SimpleNamespace(**{name: getattr(details, name) for name in
                                     ("st_dev", "st_ino", "st_mode", "st_nlink", "st_uid", "st_size")})
        changed.st_ino += 1
        with patch.object(remote, "secure_file", return_value=changed), self.assertRaisesRegex(remote.DeploymentError, "unsafe_path"):
            remote.upload(value, io.BytesIO(payload))
        self.assertFalse((remote.stage_path(STAGE) / "upload-manifest.json").exists())

    def test_reuse_refuses_trailing_transferred_data_and_does_not_consume_blobs(self):
        original, contents, value, payload = self.reuse_fixture()
        for index, wire in enumerate((payload + b"EXTRA", b"".join(contents.values()))):
            current = dict(value, stage=f"{index + 1:032x}")
            with self.subTest(index=index), patch.object(remote, "secure_file", side_effect=lambda path, maximum, private=False: path.stat()), \
                    self.assertRaises(remote.DeploymentError):
                remote.upload(current, io.BytesIO(wire))
            self.assertFalse((remote.stage_path(current["stage"]) / "upload-manifest.json").exists())

    def test_reused_artifacts_are_still_hashed_by_full_stage_verification(self):
        original, contents, value, payload = self.reuse_fixture()
        with patch.object(remote, "secure_file", side_effect=lambda path, maximum, private=False: path.stat()):
            remote.upload(value, io.BytesIO(payload))
        changed = remote.stage_path(STAGE) / "bundle/openflux-source.tar.gz"
        changed.write_bytes(b"X" + contents["bundle/openflux-source.tar.gz"][1:])
        with patch.object(remote, "secure_directory"), \
                patch.object(remote, "secure_file", side_effect=lambda path, maximum, private=False: path.stat()), \
                self.assertRaisesRegex(remote.DeploymentError, "hash_mismatch"):
            remote.verify_stage(request("check", instance="phone2", manifest_sha256=value["manifest_sha256"]))

    def test_bounded_copy_requires_exact_reused_content(self):
        expected = b"EXPECTED_PUBLIC_ARTIFACT"
        entry = {"size": len(expected), "sha256": remote.hash_bytes(expected)}
        for index, data in enumerate((expected[:-1], expected + b"EXTRA", b"X" + expected[1:])):
            with self.subTest(index=index), self.assertRaises(remote.DeploymentError):
                remote.copy_payload_file(io.BytesIO(data), self.root / ("output-" + str(index)), entry, require_eof=True)

    def test_stage_paths_never_accept_arbitrary_or_traversal_targets(self):
        self.assertEqual(remote.stage_path(STAGE).parent, self.root)
        for value in ("../etc", "/root", "a" * 31, "a" * 33, "A" * 32, STAGE + ";PRIVATE", None, []):
            with self.subTest(value=str(value)[:12]), self.assertRaises(remote.DeploymentError):
                remote.stage_path(value)

    def test_directory_and_file_permissions_reject_links_or_other_owners(self):
        for mode, owner in ((stat.S_IFDIR | 0o777, 0), (stat.S_IFDIR | 0o700, 1000), (stat.S_IFLNK | 0o700, 0)):
            with patch.object(Path, "lstat", return_value=SimpleNamespace(st_mode=mode, st_uid=owner)):
                with self.assertRaisesRegex(remote.DeploymentError, "unsafe_path"):
                    remote.secure_directory(self.root)
        for mode, links in ((stat.S_IFREG | 0o644, 2), (stat.S_IFLNK | 0o600, 1), (stat.S_IFREG | 0o644, 1)):
            info = SimpleNamespace(st_mode=mode, st_uid=0, st_nlink=links, st_size=1)
            with patch.object(remote, "secure_directory"), patch.object(Path, "lstat", return_value=info):
                with self.assertRaisesRegex(remote.DeploymentError, "unsafe_path"):
                    remote.secure_file(self.root / "secret", 16, private=True)

    def test_manifests_reject_unknown_names_and_oversized_files(self):
        for path in ("../server.json", "bundle/private-key", "private/../secrets", "/etc/passwd"):
            manifest = manifest_for()
            manifest["files"][path] = {"size": 1, "sha256": "a" * 64}
            with self.assertRaises(remote.DeploymentError):
                remote.validate_manifest(manifest)
        for size in (0, True, remote.FILES["private/server.json"] + 1):
            manifest = manifest_for()
            manifest["files"]["private/server.json"]["size"] = size
            with self.assertRaises(remote.DeploymentError):
                remote.validate_manifest(manifest)

    def test_all_remote_mutations_require_explicit_apply(self):
        for phase in remote.MUTATING:
            with self.subTest(phase=phase), self.assertRaisesRegex(remote.DeploymentError, "apply_required"):
                remote.validate_request(request(phase, apply=False))

    def test_runtime_pull_has_strict_repository_digest_and_network_consent(self):
        remote.validate_request(request("runtime-pull"))
        for value in ("alpine:latest", "alpine@sha256:" + "ab" * 32, "example.invalid/alpine@sha256:" + "ab" * 32,
                      ALPINE + ";PRIVATE", "docker.io/library/alpine@sha256:PRIVATE"):
            with self.subTest(value=value[:30]), self.assertRaises(remote.DeploymentError):
                remote.validate_request(request("runtime-pull", alpine_image=value))
        with self.assertRaisesRegex(remote.DeploymentError, "apply_required"):
            remote.validate_request(request("runtime-pull", allow_network=False))
        with self.assertRaisesRegex(remote.DeploymentError, "apply_required"):
            remote.validate_request(request("runtime-build", allow_build_network=False))

    def test_upload_is_exclusive_and_secret_separated_from_bundle(self):
        contents = fake_contents()
        result = remote.upload(request("upload"), io.BytesIO(b"".join(contents.values())))
        stage = remote.stage_path(STAGE)
        self.assertEqual(result["files_uploaded"], len(remote.FILES))
        self.assertEqual((stage / "private/server.json").read_bytes(), contents["private/server.json"])
        self.assertFalse((stage / "bundle/server.json").exists())
        self.assertEqual(set(path.relative_to(stage).as_posix() for path in stage.rglob("*") if path.is_file()), set(remote.FILES) | {"upload-manifest.json"})
        with self.assertRaisesRegex(remote.DeploymentError, "stage_exists"):
            remote.upload(request("upload"), io.BytesIO(b"".join(contents.values())))

    def test_interrupted_upload_leaves_unusable_stage_without_overwrite(self):
        with self.assertRaisesRegex(remote.DeploymentError, "stage_incomplete"):
            remote.upload(request("upload"), io.BytesIO(b""))
        self.assertFalse((remote.stage_path(STAGE) / "upload-manifest.json").exists())
        with self.assertRaisesRegex(remote.DeploymentError, "stage_exists"):
            remote.upload(request("upload"), io.BytesIO(b""))

    def test_upload_hash_mismatch_and_trailing_data_refused(self):
        contents = fake_contents()
        with self.assertRaisesRegex(remote.DeploymentError, "hash_mismatch"):
            remote.upload(request("upload"), io.BytesIO(b"X" * sum(len(data) for data in contents.values())))
        value = request("upload", stage="cd" * 16)
        with self.assertRaisesRegex(remote.DeploymentError, "invalid_request"):
            remote.upload(value, io.BytesIO(b"".join(contents.values()) + b"PRIVATE_EXTRA"))
        self.assertFalse((remote.stage_path(value["stage"]) / "upload-manifest.json").exists())

    def test_verified_stage_rejects_altered_input(self):
        contents = fake_contents()
        remote.upload(request("upload"), io.BytesIO(b"".join(contents.values())))
        with patch.object(remote, "secure_directory"), patch.object(remote, "secure_file", side_effect=lambda path, maximum, private=False: path.stat()):
            remote.verify_stage(request("check"))
            (remote.stage_path(STAGE) / "private/server.json").write_bytes(b"ALTERED_PRIVATE_CONFIG")
            with self.assertRaisesRegex(remote.DeploymentError, "hash_mismatch"):
                remote.verify_stage(request("check"))

    def test_manager_commands_are_fixed_and_never_include_config_contents(self):
        stage = remote.stage_path(STAGE)
        for phase in ("preflight", "install", "run", "check", "stop"):
            with self.subTest(phase=phase):
                command = remote.manager_arguments(phase, stage, manifest_for(), request(phase))
                self.assertEqual(command[:2], [remote.sys.executable, str(stage / "bundle/manage.py")])
                self.assertEqual(command[2], "check" if phase == "preflight" else phase)
                self.assertEqual("--apply" in command, phase in ("install", "run", "stop"))
                self.assertNotIn(DOCUMENT, " ".join(command))
                self.assertNotIn(KEY_SECRET, " ".join(command))
                self.assertNotIn("--privileged", command)
                self.assertNotIn("--instance", command)

    def test_named_manager_commands_keep_instance_for_every_phase(self):
        for phase in ("preflight", "install", "run", "check", "stop"):
            command = remote.manager_arguments(phase, remote.stage_path(STAGE), manifest_for(),
                                               request(phase, instance="phone2"))
            self.assertEqual(command[command.index("--instance") + 1], "phone2")

    def test_instance_changes_are_rejected_before_loading_staged_manager(self):
        contents = fake_contents()
        manifest = dict(manifest_for(contents), instance="phone2")
        digest = remote.hash_bytes(remote.json_bytes(manifest))
        upload = request("upload", manifest=manifest, manifest_sha256=digest, instance="phone2")
        remote.validate_request(upload)
        remote.upload(upload, io.BytesIO(b"".join(contents.values())))
        for target in ("default", "other"):
            with self.subTest(target=target), patch.object(remote, "require_host"), \
                    patch.object(remote, "secure_directory"), patch.object(remote, "secure_file"), \
                    patch.object(remote, "load_manager") as load, patch.object(remote, "command") as command:
                with self.assertRaisesRegex(remote.DeploymentError, "installation_mismatch"):
                    remote.perform(request("run", instance=target, manifest_sha256=digest), io.BytesIO())
                load.assert_not_called()
                command.assert_not_called()

    def test_upload_rejects_mismatched_manifest_instance(self):
        for target in ("default", "phone2"):
            manifest = dict(manifest_for(), instance="other")
            with self.assertRaisesRegex(remote.DeploymentError, "installation_mismatch"):
                remote.validate_request(request("upload", instance=target, manifest=manifest,
                                                manifest_sha256=remote.hash_bytes(remote.json_bytes(manifest))))

    def test_installation_binding_rejects_changed_owner_or_manifest(self):
        stage = remote.stage_path(STAGE)
        stage.mkdir()
        manifest = manifest_for()
        record = {"schema": remote.SCHEMA, "owner": "a" * 64, "manifest_sha256": remote.hash_bytes(remote.json_bytes(manifest))}
        (stage / "installation.json").write_bytes(remote.json_bytes(record))
        manager = Mock()
        manager.load_state.return_value = {"owner": "a" * 64}
        with patch.object(remote, "secure_file"):
            remote.require_installation(stage, manifest, manager)
            manager.load_state.return_value = {"owner": "b" * 64}
            with self.assertRaisesRegex(remote.DeploymentError, "installation_mismatch"):
                remote.require_installation(stage, manifest, manager)

    def test_run_check_stop_refuse_unbound_installation_before_commands(self):
        module = Mock(DeploymentError=type("FakeManagerError", (Exception,), {}))
        for phase in ("run", "check", "stop"):
            with self.subTest(phase=phase), patch.object(remote, "require_host"), patch.object(remote, "verify_stage", return_value=(remote.stage_path(STAGE), manifest_for())), \
                    patch.object(remote, "load_manager", return_value=module), patch.object(remote, "validate_artifacts"), \
                    patch.object(remote, "require_installation", side_effect=remote.DeploymentError("installation_mismatch")), patch.object(remote, "command") as command:
                with self.assertRaisesRegex(remote.DeploymentError, "installation_mismatch"):
                    remote.perform(request(phase), io.BytesIO())
                command.assert_not_called()

    def test_install_does_not_adopt_or_overwrite_existing_installation(self):
        module = Mock(DeploymentError=type("FakeManagerError", (Exception,), {}))
        module.Manager.return_value.root = self.root
        with patch.object(remote, "INSTALL_ROOT", self.root), patch.object(remote, "require_host"), \
                patch.object(remote, "verify_stage", return_value=(remote.stage_path(STAGE), manifest_for())), \
                patch.object(remote, "load_manager", return_value=module), patch.object(remote, "validate_artifacts"), \
                patch.object(remote, "bind_installation") as bind, patch.object(remote, "command") as command:
            with self.assertRaisesRegex(remote.DeploymentError, "installation_exists"):
                remote.perform(request("install"), io.BytesIO())
            bind.assert_not_called()
            command.assert_not_called()

    def test_named_install_uses_separate_root_even_with_original_present(self):
        module = Mock(DeploymentError=type("FakeManagerError", (Exception,), {}))
        manager = module.Manager.return_value
        manager.root = self.root / "unifiedvpn-openflux-phone2"
        manifest = dict(manifest_for(), instance="phone2")
        with patch.object(remote, "INSTALL_ROOT", self.root), patch.object(remote, "require_host"), \
                patch.object(remote, "verify_stage", return_value=(remote.stage_path(STAGE), manifest)), \
                patch.object(remote, "load_manager", return_value=module), patch.object(remote, "validate_artifacts"), \
                patch.object(remote, "bind_installation") as bind, patch.object(remote, "command") as command:
            remote.perform(request("install", instance="phone2"), io.BytesIO())
        module.Manager.assert_called_once_with(instance="phone2")
        command.assert_called_once()
        bind.assert_called_once_with(remote.stage_path(STAGE), manifest, manager)

    def test_old_default_stage_cannot_be_redirected_to_named_installation(self):
        contents = fake_contents()
        remote.upload(request("upload"), io.BytesIO(b"".join(contents.values())))
        with patch.object(remote, "secure_directory"), patch.object(remote, "secure_file"):
            with self.assertRaisesRegex(remote.DeploymentError, "installation_mismatch"):
                remote.verify_stage(request("stop", instance="phone2"))

    def test_runtime_pull_is_pinned_local_socket_and_keeps_original_unique_tag(self):
        stage = remote.stage_path(STAGE)
        stage.mkdir()
        manager = Mock()
        manager.inspect.side_effect = [None, {"Id": IMAGE, "Os": "linux", "Architecture": "amd64"}]
        with patch.object(remote, "command") as command:
            result = remote.runtime_pull(request("runtime-pull"), stage, manager)
        arguments = command.call_args.args[0]
        self.assertEqual(arguments, ["docker", "--host", "unix:///var/run/docker.sock", "pull", "--platform", "linux/amd64", ALPINE])
        manager.docker.assert_called_once_with("image", "tag", IMAGE, "unifiedvpn-openflux-alpine:" + STAGE)
        self.assertTrue(result["pinned_base_available"])
        self.assertTrue((stage / "runtime-base.json").exists())

    def test_runtime_build_has_tiny_context_no_secret_and_no_pull(self):
        stage = remote.stage_path(STAGE)
        (stage / "bundle").mkdir(parents=True)
        (stage / "bundle/runtime.Dockerfile").write_bytes(b"ARG ALPINE_IMAGE\nFROM ${ALPINE_IMAGE}\n")
        base = {"Id": IMAGE, "Os": "linux", "Architecture": "amd64", "RepoTags": ["retained-base"], "Config": {}}
        final = {"Id": "sha256:" + "ab" * 32, "Config": {"Labels": {"org.unifiedvpn.openflux.stage": STAGE}}}
        manager = Mock()
        manager.inspect.side_effect = [base, None, None, dict(base, RepoTags=["retained-base", "temporary"]), final]
        with patch.object(remote, "secure_file"), patch.object(remote, "command") as command:
            result = remote.runtime_build(request("runtime-build"), stage, None, manager)
        arguments = command.call_args.args[0]
        self.assertIn("--network=default", arguments)
        self.assertIn("--pull=false", arguments)
        self.assertNotIn("--privileged", arguments)
        self.assertEqual(arguments[:3], ["docker", "--host", "unix:///var/run/docker.sock"])
        self.assertEqual(set(path.name for path in (stage / "runtime-context").iterdir()), {"Dockerfile"})
        self.assertEqual(result["runtime_image_id"], final["Id"])
        self.assertEqual(manager.docker.call_args.args, ("image", "rm", "unifiedvpn-openflux-base:" + STAGE))

    def test_commands_discard_output_and_sanitize_timeouts(self):
        process = Mock(pid=4567)
        process.wait.return_value = 0
        with patch.object(remote.subprocess, "Popen", return_value=process) as run:
            remote.command(["PRIVATE_COMMAND"], 1)
        self.assertEqual(run.call_args.kwargs["stdout"], subprocess.DEVNULL)
        self.assertEqual(run.call_args.kwargs["stderr"], subprocess.DEVNULL)
        self.assertEqual(run.call_args.kwargs["stdin"], subprocess.DEVNULL)
        self.assertTrue(run.call_args.kwargs["start_new_session"])
        process.wait.side_effect = [subprocess.TimeoutExpired(["PRIVATE_COMMAND"], 1), 0]
        with patch.object(remote.subprocess, "Popen", return_value=process), patch.object(remote.os, "killpg", create=True) as kill:
            with self.assertRaisesRegex(remote.DeploymentError, "command_timeout") as caught:
                remote.command(["PRIVATE_COMMAND"], 1)
        kill.assert_called_once_with(process.pid, remote.signal.SIGTERM)
        self.assertNotIn("PRIVATE", str(caught.exception))

    def test_remote_main_sanitizes_unexpected_exceptions(self):
        value = remote.json_bytes(request("check"))
        stream = io.BytesIO(str(len(value)).encode() + b"\n" + value)
        output = io.StringIO()
        with patch.object(remote, "perform", side_effect=RuntimeError(DOCUMENT + KEY_SECRET)), contextlib.redirect_stdout(output):
            code = remote.main(stream)
        self.assertEqual(code, 1)
        self.assertNotIn(DOCUMENT, output.getvalue())
        self.assertNotIn(KEY_SECRET, output.getvalue())
        self.assertEqual(json.loads(output.getvalue())["error"], "unexpected_failure")


if __name__ == "__main__":
    unittest.main()
