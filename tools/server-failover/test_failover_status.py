import json
from pathlib import Path
import tempfile
import unittest

from failover_status import TIMERS, configuration_digest, snapshot


class FailoverStatusTests(unittest.TestCase):
    def status(self, *, enabled=True, regional=None, age=0, active_timer=True, health_change=None, config_change=None):
        with tempfile.TemporaryDirectory() as directory:
            health = Path(directory, "health.json")
            health_value = {
                "checked_at": 1000 - age, "active": "standby",
                "regional": regional or {"primary": "failed", "standby": "healthy"},
                "dataplane_ready": {"primary": True, "standby": True},
                "backup_successes": {"primary": 3, "standby": 3},
                "valid_until": 1120 - age,
            }
            config = Path(directory, "config.json")
            config_value = {
                "enabled": enabled, "control_writer": "primary", "health_file": str(health),
                "policy": {"addresses": {"primary": "192.0.2.1", "standby": "192.0.2.2"}, "protocols": ["vless"], "mode": "regional"},
            }
            health_value["configuration_digest"] = configuration_digest(config_value)
            health_value.update(health_change or {})
            health.write_text(json.dumps(health_value))
            config_value["policy"].update(config_change or {})
            config.write_text(json.dumps(config_value))
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

    def test_changed_addresses_or_coverage_invalidate_previous_health(self):
        for changed in [{"addresses": {"primary": "192.0.2.3", "standby": "192.0.2.2"}}, {"protocols": ["vless", "awg"]}]:
            value = self.status(regional={"primary": "healthy", "standby": "healthy"}, config_change=changed)
            self.assertFalse(value["healthyBackupAvailable"])

    def test_summary_cannot_extend_underlying_probe_expiration(self):
        value = self.status(regional={"primary": "healthy", "standby": "healthy"}, health_change={"valid_until": 999})
        self.assertFalse(value["healthyBackupAvailable"])

    def test_malformed_observation_shapes_do_not_break_the_metrics_api(self):
        for field in ("regional", "dataplane_ready", "backup_successes"):
            value = self.status(regional={"primary": "healthy", "standby": "healthy"}, health_change={field: []})
            self.assertFalse(value["healthyBackupAvailable"])
            self.assertTrue(value["regionalCoverageDegraded"])

    def test_invalid_expiration_or_recovery_counter_cannot_establish_health(self):
        for expiration in (float("inf"), float("nan"), True, "1120"):
            value = self.status(regional={"primary": "healthy", "standby": "healthy"}, health_change={"valid_until": expiration})
            self.assertFalse(value["healthyBackupAvailable"])
        for count in (float("inf"), True, "3"):
            value = self.status(regional={"primary": "healthy", "standby": "healthy"}, health_change={"backup_successes": {"primary": count}})
            self.assertFalse(value["healthyBackupAvailable"])


if __name__ == "__main__":
    unittest.main()
