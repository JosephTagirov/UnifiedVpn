import base64
import contextlib
import importlib.util
import io
import http.client
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import Mock, patch
import urllib.error
import urllib.request


SPEC = importlib.util.spec_from_file_location("openflux_prepare_profile", Path(__file__).resolve().parents[1] / "prepare_profile.py")
prepare = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(prepare)
LOCK_DOWN_WINDOWS = prepare.lock_down_windows
DOCUMENT = "https://disk.yandex.ru/i/PUBLIC_TEST_PLACEHOLDER?encoded=%2Fvalue%2Bvalue"


def editor_config(edit=True):
    return {"privateUnknownName": "DO_NOT_PRINT", "officeActionData": {"balancer_url": "https://DO_NOT_PRINT.invalid",
            "editor_config": {"token": "DO_NOT_PRINT_TOKEN", "document": {"key": "DO_NOT_PRINT_KEY", "title": "DO_NOT_PRINT_TITLE",
                                                                          "permissions": {"edit": edit}}}}}


def html(config):
    return "<html><title>DO_NOT_PRINT_TITLE</title><script type='application/json'\n id='client-config'>\n" + json.dumps(config, indent=2) + "\n</script></html>"


class FakeResponse:
    def __init__(self, body, status=200, headers=None):
        self.body = body
        self.status = status
        self.headers = headers or {}
        self.read_sizes = []
        self.closed = False

    def __enter__(self):
        return self

    def __exit__(self, *args):
        self.closed = True

    def read(self, count):
        self.read_sizes.append(count)
        return self.body[:count]


class ProfileTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.directory = Path(self.temporary.name)
        self.document_file = self.directory / "document.txt"
        self.document_file.write_text(" \n" + DOCUMENT + "\n", encoding="utf-8")
        os.chmod(self.document_file, 0o600)
        lock = patch.object(prepare, "lock_down_windows")
        self.lock = lock.start()
        self.addCleanup(lock.stop)

    def test_generation_matches_profile_and_native_schemas(self):
        destination = self.directory / "new-profile"
        with patch.object(prepare.secrets, "token_hex", return_value="ab" * 32) as random_key:
            result = prepare.generate(self.document_file, destination, 19181)
        random_key.assert_called_once_with(32)
        self.assertEqual(set(path.name for path in destination.iterdir()), set(prepare.OUTPUT_FILES))
        server = json.loads((destination / "server.json").read_text())
        client = json.loads((destination / "client.json").read_text())
        profile = json.loads((destination / "profile.json").read_text())
        self.assertEqual(set(profile), {"document_url", "encryption_key", "version", "transport"})
        self.assertEqual(set(server), set(profile) | {"mode", "handshake_timeout_seconds"})
        self.assertEqual(server["mode"], "server")
        self.assertEqual(client["mode"], "client")
        self.assertEqual(client["socks5"], "127.0.0.1:19181")
        self.assertEqual(client["dns_server"], "1.1.1.1:53")
        for config in (profile, server, client):
            self.assertEqual(config["document_url"], DOCUMENT)
            self.assertEqual(config["encryption_key"], "ab" * 32)
            self.assertEqual(config["version"], 1)
            self.assertEqual(config["transport"], "yandex")
        encoded = (destination / "profile.uri").read_text().strip().removeprefix("openflux://")
        self.assertNotIn("=", encoded)
        self.assertEqual(json.loads(base64.urlsafe_b64decode(encoded + "=" * (-len(encoded) % 4))), profile)
        self.assertNotIn(DOCUMENT, json.dumps(result))
        self.assertNotIn("ab" * 32, json.dumps(result))

    def test_existing_directory_prevents_key_rotation_or_overwrite(self):
        destination = self.directory / "existing"
        destination.mkdir()
        existing = destination / "server.json"
        existing.write_text("DO_NOT_CHANGE_EXISTING_KEY")
        with patch.object(prepare.secrets, "token_hex") as random_key, self.assertRaises(prepare.PreparationError):
            prepare.generate(self.document_file, destination, 19181)
        random_key.assert_not_called()
        self.assertEqual(existing.read_text(), "DO_NOT_CHANGE_EXISTING_KEY")

    def test_reserved_or_invalid_socks_port_is_rejected_before_files(self):
        for port in (10808, 0, -1, 65536, True):
            destination = self.directory / ("port-" + str(port))
            with self.subTest(port=port), self.assertRaises(prepare.PreparationError):
                prepare.generate(self.document_file, destination, port)
            self.assertFalse(destination.exists())

    def test_url_is_not_decoded_or_rewritten(self):
        value = "HTTPS://DOCS.YANDEX.RU:443/docs/view?url=%2FTEST%2F&order=b%2Ba"
        self.document_file.write_text("\ufeff \n" + value + " \n", encoding="utf-8")
        self.assertEqual(prepare.read_document(self.document_file), value)

    def test_url_restrictions_and_multiple_links(self):
        for value in ("http://disk.yandex.ru/i/SECRET", "https://passport.yandex.ru/i/SECRET", "https://user@docs.yandex.ru/i/SECRET",
                      "https://docs.yandex.ru:444/i/SECRET", "https://docs.yandex.ru/i/SECRET#fragment", "https://docs.yandex.ru/",
                      "https://docs.yandex.ru/i/SECRET#",
                      DOCUMENT + "\n" + DOCUMENT, "https://docs.yandex.ru/i/SECRET\u00a0more"):
            with self.subTest(value=value[:30]), self.assertRaises(prepare.PreparationError) as error:
                prepare.validate_document_url(value)
            self.assertNotIn("SECRET", str(error.exception))

    def test_private_file_writer_does_not_overwrite(self):
        path = self.directory / "private-output"
        prepare.write_private(path, b"original")
        with self.assertRaises(FileExistsError):
            prepare.write_private(path, b"replacement")
        self.assertEqual(path.read_bytes(), b"original")

    def test_permission_failure_happens_before_key_creation(self):
        self.lock.side_effect = prepare.PreparationError("permission failure")
        with patch.object(prepare.os, "name", "nt"), patch.object(prepare.secrets, "token_hex") as random_key:
            with self.assertRaises(prepare.PreparationError):
                prepare.generate(self.document_file, self.directory / "locked", 19181)
        random_key.assert_not_called()

    def test_windows_acl_uses_current_sid_and_system_only(self):
        # Call the implementation outside the fixture's mock.
        with patch.object(subprocess, "run") as run:
            run.side_effect = [subprocess.CompletedProcess([], 0, b'"example\\user","S-1-5-21-123-1001"\r\n'),
                               subprocess.CompletedProcess([], 0, b"")]
            LOCK_DOWN_WINDOWS(self.directory)
            command = run.call_args_list[1].args[0]
            self.assertIn("/inheritance:r", command)
            self.assertIn("*S-1-5-21-123-1001:(OI)(CI)F", command)
            self.assertIn("*S-1-5-18:(OI)(CI)F", command)
            self.assertEqual(run.call_args_list[1].kwargs["stdout"], subprocess.DEVNULL)

    def test_structure_report_never_contains_values_or_unknown_names(self):
        result = prepare.safe_structure(html(editor_config()))
        self.assertEqual(result["status"], "parsed")
        self.assertTrue(result["legacy_editor_fields_present"])
        self.assertFalse(result["encrypted_peer_verified"])
        self.assertTrue(all(result["structure"].values()))
        self.assertEqual(result["top_level_key_count"], 2)
        self.assertNotIn("DO_NOT_PRINT", json.dumps(result))
        self.assertNotIn("privateUnknownName", json.dumps(result))

    def test_edit_false_missing_fields_and_duplicate_scripts(self):
        result = prepare.safe_structure(html(editor_config(edit=False)))
        self.assertTrue(result["structure"]["permissions_edit_present"])
        self.assertFalse(result["structure"]["permissions_edit_enabled"])
        self.assertFalse(prepare.safe_structure(html({"officeActionData": {}}))["legacy_editor_fields_present"])
        self.assertEqual(prepare.safe_structure(html({}) + html({}))["status"], "ambiguous_client_config")
        self.assertEqual(prepare.safe_structure("<script id='client-config'>{DO_NOT_PRINT</script>")["status"], "client_config_invalid")
        self.assertEqual(prepare.safe_structure("<script id='client-config'>{\"officeActionData\":{},\"officeActionData\":{}}</script>")["status"], "client_config_invalid")
        self.assertEqual(prepare.safe_structure("<title>DO_NOT_PRINT</title>")["status"], "client_config_missing")

    def test_schema_types_distinguish_placeholders_without_guessing_editor_or_access(self):
        result = prepare.safe_structure(html({"officeActionData": {"editor_config": {}}}))
        self.assertTrue(result["structure"]["officeActionData"])
        self.assertTrue(result["structure"]["editor_config"])
        self.assertFalse(result["legacy_editor_fields_present"])
        self.assertFalse(result["structure"]["permissions_edit_present"])
        self.assertEqual(result["schema_types"]["editor_config"], "empty_object")
        self.assertEqual(result["schema_types"]["balancer_url"], "missing")
        self.assertEqual(result["schema_types"]["document"], "missing")
        self.assertNotIn("new_editor", result)
        self.assertNotIn("read_only", result)
        config = editor_config(edit=False)
        result = prepare.safe_structure(html(config))
        self.assertEqual(result["schema_types"]["permissions_edit"], "boolean")
        self.assertTrue(result["structure"]["permissions_edit_present"])
        self.assertFalse(result["structure"]["permissions_edit_enabled"])
        self.assertTrue(result["legacy_editor_fields_present"])

    def test_schema_types_are_bounded_fixed_keys_and_type_labels_only(self):
        cases = ((None, "null"), ({}, "empty_object"), ({"DO_NOT_PRINT": "VALUE"}, "object"),
                 ([], "empty_array"), (["DO_NOT_PRINT"], "array"), ("", "empty_string"),
                 ("DO_NOT_PRINT", "string"), (True, "boolean"), (42, "number"))
        for value, expected in cases:
            with self.subTest(expected=expected):
                result = prepare.safe_structure(html({"officeActionData": {"editor_config": value}, "DO_NOT_PRINT_NAME": value}))
                self.assertEqual(set(result["schema_types"]), set(prepare.SCHEMA_PATHS))
                self.assertEqual(len(result["schema_types"]), 8)
                self.assertEqual(result["schema_types"]["editor_config"], expected)
                self.assertEqual(result["schema_types"]["document"], "missing" if isinstance(value, dict) else "parent_unavailable")
                self.assertNotIn("DO_NOT_PRINT", json.dumps(result))
        result = prepare.safe_structure("<script id='client-config'>DO_NOT_PRINT</script>")
        self.assertTrue(all(value == "unavailable" for value in result["schema_types"].values()))

    def test_probe_get_limit_timeout_and_default_proxy_opener(self):
        response = FakeResponse(html(editor_config()).encode())
        opener = Mock()
        opener.open.return_value = response
        factory = Mock(return_value=opener)
        result = prepare.probe(self.document_file, factory)
        factory.assert_called_once()
        self.assertIsInstance(factory.call_args.args[0], prepare.YandexRedirectHandler)
        request = opener.open.call_args.args[0]
        self.assertEqual(request.full_url, DOCUMENT)
        self.assertEqual(request.get_method(), "GET")
        self.assertEqual(opener.open.call_args.kwargs["timeout"], 20)
        self.assertEqual(response.read_sizes, [prepare.MAX_RESPONSE_BODY + 1])
        self.assertTrue(response.closed)
        self.assertEqual(result["status"], "parsed")
        self.assertNotIn("DO_NOT_PRINT", json.dumps(result))

    def test_probe_body_limit_and_unsupported_encoding(self):
        for response in (FakeResponse(b"X" * (prepare.MAX_RESPONSE_BODY + 1)),
                         FakeResponse(b"", headers={"Content-Length": str(prepare.MAX_RESPONSE_BODY + 1)})):
            opener = Mock()
            opener.open.return_value = response
            self.assertEqual(prepare.probe(self.document_file, lambda _: opener)["status"], "body_limit")
        opener.open.return_value = FakeResponse(b"unused", headers={"Content-Encoding": "gzip"})
        self.assertEqual(prepare.probe(self.document_file, lambda _: opener)["status"], "unsupported_content_encoding")

    def test_only_five_supported_https_redirects_can_be_followed(self):
        handler = prepare.YandexRedirectHandler()
        request = urllib.request.Request(DOCUMENT)
        for _ in range(5):
            redirect = handler.redirect_request(request, None, 302, "Found", {}, "https://docs.yandex.ru/docs/view?url=PUBLIC")
            self.assertEqual(redirect.get_method(), "GET")
        with self.assertRaises(prepare.RedirectNeedsReview):
            handler.redirect_request(request, None, 302, "Found", {}, DOCUMENT)
        for url in ("https://passport.yandex.ru/auth/DO_NOT_PRINT", "http://docs.yandex.ru/i/DO_NOT_PRINT", "https://user@docs.yandex.ru/i/DO_NOT_PRINT"):
            with self.assertRaises(prepare.RedirectNeedsReview) as error:
                prepare.YandexRedirectHandler().redirect_request(request, None, 302, "Found", {}, url)
            self.assertNotIn("DO_NOT_PRINT", str(error.exception))

    def test_http_redirect_closes_body_without_unbounded_drain(self):
        handler = prepare.YandexRedirectHandler()
        handler.parent = Mock()
        request = urllib.request.Request(DOCUMENT)
        request.timeout = prepare.HTTP_TIMEOUT
        for code in (301, 302, 303, 307, 308):
            with self.subTest(code=code):
                response = Mock()
                result = getattr(handler, "http_error_" + str(code))(
                    request, response, code, "Found", {"location": "/docs/view?url=PUBLIC"})
                response.read.assert_not_called()
                response.close.assert_called_once()
                self.assertIs(result, handler.parent.open.return_value)
                self.assertEqual(handler.parent.open.call_args.kwargs["timeout"], prepare.HTTP_TIMEOUT)
        for location in ("https://passport.yandex.ru/DO_NOT_PRINT", "\nhttps://docs.yandex.ru/DO_NOT_PRINT", None):
            response = Mock()
            handler.parent.open.reset_mock()
            with self.assertRaises(prepare.RedirectNeedsReview):
                handler.http_error_302(request, response, 302, "Found", {"location": location})
            response.close.assert_called_once()
            response.read.assert_not_called()
            handler.parent.open.assert_not_called()

    def test_probe_network_errors_and_redirects_are_redacted(self):
        opener = Mock()
        for error, expected in ((urllib.error.URLError("DO_NOT_PRINT_URL"), "request_failed"),
                                (TimeoutError("DO_NOT_PRINT_URL"), "request_failed"),
                                (http.client.BadStatusLine("DO_NOT_PRINT_URL"), "request_failed"),
                                (prepare.RedirectNeedsReview(302), "redirect_requires_review")):
            opener.open.side_effect = error
            result = prepare.probe(self.document_file, lambda _: opener)
            self.assertEqual(result["status"], expected)
            self.assertNotIn("DO_NOT_PRINT", json.dumps(result))

    def test_cli_argument_errors_do_not_echo_secret_values(self):
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            self.assertEqual(prepare.main(["generate", "--document-file", "DO_NOT_PRINT", "--socks-port", "DO_NOT_PRINT"]), 1)
        self.assertNotIn("DO_NOT_PRINT", output.getvalue())


if __name__ == "__main__":
    unittest.main()
