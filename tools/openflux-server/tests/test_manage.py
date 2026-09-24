import contextlib
import copy
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
from unittest.mock import Mock, patch


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
        self.identity_counts = {"image": 0, "container": 0, "network": 0}

    def next_identity(self, kind):
        original = {"image": IMAGE_ID.removeprefix("sha256:"), "container": CONTAINER_ID, "network": NETWORK_ID}[kind]
        value = f"{int(original, 16) + self.identity_counts[kind]:064x}"
        self.identity_counts[kind] += 1
        return "sha256:" + value if kind == "image" else value

    @staticmethod
    def named_resource(collection, reference):
        match = next(((name, value) for name, value in collection.items()
                      if name == reference or value["Id"] == reference), None)
        if match is None:
            raise AssertionError("Fake Docker resource is missing: " + reference)
        return match

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
            image_id = self.next_identity("image")
            self.images[image_id] = {"Id": image_id, "Config": {"Labels": self.labels(args)}}
            self.tags[args[args.index("--tag") + 1]] = image_id
            return self.result(image_id)
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
            network_id = self.next_identity("network")
            self.networks[names[-1]] = {"Id": network_id, "Labels": self.labels(args), "Containers": {},
                                       "IPAM": {"Config": [{"Subnet": args[args.index("--subnet") + 1]}]}}
            return self.result(network_id + "\n")
        if (kind, operation) == ("container", "create"):
            container_id = self.next_identity("container")
            name = args[args.index("--name") + 1]
            network = args[args.index("--network") + 1]
            self.containers[name] = {"Id": container_id, "Config": {"Labels": self.labels(args)},
                                     "State": {"Running": False, "Status": "created"}}
            self.networks[network]["Containers"][container_id] = {"Name": name}
            return self.result(container_id + "\n")
        if (kind, operation) in (("container", "start"), ("container", "stop")):
            _, container = self.named_resource(self.containers, names[-1])
            container["State"] = {"Running": operation == "start", "Status": "running" if operation == "start" else "exited"}
            return self.result()
        if (kind, operation) == ("container", "rm"):
            name, container = self.named_resource(self.containers, names[-1])
            self.containers.pop(name)
            for network in self.networks.values():
                network.get("Containers", {}).pop(container["Id"], None)
            return self.result()
        if (kind, operation) == ("network", "rm"):
            name, _ = self.named_resource(self.networks, names[-1])
            self.networks.pop(name)
            return self.result()
        raise AssertionError("Unexpected command: " + repr(args))


class HostPreflightTests(unittest.TestCase):
    def setUp(self):
        self.manager = manage.Manager()
        self.manager.docker = Mock(return_value=SimpleNamespace(stdout=json.dumps({'OSType':'linux','SecurityOptions':[]})))
        self.links = Mock(side_effect=lambda path: {'/proc/1/ns/net':'net:[123]', '/proc/self/ns/net':'net:[123]'}[path])
        for mock in (patch.object(manage.platform,'system',return_value='Linux'),
                     patch.object(manage.platform,'machine',return_value='x86_64'),
                     patch.object(manage.os,'geteuid',return_value=0,create=True),
                     patch.object(manage.Path,'exists',return_value=False),
                     patch.object(manage.os,'readlink',self.links),
                     patch.object(manage.shutil,'which',return_value='/usr/bin/checked'),
                     patch.object(manage,'DOCKER_SOCKET',SimpleNamespace(stat=lambda:SimpleNamespace(st_mode=stat.S_IFSOCK | 0o660,st_uid=0)))):
            mock.start()
            self.addCleanup(mock.stop)

    def test_regular_preflight_checks_pid_one(self):
        self.manager.preflight()
        self.assertIn(('/proc/1/ns/net',),[call.args for call in self.links.call_args_list])

    def test_bound_service_does_not_need_ptrace_permission(self):
        self.links.side_effect = lambda path: 'net:[123]' if path == '/proc/self/ns/net' else (_ for _ in ()).throw(PermissionError())
        self.manager.preflight(network_namespace='net:[123]')
        self.links.assert_called_once_with('/proc/self/ns/net')

    def test_foreign_or_invalid_namespace_is_rejected(self):
        for value in ('net:[456]','invalid',True):
            with self.subTest(value=value), self.assertRaises(manage.DeploymentError):
                self.manager.preflight(network_namespace=value)
        self.manager.docker.assert_not_called()


class ValidationTests(unittest.TestCase):
    def test_default_and_named_instance_paths_are_distinct(self):
        default = manage.Manager()
        self.assertEqual(default.root, manage.INSTALL_DIR)
        self.assertEqual(default.name, manage.NAME)
        self.assertEqual(default.network, manage.NETWORK)
        second = manage.Manager(instance="phone2")
        self.assertEqual(second.root, manage.INSTALL_DIR.with_name(manage.NAME + "-phone2"))
        self.assertEqual(second.name, manage.NAME + "-phone2")
        self.assertEqual(second.network, manage.NAME + "-phone2-net")

    def test_invalid_instance_names_are_rejected_before_commands(self):
        values = (None, [], {}, True, 1, "", "Phone", "1phone", "phone-2", "phone_2", "../phone",
                  "/opt/other", "default/../phone", "phone;command", "phone\n", "ph\u043ene", "a" * 17)
        for value in values:
            with self.subTest(value=repr(value)), self.assertRaises(manage.DeploymentError):
                manage.Manager(instance=value)
        with patch.object(manage.Manager, "preflight") as preflight, contextlib.redirect_stderr(io.StringIO()):
            for command in ("check", "install", "run", "stop", "remove"):
                self.assertEqual(manage.main([command, "--apply", "--instance", "../phone"]), 1)
            preflight.assert_not_called()

    def test_installed_named_manager_refuses_missing_or_other_instance_before_actions(self):
        installed = manage.INSTALL_DIR.with_name(manage.NAME + "-phone2")
        with patch.object(manage, "SCRIPT_DIR", installed), patch.object(manage, "Manager") as factory, \
                patch.object(manage, "configure") as configure, contextlib.redirect_stderr(io.StringIO()):
            for command in ("check", "configure", "install", "run", "stop", "remove"):
                for extra in ([], ["--instance", "default"], ["--instance", "tablet"]):
                    with self.subTest(command=command, extra=extra):
                        self.assertEqual(manage.main([command, "--apply", *extra]), 1)
            factory.assert_not_called()
            configure.assert_not_called()

    def test_installed_named_manager_accepts_only_matching_instance(self):
        installed = manage.INSTALL_DIR.with_name(manage.NAME + "-phone2")
        with patch.object(manage, "SCRIPT_DIR", installed), patch.object(manage, "Manager") as factory:
            self.assertEqual(manage.main(["stop", "--apply", "--instance", "phone2"]), 0)
            factory.assert_called_once_with(instance="phone2")
            factory.return_value.stop.assert_called_once_with()

    def test_installed_manager_guard_keeps_default_and_staged_commands_compatible(self):
        stage = Path("/root/unifiedvpn-openflux-stage-" + "ab" * 16) / "bundle"
        for directory, instance in ((manage.INSTALL_DIR, "default"), (stage, "default"), (stage, "phone2")):
            with self.subTest(directory=str(directory), instance=instance), \
                    patch.object(manage, "SCRIPT_DIR", directory), patch.object(manage, "Manager") as factory:
                extra = [] if instance == "default" else ["--instance", instance]
                self.assertEqual(manage.main(["stop", "--apply", *extra]), 0)
                factory.assert_called_once_with(instance=instance)
                factory.return_value.stop.assert_called_once_with()

    def test_only_explicit_encrypted_yandex_transports_in_server_config(self):
        for transport in ("yandex", "vyandex"):
            value = dict(config(), transport=transport)
            self.assertEqual(manage.validate_config(value), value)
        mutations = [{"version": True}, {"mode": "client"}, {"transport": "oneme"},
                     {"encryption_key": ""}, {"encryption_key": "x" * 64},
                     {"handshake_timeout_seconds": True}, {"handshake_timeout_seconds": 181},
                     {"handshake_timeout_seconds": 4}, {"debug": True}, {"allow_plaintext": True},
                     {"dns_server": "1.1.1.1:53"}, {"codec": "legacy"}, {"codec": "batched"}, {"codec": None}]
        for mutation in mutations:
            with self.subTest(mutation=mutation), self.assertRaises(manage.DeploymentError):
                manage.validate_config(dict(config(), **mutation))
        incomplete = config()
        del incomplete["encryption_key"]
        with self.assertRaises(manage.DeploymentError):
            manage.validate_config(incomplete)

    def test_unknown_and_non_string_transports_never_fall_back(self):
        for transport in ("", "auto", "volga", "Yandex", "VYANDEX", None, True, [], {}):
            with self.subTest(transport=transport), self.assertRaises(manage.DeploymentError):
                manage.validate_config(dict(config(), transport=transport))
        incomplete = config()
        del incomplete["transport"]
        with self.assertRaises(manage.DeploymentError):
            manage.validate_config(incomplete)

    def test_configure_cli_passes_selected_transport_or_legacy_default(self):
        for extra, expected in (([], "yandex"), (["--transport", "yandex"], "yandex"),
                                (["--transport", "vyandex"], "vyandex")):
            with self.subTest(extra=extra), patch.object(manage, "configure") as configure:
                self.assertEqual(manage.main(["configure", "--output", "test.json", "--apply", *extra]), 0)
                configure.assert_called_once_with(Path("test.json"), expected)

    def test_existing_config_transport_cannot_be_overridden_by_cli(self):
        with patch.object(manage, "Manager") as factory, contextlib.redirect_stderr(io.StringIO()):
            for command in ("check", "install", "run", "stop", "remove"):
                with self.subTest(command=command):
                    self.assertEqual(manage.main([command, "--transport", "vyandex", "--apply"]), 1)
            factory.assert_not_called()

    def test_configure_writes_explicit_transport_without_leaking_secrets(self):
        with tempfile.TemporaryDirectory() as directory, \
                patch.object(manage.platform, "system", return_value="Linux"), \
                patch.object(manage.os, "geteuid", return_value=0, create=True), \
                patch.object(manage.Manager, "secure_directory"), \
                patch.object(manage.sys.stdin, "isatty", return_value=True), \
                patch.object(manage.getpass, "getpass", return_value=config()["document_url"]), \
                contextlib.redirect_stdout(io.StringIO()) as output:
            for transport in ("yandex", "vyandex"):
                path = Path(directory) / (transport + ".json")
                manage.configure(path, transport)
                value = manage.validate_config(json.loads(path.read_text()))
                self.assertEqual(value["transport"], transport)
                self.assertNotIn(value["encryption_key"], output.getvalue())
                self.assertNotIn(value["document_url"], output.getvalue())

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
        self.assertLess(script.index('[ -f /.dockerenv ]'), script.index("exec /usr/local/bin/openflux"))
        self.assertLess(script.index('[ "$namespace" != "$OPENFLUX_HOST_NETNS" ]'), script.index("exec /usr/local/bin/openflux"))
        self.assertIn("--config /run/secrets/server.json >/dev/null 2>&1", script)
        self.assertNotIn("--url", script)
        self.assertNotIn("--encryption-key", script)
        self.assertNotIn("|| true", script)
        self.assertNotIn("iptables", script)
        self.assertNotIn("UVPN_OFLUX_RST", script)
        self.assertNotIn("iptables", (manage.SCRIPT_DIR / "Dockerfile").read_text(encoding="utf-8"))


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
        self.assertNotIn("--cap-add", create)
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

    def test_browser_stdio_prepares_only_the_new_owned_native_container(self):
        self.manager.install(self.args)
        self.fake.commands.clear()
        state = self.manager.run(bootstrap_stdio=True, start=False)
        create = next(argv for argv in self.fake.commands if argv[3:5] == ["container", "create"])
        self.assertIn("--interactive", create)
        self.assertIn("OPENFLUX_BROWSER_BOOTSTRAP=stdio", create)
        self.assertEqual(create[create.index("--log-driver") + 1], "none")
        self.assertEqual(create[create.index("--restart") + 1], "no")
        self.assertTrue(state["browser_bootstrap_stdio"])
        self.assertFalse(any(argv[3:5] == ["container", "start"] for argv in self.fake.commands))
        self.assertTrue((self.root / "server.json").exists())

    def test_deferred_nonbrowser_start_is_rejected_before_preflight(self):
        with patch.object(self.manager, "preflight") as preflight, self.assertRaises(manage.DeploymentError):
            self.manager.run(start=False)
        preflight.assert_not_called()

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

    def test_legacy_state_is_accepted_only_by_default_instance(self):
        self.install_and_run()
        state = self.manager.load_state()
        state.pop("instance")
        self.manager.write_state(state)
        before = (self.root / "state.json").read_bytes()
        self.assertEqual(self.manager.load_state(), state)
        second = manage.Manager(self.root, self.fake, instance="phone")
        self.fake.commands.clear()
        with self.assertRaisesRegex(manage.DeploymentError, "different instance"):
            second.stop()
        self.assertEqual(self.fake.commands, [])
        self.assertEqual((self.root / "state.json").read_bytes(), before)
        self.assertTrue(self.fake.containers[manage.NAME]["State"]["Running"])

    def test_named_state_cannot_be_used_by_another_instance(self):
        manager = manage.Manager(self.root, self.fake, instance="phone")
        manager.install(self.args)
        manager.run()
        state_before = (self.root / "state.json").read_bytes()
        for instance in ("default", "tablet"):
            wrong = manage.Manager(self.root, self.fake, instance=instance)
            for action in ("check", "run", "stop", "remove"):
                self.fake.commands.clear()
                with self.subTest(instance=instance, action=action), self.assertRaisesRegex(manage.DeploymentError, "different instance"):
                    getattr(wrong, action)(self.args) if action == "check" else getattr(wrong, action)()
                self.assertEqual(self.fake.commands, [])
                self.assertEqual((self.root / "state.json").read_bytes(), state_before)
        self.assertTrue(self.fake.containers[manager.name]["State"]["Running"])

    def test_two_instances_lifecycle_preserves_original_resources_and_files(self):
        self.install_and_run()
        original_state = self.manager.load_state()

        def original_snapshot():
            return {
                "files": {path.name: path.read_bytes() for path in self.root.iterdir() if path.is_file()},
                "container": copy.deepcopy(self.fake.containers[manage.NAME]),
                "network": copy.deepcopy(self.fake.networks[manage.NETWORK]),
                "image": copy.deepcopy(self.fake.images[original_state["image_id"]]),
                "runtime": copy.deepcopy(self.fake.images[RUNTIME_ID]),
                "retained_tags": {tag: image for tag, image in self.fake.tags.items()
                                  if image in (original_state["image_id"], RUNTIME_ID)},
            }

        original = original_snapshot()
        second_root = self.directory / "phone-installation"
        second = manage.Manager(second_root, self.fake, instance="phone")
        second_config = self.directory / "phone-server.json"
        second_config.write_text(json.dumps(dict(config(), document_url="https://disk.yandex.ru/i/second-test-placeholder",
                                                 encryption_key="cd" * 32)), encoding="utf-8")
        second_args = SimpleNamespace(**dict(vars(self.args), config=second_config, subnet="172.30.252.0/28"))

        second.install(second_args)
        second.run()
        second_state = second.load_state()
        self.assertEqual(second_state["instance"], "phone")
        for identity in ("owner", "image_id", "container_id", "network_id"):
            self.assertNotEqual(original_state[identity], second_state[identity])
        self.assertEqual(original_snapshot(), original)
        self.assertTrue(all(container["State"]["Running"] for container in self.fake.containers.values()))
        create = next(argv for argv in reversed(self.fake.commands) if argv[3:5] == ["container", "create"])
        self.assertEqual(create[create.index("--name") + 1], second.name)
        self.assertEqual(create[create.index("--network") + 1], second.network)
        self.assertIn(f"src={second_root / 'server.json'},", create[create.index("--mount") + 1])
        self.assertNotEqual((self.root / "server.json").read_bytes(), (second_root / "server.json").read_bytes())

        self.fake.commands.clear()
        second.stop()
        self.assertEqual(original_snapshot(), original)
        self.assertEqual(set(self.fake.containers), {manage.NAME})
        self.assertEqual(set(self.fake.networks), {manage.NETWORK})
        for command in self.mutations():
            self.assertIn(command[-1], (second_state["container_id"], second_state["network_id"]))
        self.assertTrue((second_root / "server.json").exists())

        second.run()
        self.assertEqual(original_snapshot(), original)
        second.remove()
        self.assertEqual(original_snapshot(), original)
        self.assertFalse(second_root.exists())
        self.assertTrue(second_config.exists())
        self.assertNotIn(second_state["image_id"], self.fake.images)
        self.assertEqual(set(self.fake.containers), {manage.NAME})
        self.assertEqual(set(self.fake.networks), {manage.NETWORK})
        self.assertTrue(all(not tag.startswith(second.name + "-runtime:") for tag in self.fake.tags))


if __name__ == "__main__":
    unittest.main()
