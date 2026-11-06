import copy
from contextlib import closing
import json
from pathlib import Path
import sqlite3
import tempfile
import unittest
from unittest.mock import patch

import vless_sync as sync


class FakeRuntime:
    def __init__(self):
        self.users = {(6, "a"): True, (7, "b"): True, (11, "local"): True}
        self.fail_once = None
        self.changes = []

    def present(self, row, client):
        return self.users.get((row["id"], client["email"]), False)

    def set(self, row, client, active):
        key = (row["id"], client["email"])
        self.changes.append((key, active))
        self.users[key] = False
        if self.fail_once == key and active:
            self.fail_once = None
            raise RuntimeError("injected runtime activation failure")
        self.users[key] = active


class SynchronizationTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.root = Path(self.directory.name)
        self.database = self.root / "x-ui.db"
        self.state = self.root / "receiver.json"
        self.config = {"database": str(self.database), "state": str(self.state), "epoch": 1}
        self.original = {
            6: [{"id": "a", "email": "a", "enable": True}],
            7: [{"id": "b", "email": "b", "enable": True}],
            11: [{"id": "local", "email": "local", "enable": True, "comment": "retain"}],
        }
        connection = sqlite3.connect(self.database)
        connection.executescript("""
            create table inbounds(id integer primary key,enable integer,listen text,port integer,protocol text,stream_settings text,settings text,tag text);
            create table client_traffics(id integer primary key,inbound_id integer,enable integer,email text,up integer,down integer,all_time integer,expiry_time integer,total integer,reset integer,last_online integer);
        """)
        for inbound, clients in self.original.items():
            connection.execute("insert into inbounds values(?,1,'127.0.0.1',?,'vless',?,?,?)",
                               (inbound, inbound, json.dumps({"network": "ws" if inbound == 11 else "xhttp"}), json.dumps({"clients": clients}), "inbound-" + str(inbound)))
            for client in clients:
                connection.execute("insert into client_traffics(inbound_id,enable,email,up,down,all_time,expiry_time,total,reset,last_online) values(?,1,?,9,10,19,0,0,0,0)", (inbound, client["email"]))
        connection.commit()
        connection.close()
        self.state.write_text(json.dumps({"epoch": 1, "revision": 0, "sha256": None, "managed_ids": ["a", "b"], "tombstones": {}}))
        self.runtime = FakeRuntime()
        self.trust = patch.object(sync, "trusted_json", lambda path: json.loads(Path(path).read_text()))
        self.trust.start()

    def tearDown(self):
        self.trust.stop()
        self.directory.cleanup()

    def request(self, clients, revision=1):
        with closing(sqlite3.connect(self.database)) as connection:
            payload = sync.snapshot(connection, {"a", "b"})
        payload["identities"] = sorted(
            [{"inbound_id": inbound, "client": client} for inbound, items in clients.items() for client in items],
            key=lambda item: item["client"]["id"],
        )
        return {"scope": sync.SCOPE, "origin": "primary", "epoch": 1, "revision": revision, "payload": payload, "sha256": sync.digest(payload)}

    def clients(self):
        with closing(sqlite3.connect(self.database)) as connection:
            return {client["id"]: (row[0], client) for row in connection.execute("select id,settings from inbounds") for client in json.loads(row[1])["clients"]}

    def test_move_add_disable_preserves_local_and_counters(self):
        wanted = {6: [{"id": "new", "email": "new", "enable": True}],
                  7: [{"id": "b", "email": "b", "enable": False}],
                  11: [{"id": "a", "email": "a", "enable": True}]}
        result = sync.apply_request(self.config, self.request(wanted), self.runtime)
        self.assertEqual(result["identity_count"], 3)
        self.assertEqual(self.clients()["a"][0], 11)
        self.assertEqual(self.clients()["local"], (11, self.original[11][0]))
        with closing(sqlite3.connect(self.database)) as connection:
            self.assertEqual(connection.execute("select up,down,all_time,inbound_id from client_traffics where email='a'").fetchone(), (9, 10, 19, 11))
            self.assertEqual(connection.execute("select enable from client_traffics where email='b'").fetchone()[0], 0)

    def test_delete_retains_usage_and_tombstone(self):
        wanted = {7: copy.deepcopy(self.original[7])}
        request = self.request(wanted)
        sync.apply_request(self.config, request, self.runtime)
        sync.apply_request(self.config, request, self.runtime)
        self.assertNotIn("a", self.clients())
        self.assertIn("a", json.loads(self.state.read_text())["tombstones"])
        with closing(sqlite3.connect(self.database)) as connection:
            self.assertEqual(connection.execute("select up,down,all_time,enable from client_traffics where email='a'").fetchone(), (9, 10, 19, 0))

    def test_standby_identity_collision_is_rejected(self):
        request = self.request({11: copy.deepcopy(self.original[11])})
        with self.assertRaisesRegex(ValueError, "standby-owned"):
            sync.apply_request(self.config, request, self.runtime)
        self.assertEqual(len(self.clients()), 3)

    def test_runtime_failure_rolls_back_then_retry_repairs(self):
        request = self.request({7: copy.deepcopy(self.original[7]), 11: copy.deepcopy(self.original[6])})
        self.runtime.fail_once = (11, "a")
        with self.assertRaises(RuntimeError):
            sync.apply_request(self.config, request, self.runtime)
        self.assertEqual(self.clients()["a"][0], 6)
        self.assertTrue(self.runtime.users[(6, "a")])
        sync.apply_request(self.config, request, self.runtime)
        self.assertEqual(self.clients()["a"][0], 11)

    def test_crash_after_database_commit_keeps_pending_ownership(self):
        wanted = {6: [*copy.deepcopy(self.original[6]), {"id": "new", "email": "new", "enable": True}], 7: copy.deepcopy(self.original[7])}
        request = self.request(wanted)
        save = sync.atomic_json
        def fail_state(path, value):
            if Path(path) == self.state:
                raise OSError("injected state publication failure")
            save(path, value)
        with patch.object(sync, "atomic_json", fail_state):
            with self.assertRaises(OSError):
                sync.apply_request(self.config, request, self.runtime)
        self.assertIn("new", self.clients())
        sync.apply_request(self.config, request, self.runtime)
        self.assertEqual(json.loads(self.state.read_text())["managed_ids"], ["a", "b", "new"])

    def test_generation_cannot_be_rewritten(self):
        request = self.request({6: copy.deepcopy(self.original[6]), 7: copy.deepcopy(self.original[7])})
        sync.apply_request(self.config, request, self.runtime)
        request["payload"]["identities"][0]["client"]["enable"] = False
        request["sha256"] = sync.digest(request["payload"])
        with self.assertRaisesRegex(ValueError, "generation cannot change"):
            sync.apply_request(self.config, request, self.runtime)

    def test_uuid_rotation_with_same_email_keeps_usage_enabled(self):
        replacement = {"id": "replacement", "email": "a", "enable": True}
        request = self.request({6: [replacement], 7: copy.deepcopy(self.original[7])})
        sync.apply_request(self.config, request, self.runtime)
        with closing(sqlite3.connect(self.database)) as connection:
            self.assertEqual(connection.execute("select up,down,enable from client_traffics where email='a'").fetchone(), (9, 10, 1))

    def test_email_rename_retains_counter_row_and_removes_old_runtime(self):
        renamed = {"id": "a", "email": "renamed", "enable": True, "totalGB": 100}
        request = self.request({6: [renamed], 7: copy.deepcopy(self.original[7])})
        sync.apply_request(self.config, request, self.runtime)
        self.assertFalse(self.runtime.users[(6, "a")])
        self.assertTrue(self.runtime.users[(6, "renamed")])
        with closing(sqlite3.connect(self.database)) as connection:
            self.assertEqual(connection.execute("select id,up,down,all_time from client_traffics where email='renamed'").fetchone(), (1, 9, 10, 19))
            self.assertEqual(connection.execute("select count(*) from client_traffics").fetchone()[0], 3)

    def test_email_swap_preserves_both_counter_rows_with_unique_index(self):
        with closing(sqlite3.connect(self.database)) as connection:
            connection.execute("create unique index unique_email on client_traffics(email)")
            connection.execute("update client_traffics set up=25 where email='b'")
            connection.commit()
        request = self.request({6: [{"id": "a", "email": "b", "enable": True}],
                                7: [{"id": "b", "email": "a", "enable": True}]})
        sync.apply_request(self.config, request, self.runtime)
        with closing(sqlite3.connect(self.database)) as connection:
            self.assertEqual(connection.execute("select id,up from client_traffics where email='b'").fetchone(), (1, 9))
            self.assertEqual(connection.execute("select id,up from client_traffics where email='a'").fetchone(), (2, 25))

    def test_duplicate_standby_uuid_holds_without_changing_runtime(self):
        request = self.request({6: copy.deepcopy(self.original[6]), 7: copy.deepcopy(self.original[7])})
        with closing(sqlite3.connect(self.database)) as connection:
            connection.execute("update inbounds set settings=? where id=11", (json.dumps({"clients": [*self.original[11], self.original[6][0]]}),))
            connection.commit()
        before = dict(self.runtime.users)
        with self.assertRaisesRegex(ValueError, "multiple locations"):
            sync.apply_request(self.config, request, self.runtime)
        self.assertEqual(before, self.runtime.users)

    def test_metadata_only_changes_do_not_recreate_live_users(self):
        client = {**self.original[6][0], "comment": "updated", "expiryTime": 9999999999999, "totalGB": 100}
        request = self.request({6: [client], 7: copy.deepcopy(self.original[7])})
        sync.apply_request(self.config, request, self.runtime)
        self.assertEqual(self.runtime.changes, [])
        self.assertEqual(self.clients()["a"][1], client)


if __name__ == "__main__":
    unittest.main()
