import copy
import io
import http.client
import json
import unittest
from urllib.parse import parse_qs
from types import SimpleNamespace

from dns import DnsDrift, DnsUnavailable, RegRu, reconcile


OLD = "192.0.2.10"
NEW = "198.51.100.20"
NAMES = ("vpn.example.net", "web.example.net")


class MemoryProvider:
    def __init__(self, records=None):
        self.records = copy.deepcopy(records or {name: [OLD] for name in NAMES})
        self.calls = []
        self.lose_reply = False

    def records_now(self):
        return copy.deepcopy(self.records)

    def add_a(self, name, address):
        self.calls.append(("add", name, address))
        self.records[name] = sorted(set(self.records[name] + [address]))
        if self.lose_reply:
            self.lose_reply = False
            raise DnsUnavailable("Lost acknowledgement")

    def remove_a(self, name, address):
        self.calls.append(("remove", name, address))
        self.records[name].remove(address)


class Opener:
    def __init__(self, result):
        self.result = result
        self.requests = []

    def open(self, request, timeout):
        self.requests.append(request)
        return io.BytesIO(json.dumps(self.result).encode())


def response(**domain):
    return {"result": "success", "answer": {"domains": [{"dname": "example.net", "result": "success", **domain}]}}


class DnsTests(unittest.TestCase):
    def test_truncated_registrar_reply_is_unknown_without_exposing_the_body(self):
        class TruncatedResponse(io.BytesIO):
            def read(self, *arguments):
                raise http.client.IncompleteRead(b"private-response", 20)
        opener = SimpleNamespace(open=lambda *arguments, **keywords: TruncatedResponse())
        provider = RegRu("example.net", NAMES, {"username": "test-user", "password": "test-password"}, opener=opener)
        with self.assertRaises(DnsUnavailable) as result:
            provider.records_now()
        self.assertNotIn("private-response", str(result.exception))

    def authority(self, values=None):
        records = values or {name: [OLD] for name in NAMES}
        return {"ns1.example.net": copy.deepcopy(records), "ns2.example.net": copy.deepcopy(records)}

    def test_add_before_remove_preserves_every_other_record(self):
        provider = MemoryProvider()
        result = reconcile(provider, NAMES, OLD, NEW, self.authority())
        self.assertEqual(result, {name: [NEW] for name in NAMES})
        self.assertEqual(provider.calls, [
            ("add", NAMES[0], NEW), ("remove", NAMES[0], OLD),
            ("add", NAMES[1], NEW), ("remove", NAMES[1], OLD),
        ])

    def test_restart_reconciles_lost_add_acknowledgement(self):
        provider = MemoryProvider()
        provider.lose_reply = True
        with self.assertRaises(DnsUnavailable):
            reconcile(provider, NAMES, OLD, NEW, self.authority())
        self.assertEqual(provider.records[NAMES[0]], sorted([OLD, NEW]))
        reconcile(provider, NAMES, OLD, NEW, self.authority(), resuming=True)
        self.assertEqual(provider.calls.count(("add", NAMES[0], NEW)), 1)
        self.assertEqual(provider.records, {name: [NEW] for name in NAMES})

    def test_restart_reconciles_verified_target_without_more_writes(self):
        provider = MemoryProvider({name: [NEW] for name in NAMES})
        reconcile(provider, NAMES, OLD, NEW, self.authority(), resuming=True)
        self.assertEqual(provider.calls, [])

    def test_unexpected_registrar_address_blocks_every_write(self):
        provider = MemoryProvider({name: ["203.0.113.99"] for name in NAMES})
        with self.assertRaises(DnsDrift):
            reconcile(provider, NAMES, OLD, NEW, self.authority())
        self.assertEqual(provider.calls, [])

    def test_mismatched_authority_blocks_initial_write(self):
        provider = MemoryProvider()
        authority = self.authority()
        authority["ns2.example.net"][NAMES[0]] = [NEW]
        with self.assertRaises(DnsDrift):
            reconcile(provider, NAMES, OLD, NEW, authority)
        self.assertEqual(provider.calls, [])

    def test_pending_operation_never_removes_a_third_party_address(self):
        provider = MemoryProvider({name: [OLD, NEW, "203.0.113.30"] for name in NAMES})
        with self.assertRaises(DnsDrift):
            reconcile(provider, NAMES, OLD, NEW, self.authority(), resuming=True)
        self.assertEqual(provider.calls, [])

    def provider(self, result, **kwargs):
        opener = Opener(result)
        provider = RegRu("example.net", NAMES, {"username": "test-user", "password": "test-password"}, opener=opener, clock=lambda: 1000, sleep=lambda _: None, **kwargs)
        return provider, opener

    def test_nested_domain_error_is_failure_even_when_top_success(self):
        provider, _ = self.provider(response(result="error", error_code="TEST_ERROR"), allow_writes=True)
        with self.assertRaises(DnsUnavailable):
            provider.add_a(NAMES[0], NEW)

    def test_unexpected_domain_is_rejected(self):
        provider, _ = self.provider(response(dname="other.example"))
        with self.assertRaises(DnsUnavailable):
            provider.records_now()

    def test_credentials_stay_in_post_body_and_exact_content_is_removed(self):
        provider, opener = self.provider(response(), allow_writes=True)
        provider.remove_a(NAMES[0], OLD)
        request = opener.requests[0]
        self.assertEqual(request.method, "POST")
        self.assertNotIn("test-password", request.full_url)
        self.assertNotIn("?", request.full_url)
        form = parse_qs(request.data.decode())
        self.assertEqual(form["input_format"], ["json"])
        payload = json.loads(form["input_data"][0])
        self.assertEqual(payload["content"], OLD)
        self.assertEqual(payload["record_type"], "A")
        self.assertEqual(payload["subdomain"], "vpn")
        self.assertEqual(payload["domains"], [{"dname": "example.net"}])
        self.assertEqual(payload["password"], "test-password")
        self.assertEqual(payload["show_input_params"], 0)

    def test_mutations_are_disabled_by_default(self):
        provider, opener = self.provider(response())
        with self.assertRaises(DnsUnavailable):
            provider.add_a(NAMES[0], NEW)
        self.assertEqual(opener.requests, [])

    def test_reads_only_allowlisted_a_records_and_rejects_ipv6_alias_drift(self):
        records = [
            {"subname": "vpn", "rectype": "A", "content": OLD, "state": "A"},
            {"subname": "web", "rectype": "A", "content": OLD, "state": "A"},
            {"subname": "mail", "rectype": "MX", "content": "mail.example.net", "state": "A"},
        ]
        provider, _ = self.provider(response(rrs=records))
        self.assertEqual(provider.records_now(), {name: [OLD] for name in NAMES})
        provider, _ = self.provider(response(rrs=records + [{"subname": "vpn", "rectype": "AAAA", "content": "2001:db8::1", "state": "A"}]))
        with self.assertRaises(DnsDrift):
            provider.records_now()

    def test_pending_record_is_unknown(self):
        provider, _ = self.provider(response(rrs=[{"subname": "vpn", "rectype": "A", "content": NEW, "state": "P"}]))
        with self.assertRaises(DnsUnavailable):
            provider.records_now()


if __name__ == "__main__":
    unittest.main()
