import json,pathlib,tempfile,unittest
from unittest.mock import patch
from server_metrics import cluster_status
class ClusterStatusTests(unittest.TestCase):
 def test_only_public_operational_fields_are_exposed(self):
  with tempfile.TemporaryDirectory() as d:
   p=pathlib.Path(d)/'status.json';p.write_text(json.dumps({'primary':'192.0.2.1','standbyReachable':False,'automaticTrafficFailover':False,'sshKey':'secret','reason':'secret diagnostics'}))
   with patch('server_metrics.failover_snapshot',return_value={'automaticTrafficFailover':True,'healthyBackupAvailable':False,'regionalCoverageDegraded':True}):
    self.assertEqual(cluster_status(p),{'primary':'192.0.2.1','standbyReachable':False,'automaticTrafficFailover':True,'healthyBackupAvailable':False,'regionalCoverageDegraded':True})
 def test_missing_or_invalid_status_does_not_break_metrics(self):
  with tempfile.TemporaryDirectory() as d:
   p=pathlib.Path(d)/'missing';self.assertIsNone(cluster_status(p));p.write_text('{');self.assertIsNone(cluster_status(p))
