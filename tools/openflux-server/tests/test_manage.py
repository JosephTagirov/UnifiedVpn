import contextlib
import importlib.util
import io
import json
import os
from pathlib import Path
import stat
import struct
import subprocess
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch


SPEC = importlib.util.spec_from_file_location("openflux_server_manager", Path(__file__).resolve().parents[1] / "manage.py")
manage = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(manage)
RUNTIME_ID = "sha256:" + "1" * 64
IMAGE_ID = "sha256:" + "2" * 64
CONTAINER_ID = "3" * 64
NETWORK_ID = "4" * 64


def config():
    return {"version": 1, "mode": "server", "transport": "yandex",
            "document_url": "https://disk.yandex.ru/i/public-test-placeholder",
            "encryption_key": "ab" * 32, "handshake_timeout_seconds": 60}


def elf(dynamic=False):
    header = bytearray(120)
    header[:6] = b"\x7fELF\x02\x01"
    struct.pack_into("<H", header, 18, 62)
    struct.pack_into("<Q", header, 32, 64)
    struct.pack_into("<HH", header, 54, 56, 1)
    struct.pack_into("<I", header, 64, 3 if dynamic else 1)
    return header


class FakeDocker:
    def __init__(self):
        self.commands = []
        self.routes = [{"dst": "default", "gateway": "192.0.2.1"}, {"dst": "192.0.2.0/24"}]
        self.images = {RUNTIME_ID: {"Id": RUNTIME_ID, "Os": "linux", "Architecture": "amd64", "Config": {}}}
        self.tags = {"reviewed-runtime:local": RUNTIME_ID}
        self.containers = {}
        self.networks = {}
        self.fail_build = False
        self.build_context = None

    @staticmethod
    def result(stdout="", returncode=0):
        return subprocess.CompletedProcess([], returncode, stdout, "")

    @staticmethod
    def labels(args):
        return dict(item.split("=", 1) for index, item in enumerate(args) if index and args[index - 1] == "--label")

    def __call__(self, argv, timeout=30, allow_failure=False):
        self.commands.append(argv)
        if argv[0] == "ip":
            if argv != ["ip", "-j", "-4", "route", "show", "table", "all"]:
                raise AssertionError("Unexpected route command")
            return self.result(json.dumps(self.routes))
        if argv[:3] != ["docker", "--host", "unix:///var/run/docker.sock"]:
            raise AssertionError("Docker must explicitly address the local UNIX socket")
        args = argv[3:]
        if args[0] == "build":
            self.build_context = {path.name: path.read_bytes() for path in Path(args[-1]).iterdir()}
            if self.fail_build:
                raise manage.DeploymentError("Simulated build failure")
            self.images[IMAGE_ID] = {"Id": IMAGE_ID, "Config": {"Labels": self.labels(args)}}
            self.tags[args[args.index("--tag") + 1]] = IMAGE_ID
            return self.result(IMAGE_ID)
        kind, operation = args[:2]
        names = args[2:]
        if operation == "inspect":
            result = []
            for name in names:
                if kind == "image":
                    resource = self.images.get(self.tags.get(name, name))
                    if resource:
                        resource = dict(resource, RepoTags=[tag for tag, image in self.tags.items() if image == resource["Id"]])
                else:
                    collection = self.containers if kind == "container" else self.networks
                    resource = collection.get(name) or next((value for value in collection.values() if value["Id"] == name), None)
                if resource is None:
                    return self.result(returncode=1)
                result.append(resource)
            return self.result(json.dumps(result))
        if (kind, operation) == ("network", "ls"):
            return self.result("\n".join(network["Id"] for network in self.networks.values()))
        if (kind, operation) == ("image", "tag"):
            self.tags[names[1]] = names[0]
            return self.result()
        if (kind, operation) == ("image", "rm"):
            if names[0] in self.tags:
                del self.tags[names[0]]
            else:
                self.images.pop(names[0])
                self.tags = {tag: image for tag, image in self.tags.items() if image != names[0]}
            return self.result()
        if (kind, operation) == ("network", "create"):
            self.networks[names[-1]] = {"Id": NETWORK_ID, "Labels": self.labels(args), "Containers": {},
                                       "IPAM": {"Config": [{"Subnet": args[args.index("--subnet") + 1]}]}}
            return self.result(NETWORK_ID + "\n")
        if (kind, operation) == ("container", "create"):
            self.containers[manage.NAME] = {"Id": CONTAINER_ID, "Config": {"Labels": self.labels(args)},
                                            "State": {"Running": False, "Status": "created"}}
            self.networks[manage.NETWORK]["Containers"][CONTAINER_ID] = {"Name": manage.NAME}
            return self.result(CONTAINER_ID + "\n")
        if (kind, operation) in (("container", "start"), ("container", "stop")):
            self.containers[manage.NAME]["State"] = {"Running": operation == "start", "Status": "running" if operation == "start" else "exited"}
            return self.result()
        if (kind, operation) == ("container", "rm"):
            self.containers.pop(manage.NAME)
            self.networks[manage.NETWORK]["Containers"].pop(CONTAINER_ID, None)
            return self.result()
        if (kind, operation) == ("network", "rm"):
            self.networks.pop(manage.NETWORK)
            return self.result()
        raise AssertionError("Unexpected command: " + repr(args))


class ValidationTests(unittest.TestCase):
    def test_only_encrypted_yandex_server_config(self):
        self.assertEqual(manage.validate_config(config()), config())
        mutations = [{"version": True}, {"mode": "client"}, {"transport": "oneme"},
                     {"encryption_key": ""}, {"encryption_key": "x" * 64},
                     {"handshake_timeout_seconds": True}, {"handshake_timeout_seconds": 181},
                     {"handshake_timeout_seconds": 4}, {"debug": True}, {"allow_plaintext": True},
                     {"dns_server": "1.1.1.1:53"}]
        for mutation in mutations:
            with self.subTest(mutation=mutation), self.assertRaises(manage.DeploymentError):
                manage.validate_config(dict(config(), **mutation))
        incomplete = config()
        del incomplete["encryption_key"]
        with self.assertRaises(manage.DeploymentError):
            manage.validate_config(incomplete)

    def test_url_validation_does_not_print_url(self):
        for value in ("http://docs.yandex.ru/i/SECRET", "https://docs.yandex.ru.evil.test/i/SECRET",
                      "https://SECRET@docs.yandex.ru/i/test", "https://disk.yandex.ru/i/SECRET#fragment",
                      "https://docs.yandex.ru:444/i/SECRET", "https://docs.yandex.ru/", " https://docs.yandex.ru/i/SECRET",
                      "https://docs.yandex.ru/i/SECRET\n", "https://docs.yandex.ru/i/" + "X" * 8192):
            with self.subTest(value=value[:40]), self.assertRaises(manage.DeploymentError) as error:
                manage.validate_document_url(value)
            self.assertNotIn("SECRET", str(error.exception))

    def test_document_hosts_and_default_port(self):
        for value in ("https://docs.yandex.ru/docs/view?url=public-placeholder", "https://disk.yandex.ru:443/i/public-placeholder"):
            manage.validate_document_url(value)

    def test_duplicate_and_malformed_json_are_rejected_without_contents(self):
        for value in ('{"version":1,"version":1}', '{"SECRET":'):
            with self.assertRaises(manage.DeploymentError) as error:
                manage.decode_json(value)
            self.assertNotIn("SECRET", str(error.exception))

    def test_subnet_constraints(self):
        self.assertEqual(str(manage.validate_subnet("172.30.251.0/28")), "172.30.251.0/28")
        for value in ("172.30.251.1/28", "0.0.0.0/0", "192.0.2.0/28", "10.10.10.0/28", "10.0.0.0/8", "::/0"):
            with self.subTest(value=value), self.assertRaises(manage.DeploymentError):
                manage.validate_subnet(value)

    def test_file_owner_and_modes(self):
        path = Path("example.json")
        with patch.object(manage, "secure_directory"), patch.object(Path, "lstat") as details:
            for mode, uid, links in ((stat.S_IFREG | 0o644, 0, 1), (stat.S_IFREG | 0o600, 1000, 1),
                                     (stat.S_IFLNK | 0o600, 0, 1), (stat.S_IFREG | 0o600, 0, 2)):
                details.return_value = SimpleNamespace(st_mode=mode, st_uid=uid, st_nlink=links)
                with self.assertRaises(manage.DeploymentError):
                    manage.require_secure_file(path, private=True)
            details.return_value = SimpleNamespace(st_mode=stat.S_IFREG | 0o600, st_uid=0, st_nlink=1)
            manage.require_secure_file(path, private=True)

    def test_writable_or_symlink_parents_rejected(self):
        with patch.object(Path, "lstat") as details:
            for mode in (stat.S_IFDIR | 0o777, stat.S_IFLNK | 0o700):
                details.return_value = SimpleNamespace(st_mode=mode, st_uid=0)
                with self.assertRaises(manage.DeploymentError):
                    manage.secure_directory(Path("/root/bundle"))

    def test_elf_requires_static_linux_amd64(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "openflux"
            for content in (b"MZ", elf(dynamic=True), bytes(64)):
                path.write_bytes(content)
                with self.assertRaises(manage.DeploymentError):
                    manage.validate_elf(path)
            path.write_bytes(elf())
            manage.validate_elf(path)

    def test_mutation_requires_opt_in_before_preflight(self):
        with patch.object(manage.Manager, "preflight") as preflight, contextlib.redirect_stderr(io.StringIO()):
            for command in ("configure", "install", "run", "stop", "remove"):
                self.assertEqual(manage.main([command]), 1)
            preflight.assert_not_called()

    def test_no_remote_docker_or_builder_environment(self):
        environment = {"DOCKER_HOST": "ssh://private-server", "DOCKER_CONTEXT": "production", "BUILDX_BUILDER": "remote", "BUILDKIT_HOST": "tcp://remote"}
        with patch.dict(os.environ, environment), patch.object(subprocess, "run") as run:
            run.return_value = subprocess.CompletedProcess([], 0, "", "")
            manage.run_command(["docker", "version"])
            forwarded = run.call_args.kwargs["env"]
            for key in environment:
                self.assertNotIn(key, forwarded)
            self.assertEqual(run.call_args.kwargs["stdin"], subprocess.DEVNULL)

    def test_command_failures_do_not_disclose_output(self):
        with patch.object(subprocess, "run", return_value=subprocess.CompletedProcess([], 1, "SECRET", "SECRET")):
            with self.assertRaises(manage.DeploymentError) as error:
                manage.run_command(["docker", "info"])
            self.assertNotIn("SECRET", str(error.exception))

    def test_entrypoint_is_fail_closed_and_namespace_scoped(self):
        script = (manage.SCRIPT_DIR / "entrypoint.sh").read_text(encoding="utf-8")
        self.assertLess(script.index('[ -f /.dockerenv ]'), script.index("iptables -w"))
        self.assertLess(script.index('[ "$namespace" != "$OPENFLUX_HOST_NETNS" ]'), script.index("iptables -w"))
        self.assertIn("--config /run/secrets/server.json >/dev/null 2>&1", script)
        self.assertNotIn("--url", script)
        self.assertNotIn("--encryption-key", script)
        self.assertNotIn("|| true", script)
        for line in script.splitlines():
            if line.startswith("iptables "):
                self.assertTrue(line.endswith("|| fail"))


class LifecycleTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.directory = Path(self.temporary.name)
        self.root = self.directory / "installation"
        self.fake = FakeDocker()
        self.manager = manage.Manager(self.root, self.fake)
        for target in (patch.object(manage.Manager, "preflight"), patch.object(manage, "secure_directory"),
                       patch.object(manage, "require_secure_file", side_effect=lambda path, private=False: path.stat()),
                       patch.object(os, "readlink", return_value="net:[100]")):
            target.start()
            self.addCleanup(target.stop)
        self.output = io.StringIO()
        redirect = contextlib.redirect_stdout(self.output)
        redirect.__enter__()
        self.addCleanup(redirect.__exit__, None, None, None)
        self.binary = self.directory / "openflux-linux-amd64"
        self.binary.write_bytes(elf())
        self.source = self.directory / "source.tar.gz"
        self.source.write_bytes(b"\x1f\x8bpublic-source-test-fixture")
        self.secret = self.directory / "server.json"
        self.secret.write_text(json.dumps(config()), encoding="utf-8")
        for name in ("LICENSE", "NOTICE", "COPYRIGHT"):
            (self.directory / name).write_text("Public " + name, encoding="utf-8")
        self.args = manage.parser().parse_args(["check", "--binary", str(self.binary), "--binary-sha256", manage.sha256_file(self.binary),
                                              "--source-archive", str(self.source), "--source-sha256", manage.sha256_file(self.source),
                                              "--licenses-dir", str(self.directory), "--runtime-image", RUNTIME_ID,
                                              "--config", str(self.secret), "--subnet", "172.30.251.0/28"])

    def install_and_run(self):
        self.manager.install(self.args)
        self.manager.run()

    def mutations(self):
        return [argv for argv in self.fake.commands if argv[0] == "docker" and (argv[3] == "build" or argv[4] in ("create", "start", "stop", "rm", "tag"))]

    def test_preflight_check_is_read_only_and_does_not_run_binary(self):
        self.manager.check(self.args)
        self.assertFalse(self.root.exists())
        self.assertEqual(self.mutations(), [])
        self.assertTrue(all(argv[0] in ("docker", "ip") for argv in self.fake.commands))

    def test_install_build_context_excludes_secrets(self):
        self.manager.install(self.args)
        self.assertEqual(set(self.fake.build_context), set(manage.CONTEXT_FILES))
        self.assertNotIn("server.json", self.fake.build_context)
        self.assertNotIn("state.json", self.fake.build_context)
        self.assertEqual(self.fake.containers, {})
        self.assertEqual(self.fake.networks, {})
        self.assertFalse((self.root / "image-context").exists())
        build = next(argv for argv in self.fake.commands if argv[3] == "build")
        self.assertIn("--network=none", build)
        self.assertIn("--pull=false", build)
        self.assertEqual(build[build.index("--builder") + 1], "default")
        self.assertIn("EXPECTED_VERSION=" + manage.VERSION, build)
        self.assertTrue(all(not tag.startswith(manage.NAME + "-runtime:") for tag in self.fake.tags))
        provenance = json.loads((self.root / "provenance.json").read_text())
        self.assertEqual(provenance["upstream_commit"], manage.UPSTREAM)

    def test_install_does_not_overwrite_existing_directory(self):
        self.root.mkdir()
        sentinel = self.root / "unrelated"
        sentinel.write_text("untouched")
        with self.assertRaises(manage.DeploymentError):
            self.manager.install(self.args)
        self.assertEqual(sentinel.read_text(), "untouched")
        self.assertEqual(self.mutations(), [])

    def test_sha_mismatch_blocks_all_mutations(self):
        self.args.binary_sha256 = "0" * 64
        with self.assertRaises(manage.DeploymentError):
            self.manager.install(self.args)
        self.assertFalse(self.root.exists())
        self.assertEqual(self.mutations(), [])

    def test_mutable_runtime_image_and_onbuild_rejected(self):
        self.args.runtime_image = "alpine:latest"
        with self.assertRaises(manage.DeploymentError):
            self.manager.validate_inputs(self.args)
        self.args.runtime_image = RUNTIME_ID
        self.fake.images[RUNTIME_ID]["Config"]["OnBuild"] = ["RUN something"]
        with self.assertRaises(manage.DeploymentError):
            self.manager.validate_inputs(self.args)
        self.assertEqual(self.mutations(), [])

    def test_existing_host_route_overlap_blocks_install(self):
        self.fake.routes.append({"dst": "172.30.0.0/16"})
        with self.assertRaises(manage.DeploymentError):
            self.manager.install(self.args)
        self.assertEqual(self.mutations(), [])

    def test_existing_docker_subnet_overlap_blocks_install(self):
        self.fake.networks["working-awg"] = {"Id": "9" * 64, "IPAM": {"Config": [{"Subnet": "172.30.0.0/16"}]}}
        with self.assertRaises(manage.DeploymentError):
            self.manager.install(self.args)
        self.assertEqual(self.mutations(), [])

    def test_unrelated_name_collision_blocks_install(self):
        self.fake.containers[manage.NAME] = {"Id": "9" * 64, "Config": {"Labels": None}}
        with self.assertRaises(manage.DeploymentError):
            self.manager.install(self.args)
        self.assertEqual(self.mutations(), [])

    def test_run_is_hardened_and_secrets_never_enter_argv(self):
        self.install_and_run()
        create = next(argv for argv in self.fake.commands if argv[3:5] == ["container", "create"])
        self.assertEqual(create[create.index("--network") + 1], manage.NETWORK)
        self.assertEqual(create[create.index("--cap-drop") + 1], "ALL")
        self.assertEqual([item for index, item in enumerate(create) if index and create[index - 1] == "--cap-add"], ["NET_RAW", "NET_ADMIN"])
        self.assertEqual(create[create.index("--log-driver") + 1], "none")
        self.assertEqual(create[create.index("--restart") + 1], "no")
        self.assertIn("--read-only", create)
        self.assertIn("no-new-privileges:true", create)
        self.assertIn("--pull=never", create)
        self.assertNotIn("--privileged", create)
        self.assertNotIn("--publish", create)
        self.assertNotIn("--pid", create)
        mount = create[create.index("--mount") + 1]
        self.assertIn("dst=/run/secrets/server.json,readonly", mount)
        all_output = repr(self.fake.commands) + self.output.getvalue()
        self.assertNotIn(config()["document_url"], all_output)
        self.assertNotIn(config()["encryption_key"], all_output)

    def test_installed_check_is_read_only(self):
        self.install_and_run()
        self.fake.commands.clear()
        self.manager.check(self.args)
        self.assertEqual(self.mutations(), [])
        self.assertIn("running", self.output.getvalue())

    def test_stop_removes_only_owned_resources_and_retains_config(self):
        self.install_and_run()
        self.fake.commands.clear()
        self.manager.stop()
        self.assertEqual(self.fake.containers, {})
        self.assertEqual(self.fake.networks, {})
        self.assertTrue((self.root / "server.json").exists())
        self.assertIn(IMAGE_ID, self.fake.images)
        for command in self.mutations():
            self.assertIn(command[-1], (CONTAINER_ID, NETWORK_ID))

    def test_foreign_container_or_network_prevents_any_stop(self):
        self.install_and_run()
        original = self.fake.containers[manage.NAME]["Config"]["Labels"]
        self.fake.containers[manage.NAME]["Config"]["Labels"] = None
        self.fake.commands.clear()
        with self.assertRaises(manage.DeploymentError):
            self.manager.stop()
        self.assertEqual(self.mutations(), [])
        self.fake.containers[manage.NAME]["Config"]["Labels"] = original
        self.fake.networks[manage.NETWORK]["Labels"] = {}
        self.fake.commands.clear()
        with self.assertRaises(manage.DeploymentError):
            self.manager.stop()
        self.assertEqual(self.mutations(), [])

    def test_replacement_with_same_label_but_different_id_is_not_removed(self):
        self.install_and_run()
        self.fake.containers[manage.NAME]["Id"] = "8" * 64
        self.fake.commands.clear()
        with self.assertRaises(manage.DeploymentError):
            self.manager.stop()
        self.assertEqual(self.mutations(), [])

    def test_other_endpoint_is_never_disconnected(self):
        self.install_and_run()
        self.fake.networks[manage.NETWORK]["Containers"]["other"] = {"Name": "unrelated"}
        self.fake.commands.clear()
        with self.assertRaises(manage.DeploymentError):
            self.manager.stop()
        self.assertIn(manage.NETWORK, self.fake.networks)
        self.assertFalse(any("disconnect" in command for command in self.fake.commands))

    def test_remove_preserves_original_inputs_and_runtime(self):
        self.install_and_run()
        self.manager.remove()
        self.assertFalse(self.root.exists())
        self.assertTrue(self.binary.exists())
        self.assertTrue(self.source.exists())
        self.assertTrue(self.secret.exists())
        self.assertIn(RUNTIME_ID, self.fake.images)
        self.assertNotIn(IMAGE_ID, self.fake.images)

    def test_remove_refuses_unknown_files(self):
        self.manager.install(self.args)
        sentinel = self.root / "someone-elses-file"
        sentinel.write_text("untouched")
        with self.assertRaises(manage.DeploymentError):
            self.manager.remove()
        self.assertEqual(sentinel.read_text(), "untouched")
        self.assertTrue((self.root / "state.json").exists())

    def test_failed_build_leaves_removable_owned_installation(self):
        self.fake.fail_build = True
        with self.assertRaises(manage.DeploymentError):
            self.manager.install(self.args)
        self.assertTrue((self.root / "state.json").exists())
        self.assertFalse((self.root / "image-context").exists())
        self.assertEqual(self.fake.containers, {})
        self.manager.remove()
        self.assertFalse(self.root.exists())
        self.assertEqual(self.fake.tags, {"reviewed-runtime:local": RUNTIME_ID})

    def test_crashed_build_context_cleanup_is_bounded(self):
        self.manager.install(self.args)
        context = self.root / "image-context"
        context.mkdir()
        (context / "openflux").write_bytes(b"public-build-file")
        self.manager.remove()
        self.assertFalse(self.root.exists())

    def test_old_state_temporary_is_recovered(self):
        self.manager.install(self.args)
        (self.root / "state.new").write_text("partial write", encoding="utf-8")
        self.manager.stop()
        self.assertFalse((self.root / "state.new").exists())
        self.assertEqual(self.manager.load_state()["upstream"], manage.UPSTREAM)


if __name__ == "__main__":
    unittest.main()
