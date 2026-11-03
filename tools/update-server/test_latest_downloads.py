import io,json,pathlib,tempfile,unittest,hashlib
from unittest.mock import patch
import downloads,release_store

class Response:
    def __init__(self,headers=None):self.headers=headers or {};self.wfile=io.BytesIO();self.response_headers={};self.status=None
    def send_response(self,status):self.status=status
    def send_header(self,name,value):self.response_headers[name]=value
    def end_headers(self):pass
    def send_error(self,status):self.status=status

class LatestDownloadsTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.base=pathlib.Path(self.temp.name)
        self.download_patch=patch.object(downloads,'BASE',self.base);self.store_patch=patch.object(release_store,'BASE',self.base)
        self.download_patch.start();self.store_patch.start()
        self.public=self.release('2026110204');self.owner=self.release('2026110205',owner=True)
        (self.base/'release-stable.json').write_text(json.dumps(self.public))
        (self.base/'release-owner.json').write_text(json.dumps(self.owner))
    def tearDown(self):
        self.store_patch.stop();self.download_patch.stop();self.temp.cleanup()
    def release(self,build,owner=False):
        assets=[];sums={}
        for platform,extension in [('windows','exe'),('windows','zip'),('android','apk')]:
            name='UnifiedVPN-'+('Android' if platform=='android' else 'Windows')+'-'+build+'.'+extension
            content=(b'MZ' if extension=='exe' else b'PK')+(build+extension).encode()
            (self.base/name).write_bytes(content);sha=hashlib.sha256(content).hexdigest();sums[name]=sha
            display=f'UnifiedVPN-{"owner-" if owner else ""}0.1.5-build.{build}-{platform}.{extension}'
            assets.append({'name':display,'browser_download_url':'https://panel.meesharr.space/vpn-control/downloads/'+name,'size':len(content),'digest':'sha256:'+sha})
        (self.base/('SHA256-build-'+build+'.json')).write_text(json.dumps(sums))
        return {'tag_name':'v0.1.5','assets':assets}
    def test_legacy_aliases_use_public_and_keep_file_type(self):
        for platform,extension in [('Android','apk'),('Windows','zip'),('Windows','exe')]:
            response=Response()
            self.assertTrue(downloads.serve(response,f'/vpn-control/downloads/UnifiedVPN-{platform}-latest.{extension}',head=True))
            self.assertEqual(response.status,302)
            self.assertEqual(response.response_headers['Location'],f'/vpn-control/downloads/UnifiedVPN-{platform}-2026110204.{extension}')
            self.assertEqual(response.response_headers['Cache-Control'],'no-store')
    def test_public_update_channel_keeps_public_build(self):
        self.assertEqual(release_store.latest(),self.public)
        self.assertTrue(all('2026110204' in asset['browser_download_url'] for asset in release_store.latest()['assets']))
    def test_absent_owner_channel_does_not_affect_public_aliases(self):
        (self.base/'release-owner.json').unlink();response=Response()
        downloads.serve(response,'/vpn-control/downloads/UnifiedVPN-Android-latest.apk',head=True)
        self.assertEqual(response.status,302);self.assertIn('2026110204',response.response_headers['Location'])
    def test_head_range_and_installer_content_type(self):
        path='/vpn-control/downloads/UnifiedVPN-Windows-2026110205.exe'
        response=Response();downloads.serve(response,path,head=True)
        self.assertEqual(response.status,200);self.assertEqual(response.wfile.getvalue(),b'')
        self.assertEqual(response.response_headers['Content-Type'],'application/vnd.microsoft.portable-executable')
        response=Response({'Range':'bytes=0-3'});downloads.serve(response,path)
        self.assertEqual(response.status,206);self.assertEqual(response.wfile.getvalue(),b'MZ20')
        self.assertEqual(response.response_headers['Content-Length'],'4')
    def test_bad_ranges_and_traversal_are_rejected(self):
        path='/vpn-control/downloads/UnifiedVPN-Android-2026110205.apk'
        for value,status in [('bytes=999999-',416),('bytes=1-2,4-5',400)]:
            response=Response({'Range':value});downloads.serve(response,path);self.assertEqual(response.status,status)
        response=Response();downloads.serve(response,'/vpn-control/downloads/../release-owner.json');self.assertEqual(response.status,404)
    def test_invalid_owner_channel_does_not_affect_public_aliases(self):
        self.owner['assets'][0]['browser_download_url']='https://panel.meesharr.space/vpn-control/downloads/../../secret.exe'
        (self.base/'release-owner.json').write_text(json.dumps(self.owner));response=Response()
        downloads.serve(response,'/vpn-control/downloads/UnifiedVPN-Windows-latest.exe',head=True)
        self.assertEqual(response.status,302)
        self.assertEqual(response.response_headers['Location'],'/vpn-control/downloads/UnifiedVPN-Windows-2026110204.exe')
    def test_missing_public_channel_does_not_fall_back_to_owner(self):
        (self.base/'release-stable.json').unlink();response=Response()
        downloads.serve(response,'/vpn-control/downloads/UnifiedVPN-Windows-latest.exe',head=True)
        self.assertEqual(response.status,503)
    def test_invalid_public_channel_fails_closed(self):
        self.public['assets'][0]['browser_download_url']='https://panel.meesharr.space/vpn-control/downloads/../../secret.exe'
        (self.base/'release-stable.json').write_text(json.dumps(self.public));response=Response()
        downloads.serve(response,'/vpn-control/downloads/UnifiedVPN-Windows-latest.exe',head=True)
        self.assertEqual(response.status,503)
    def test_owner_build_in_public_channel_fails_closed(self):
        (self.base/'release-stable.json').write_text(json.dumps(self.owner));response=Response()
        downloads.serve(response,'/vpn-control/downloads/UnifiedVPN-Android-latest.apk',head=True)
        self.assertEqual(response.status,503)
    def test_retention_preserves_active_owner_build(self):
        self.release('2026110206');self.release('2026110207')
        result=release_store.publish('2026110207','0.1.5','https://panel.meesharr.space')
        self.assertEqual(result['keptBuilds'],['2026110207','2026110206','2026110205'])
        self.assertTrue((self.base/'UnifiedVPN-Android-2026110205.apk').exists())
        self.assertFalse((self.base/'UnifiedVPN-Android-2026110204.apk').exists())

if __name__=='__main__':unittest.main()
