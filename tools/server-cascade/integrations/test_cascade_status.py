import json,pathlib,tempfile,time,unittest
from server_metrics import cascade_status

class CascadeStatusTests(unittest.TestCase):
 def test_no_scope_credentials_or_diagnostics_in_owner_api(self):
  with tempfile.TemporaryDirectory() as d:
   p=pathlib.Path(d)/'status.json'
   p.write_text(json.dumps({'checkedAt':int(time.time()),'cascadeEnabled':True,'mode':'cascade','scope':{'private':'x'},'secret':'x','reason':'private'}))
   self.assertEqual(cascade_status(p),{'checkedAt':int(time.time()),'cascadeEnabled':True,'mode':'cascade','stale':False})
 def test_stale_state_does_not_claim_live_cascade(self):
  with tempfile.TemporaryDirectory() as d:
   p=pathlib.Path(d)/'status.json'
   p.write_text(json.dumps({'checkedAt':int(time.time())-90,'cascadeEnabled':True,'mode':'cascade'}))
   value=cascade_status(p)
   self.assertTrue(value['stale']);self.assertFalse(value['cascadeEnabled']);self.assertEqual(value['mode'],'unknown')
 def test_missing_and_malformed_state(self):
  with tempfile.TemporaryDirectory() as d:
   p=pathlib.Path(d)/'status.json';self.assertIsNone(cascade_status(p))
   for value in ['{','null','[]','{}']:
    p.write_text(value);self.assertIsNone(cascade_status(p))
