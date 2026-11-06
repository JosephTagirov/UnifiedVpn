import unittest

from readiness import localize_observation


class ReadinessAgeTests(unittest.TestCase):
    def test_pinned_peer_clock_ahead_preserves_fresh_local_age(self):
        value = {"observed_at": 10000.3, "scopes": {"one": {"checked_at": 10000.1}}, "dataplane": {"vless": {"checked_at": 9990.3, "failure_checked_at": 9980.3}}}
        localize_observation(value, received_at=10000.0, elapsed=.1)
        self.assertAlmostEqual(value["scopes"]["one"]["checked_at"], 9999.7)
        self.assertAlmostEqual(value["dataplane"]["vless"]["checked_at"], 9989.9)
        self.assertAlmostEqual(value["dataplane"]["vless"]["failure_checked_at"], 9979.9)

    def test_old_evidence_remains_old_when_peer_clock_is_behind(self):
        value = {"observed_at": 5000.0, "scopes": {"one": {"checked_at": 4000.0}}}
        localize_observation(value, received_at=10000.0, elapsed=1)
        self.assertEqual(value["scopes"]["one"]["checked_at"], 8999)

    def test_missing_anchor_and_future_peer_measurement_are_unknown(self):
        with self.assertRaises(ValueError):
            localize_observation({"scopes": {}}, received_at=10000, elapsed=0)
        value = {"observed_at": 10000, "scopes": {"one": {"checked_at": 10001}}}
        localize_observation(value, received_at=10000, elapsed=0)
        self.assertIsNone(value["scopes"]["one"]["checked_at"])


if __name__ == "__main__":
    unittest.main()
