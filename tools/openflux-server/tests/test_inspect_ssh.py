import base64
import hashlib
import importlib.util
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch


SPEC = importlib.util.spec_from_file_location("inspect_ssh", Path(__file__).resolve().parents[1] / "inspect_ssh.py")
ssh = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(ssh)
BLOB = b"\x00\x00\x00\x0bssh-ed25519\x00\x00\x00\x20" + bytes(range(32))
KEY = base64.b64encode(BLOB).decode("ascii")
FINGERPRINT = "SHA256:" + base64.b64encode(hashlib.sha256(BLOB).digest()).decode("ascii").rstrip("=")


def access():
    return {"host": "192.0.2.1", "port": 22, "user": "testuser", "host_key_sha256": FINGERPRINT}


class InspectionSshTests(unittest.TestCase):
    def test_bare_and_prefixed_fingerprints(self):
        for fingerprint in (FINGERPRINT, FINGERPRINT + "=", FINGERPRINT[7:]):
            self.assertEqual(ssh.normalize_access(dict(access(), host_key_sha256=fingerprint))["host_key_sha256"], FINGERPRINT)

    def test_rejects_unsafe_settings_without_echoing_values(self):
        cases = [{"host": "-oProxyCommand=PRIVATE"}, {"user": "root;PRIVATE"},
                 {"port": True}, {"port": "22"}, {"port": 0}, {"port": 65536},
                 {"host": "host..test"}, {"host": "a.-b.test"}, {"host": "fe80::1%eth0"},
                 {"host_key_sha256": "PRIVATE"}, {"password": "PRIVATE"}]
        for overrides in cases:
            with self.subTest(overrides=overrides):
                with self.assertRaises(ssh.InspectionError) as caught:
                    ssh.normalize_access(dict(access(), **overrides))
                self.assertNotIn("PRIVATE", str(caught.exception))

    def test_accepts_ipv6_and_hostname(self):
        for host in ("2001:db8::1", "vpn.example.test", "example.test"):
            self.assertEqual(ssh.normalize_access(dict(access(), host=host))["host"], host)

    def test_keyscan_is_only_accepted_after_exact_fingerprint_match(self):
        scan = f"# banner\n[192.0.2.1]:22 ssh-ed25519 {KEY}\n"
        self.assertEqual(ssh.verified_host_line(scan + scan, FINGERPRINT), f"{ssh.HOST_ALIAS} ssh-ed25519 {KEY}\n")
        for invalid in ("SHA256:" + "a" * 43, "SHA256:" + "b" * 43):
            with self.assertRaisesRegex(ssh.InspectionError, "MISMATCH"):
                ssh.verified_host_line(scan, invalid)

    def test_malformed_and_unexpected_key_types_fail_closed(self):
        for scan in (f"host ssh-rsa {KEY}", "host ssh-ed25519 ??", "", "# banner"):
            with self.assertRaises(ssh.InspectionError):
                ssh.verified_host_line(scan, FINGERPRINT)

    def test_native_ssh_has_strict_verification_and_no_forwarding_or_passwords(self):
        args = ssh.ssh_arguments(Path("ssh"), access(), Path("private key"), Path("known hosts"))
        self.assertIn("StrictHostKeyChecking=yes", args)
        self.assertIn("PasswordAuthentication=no", args)
        self.assertIn("KbdInteractiveAuthentication=no", args)
        self.assertIn("ClearAllForwardings=yes", args)
        self.assertIn("IdentityAgent=none", args)
        self.assertIn("GlobalKnownHostsFile=none", args)
        self.assertEqual(args[-3:], ["--", "192.0.2.1", "python3 -"])
        self.assertEqual(args[1:3], ["-F", "none"])

    def test_subprocess_timeout_is_secret_free(self):
        with patch.object(ssh.subprocess, "run", side_effect=subprocess.TimeoutExpired(["SECRET"], 1)):
            with self.assertRaises(ssh.InspectionError) as caught:
                ssh.native(["SECRET"])
        self.assertNotIn("SECRET", str(caught.exception))

    def test_private_outputs_never_overwrite_existing_files(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "private-report"
            ssh.write_private(path, b"original")
            with self.assertRaises(FileExistsError):
                ssh.write_private(path, b"replacement")
            self.assertEqual(path.read_bytes(), b"original")

    def test_proxy_relay_uses_separate_loopback_port_and_cleans_up(self):
        relay = ssh.SocksRelay(access(), 10808)
        with relay as endpoint:
            self.assertEqual(endpoint["host"], "127.0.0.1")
            self.assertNotEqual(endpoint["port"], 10808)
            self.assertEqual(endpoint["user"], "testuser")
            self.assertEqual(endpoint["host_key_sha256"], FINGERPRINT)
            self.assertTrue(relay.thread.is_alive())
        self.assertFalse(relay.thread.is_alive())
        self.assertEqual(relay.server.socket.fileno(), -1)

    def test_proxy_relay_rejects_invalid_ports(self):
        for port in (True, 0, 65536, "10808"):
            with self.assertRaises(ssh.InspectionError):
                ssh.SocksRelay(access(), port)


if __name__ == "__main__":
    unittest.main()
