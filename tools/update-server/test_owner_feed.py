import json,pathlib,tempfile,unittest
from unittest.mock import patch
import release_store

class OwnerFeed(unittest.TestCase):
 def test_independent_feed_is_read_fresh(self):
  with tempfile.TemporaryDirectory() as directory,patch.object(release_store,'BASE',pathlib.Path(directory)):
   base=pathlib.Path(directory)
   public={'assets':[{'name':'UnifiedVPN-public.apk'}]}
   owner={'assets':[{'name':'UnifiedVPN-owner-android.apk'}]}
   (base/'release-stable.json').write_text(json.dumps(public))
   (base/'release-owner.json').write_text(json.dumps(owner))
   self.assertEqual(release_store.latest(),public)
   self.assertEqual(release_store.owner_latest(),owner)
   owner['assets'][0]['name']='UnifiedVPN-owner-new.apk'
   (base/'release-owner.json').write_text(json.dumps(owner))
   self.assertEqual(release_store.owner_latest(),owner)
 def test_missing_owner_does_not_downgrade_to_public(self):
  with tempfile.TemporaryDirectory() as directory,patch.object(release_store,'BASE',pathlib.Path(directory)):
   (pathlib.Path(directory)/'release-stable.json').write_text('{"assets":[]}')
   with self.assertRaises(ValueError):release_store.owner_latest()
 def test_wrong_edition_is_rejected(self):
  with tempfile.TemporaryDirectory() as directory,patch.object(release_store,'BASE',pathlib.Path(directory)):
   for assets in [[],[{'name':'UnifiedVPN-public.apk'}]]:
    (pathlib.Path(directory)/'release-owner.json').write_text(json.dumps({'assets':assets}))
    with self.assertRaises(ValueError):release_store.owner_latest()
