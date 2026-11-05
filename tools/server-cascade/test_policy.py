import unittest
from policy import decide


class PolicyTests(unittest.TestCase):
    def setUp(self):
        self.entry = {"availableMiB": 1000, "freeDiskMiB": 4000, "cpuBusyPct": 40}
        self.exit = {
            "availableMiB": 2000,
            "freeDiskMiB": 10000,
            "cpuBusyPct": 30,
            "internetOk": True,
            "internetAgeSeconds": 3,
        }

    def test_probe_failure_and_operator_test_choose_direct(self):
        self.assertFalse(decide(self.entry, self.exit, reachable=False).enabled)
        self.assertFalse(decide(self.entry, self.exit, forced_failure=True).enabled)
        self.assertFalse(decide(self.entry, dict(self.exit, internetOk=False)).enabled)

    def test_reserves_on_either_node_are_required(self):
        for node, key, value in [
            ("entry", "availableMiB", 383),
            ("entry", "cpuBusyPct", 88),
            ("entry", "freeDiskMiB", 511),
            ("exit", "availableMiB", 639),
            ("exit", "cpuBusyPct", 92),
            ("exit", "freeDiskMiB", 1023),
        ]:
            entry = dict(self.entry)
            exit = dict(self.exit)
            (entry if node == "entry" else exit)[key] = value
            with self.subTest(node=node, key=key):
                self.assertFalse(decide(entry, exit).enabled)

    def test_moderate_load_reduces_extra_hop_rate(self):
        self.assertEqual(decide(self.entry, self.exit).rate_mbit, 120)
        self.entry["cpuBusyPct"] = 70
        result = decide(self.entry, self.exit)
        self.assertTrue(result.enabled)
        self.assertEqual(result.rate_mbit, 72)

    def test_probe_expiry_uses_exit_age_instead_of_cross_host_wall_clock(self):
        self.exit["checkedAt"] = 9999999999
        self.assertTrue(decide(self.entry, self.exit).enabled)
        self.exit["internetAgeSeconds"] = 90
        self.assertFalse(decide(self.entry, self.exit).enabled)

    def test_connection_table_pressure_on_either_node_protects_current_sessions(self):
        for target in ["entry", "exit"]:
            entry = dict(self.entry)
            exit = dict(self.exit)
            node = entry if target == "entry" else exit
            node.update(conntrackCount=52429, conntrackMax=65536)
            self.assertFalse(decide(entry, exit).enabled)


if __name__ == "__main__":
    unittest.main()
