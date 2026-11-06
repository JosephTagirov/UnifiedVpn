import json
from pathlib import Path
import tempfile
import unittest

from failover_status import TIMERS, snapshot


class FailoverStatusTests(unittest.TestCase):
    def status(self, *, enabled=True, regional=None, age=0, active_timer=True):
        with tempfile.TemporaryDirectory() as directory:
            health = Path(directory, "health.json")
            health.write_text(json.dumps({
                "checked_at": 1000 - age, "active": "standby",
                "regional": regional or {"primary": "failed", "standby": "healthy"},
                "dataplane_ready": {"primary": True, "standby": True},
                "backup_successes": {"primary": 3, "standby": 3},
            }))
            config = Path(directory, "config.json")
            config.write_text(json.dumps({
                "enabled": enabled, "control_writer": "primary", "health_file": str(health),
                "policy": {"addresses": {"primary": "192.0.2.1", "standby": "192.0.2.2"}, "protocols": ["vless"], "mode": "regional"},
            }))
            units = {name: {"ActiveState": "active" if active_timer else "inactive", "UnitFileState": "enabled"} for name in TIMERS}
            return snapshot(config, units=units, now=1000)

    def test_enabled_policy_does_not_imply_healthy_russian_backup(self):
        value = self.status()
        self.assertTrue(value["automaticTrafficFailover"])
        self.assertFalse(value["healthyBackupAvailable"])
        self.assertTrue(value["regionalCoverageDegraded"])
        self.assertEqual(value["failoverStatus"], "enabled_degraded")

    def test_disabled_or_stopped_monitor_is_not_automatic(self):
        self.assertFalse(self.status(enabled=False)["automaticTrafficFailover"])
        self.assertFalse(self.status(active_timer=False)["automaticTrafficFailover"])

    def test_stale_region_result_cannot_claim_backup_health(self):
        value = self.status(regional={"primary": "healthy", "standby": "healthy"}, age=121)
        self.assertFalse(value["healthyBackupAvailable"])
        self.assertTrue(value["regionalCoverageDegraded"])

    def test_fresh_healthy_protected_backup_is_reported_separately(self):
        value = self.status(regional={"primary": "healthy", "standby": "healthy"})
        self.assertTrue(value["healthyBackupAvailable"])
        self.assertFalse(value["regionalCoverageDegraded"])
        self.assertEqual(value["controlWriter"], "primary")
        self.assertFalse(value["automaticControlPromotion"])


if __name__ == "__main__":
    unittest.main()
