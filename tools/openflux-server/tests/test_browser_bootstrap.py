import asyncio
import contextlib
import copy
import importlib.util
import io
import json
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import unittest
from types import SimpleNamespace
from unittest.mock import AsyncMock, Mock, patch


HERE = Path(__file__).resolve().parents[1]


def load(name, filename):
    specification = importlib.util.spec_from_file_location(name, HERE / filename)
    module = importlib.util.module_from_spec(specification)
    specification.loader.exec_module(module)
    return module


supervisor = load("browser_supervisor_tests", "browser_supervisor.py")
worker = supervisor.worker
manage = supervisor.manage
REQUEST_ID = "a" * 32
DOCUMENT = "https://docs.yandex.ru/docs/view?public-test-placeholder"
IMAGE = "sha256:" + "c" * 64
STATE = {"owner": "a" * 64, "container_id": "b" * 64, "image_id": "sha256:" + "d" * 64}


def cookie(**changes):
    return dict({"name": "anonymous_check", "value": "public-test-placeholder", "domain": ".yandex.ru",
                 "path": "/", "secure": True, "expires": 0}, **changes)


def response(cookies=None, **changes):
    return dict({"id": REQUEST_ID, "cookies": [cookie()] if cookies is None else cookies}, **changes)


class ContractTests(unittest.TestCase):
    def test_request_round_trip_and_no_credentials(self):
        request = {"id": REQUEST_ID, "document_url": DOCUMENT}
        self.assertEqual(worker.validate_request(worker.decode_message(worker.encode_message(request))), request)
        for additional in ("encryption_key", "password", "storage_state", "user_data_dir"):
            with self.subTest(additional=additional), self.assertRaises(worker.VerificationError):
                worker.validate_request(dict(request, **{additional: "SECRET"}))

    def test_url_boundary_tls_account_and_navigation_restrictions(self):
        for value in (DOCUMENT, "https://disk.yandex.ru/i/public-test-placeholder", "https://docs.yandex.ru/checkcaptcha"):
            self.assertTrue(worker.allowed_url(value, navigation=True))
        for value in ("https://static.yastatic.net/script.js", "https://files.yandex.net/file", "https://assets.yandexcloud.net/item"):
            self.assertTrue(worker.allowed_url(value))
            self.assertFalse(worker.allowed_url(value, navigation=True))
        for value in ("http://docs.yandex.ru/i/test", "https://docs.yandex.ru.evil.test/i/test",
                      "https://evil-yandex.ru/i/test", "https://user@docs.yandex.ru/i/test",
                      "https://docs.yandex.ru:8443/i/test", "https://127.0.0.1/i/test",
                      "file:///etc/passwd", "https://passport.yandex.ru/auth", "https://docs.yandex.ru/nested/login",
                      "https://docs.yandex.ru/%6fauth/authorize", "https://docs.yandex.ru/a\nb"):
            with self.subTest(value=value):
                self.assertFalse(worker.allowed_url(value))
                self.assertFalse(worker.allowed_url(value, navigation=True))

    def test_duplicate_oversized_and_bad_id_requests_fail_without_contents(self):
        for raw in (b'{"id":"a","id":"b"}', b"x" * (worker.MAX_MESSAGE + 1), b"{SECRET", b"\xff"):
            with self.assertRaises(worker.VerificationError) as raised:
                worker.decode_message(raw)
            self.assertNotIn("SECRET", str(raised.exception))
        for request_id in ("", "a" * 31, "a" * 33, "A" * 32, "../secret", None, 5):
            with self.assertRaises(worker.VerificationError):
                worker.validate_request({"id": request_id, "document_url": DOCUMENT})

    def test_all_applicable_anonymous_cookies_are_promoted_to_secure(self):
        request = {"id": REQUEST_ID, "document_url": DOCUMENT}
        result = worker.export_cookies([cookie(secure=False, expires=-1)], request)
        self.assertEqual(result, response())
        self.assertNotIn("document_url", result)

    def test_response_rejects_auth_scope_controls_expiration_duplicates(self):
        mutations = [
            {"name": "Session_id"}, {"name": "SESSIONID2"}, {"name": "yandex_login"}, {"name": "bad name"},
            {"value": "private\r\nHeader: value"}, {"value": "x;y"}, {"domain": ".ru"},
            {"domain": "yandex.ru.evil.test"}, {"path": "/docs"}, {"secure": False},
            {"expires": -1}, {"expires": True}, {"expires": 1.2}, {"expires": 2 ** 63},
            {"value": "x" * (worker.MAX_COOKIE + 1)}, {"name": "n" * 129}, {"extra": "SECRET"},
        ]
        for change in mutations:
            with self.subTest(change=list(change)), self.assertRaises(worker.VerificationError):
                worker.validate_response(response([cookie(**change)]), REQUEST_ID, DOCUMENT)
        for value in (response([]), response([cookie(), cookie()]), response(id="b" * 32),
                      response(extra="SECRET"), {"id": REQUEST_ID, "error": "raw SECRET"},
                      response([cookie(name="c" + str(index)) for index in range(65)])):
            with self.assertRaises(worker.VerificationError):
                worker.validate_response(value, REQUEST_ID, DOCUMENT)

    def test_export_auth_or_narrow_path_fails_closed(self):
        for value in (cookie(name="Session_id"), cookie(path="/docs")):
            with self.assertRaises(worker.VerificationError):
                worker.export_cookies([value], {"id": REQUEST_ID, "document_url": DOCUMENT})

    def test_total_message_limit_and_typed_failure(self):
        oversized = [cookie(name="c" + str(index), value="x" * 3500) for index in range(10)]
        with self.assertRaises(worker.VerificationError):
            worker.validate_response(response(oversized), REQUEST_ID, DOCUMENT)
        self.assertEqual(worker.validate_response(supervisor.failure(REQUEST_ID), REQUEST_ID, DOCUMENT),
                         {"id": REQUEST_ID, "error": "verification_failed"})

    def test_cookie_name_value_and_aggregate_exact_native_boundaries(self):
        accepted = response([cookie(name="n" * 128, value="v" * 4096)])
        self.assertEqual(worker.validate_response(accepted, REQUEST_ID, DOCUMENT), accepted)
        self.assertEqual(worker.validate_response(response([cookie(value="space and,comma")]), REQUEST_ID, DOCUMENT),
                         response([cookie(value="space and,comma")]))
        at_limit = response([cookie(name="c" + str(index), value="v" * 4083) for index in range(6)])
        self.assertEqual(worker.validate_response(at_limit, REQUEST_ID, DOCUMENT), at_limit)
        above_limit = copy.deepcopy(at_limit)
        above_limit["cookies"][-1]["value"] += "v"
        with self.assertRaises(worker.VerificationError):
            worker.validate_response(above_limit, REQUEST_ID, DOCUMENT)

    def test_domain_variants_and_duplicate_identity_match_native_host_only_rules(self):
        for domain in ("", "docs.yandex.ru", ".docs.yandex.ru", "yandex.ru", ".yandex.ru", "DOCS.YANDEX.RU"):
            value = response([cookie(domain=domain)])
            self.assertEqual(worker.validate_response(value, REQUEST_ID, DOCUMENT), value)
        for domains in (("docs.yandex.ru", ""), ("yandex.ru", ".yandex.ru"), (".DOCS.YANDEX.RU", ".docs.yandex.ru")):
            with self.assertRaises(worker.VerificationError):
                worker.validate_response(response([cookie(name="Mixed", domain=domains[0]),
                                                   cookie(name="mixed", domain=domains[1])]), REQUEST_ID, DOCUMENT)
        distinct = response([cookie(name="Mixed", domain="docs.yandex.ru"),
                             cookie(name="mixed", domain=".docs.yandex.ru")])
        self.assertEqual(worker.validate_response(distinct, REQUEST_ID, DOCUMENT), distinct)
        host_only_limit = response([cookie(name="c" + str(index), value="v" * 4093, domain="docs.yandex.ru")
                                    for index in range(6)])
        self.assertEqual(worker.validate_response(host_only_limit, REQUEST_ID, DOCUMENT), host_only_limit)

    def test_refresh_budget_rolls_instead_of_lifetime_cap(self):
        clock = [0]
        budget = supervisor.RefreshBudget(lambda: clock[0])
        self.assertTrue(budget.allow("a" * 32))
        self.assertFalse(budget.allow("a" * 32))
        clock[0] = 60
        self.assertTrue(budget.allow("b" * 32))
        self.assertFalse(budget.allow("c" * 32))
        clock[0] = 300
        self.assertTrue(budget.allow("c" * 32))
        clock[0] = 360
        self.assertTrue(budget.allow("d" * 32))

    def test_browser_flags_are_separate_unprivileged_pipe_only_and_same_egress(self):
        args = supervisor.browser_arguments(STATE, "test-verify", IMAGE, Path("/root/reviewed-seccomp.json"))
        self.assertEqual(args[args.index("--network") + 1], "container:" + STATE["container_id"])
        self.assertEqual(args[args.index("--user") + 1], "10001:10001")
        self.assertEqual(args[args.index("--cap-drop") + 1], "ALL")
        self.assertEqual(args[args.index("--log-driver") + 1], "none")
        self.assertEqual(args[args.index("--restart") + 1], "no")
        for value in ("--read-only", "--pull=never", "--init", "no-new-privileges:true", "--memory", "--cpus", "--pids-limit"):
            self.assertIn(value, args)
        for value in ("--cap-add", "--privileged", "--mount", "--volume", "--publish", "--ipc", "--pid",
                      "--no-sandbox", "seccomp=unconfined", "apparmor=unconfined", DOCUMENT):
            self.assertNotIn(value, args)

    def test_bridge_keeps_waiting_after_failure_and_does_not_log_private_text(self):
        manager = SimpleNamespace(name="unifiedvpn-openflux-browsercheck")
        instance = supervisor.Supervisor(manager, IMAGE, Path("/root/seccomp"), "f" * 64)
        ids = (REQUEST_ID, "b" * 32)
        success = {"id": ids[1], "cookies": [cookie()]}
        instance.verify = Mock(side_effect=[supervisor.failure(ids[0]), success])
        source = io.BytesIO(b"SECRET private native error\nOPENFLUX_SERVER_WAITING\n" +
                            b"".join(b"OPENFLUX_BROWSER_VERIFY " + value.encode() + b"\n" for value in ids))
        destination, visible = io.BytesIO(), io.StringIO()
        with contextlib.redirect_stdout(visible):
            instance.bridge(source, destination, STATE, DOCUMENT)
        replies = [json.loads(line) for line in destination.getvalue().splitlines()]
        self.assertEqual(replies, [supervisor.failure(ids[0]), success])
        self.assertEqual(instance.verify.call_count, 2)
        self.assertEqual(visible.getvalue(), "OPENFLUX_SERVER_WAITING\n")

    def test_production_names_refused_before_host_or_docker_calls(self):
        for name in ("default", "phone2", "releasewin", "releaseandroid", "releasetest"):
            manager = SimpleNamespace(instance=name, name="name", preflight=Mock())
            instance = supervisor.Supervisor(manager, IMAGE, Path("/root/seccomp"), "f" * 64)
            with self.assertRaises(manage.DeploymentError):
                instance.preflight()
            manager.preflight.assert_not_called()

    def test_cli_errors_never_echo_private_misplaced_arguments(self):
        visible = io.StringIO()
        with contextlib.redirect_stderr(visible), self.assertRaises(SystemExit):
            supervisor.main(["check", "--PRIVATE_DOCUMENT_URL_SECRET"])
        self.assertNotIn("PRIVATE_DOCUMENT_URL_SECRET", visible.getvalue())

    def test_source_loader_never_uses_existing_bytecode_cache(self):
        with patch.object(importlib.util, "spec_from_file_location", wraps=importlib.util.spec_from_file_location) as specification:
            loaded = supervisor.load("fresh_worker_for_test", "browser_worker.py")
        self.assertEqual(loaded.MAX_MESSAGE, 32768)
        self.assertEqual(specification.call_count, 1)

    @unittest.skipUnless(shutil.which("node"), "Optional local JS engine is unavailable")
    def test_all_platforms_accept_classic_editable_or_volga_bootstrap_shapes(self):
        valid = {"officeActionData": {"balancer_url": "https://docs.yandex.ru/public-placeholder",
                                     "editor_config": {"token": "public-placeholder",
                                                       "document": {"key": "public-placeholder", "permissions": {"edit": True}}}}}
        readonly, missing_token = copy.deepcopy(valid), copy.deepcopy(valid)
        readonly["officeActionData"]["editor_config"]["document"]["permissions"]["edit"] = False
        del missing_token["officeActionData"]["editor_config"]["token"]
        fixtures = [{"text": json.dumps(value), "count": 1} for value in (valid, readonly, missing_token, {}, [])]
        expected = [True, False, False, False, False]

        def add(value, accepted, count=1):
            fixtures.append({"text": json.dumps(value), "count": count})
            expected.append(accepted)

        for count in (0, 2):
            add(valid, False, count)
        fixtures.extend(({"text": "{not-json", "count": 1}, {"text": "x" * 2097153, "count": 1}))
        expected.extend((False, False))

        # Volga readiness only releases anonymous cookies; it never grants native write access.
        volga = {"officeActionData": {"office_online_editor_type": "volga", "access_token": "public-placeholder",
                                      "action_url": "https://volga.yandex.ru/auth/initial",
                                      "editor_config": {"documentType": "text"}}}
        add(volga, True)
        for location in ("root", "editorParams"):
            for rights, accepted in ((["read", "write"], True), (["read"], False), ([], False)):
                item = copy.deepcopy(volga)
                (item if location == "root" else item.setdefault(location, {}))["rights"] = rights
                add(item, accepted)
        item = copy.deepcopy(volga)
        item["officeActionData"]["editor_config"]["document"] = {"permissions": {"edit": False}}
        add(item, False)
        for field, value in (("office_online_editor_type", "yandex"), ("access_token", ""), ("access_token", 10),
                             ("action_url", ""), ("action_url", None)):
            item = copy.deepcopy(volga)
            item["officeActionData"][field] = value
            add(item, False)
        for document_type in ("spreadsheet", "presentation", "", None):
            item = copy.deepcopy(volga)
            item["officeActionData"]["editor_config"]["documentType"] = document_type
            add(item, False)
        for action_url, accepted in (
                ("https://volga.yandex.ru:443/auth/initial", True),
                ("http://volga.yandex.ru/auth/initial", False),
                ("https://volga.yandex.ru.evil.test/auth/initial", False),
                ("https://evilvolga.yandex.ru/auth/initial", False),
                ("https://docs.yandex.ru/auth/initial", False),
                ("https://volga.yandex.ru:8443/auth/initial", False),
                ("https://user@volga.yandex.ru/auth/initial", False),
                ("https://user:password@volga.yandex.ru/auth/initial", False),
                ("https://volga.yandex.ru/auth/initial#fragment", False),
                (" https://volga.yandex.ru/auth/initial", False),
                ("https://volga.yandex.ru/auth/ini\ttial", False),
                ("//volga.yandex.ru/auth/initial", False),
                ("https://volga.yandex.ru/" + "x" * 8192, False)):
            item = copy.deepcopy(volga)
            item["officeActionData"]["action_url"] = action_url
            add(item, accepted)
        # A Volga marker with only a classic configuration cannot bypass the Volga checks.
        item = copy.deepcopy(valid)
        item["officeActionData"]["office_online_editor_type"] = "volga"
        add(item, False)

        project = HERE.parents[1]
        scripts = {"server": worker.READY_SCRIPT}
        for platform, source, pattern in (
                ("windows", "desktopApp/src/windowsBrowserHelper/BrowserHelper.cs", r'BootstrapReadyScript = @"(.*?)";'),
                ("android", "sharedUI/src/androidMain/kotlin/org/olcbox/app/vpn/service/OpenFluxBrowserPolicy.kt",
                 r'OPENFLUX_BROWSER_BOOTSTRAP_READY_SCRIPT = """(.*?)"""\.trimIndent\(\)')):
            source_path = project / source
            if not source_path.is_file():
                scripts[platform] = None
                continue
            match = re.search(pattern, source_path.read_text(encoding="utf-8"), re.DOTALL)
            self.assertIsNotNone(match)
            scripts[platform] = match.group(1)
        script = ("const fs=require('fs'); const input=JSON.parse(fs.readFileSync(0,'utf8'));"
                  "const output=input.fixtures.map(f=>{const document={querySelectorAll:()=>"
                  "Array.from({length:f.count},()=>({textContent:f.text}))};"
                  "return new Function('document','return ('+input.script+')()')(document);});"
                  "process.stdout.write(JSON.stringify(output));")
        for platform, ready_script in scripts.items():
            with self.subTest(platform=platform):
                if ready_script is None:
                    self.skipTest(platform + " source is not included in the standalone server tools")
                result = subprocess.run([shutil.which("node"), "-e", script],
                                        input=json.dumps({"script": ready_script, "fixtures": fixtures}),
                                        text=True, capture_output=True, timeout=10, check=True)
                self.assertEqual(json.loads(result.stdout), expected)


class BrowserApiTests(unittest.IsolatedAsyncioTestCase):
    async def test_sandbox_strict_tls_ephemeral_context_and_cleanup(self):
        page = SimpleNamespace(url=DOCUMENT, goto=AsyncMock(), wait_for_function=AsyncMock())
        context = SimpleNamespace(route=AsyncMock(), route_web_socket=AsyncMock(), new_page=AsyncMock(return_value=page),
                                  cookies=AsyncMock(return_value=[cookie(secure=False)]))
        browser = SimpleNamespace(new_context=AsyncMock(return_value=context), close=AsyncMock())
        chromium = SimpleNamespace(launch=AsyncMock(return_value=browser))
        class Factory:
            async def __aenter__(self):
                return SimpleNamespace(chromium=chromium)
            async def __aexit__(self, *_):
                pass
        result = await worker.verify({"id": REQUEST_ID, "document_url": DOCUMENT}, Factory)
        self.assertEqual(result, response())
        kwargs = chromium.launch.call_args.kwargs
        self.assertIs(kwargs["chromium_sandbox"], True)
        self.assertEqual(kwargs["args"], ["--no-proxy-server"])
        self.assertNotIn("user_data_dir", kwargs)
        self.assertFalse(browser.new_context.call_args.kwargs["ignore_https_errors"])
        self.assertFalse(browser.new_context.call_args.kwargs["accept_downloads"])
        self.assertEqual(browser.new_context.call_args.kwargs["service_workers"], "block")
        self.assertIn("doc.permissions.edit === true", page.wait_for_function.call_args.args[0])
        browser.close.assert_awaited_once()
        route = SimpleNamespace(request=SimpleNamespace(url="https://passport.yandex.ru/login",
                                                       frame=SimpleNamespace(parent_frame=None), is_navigation_request=lambda: True),
                                continue_=AsyncMock(), abort=AsyncMock())
        await context.route.call_args.args[1](route)
        route.abort.assert_awaited_once_with("blockedbyclient")
        route.continue_.assert_not_awaited()
        websocket = SimpleNamespace(close=AsyncMock(), connect_to_server=AsyncMock())
        context.route_web_socket.assert_awaited_once()
        self.assertEqual(context.route_web_socket.call_args.args[0], "**/*")
        await context.route_web_socket.call_args.args[1](websocket)
        websocket.close.assert_awaited_once_with(code=1008, reason="bootstrap_only")
        websocket.connect_to_server.assert_not_awaited()
        for main_frame in (True, False):
            route = SimpleNamespace(request=SimpleNamespace(
                url="https://static.yastatic.net/captcha/frame",
                frame=SimpleNamespace(parent_frame=None if main_frame else object()),
                is_navigation_request=lambda: True), continue_=AsyncMock(), abort=AsyncMock())
            await context.route.call_args.args[1](route)
            if main_frame:
                route.abort.assert_awaited_once_with("blockedbyclient")
                route.continue_.assert_not_awaited()
            else:
                route.continue_.assert_awaited_once()
                route.abort.assert_not_awaited()


class OwnershipTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.manager = SimpleNamespace(name="test-browser", root=Path(self.directory.name), inspect=Mock(), docker=Mock())
        self.instance = supervisor.Supervisor(self.manager, IMAGE, Path("/root/seccomp"), "f" * 64)
        self.resource = {
            "Id": "e" * 64, "Image": IMAGE,
            "Config": {"Labels": {manage.LABEL + ".owner": STATE["owner"], manage.LABEL + ".managed": "1", supervisor.LABEL: "1"}},
            "HostConfig": {"NetworkMode": "container:" + STATE["container_id"]},
            "State": {"Running": True},
        }
        self.manager.inspect.return_value = self.resource
        self.receipt = self.manager.root / supervisor.RECEIPT
        self.receipt.write_text(json.dumps({"owner": STATE["owner"], "container_id": self.resource["Id"],
                                           "native_container_id": STATE["container_id"], "image_id": IMAGE}))
        permissions = patch.object(manage, "require_secure_file")
        permissions.start()
        self.addCleanup(permissions.stop)

    def test_changed_owner_image_or_network_never_stops_container(self):
        mutations = (
            lambda item: item["Config"]["Labels"].update({manage.LABEL + ".owner": "f" * 64}),
            lambda item: item.update(Image="sha256:" + "f" * 64),
            lambda item: item["HostConfig"].update(NetworkMode="host"),
        )
        for mutate in mutations:
            changed = copy.deepcopy(self.resource)
            mutate(changed)
            self.manager.inspect.return_value = changed
            with self.assertRaises(manage.DeploymentError):
                self.instance.cleanup_browser(STATE)
            self.manager.docker.assert_not_called()

    def test_matching_cleanup_uses_only_verified_id(self):
        self.instance.cleanup_browser(STATE)
        commands = self.manager.docker.call_args_list
        self.assertEqual([call.args[:2] for call in commands], [("container", "stop"), ("container", "rm")])
        self.assertTrue(all(call.args[-1] == self.resource["Id"] for call in commands))

    def test_cleanup_failure_blocks_new_browsers_not_native_restart(self):
        self.receipt.unlink()
        self.manager.owned = Mock(return_value={"State": {"Running": True}})
        self.manager.inspect.return_value = None
        self.manager.docker.side_effect = manage.DeploymentError("safe failure")
        self.instance.cleanup_browser = Mock(side_effect=manage.DeploymentError("safe cleanup failure"))
        with contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(self.instance.verify(STATE, DOCUMENT, REQUEST_ID), supervisor.failure(REQUEST_ID))
        self.assertTrue(self.instance.cleanup_failed)
        self.manager.docker.reset_mock()
        self.assertEqual(self.instance.verify(STATE, DOCUMENT, "b" * 32), supervisor.failure("b" * 32))
        self.manager.docker.assert_not_called()

    def test_missing_or_changed_browser_id_receipt_never_adopts_a_container(self):
        self.receipt.unlink()
        with self.assertRaises(manage.DeploymentError):
            self.instance.cleanup_browser(STATE)
        self.manager.docker.assert_not_called()
        self.receipt.write_text(json.dumps({"owner": STATE["owner"], "container_id": "f" * 64,
                                           "native_container_id": STATE["container_id"], "image_id": IMAGE}))
        with self.assertRaises(manage.DeploymentError):
            self.instance.cleanup_browser(STATE)
        self.manager.docker.assert_not_called()


class PreflightTests(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.root = Path(directory.name)
        self.provenance = self.root / "provenance.json"
        self.provenance.write_text(json.dumps({"wrapper_version": manage.VERSION}), encoding="ascii")
        self.seccomp = self.root / "seccomp.json"
        self.seccomp.write_text(json.dumps({"defaultAction": "SCMP_ACT_ERRNO",
                                           "syscalls": [{"names": ["read", "clone", "setns", "unshare"], "action": "SCMP_ACT_ALLOW"}]}))
        self.image = {"Id": IMAGE, "Os": "linux", "Architecture": "amd64", "RepoTags": ["reviewed-browser:retained"],
                      "Config": {"User": "10001:10001", "Labels": {manage.LABEL + ".browser-contract": "2"}}}
        self.manager = SimpleNamespace(instance="browsercheck", name="test-browser", root=self.root,
                                       preflight=Mock(), load_state=Mock(return_value=STATE),
                                       owned=Mock(return_value={"Id": STATE["image_id"]}),
                                       inspect=Mock(return_value=self.image), docker=Mock())
        self.instance = supervisor.Supervisor(self.manager, IMAGE, self.seccomp, manage.sha256_file(self.seccomp))
        self.permissions = patch.object(manage, "require_secure_file")
        self.config = patch.object(manage, "read_config", return_value={"document_url": DOCUMENT})
        self.permissions.start()
        self.config.start()
        self.addCleanup(self.permissions.stop)
        self.addCleanup(self.config.stop)

    def test_preflight_only_inspects_and_accepts_exact_new_provenance(self):
        self.assertEqual(self.instance.preflight(), (STATE, {"document_url": DOCUMENT}))
        self.manager.docker.assert_not_called()

    def test_old_wrapper_rejected_before_browser_image_inspection(self):
        for version in (1, 2, 3):
            with self.subTest(version=version):
                name, current, metadata = manage.VERSION.split(" ", 2)
                self.assertEqual(name, "unified-openflux")
                self.assertNotEqual(current, str(version))
                old = " ".join((name, str(version), metadata))
                self.assertNotEqual(old, manage.VERSION)
                self.provenance.write_text(json.dumps({"wrapper_version": old}))
                with self.assertRaises(manage.DeploymentError):
                    self.instance.preflight()
                self.manager.inspect.assert_not_called()
                self.manager.docker.assert_not_called()

    def test_unprivileged_image_identity_contract_and_no_volume_requirements(self):
        mutations = [
            lambda image: image.update(Id="sha256:" + "f" * 64),
            lambda image: image.update(RepoTags=[]),
            lambda image: image["Config"].update(User="0:0"),
            lambda image: image["Config"].update(Volumes={"/home": {}}),
            lambda image: image["Config"].update(OnBuild=["RUN arbitrary"]),
            lambda image: image["Config"]["Labels"].update({manage.LABEL + ".browser-contract": "1"}),
        ]
        for mutate in mutations:
            image = copy.deepcopy(self.image)
            mutate(image)
            self.manager.inspect.return_value = image
            with self.assertRaises(manage.DeploymentError):
                self.instance.preflight()
            self.manager.docker.assert_not_called()

    def test_seccomp_hash_and_default_allow_rejected(self):
        self.instance.seccomp_hash = "0" * 64
        with self.assertRaises(manage.DeploymentError):
            self.instance.preflight()
        self.seccomp.write_text(json.dumps({"defaultAction": "SCMP_ACT_ALLOW", "syscalls": []}))
        self.instance.seccomp_hash = manage.sha256_file(self.seccomp)
        with self.assertRaises(manage.DeploymentError):
            self.instance.preflight()
        self.manager.docker.assert_not_called()


if __name__ == "__main__":
    unittest.main()
