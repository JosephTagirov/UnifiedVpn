import json
import shutil
import subprocess
import time
import unittest
from types import SimpleNamespace
from unittest.mock import AsyncMock, Mock, patch

from test_browser_bootstrap import DOCUMENT, REQUEST_ID, STATE, cookie, response, supervisor, worker


def classic(**changes):
    return dict(dict(source_url=DOCUMENT, captured_at=int(time.time()),
                balancer_url="https://office.yandex.ru", token="synthetic.token",
                document_key="synthetic-key", file_type="docx",
                download_url="https://files.yandex.net/document?public-fixture", title="Fixture", edit=True), **changes)


class ClassicContractTests(unittest.TestCase):
    def test_round_trip_and_cookie_only_compatibility(self):
        for extra in ({}, {"classic_document": classic()}, {"classic_document": classic(), "user_agent": "Browser/1"}):
            value = response(**extra)
            self.assertEqual(worker.validate_response(worker.decode_message(worker.encode_message(value)), REQUEST_ID, DOCUMENT), value)

    def test_exact_fields_binding_types_and_freshness(self):
        base = classic()
        changes = [("source_url", DOCUMENT + "&other"), ("source_url", None),
                   ("captured_at", True), ("captured_at", 1.5), ("captured_at", base["captured_at"] - 121),
                   ("captured_at", base["captured_at"] + 6), ("edit", False), ("edit", 1),
                   ("token", ""), ("token", "x" * 16385), ("token", "bad token"),
                   ("token", "bad\u0085token"), ("document_key", "."), ("document_key", ".."),
                   ("document_key", "x" * 513), ("file_type", "text/docx"), ("file_type", "x" * 33),
                   ("title", "\n"), ("title", "\u0080"), ("title", "\ud800"), ("title", "\u044f" * 513),
                   ("unknown", "value")]
        for field, value in changes:
            item = dict(base, **{field: value})
            with self.subTest(field=field), self.assertRaises(worker.VerificationError):
                worker.validate_classic_document(item, DOCUMENT)
        for field in base:
            item = dict(base)
            del item[field]
            with self.subTest(missing=field), self.assertRaises(worker.VerificationError):
                worker.validate_classic_document(item, DOCUMENT)
        with patch.object(worker.time, "time", return_value=base["captured_at"]):
            for offset in (-120, 5):
                self.assertEqual(worker.validate_classic_document(dict(base, captured_at=base["captured_at"] + offset), DOCUMENT)["edit"], True)

    def test_url_boundaries_and_no_local_or_insecure_endpoint(self):
        invalid = ["http://office.yandex.ru", "https://yandex.ru", "https://yandex.net", "https://localhost",
                   "https://127.0.0.1", "https://office.yandex.ru.evil.test", "https://office.yandex.ru:8443",
                   "https://user:password@office.yandex.ru", "https://office.yandex.ru#", "https://office.yandex.ru/#part",
                   "https://office.yandex.ru\\evil", "https://office.yandex.ru/white space", "https://office.yandex.ru/\u00a0",
                   "https://office.yandex.ru:0443", "https://office.yandex.ru:", "https://office.yandex.ru/\u0080",
                   "https://office.yandex.ru/%", "https://office.yandex.ru/?bad=%zz",
                   "https://office.yandex.ru/" + "\u044f" * 4090,
                   "https://a..yandex.ru", "https://-a.yandex.ru", "https://a!.yandex.ru", "https://a.yandex.ru.",
                   "https://\u044f.yandex.ru"]
        for endpoint in invalid:
            for field in ("balancer_url", "download_url"):
                with self.subTest(field=field, endpoint=endpoint), self.assertRaises(worker.VerificationError):
                    worker.validate_classic_document(dict(classic(), **{field: endpoint}), DOCUMENT)
        for endpoint in ("https://office.yandex.ru", "https://office.yandex.net", "https://office.yandex.ru:443"):
            worker.validate_classic_document(dict(classic(), balancer_url=endpoint), DOCUMENT)
        for endpoint in ("https://office.yandex.ru/path", "https://office.yandex.ru/", "https://office.yandex.ru/?"):
            with self.assertRaises(worker.VerificationError):
                worker.validate_classic_document(dict(classic(), balancer_url=endpoint), DOCUMENT)

    def test_underscore_is_supported_only_for_yandex_balancer_origin(self):
        for origin in ("https://office_balancer.yandex.net", "https://office_2.yandex.ru:443"):
            with self.subTest(origin=origin):
                self.assertTrue(worker.classic_url(origin, origin=True))
                self.assertFalse(worker.classic_url(origin))
                worker.validate_classic_document(classic(balancer_url=origin), DOCUMENT)
                with self.assertRaises(worker.VerificationError):
                    worker.validate_classic_document(classic(download_url=origin), DOCUMENT)
        for origin in ("https://office_balancer.yandex.net/", "https://office_balancer.yandex.net?",
                       "http://office_balancer.yandex.net", "https://office_balancer.yandex.net:444",
                       "https://office_balancer.yandex.net:0443", "https://office_balancer.yandex.net:",
                       "https://user@office_balancer.yandex.net", "https://office_balancer.yandex.net#",
                       "https://office_balancer.yandex.net.evil.test", "https://office_balancer..yandex.net",
                       "https://-office_balancer.yandex.net", "https://office_balancer-.yandex.net",
                       "https://office_balancer.yandex.net.", "https://office%5fbalancer.yandex.net",
                       "https://" + "x" * 64 + "_.yandex.net", "https://office_\u044f.yandex.net"):
            with self.subTest(origin=origin), self.assertRaises(worker.VerificationError):
                worker.validate_classic_document(classic(balancer_url=origin), DOCUMENT)

    def test_bounded_metadata_and_full_reply(self):
        item = dict(classic(), token="x" * 16384, download_url="https://files.yandex.net/" + "x" * 8150)
        with self.assertRaises(worker.VerificationError):
            worker.validate_classic_document(item, DOCUMENT)
        item = dict(classic(), token="x" * 16384)
        value = response([cookie(name="c" + str(n), value="x" * 4090) for n in range(5)], classic_document=item)
        with self.assertRaises(worker.VerificationError):
            worker.validate_response(value, REQUEST_ID, DOCUMENT)
        for invalid in (None, False, [], "private"):
            with self.assertRaises(worker.VerificationError) as raised:
                worker.validate_response(response(classic_document=invalid), REQUEST_ID, DOCUMENT)
            self.assertEqual(str(raised.exception), "")

    def test_internal_file_reference_is_preserved_but_never_a_network_destination(self):
        references = ("http://localhost:12701/disk/synthetic",
                      "http://localhost:12701/disk/synthetic?signature=a%2Fb%3D&download=1",
                      "http://localhost:12701/disk/%D1%84%D0%B0%D0%B9%D0%BB")
        for reference in references:
            with self.subTest(reference=reference):
                item = classic(download_url=reference)
                result = worker.validate_response(response(classic_document=item), REQUEST_ID, DOCUMENT)
                self.assertEqual(result["classic_document"]["download_url"], reference)
                self.assertFalse(worker.allowed_url(reference))
                self.assertFalse(worker.allowed_url(reference, navigation=True))
                self.assertFalse(worker.classic_url(reference, origin=True))
                with self.assertRaises(worker.VerificationError):
                    worker.validate_classic_document(classic(balancer_url=reference), DOCUMENT)

    def test_internal_file_reference_rejects_aliases_traversal_and_malformed_encoding(self):
        prefix = "http://localhost:12701/disk/"
        invalid = ("http://127.0.0.1:12701/disk/x", "http://localhost:12702/disk/x",
                   "http://localhost:012701/disk/x", "http://LOCALHOST:12701/disk/x",
                   "http://user@localhost:12701/disk/x", "http://localhost:12701/other/x",
                   "http://localhost:12701.evil.test/disk/x", "https://localhost:12701/disk/x",
                   prefix, prefix + "../x", prefix + "a/%2e%2e/x", prefix + "a%2f..%2fx",
                   prefix + "%252e%252e/x", prefix + "%ff", prefix + "%5cx", prefix + "%3fx",
                   prefix + "%23x", prefix + "%00x", prefix + "%20x", prefix + "%C2%85x",
                   prefix + "x#", prefix + "x?bad=%zz", prefix + "x?bad=%", prefix + "x\\y",
                   prefix + "\ud800", prefix + "x" * 8192)
        for reference in invalid:
            with self.subTest(reference=reference), self.assertRaises(worker.VerificationError):
                worker.validate_classic_document(classic(download_url=reference), DOCUMENT)

    def test_utf8_wire_encoding_matches_metadata_byte_budget(self):
        item = dict(classic(), title="\u044f" * 512)
        wire = worker.encode_message(response(classic_document=item))
        self.assertIn(("\u044f" * 512).encode("utf-8"), wire)
        self.assertEqual(worker.decode_message(wire), response(classic_document=item))
        for url in (DOCUMENT + "#", DOCUMENT + "%", DOCUMENT + "\ud800", DOCUMENT + "\u0080"):
            with self.assertRaises(worker.VerificationError):
                worker.validate_request({"id": REQUEST_ID, "document_url": url})

    def test_manual_supervisor_preserves_and_revalidates_metadata(self):
        challenge = supervisor.manual_challenge(STATE, "browsercheck", REQUEST_ID, DOCUMENT)
        value = {"binding": challenge, "cookies": [cookie()], "classic_document": classic()}
        self.assertEqual(supervisor.manual_response(value, challenge, DOCUMENT), response(classic_document=value["classic_document"]))
        value["classic_document"]["source_url"] += "&other"
        with self.assertRaises(worker.VerificationError):
            supervisor.manual_response(value, challenge, DOCUMENT)

    @unittest.skipUnless(shutil.which("node"), "Node unavailable")
    def test_browser_exports_only_selected_classic_fields(self):
        expected = classic()
        payload = {"officeActionData": {"balancer_url": expected["balancer_url"], "secret": "NO_EXPORT",
                   "editor_config": {"token": expected["token"], "user": {"private": "NO_EXPORT"},
                   "document": {"key": expected["document_key"], "fileType": expected["file_type"],
                                "url": expected["download_url"], "title": expected["title"], "permissions": {"edit": True}}}}}
        code = ("const fs=require('fs');const i=JSON.parse(fs.readFileSync(0,'utf8'));"
                "const document={querySelectorAll:()=>[{textContent:JSON.stringify(i.fixture)}]};"
                "process.stdout.write(JSON.stringify(new Function('document','return ('+i.script+')()')(document)));")
        result = subprocess.run([shutil.which("node"), "-e", code],
                                input=json.dumps({"script": worker.CLASSIC_DOCUMENT_SCRIPT, "fixture": payload}),
                                capture_output=True, text=True, timeout=10, check=True)
        snapshot = json.loads(result.stdout)
        self.assertEqual(snapshot, {"kind": "classic", "document": {k: v for k, v in expected.items() if k not in ("source_url", "captured_at")}})
        self.assertNotIn("NO_EXPORT", result.stdout)


class BrowserSnapshotTests(unittest.IsolatedAsyncioTestCase):
    async def verify_fixture(self, mutate=None):
        metadata = classic()
        document = {k: v for k, v in metadata.items() if k not in ("source_url", "captured_at")}
        page = SimpleNamespace(url=DOCUMENT, main_frame=object(), goto=AsyncMock(),
                               wait_for_function=AsyncMock(), on=Mock(),
                               evaluate=AsyncMock(side_effect=["Browser/1", {"kind": "classic", "document": document}]))
        async def cookies(*_):
            if mutate:
                mutate(page, document)
            return [cookie()]
        context = SimpleNamespace(route=AsyncMock(), route_web_socket=AsyncMock(),
                                  new_page=AsyncMock(return_value=page), cookies=cookies)
        browser = SimpleNamespace(new_context=AsyncMock(return_value=context), close=AsyncMock())
        class Factory:
            async def __aenter__(self):
                return SimpleNamespace(chromium=SimpleNamespace(launch=AsyncMock(return_value=browser)))
            async def __aexit__(self, *_):
                pass
        try:
            return await worker.verify({"id": REQUEST_ID, "document_url": DOCUMENT}, Factory)
        finally:
            browser.close.assert_awaited_once()

    async def test_verified_snapshot_returns_without_document_reload(self):
        result = await self.verify_fixture()
        self.assertEqual(result["classic_document"]["source_url"], DOCUMENT)
        self.assertEqual(result["classic_document"]["token"], "synthetic.token")

    async def test_navigation_during_cookie_await_invalidates_snapshot(self):
        def navigate(page, _):
            page.on.call_args.args[1](page.main_frame)
        with self.assertRaises(worker.VerificationError):
            await self.verify_fixture(navigate)
        def change_url(page, _):
            page.url = DOCUMENT + "&other"
        with self.assertRaises(worker.VerificationError):
            await self.verify_fixture(change_url)


if __name__ == "__main__":
    unittest.main()
