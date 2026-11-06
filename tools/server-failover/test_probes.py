import math
import unittest
from types import SimpleNamespace

from probes import CheckHost, ProbeUnavailable, authoritative_dns, tcp_status


ADDRESS = "192.0.2.10"


class CheckHostStub(CheckHost):
    def __init__(self, replies):
        super().__init__(sleep=lambda _: None, clock=lambda: 1000)
        self.replies = iter(replies)
        self.paths = []

    def request(self, path, parameters=()):
        self.paths.append((path, parameters))
        return next(self.replies)


class ProbeTests(unittest.TestCase):
    def test_real_tcp_result_shapes(self):
        self.assertEqual(tcp_status([{"time": 0.03, "address": ADDRESS}], ADDRESS), "healthy")
        self.assertEqual(tcp_status([{"error": "Connection timed out"}], ADDRESS), "failed")
        self.assertEqual(tcp_status([{"error": "Connection refused"}], ADDRESS), "failed")
        for value in [None, [], {}, [[None]], [{"error": "API unavailable"}], [{"error": "Too many requests"}]]:
            self.assertEqual(tcp_status(value, ADDRESS), "unknown")

    def test_mismatched_address_is_always_unknown(self):
        self.assertEqual(tcp_status([{"time": 0.1, "address": "198.51.100.20"}], ADDRESS), "unknown")
        self.assertEqual(tcp_status([{"error": "Connection timed out", "address": "198.51.100.20"}], ADDRESS), "unknown")

    def test_malformed_timing_never_counts_as_success(self):
        for elapsed in [True, False, -1, math.nan, math.inf, "0.1", None]:
            self.assertEqual(tcp_status([{"time": elapsed, "address": ADDRESS}], ADDRESS), "unknown")

    def test_russian_nodes_need_different_asns(self):
        checker = CheckHostStub([{"nodes": {
            "ru1.node.check-host.net": {"asn": "AS1", "location": ["ru", "Russia", "Moscow"]},
            "ru2.node.check-host.net": {"asn": "AS1", "location": ["ru", "Russia", "Other"]},
            "ru3.node.check-host.net": {"asn": "AS2", "location": ["ru", "Russia", "Other"]},
            "de1.node.check-host.net": {"asn": "AS3", "location": ["de", "Germany", "Berlin"]},
        }}])
        nodes = checker.russian_nodes()
        self.assertEqual(set(nodes), {"ru1.node.check-host.net", "ru3.node.check-host.net"})

    def test_extended_results_bind_target_and_request_identity(self):
        nodes = {"ru1.node.check-host.net": {"country": "ru", "asn": "AS1"}}
        checker = CheckHostStub([
            {"ok": 1, "request_id": "abc12", "nodes": {"ru1.node.check-host.net": ["ru", "Russia", "Moscow", "203.0.113.1", "AS1"]}},
            {"command": "tcp", "created": 999, "host": ADDRESS + ":443", "results": {"ru1.node.check-host.net": [{"error": "Connection timed out"}]}},
        ])
        samples = checker.tcp(ADDRESS, nodes)
        self.assertEqual(samples[0]["status"], "failed")
        self.assertEqual(samples[0]["request_id"], "abc12")
        self.assertEqual(checker.paths[-1][0], "/check-result-extended/abc12")

    def test_api_wrong_target_is_unknown_error(self):
        checker = CheckHostStub([
            {"ok": 1, "request_id": "abc12", "nodes": {}},
            {"command": "tcp", "created": 999, "host": "other.example:443", "results": {}},
        ])
        with self.assertRaises(ProbeUnavailable):
            checker.tcp(ADDRESS, {})

    def test_live_tcp_extended_schema_has_separate_host_and_port(self):
        nodes = {"ru1.node.check-host.net": {"country": "ru", "asn": "AS1"}}
        checker = CheckHostStub([
            {"ok": 1, "request_id": "abc12", "nodes": {"ru1.node.check-host.net": ["ru", "Russia", "Moscow", "203.0.113.1", "AS1"]}},
            {"command": "tcp", "created": 999, "host": ADDRESS, "port": "443", "results": {"ru1.node.check-host.net": [{"time": 0.1, "address": ADDRESS}]}},
        ])
        self.assertEqual(checker.tcp(ADDRESS, nodes)[0]["status"], "healthy")

    def test_authority_needs_aa_and_rejects_uncovered_ipv6(self):
        def command(arguments, **kwargs):
            kind = arguments[-1]
            text = ";; ->>HEADER<<- opcode: QUERY, status: NOERROR, id: 1\n;; flags: qr aa; QUERY: 1\n"
            if kind == "A":
                text += "vpn.example.net. 3600 IN A " + ADDRESS + "\n"
            return SimpleNamespace(returncode=0, stdout=text)
        result = authoritative_dns(["ns1.example.net", "ns2.example.net"], ["vpn.example.net"], command=command)
        self.assertEqual(result["ns1.example.net"]["vpn.example.net"], [ADDRESS])
        def recursive(arguments, **kwargs):
            result = command(arguments, **kwargs)
            result.stdout = result.stdout.replace("qr aa", "qr rd ra")
            return result
        with self.assertRaises(ProbeUnavailable):
            authoritative_dns(["ns1.example.net", "ns2.example.net"], ["vpn.example.net"], command=recursive)


if __name__ == "__main__":
    unittest.main()
