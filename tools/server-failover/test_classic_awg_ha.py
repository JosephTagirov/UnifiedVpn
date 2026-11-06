import base64
import copy
import json
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

import classic_awg_ha as worker
from integrations import classic_peer_mutation as bridge


RAW = b"[Interface]\nPrivateKey = server-secret\nAddress = 192.0.2.1/24\nListenPort = 55424\nJc = 6\nJmin = 10\nJmax = 50\nS1 = 48\nS2 = 12\nH1 = 1\nH2 = 2\nH3 = 3\nH4 = 4\n\n[Peer]\nPublicKey = peer-a\nPresharedKey = psk-a\nAllowedIPs = 192.0.2.2/32\n"
NEXT = RAW + b"\n[Peer]\nPublicKey = peer-b\nPresharedKey = psk-b\nAllowedIPs = 192.0.2.3/32\n"


def fixture(raw=RAW, revision=1):
    config = {"scope": "classic-old-v1", "source_machine_id": "source-machine", "source_container_id": "source-container",
              "server_public_key_sha256": "public-digest", "interface_sha256": worker.digest(worker.classic_configuration(raw.decode())["interface"]),
              "source": False, "tunnel_address": "192.0.2.1/24", "mtu": 1420, "resource": {"container": "owned-replica"}}
    manifest = {**worker.authority(config), "epoch": 1, "revision": revision, "payload_sha256": worker.sha(raw),
                "sha256": worker.scope_hash(config["scope"], raw, config["mtu"]), "peer_count": len(worker.classic_configuration(raw.decode())["peers"])}
    return config, {"manifest": manifest, "configuration": base64.b64encode(raw).decode()}


class ClassicTests(unittest.TestCase):
    def test_control_bridge_keeps_secret_peer_material_out_of_arguments(self):
        resource = {"payload": {"container": "owned-source", "public": "peer-public", "block": "[Peer]\nPresharedKey = private-psk\n"}}
        settings = {"source": True, "resource": {"container": "owned-source"}}
        with patch.object(bridge.Path, "lstat", return_value=SimpleNamespace(st_size=100, st_uid=0, st_mode=0o100600)), patch.object(bridge.Path, "is_symlink", return_value=False), patch.object(bridge.Path, "is_file", return_value=True), patch.object(bridge.Path, "read_text", return_value=json.dumps(settings)), patch.object(worker, "command") as command:
            bridge.apply(resource, True, command)
            arguments = command.call_args.args[0]
            self.assertNotIn("private-psk", " ".join(arguments))
            self.assertEqual(json.loads(command.call_args.kwargs["data"])["block"], resource["payload"]["block"])
            settings["resource"]["container"] = "foreign-source"
            with patch.object(bridge.Path, "read_text", return_value=json.dumps(settings)):
                with self.assertRaises(ValueError):
                    bridge.apply(resource, True, command)

    def test_only_default_headers_are_normalized(self):
        missing = RAW
        zero = RAW
        for number in range(1, 5):
            missing = missing.replace(("H" + str(number) + " = " + str(number) + "\n").encode(), b"")
            zero = zero.replace(("H" + str(number) + " = " + str(number)).encode(), ("H" + str(number) + " = 0").encode())
        self.assertEqual(worker.classic_configuration(missing.decode()), worker.classic_configuration(RAW.decode()))
        self.assertEqual(worker.classic_configuration(zero.decode()), worker.classic_configuration(RAW.decode()))
        self.assertNotEqual(worker.classic_configuration(RAW.replace(b"H1 = 1", b"H1 = 99").decode()), worker.classic_configuration(RAW.decode()))

    def test_incremental_tool_reset_is_detected(self):
        reset = RAW
        for line in [b"Jc = 6\n", b"Jmin = 10\n", b"Jmax = 50\n", b"S1 = 48\n", b"S2 = 12\n"]:
            reset = reset.replace(line, b"")
        self.assertNotEqual(worker.classic_configuration(reset.decode()), worker.classic_configuration(RAW.decode()))

    def test_unknown_interface_fields_are_rejected(self):
        for line in [b"FwMark = 7\n", b"PostUp = unsafe-command\n", b"S3 = 4\n"]:
            with self.assertRaises(ValueError):
                worker.classic_configuration(RAW.replace(b"[Interface]\n", b"[Interface]\n" + line).decode())

    def test_peer_identity_and_overlapping_routes_are_rejected(self):
        for peer in [b"[Peer]\nPublicKey = peer-a\nAllowedIPs = 192.0.2.9/32\n", b"[Peer]\nPublicKey = peer-b\nAllowedIPs = 192.0.2.0/24\n"]:
            with self.assertRaises(ValueError):
                worker.classic_configuration((RAW + peer).decode())

    def test_runtime_rendering_keeps_all_obfuscation_parameters(self):
        rendered = worker.runtime_configuration(RAW)
        self.assertNotIn(b"Address =", rendered)
        for line in [b"Jc = 6", b"Jmin = 10", b"Jmax = 50", b"S1 = 48", b"S2 = 12", b"H1 = 1", b"H4 = 4"]:
            self.assertIn(line, rendered)
        self.assertEqual(worker.classic_configuration(rendered.decode()), worker.classic_configuration(RAW.decode()))

    def test_cross_origin_and_generation_types_are_rejected(self):
        config, payload = fixture()
        for key, value in [("source_machine_id", "foreign"), ("source_container_id", "foreign"), ("scope", "other"), ("epoch", True), ("revision", True), ("revision", 0)]:
            altered = copy.deepcopy(payload)
            altered["manifest"][key] = value
            with self.assertRaises(RuntimeError):
                worker.validate(config, altered)

    def test_interface_change_is_rejected_even_with_fresh_digest(self):
        config, _ = fixture()
        _, changed = fixture(RAW.replace(b"S1 = 48", b"S1 = 49"), 2)
        with self.assertRaises(RuntimeError):
            worker.validate(config, changed)

    def test_persisted_address_change_is_rejected_with_fresh_digest(self):
        config, _ = fixture()
        _, changed = fixture(RAW.replace(b"192.0.2.1/24", b"192.0.2.9/24"), 2)
        with self.assertRaises(RuntimeError):
            worker.validate(config, changed)

    def test_live_address_and_mtu_are_required(self):
        for local, mtu in [("192.0.2.9", 1420), ("192.0.2.1", 1280)]:
            with self.subTest(local=local, mtu=mtu), patch.object(worker, "command", return_value=json.dumps([{"mtu": mtu, "addr_info": [{"family": "inet", "local": local, "prefixlen": 24}]}]).encode()):
                with self.assertRaises(RuntimeError):
                    worker.route_guard("owned-replica", RAW, "192.0.2.1/24", 1420)

    def test_foreign_network_attachment_is_rejected(self):
        config, _ = fixture()
        config.update(machine_id="replica-machine")
        config["resource"].update(container_id="owned-id", image_id="owned-image", network="owned-network")
        value = {"Id": "owned-id", "Image": "owned-image", "State": {"Running": True}, "NetworkSettings": {"Networks": {"owned-network": {}, "foreign-network": {}}}}
        with patch.object(worker.Path, "read_text", return_value="replica-machine"), patch.object(worker, "command", return_value=json.dumps([value]).encode()):
            with self.assertRaises(RuntimeError):
                worker.guard(config)

    def test_only_two_approved_replica_capabilities_are_accepted(self):
        config, _ = fixture()
        config.update(machine_id="replica-machine", server_public_key_sha256=worker.sha(b"server-public"))
        config["resource"].update(container_id="owned-id", image_id="owned-image", network="owned-network", mount_source="/owned/active", config_path="/owned/active/wg0.conf")
        host = {"NetworkMode": "owned-network", "ReadonlyRootfs": True, "Privileged": False, "CapAdd": ["NET_ADMIN", "NET_RAW"], "CapDrop": ["ALL"],
                "Memory": 134217728, "PidsLimit": 64, "NanoCpus": 500000000, "SecurityOpt": ["no-new-privileges:true"],
                "PortBindings": {"55424/udp": [{"HostIp": "0.0.0.0", "HostPort": "55424"}]}}
        value = {"Id": "owned-id", "Image": "owned-image", "State": {"Running": True}, "NetworkSettings": {"Networks": {"owned-network": {}}},
                 "Config": {"Labels": {"org.unifiedvpn.classic-ha": "classic-old"}}, "HostConfig": host,
                 "Mounts": [{"Destination": "/opt/amnezia/awg", "Source": "/owned/active", "Type": "bind", "RW": False}]}
        for caps, accepted in [(["NET_ADMIN", "NET_RAW"], True), (["NET_ADMIN"], False), (["NET_ADMIN", "NET_RAW", "SYS_ADMIN"], False)]:
            with self.subTest(caps=caps):
                host["CapAdd"] = caps
                with patch.object(worker.Path, "read_text", return_value="replica-machine"), patch.object(worker, "command", side_effect=[json.dumps([value]).encode(), b"server-public\n"]):
                    if accepted:
                        worker.guard(config)
                    else:
                        with self.assertRaises(RuntimeError):
                            worker.guard(config)

    def test_source_cannot_receive_and_replica_cannot_publish(self):
        config, payload = fixture()
        with self.assertRaises(RuntimeError):
            worker.snapshot(config)
        config["source"] = True
        with self.assertRaises(RuntimeError):
            worker.receive(config, payload)

    def test_bound_startup_inode_is_preserved(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder, "wg0.conf")
            path.write_bytes(RAW)
            inode = path.stat().st_ino
            worker.replace_bound(path, NEXT)
            self.assertEqual(inode, path.stat().st_ino)
            self.assertEqual(path.read_bytes(), NEXT)

    def test_control_existing_peer_is_idempotent_without_runtime_write(self):
        config, _ = fixture()
        config["source"] = True
        with tempfile.TemporaryDirectory() as folder:
            config["state_root"] = folder
            payload = {"container": "owned-replica", "public": "peer-a", "block": "unused", "enabled": True}
            with patch.object(worker, "read_pair", return_value=RAW), patch.object(worker, "runtime_apply") as apply:
                self.assertEqual(worker.control_peer(config, payload), {"applied": True, "changed": False})
                apply.assert_not_called()

    def test_control_new_peer_collision_fails_before_runtime_write(self):
        config, _ = fixture()
        config["source"] = True
        with tempfile.TemporaryDirectory() as folder:
            config["state_root"] = folder
            payload = {"container": "owned-replica", "public": "peer-b", "enabled": True,
                       "block": "[Peer]\nPublicKey = peer-b\nAllowedIPs = 192.0.2.2/32\n"}
            with patch.object(worker, "read_pair", return_value=RAW), patch.object(worker, "runtime_apply") as apply:
                with self.assertRaises(ValueError):
                    worker.control_peer(config, payload)
                apply.assert_not_called()

    def test_source_control_recovery_holds_on_independent_content(self):
        config, _ = fixture()
        config["source"] = True
        with tempfile.TemporaryDirectory() as folder:
            config["state_root"] = folder
            path = Path(folder, "wg0.conf")
            path.write_bytes(RAW + b"# independent\n")
            config["resource"]["config_path"] = str(path)
            journal = Path(folder, "source-peer.pending.json")
            value = {"authority": worker.authority(config), "before": base64.b64encode(RAW).decode(), "after": base64.b64encode(NEXT).decode(), "before_sha256": worker.sha(RAW), "after_sha256": worker.sha(NEXT)}
            journal.write_text(json.dumps(value))
            with patch.object(worker, "trusted_json", return_value=value), patch.object(worker, "guard"), patch.object(worker, "command", return_value=RAW), patch.object(worker, "runtime_apply") as apply:
                with self.assertRaises(RuntimeError):
                    worker.source_recover(config, journal)
                apply.assert_not_called()

    def test_source_control_recovery_completes_known_full_parameter_state(self):
        config, _ = fixture()
        config["source"] = True
        with tempfile.TemporaryDirectory() as folder:
            config["state_root"] = folder
            path = Path(folder, "wg0.conf")
            path.write_bytes(NEXT)
            config["resource"]["config_path"] = str(path)
            journal = Path(folder, "source-peer.pending.json")
            value = {"authority": worker.authority(config), "before": base64.b64encode(RAW).decode(), "after": base64.b64encode(NEXT).decode(), "before_sha256": worker.sha(RAW), "after_sha256": worker.sha(NEXT)}
            journal.write_text(json.dumps(value))
            with patch.object(worker, "trusted_json", return_value=value), patch.object(worker, "guard"), patch.object(worker, "command", return_value=NEXT), patch.object(worker, "runtime_apply") as apply, patch.object(worker, "read_pair", return_value=NEXT):
                worker.source_recover(config, journal)
                apply.assert_called_once_with(config, NEXT)
            self.assertFalse(journal.exists())

    def test_same_revision_cannot_replace_different_payload(self):
        config, payload = fixture(NEXT)
        _, current = fixture()
        with tempfile.TemporaryDirectory() as folder:
            config["state_root"] = folder
            with patch.object(worker, "status", return_value={"manifest": current["manifest"]}), patch.object(worker, "runtime_apply") as apply:
                with self.assertRaises(RuntimeError):
                    worker.receive(config, payload)
                apply.assert_not_called()

    def test_journal_complete_and_mixed_states_recover_without_restart(self):
        for saved_after, live_after in [(True, True), (False, True), (True, False)]:
            with self.subTest(saved_after=saved_after, live_after=live_after), tempfile.TemporaryDirectory() as folder:
                config, before = fixture()
                _, after = fixture(NEXT, 2)
                root = Path(folder)
                config["state_root"] = folder
                config["resource"]["config_path"] = str(root / "wg0.conf")
                path = root / "wg0.conf"
                path.write_bytes(NEXT if saved_after else RAW)
                inode = path.stat().st_ino
                journal = root / "pending.json"
                journal.write_text(json.dumps({"before": before, "after": after}))
                (root / "applied.json").write_text(json.dumps(before["manifest"]))
                with patch.object(worker, "trusted_json", side_effect=lambda p, **kw: json.loads(Path(p).read_text())), patch.object(worker, "guard"), patch.object(worker, "command", return_value=NEXT if live_after else RAW), patch.object(worker, "runtime_apply") as apply, patch.object(worker, "read_pair"):
                    worker.recover(config, journal)
                complete = saved_after and live_after
                self.assertEqual(path.read_bytes(), NEXT if complete else RAW)
                self.assertEqual(json.loads((root / "applied.json").read_text())["revision"], 2 if complete else 1)
                self.assertEqual(path.stat().st_ino, inode)
                self.assertFalse(journal.exists())
                apply.assert_called_once()

    def test_unknown_journal_state_is_never_overwritten(self):
        with tempfile.TemporaryDirectory() as folder:
            config, before = fixture()
            _, after = fixture(NEXT, 2)
            config["state_root"] = folder
            path = Path(folder, "wg0.conf")
            config["resource"]["config_path"] = str(path)
            foreign = RAW + b"# independently edited\n"
            path.write_bytes(foreign)
            journal = Path(folder, "pending.json")
            journal.write_text(json.dumps({"before": before, "after": after}))
            Path(folder, "applied.json").write_text(json.dumps(before["manifest"]))
            with patch.object(worker, "trusted_json", side_effect=lambda p, **kw: json.loads(Path(p).read_text())), patch.object(worker, "guard"), patch.object(worker, "command", return_value=RAW), patch.object(worker, "runtime_apply") as apply:
                with self.assertRaises(RuntimeError):
                    worker.recover(config, journal)
                apply.assert_not_called()
            self.assertEqual(path.read_bytes(), foreign)

    def test_stale_ack_never_refreshes_freshness(self):
        config, payload = fixture()
        desired = {key: payload["manifest"][key] for key in ["scope", "epoch", "revision", "sha256"]}
        desired.update(sha256="stale", synchronized_at=100)
        with patch.object(worker, "status", return_value={"manifest": payload["manifest"]}), patch.object(worker, "atomic_json") as write:
            with self.assertRaises(RuntimeError):
                worker.acknowledge(config, desired)
            write.assert_not_called()

    def test_ack_timestamp_must_be_finite_recent_and_nonfuture(self):
        config, payload = fixture()
        desired = {key: payload["manifest"][key] for key in ["scope", "epoch", "revision", "sha256"]}
        for at in [True, float("inf"), float("nan"), 101, 9]:
            with self.subTest(at=at), patch.object(worker, "status", return_value={"manifest": payload["manifest"]}), patch.object(worker.time, "time", return_value=100), patch.object(worker, "atomic_json") as write:
                with self.assertRaises(RuntimeError):
                    worker.acknowledge(config, {**desired, "synchronized_at": at})
                write.assert_not_called()


if __name__ == "__main__":
    unittest.main()
