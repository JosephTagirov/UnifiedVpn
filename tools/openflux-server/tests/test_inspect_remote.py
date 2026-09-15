import importlib.util
import json
import os
from pathlib import Path
import subprocess
import unittest
from unittest.mock import patch


SPEC = importlib.util.spec_from_file_location("openflux_readonly_inspector", Path(__file__).resolve().parents[1] / "inspect_remote.py")
remote = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(remote)
CONTAINER = "1" * 64
NETWORK = "2" * 64
IMAGE = "sha256:" + "3" * 64


def metadata():
    return {"os": "Linux", "kernel": "6.8.0", "architecture": "x86_64", "python_version": "3.12.3", "uid": 0,
            "resources": {"cpu_count": 2, "memory_total_bytes": 2048, "memory_free_bytes": 1024,
                          "root_disk_total_bytes": 4096, "root_disk_free_bytes": 2048, "load_average": [0.1, 0.2, 0.3]}}


class FakeQueries:
    def __init__(self):
        self.commands = []
        self.docker_available = True
        self.extra_counter = 1

    @staticmethod
    def response(data, raw=False):
        return {"status": "ok", "data": data.encode() if raw else json.dumps(data).encode()}

    def __call__(self, argv, timeout=10):
        self.commands.append(argv)
        if timeout != 10:
            raise AssertionError("Every subprocess must have a bounded timeout")
        if argv[:3] == remote.DOCKER:
            args = argv[3:]
            if args[0] == "version":
                if not self.docker_available:
                    return {"status": "unavailable", "data": b""}
                return self.response({"client_version": "28.0.0", "client_api_version": "1.48", "server_version": "28.0.0",
                                      "server_api_version": "1.48", "server_min_api_version": "1.24", "os": "linux", "architecture": "amd64",
                                      "private_extra": "DO_NOT_REPORT"})
            if args[:2] == ["container", "ls"]:
                return self.response({"id": CONTAINER, "name": "amnezia-awg", "image": "amnezia-wg:reviewed", "state": "running",
                                      "Env": ["TOKEN=DO_NOT_REPORT"], "Command": "DO_NOT_REPORT", "Mounts": "DO_NOT_REPORT"})
            if args[:2] == ["network", "ls"]:
                return self.response(NETWORK, raw=True)
            if args[:2] == ["network", "inspect"]:
                return self.response({"id": NETWORK, "name": "amnezia", "driver": "bridge", "scope": "local", "internal": False,
                                      "ipv6": False, "containers_count": 1, "ipam": [{"Subnet": "172.20.0.0/24", "Gateway": "172.20.0.1",
                                                                                       "Options": "DO_NOT_REPORT"}], "Labels": "DO_NOT_REPORT"})
            if args[:2] == ["image", "ls"]:
                return self.response(IMAGE + "\n" + IMAGE, raw=True)
            if args[:2] == ["image", "inspect"]:
                return self.response({"id": IMAGE, "tags": ["runtime:reviewed"], "digests": [], "os": "linux", "architecture": "amd64",
                                      "Config": {"Env": ["TOKEN=DO_NOT_REPORT"]}})
            raise AssertionError("Unexpected Docker command")
        if argv[0] == "systemctl":
            return self.response("Id=docker.service\nLoadState=loaded\nActiveState=active\nSubState=running\nUnitFileState=enabled\nExecStart=DO_NOT_REPORT\n\n"
                                 "Id=unrelated-private.service\nActiveState=active\n", raw=True)
        if argv == ["ip", "-j", "-4", "route", "show", "table", "all"]:
            return self.response([{"dst": "default", "gateway": "192.0.2.1", "dev": "eth0", "private_extra": "DO_NOT_REPORT"},
                                  {"dst": "172.20.0.0/24", "dev": "docker0", "table": 100},
                                  {"dst": "198.51.100.0/24", "nexthops": [{"gateway": "192.0.2.2", "dev": "eth0", "weight": 1, "extra": "DO_NOT_REPORT"}]}])
        if argv[0] in ("iptables-save", "ip6tables-save"):
            return self.response(f"# Generated at {self.extra_counter}\n*filter\n:INPUT ACCEPT [{self.extra_counter}:99]\n-A INPUT -m comment --comment DO_NOT_REPORT -j ACCEPT\nCOMMIT\n", raw=True)
        if argv == ["nft", "--json", "list", "ruleset"]:
            return self.response({"nftables": [{"metainfo": {"version": str(self.extra_counter)}},
                                               {"rule": {"comment": "DO_NOT_REPORT", "expr": [{"counter": {"packets": self.extra_counter, "bytes": 99}},
                                                                                           {"accept": None}]}}]})
        raise AssertionError("Unexpected command: " + repr(argv))


class InspectionTests(unittest.TestCase):
    def setUp(self):
        self.fake = FakeQueries()
        self.inspector = remote.Inspector(self.fake, metadata)

    def test_report_schema_and_summary_are_metadata_only(self):
        report = self.inspector.inspect()
        self.assertEqual(report["schema"], "unifiedvpn-openflux-server-inspect-v1")
        summary = report["summary"]
        self.assertEqual(summary["docker_version"], "28.0.0")
        self.assertTrue(summary["docker_available"])
        self.assertEqual(summary["container_count"], 1)
        self.assertEqual(summary["running_container_count"], 1)
        self.assertEqual(summary["network_count"], 1)
        self.assertEqual(summary["image_count"], 1)
        self.assertEqual(summary["resources"]["cpu_count"], 2)
        self.assertNotIn("192.0.2", json.dumps(summary))
        self.assertNotIn("amnezia-awg", json.dumps(summary))
        self.assertNotIn("DO_NOT_REPORT", json.dumps(report))
        self.assertEqual(set(report["containers"][0]), {"id", "name", "image", "state"})
        self.assertEqual(report["services"][0]["name"], "docker.service")
        self.assertEqual(len(report["services"]), 1)

    def test_no_mutating_or_container_execution_commands(self):
        self.inspector.inspect(firewall_hashes=True)
        for command in self.fake.commands:
            self.assertNotIn("ssh", command)
            self.assertNotIn("exec", command)
            self.assertNotIn("logs", command)
            self.assertNotIn("start", command)
            self.assertNotIn("restart", command)
            self.assertNotIn("run", command)
            self.assertNotIn("create", command)
            self.assertNotIn("build", command)
            self.assertNotIn("pull", command)
            self.assertNotIn("stop", command)
            if command[0] == "docker":
                self.assertEqual(command[:3], remote.DOCKER)
            if "inspect" in command:
                self.assertIn("--format", command)
            for argument in command:
                self.assertNotIn(".Env", argument)
                self.assertNotIn("json .Config", argument)
                self.assertNotIn(".Mounts", argument)
                self.assertNotIn(".Command", argument)

    def test_firewall_is_opt_in(self):
        report = self.inspector.inspect()
        self.assertEqual(report["firewall"], {"status": "not_requested"})
        self.assertFalse(any(command[0] in ("iptables-save", "ip6tables-save", "nft") for command in self.fake.commands))

    def test_firewall_hashes_hide_rules_and_ignore_live_counters(self):
        first = self.inspector.inspect(firewall_hashes=True)["firewall"]
        self.fake.extra_counter = 900
        second = self.inspector.inspect(firewall_hashes=True)["firewall"]
        self.assertEqual(first, second)
        self.assertEqual(first["iptables-save"]["rule_count"], 1)
        self.assertEqual(first["nft"]["rule_count"], 1)
        self.assertEqual(len(first["nft"]["sha256"]), 64)
        self.assertNotIn("DO_NOT_REPORT", json.dumps(first))

    def test_missing_docker_is_not_reported_as_zero_containers(self):
        self.fake.docker_available = False
        report = self.inspector.inspect()
        self.assertFalse(report["summary"]["docker_available"])
        self.assertIsNone(report["summary"]["container_count"])
        self.assertEqual(report["containers"], [])
        self.assertFalse(any(command[3:5] == ["container", "ls"] for command in self.fake.commands))

    def test_failed_container_query_does_not_claim_zero_containers(self):
        def query(argv, timeout=10):
            if argv[3:5] == ["container", "ls"]:
                return {"status": "failed", "data": b""}
            return self.fake(argv, timeout=timeout)
        report = remote.Inspector(query, metadata).inspect()
        self.assertTrue(report["summary"]["docker_available"])
        self.assertIsNone(report["summary"]["container_count"])
        self.assertIsNone(report["summary"]["running_container_count"])

    def test_partial_service_results_keep_only_requested_properties(self):
        def query(argv, timeout=10):
            return {"status": "partial", "data": b"Id=ssh.service\nActiveState=active\nExecStart=DO_NOT_REPORT\n"}
        inspector = remote.Inspector(query, metadata)
        self.assertEqual(inspector.services(), [{"name": "ssh.service", "active_state": "active"}])
        self.assertEqual(inspector.checks["services"], "partial")

    def test_olcrtc_instances_are_selected_but_unrelated_services_are_not(self):
        calls = []
        names = ("olcrtc@jitsi2.service", "olcrtc@jitsi3.service", "unrelated@jitsi2.service",
                 "olcrtc@.service", "olcrtc@@jitsi2.service", "olcrtc@../../etc/passwd.service",
                 "olcrtc@jitsi2.service;id", "olcrtc@" + "a" * 65 + ".service")
        def query(argv, timeout=10):
            calls.append(argv)
            blocks = [f"Id={name}\nLoadState=loaded\nActiveState=active\nSubState=running\n\n" for name in names]
            return {"status": "ok", "data": "".join(blocks).encode()}
        services = remote.Inspector(query, metadata).services()
        self.assertIn("olcrtc@*.service", calls[0])
        self.assertEqual([service["name"] for service in services], ["olcrtc@jitsi2.service", "olcrtc@jitsi3.service"])
        self.assertTrue(all(service["active_state"] == "active" and service["sub_state"] == "running" for service in services))

    def test_unsafe_identity_is_not_forwarded_into_commands(self):
        def query(argv, timeout=10):
            return {"status": "ok", "data": b"--malicious-option"}
        inspector = remote.Inspector(query, metadata)
        self.assertEqual(inspector.identities("network"), [])
        self.assertEqual(inspector.checks["network_list"], "invalid_data")

    def test_malformed_json_has_no_raw_output(self):
        def query(argv, timeout=10):
            return {"status": "ok", "data": b"DO_NOT_REPORT"}
        inspector = remote.Inspector(query, metadata)
        self.assertEqual(inspector.docker_version(), {})
        self.assertEqual(inspector.checks["docker_version"], "invalid_data")

    def test_remote_docker_environment_is_removed(self):
        variables = {"DOCKER_HOST": "ssh://PRIVATE", "DOCKER_CONTEXT": "PRIVATE", "BUILDX_BUILDER": "PRIVATE", "BUILDKIT_HOST": "tcp://PRIVATE"}
        with patch.dict(os.environ, variables), patch.object(subprocess, "run") as run:
            run.return_value = subprocess.CompletedProcess([], 0, b"{}", None)
            self.assertEqual(remote.run_query(remote.DOCKER + ["version"])["status"], "ok")
            forwarded = run.call_args.kwargs["env"]
            self.assertFalse(any(key in forwarded for key in variables))
            self.assertEqual(run.call_args.kwargs["stdin"], subprocess.DEVNULL)
            self.assertEqual(run.call_args.kwargs["stderr"], subprocess.DEVNULL)
            self.assertEqual(run.call_args.kwargs["timeout"], 10)

    def test_failures_and_timeouts_never_return_command_output(self):
        with patch.object(subprocess, "run", return_value=subprocess.CompletedProcess([], 1, b"DO_NOT_REPORT", None)):
            self.assertEqual(remote.run_query(["docker", "version"]), {"status": "failed", "data": b""})
        with patch.object(subprocess, "run", side_effect=subprocess.TimeoutExpired(["PRIVATE"], 10, output=b"DO_NOT_REPORT")):
            self.assertEqual(remote.run_query(["docker", "version"]), {"status": "timeout", "data": b""})
        with patch.object(subprocess, "run", side_effect=FileNotFoundError()):
            self.assertEqual(remote.run_query(["docker", "version"]), {"status": "unavailable", "data": b""})

    def test_large_output_is_rejected(self):
        with patch.object(subprocess, "run", return_value=subprocess.CompletedProcess([], 0, b"X" * (remote.MAX_OUTPUT_BYTES + 1), None)):
            self.assertEqual(remote.run_query(["ip", "-j", "route"]), {"status": "output_limit", "data": b""})

    def test_metadata_strings_reject_control_sequences(self):
        self.assertIsNone(remote.text("value\nPRIVATE"))
        self.assertIsNone(remote.version("https://private.invalid"))
        self.assertIsNone(remote.integer(True))


if __name__ == "__main__":
    unittest.main()
