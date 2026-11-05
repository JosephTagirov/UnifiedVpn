import gc,json,pathlib,sqlite3,tempfile,time,unittest
from types import SimpleNamespace
from unittest.mock import Mock,patch
import existing

class ExistingTests(unittest.TestCase):
 def setUp(self):
  self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup);self.addCleanup(gc.collect)
  base=pathlib.Path(self.temp.name);existing.init(base)
  self.xui=base/'xui.db'
  self.clients=[{'id':'uuid-1','email':'old-one','enable':True,'expiryTime':0,'totalGB':123456789,'subId':'keep-sub','flow':'','comment':'keep','limitIp':3,'unknown':{'keep':True}},
    {'id':'uuid-2','email':'old-two','enable':False,'expiryTime':0,'totalGB':0,'subId':'keep-two'}]
  with sqlite3.connect(existing.DB) as db:db.execute('create table devices(awg_public text,vless_id text)')
  with sqlite3.connect(self.xui) as db:
   db.executescript('create table inbounds(id integer,protocol text,remark text,port integer,enable integer,settings text,stream_settings text,tag text);create table client_traffics(inbound_id integer,email text,enable integer,expiry_time integer,total integer,up integer,down integer);')
   db.execute('insert into inbounds values(1,?,?,?,?,?,?,?)',('vless','old',8443,0,json.dumps({'clients':self.clients,'decryption':'none','extra':'keep'}),json.dumps({'network':'xhttp','security':'none'}),'inbound-8443'))
   db.execute('insert into client_traffics values(1,?,1,0,123456789,100,200)',('old-one',))
  self.fake=SimpleNamespace(XUI=str(self.xui),XRAY='xray',command=Mock())
  self.addCleanup(patch.stopall);patch.object(existing,'adapters',self.fake).start()
  patch.object(existing,'GROUPS',{}).start()
 def rows(self):
  with sqlite3.connect(self.xui) as db:return json.loads(db.execute('select settings from inbounds').fetchone()[0])
 def resource(self):return existing.discover()['profiles'][0]
 def test_import_is_read_only_and_idempotent(self):
  before=self.xui.read_bytes();a=existing.discover();b=existing.discover()
  self.assertEqual(a,b);self.assertEqual(before,self.xui.read_bytes());self.fake.command.assert_not_called()
  self.assertEqual(len(a['profiles']),2);self.assertNotIn('uuid-1',json.dumps(a));self.assertNotIn('keep-sub',json.dumps(a))
 def test_disable_only_selected_client_preserves_every_other_field(self):
  p=self.resource();existing.modify(p['id'],'disable',{})
  after=self.rows();expected=[{**self.clients[0],'enable':False},self.clients[1]]
  self.assertEqual(after,{'clients':expected,'decryption':'none','extra':'keep'});self.fake.command.assert_not_called()
  existing.modify(p['id'],'enable',{});self.assertEqual(self.rows()['clients'],self.clients)
  with sqlite3.connect(self.xui) as db:self.assertEqual(db.execute('select up,down from client_traffics').fetchone(),(100,200))
 def test_label_and_limits_preserve_credentials_counters_and_inbound(self):
  p=self.resource();existing.modify(p['id'],'save',{'name':'New name','totalGB':25,'expires':2000000000,'comment':'note'})
  c=self.rows()['clients'][0]
  self.assertEqual(c,{**self.clients[0],'totalGB':25*1024**3,'expiryTime':2000000000000,'comment':'note'})
  self.assertEqual(existing.get(p['id'])['name'],'New name');self.fake.command.assert_not_called()
 def test_invalid_save_does_not_partially_apply(self):
  p=self.resource();before=self.xui.read_bytes()
  for data in [{'name':'','totalGB':1},{'name':'safe','totalGB':-1},{'expires':True},{'totalGB':float('nan')}]:
   with self.assertRaises(ValueError):existing.modify(p['id'],'save',data)
   self.assertEqual(before,self.xui.read_bytes())
 def test_live_disable_confirmed_without_restarting_xray(self):
  with sqlite3.connect(self.xui) as db:db.execute('update inbounds set enable=1')
  self.fake.command.return_value=SimpleNamespace(stdout=b'{"users":[]}',returncode=0)
  p=self.resource();existing.modify(p['id'],'disable',{})
  calls=[c.args[0] for c in self.fake.command.call_args_list]
  self.assertEqual([c[2] for c in calls],['rmu','inbounduser']);self.assertTrue(all('inbound-8443' in ' '.join(c) for c in calls))
 def test_legacy_export_uses_configured_host_without_rotating_identity(self):
  p=self.resource();before=self.xui.read_bytes()
  with patch.dict(existing.os.environ,{'UNIFIEDVPN_PUBLIC_HOST':'vpn.example.net'}):
   text=existing.export(p['id'])['text']
  self.assertIn('vless://uuid-1@vpn.example.net:8443?',text)
  self.assertIn('type=xhttp',text);self.assertEqual(before,self.xui.read_bytes())
 def test_unknown_identity_never_changes_inbound(self):
  p=self.resource()
  with sqlite3.connect(self.xui) as db:db.execute('update inbounds set settings=?',(json.dumps({'clients':[self.clients[1]]}),))
  before=self.xui.read_bytes()
  with self.assertRaises(ValueError):existing.modify(p['id'],'disable',{})
  self.assertEqual(before,self.xui.read_bytes());self.fake.command.assert_not_called()
 def test_saved_awg_export_replaces_endpoint_host_and_preserves_original(self):
  id=existing.upsert('awg','test:key','test',{'container':'test','public':'key'},True)
  path=existing.export_path(id);path.parent.mkdir()
  for endpoint in ['192.0.2.4','old.example.net','[2001:db8::4]']:
   with self.subTest(endpoint=endpoint):
    text='[Interface]\r\nPrivateKey = original-private\r\nJc = 4\r\n\r\n[Peer]\r\nPublicKey = original-public\r\nPresharedKey = original-psk\r\n# Endpoint = 192.0.2.99:9999\r\nEndpoint = '+endpoint+':43559 # preserve comment\r\nAllowedIPs = 0.0.0.0/0\r\n'
    path.write_bytes(text.encode());before=path.read_bytes()
    with patch.dict(existing.os.environ,{'UNIFIEDVPN_PUBLIC_HOST':'vpn.example.net'}):result=existing.export(id)
    self.assertEqual(result['text'],text.replace('Endpoint = '+endpoint+':43559','Endpoint = vpn.example.net:43559'))
    self.assertEqual(path.read_bytes(),before);self.assertEqual(result['kind'],'awg')
  self.fake.command.assert_not_called()
 def test_saved_awg_export_rejects_missing_or_ambiguous_endpoint(self):
  for text in ['[Peer]\nPublicKey = key\n','[Peer]\nEndpoint = old:8443\nEndpoint = other:8443\n']:
   with self.subTest(text=text),self.assertRaises(ValueError):existing.portable_awg_config(text)
 def test_saved_awg_export_routes_legacy_server_by_its_public_key(self):
  text='[Peer]\nPublicKey = original-public\nEndpoint = 192.0.2.4:49311\n'
  env={'UNIFIEDVPN_PUBLIC_HOST':'vpn.example.net','UNIFIEDVPN_AWG_HOSTS':json.dumps({'original-public':'legacy.example.net'})}
  with patch.dict(existing.os.environ,env):
   self.assertIn('Endpoint = legacy.example.net:49311',existing.portable_awg_config(text))
   self.assertIn('Endpoint = vpn.example.net:49311',existing.portable_awg_config(text.replace('original-public','current-public')))
 def test_ws_export_uses_configured_address_and_retains_tls_identity(self):
  with sqlite3.connect(self.xui) as db:db.execute('update inbounds set id=11,port=10086,stream_settings=?',(json.dumps({'network':'ws','security':'none','wsSettings':{'path':'/original'}}),))
  p=self.resource();before=self.xui.read_bytes()
  with patch.dict(existing.os.environ,{'UNIFIEDVPN_PUBLIC_HOST':'vpn.example.net'}):text=existing.export(p['id'])['text']
  self.assertIn('vless://uuid-1@vpn.example.net:443?',text)
  self.assertIn('sni=panel.meesharr.space',text);self.assertIn('host=panel.meesharr.space',text)
  self.assertIn('path=%2Foriginal',text);self.assertEqual(self.xui.read_bytes(),before)
 def test_loopback_reality_exports_public_port_and_original_identity(self):
  stream={'network':'tcp','security':'reality','realitySettings':{'privateKey':'private','serverNames':['example.org'],'shortIds':['ab12'],'settings':{'publicKey':'original-public'}}}
  with sqlite3.connect(self.xui) as db:db.execute('update inbounds set port=10443,stream_settings=?',(json.dumps(stream),))
  p=self.resource();before=self.xui.read_bytes();text=existing.export(p['id'])['text']
  self.assertIn('vless://uuid-1@panel.meesharr.space:443?',text)
  self.assertIn('sni=example.org',text);self.assertIn('pbk=original-public',text)
  self.assertNotIn(':10443',text);self.assertEqual(before,self.xui.read_bytes())
 def test_awg_disable_restore_exact_peer_and_keep_other_peers(self):
  patch.object(existing,'GROUPS',{'unifiedvpn-awg3':('awg','awg0','/run/secrets/server.conf')}).start()
  original='[Interface]\nPrivateKey = server-secret\nListenPort = 8443\n\n[Peer]\nPublicKey = one\nPresharedKey = one-psk\nAllowedIPs = 10.0.0.1/32\n\n# keep marker\n[Peer]\nPublicKey = two\nPresharedKey = two-psk\nAllowedIPs = 10.0.0.2/32\n# keep ending\n'
  box={'text':original,'peers':['one','two']}
  def docker(name,*args,**kwargs):
   if args[1]=='set':
    key=args[4]
    if args[-1]=='remove':box['peers']=[p for p in box['peers'] if p!=key]
    elif key not in box['peers']:box['peers'].append(key)
   return SimpleNamespace(stdout=('\n'.join(box['peers'])+'\n').encode())
  patch.object(existing,'docker',side_effect=docker).start();patch.object(existing,'read_config',side_effect=lambda _:box['text']).start();patch.object(existing,'write_config',side_effect=lambda _,text:box.update(text=text)).start()
  block=existing.peer_blocks(original)[0][2]
  id=existing.upsert('awg','unifiedvpn-awg3:one','one',{'container':'unifiedvpn-awg3','public':'one','block':block,'allowed':'10.0.0.1/32'},True)
  existing.modify(id,'disable',{});self.assertNotIn('PublicKey = one',box['text']);self.assertIn('# keep marker',box['text']);self.assertIn('PublicKey = two',box['text'])
  existing.modify(id,'enable',{});self.assertIn(block,box['text']);self.assertEqual(len(existing.peer_blocks(box['text'])),2);self.assertEqual(box['peers'],['two','one'])
 def test_expiry_only_removes_managed_expired_awg_once(self):
  id=existing.upsert('awg','test:key','test',{'container':'test','public':'key','block':'','allowed':''},True,time.time()-1)
  with patch.object(existing,'awg_apply') as apply:
   existing.expire();existing.expire();self.assertEqual(apply.call_count,1);self.assertFalse(apply.call_args.args[1])
  self.assertEqual(existing.get(id)['status'],'expired')
 def test_repair_moves_one_client_without_resetting_identity_or_counters(self):
  with sqlite3.connect(self.xui) as db:db.execute('insert into inbounds values(11,?,?,?,?,?,?,?)',('vless','443',10086,1,json.dumps({'decryption':'none','clients':[]}),json.dumps({'network':'ws','security':'none'}),'inbound-10086'))
  self.fake.inbound=lambda db:(db.execute('select * from inbounds where id=11').fetchone(),{})
  p=self.resource()
  with patch.object(existing,'runtime_client') as runtime:
   existing.repair_vless(p['id']);existing.repair_vless(p['id']);self.assertEqual(runtime.call_count,2)
  with sqlite3.connect(self.xui) as db:
   self.assertEqual(json.loads(db.execute('select settings from inbounds where id=1').fetchone()[0])['clients'],[self.clients[1]])
   self.assertEqual(json.loads(db.execute('select settings from inbounds where id=11').fetchone()[0])['clients'],[self.clients[0]])
   self.assertEqual(db.execute('select inbound_id,up,down from client_traffics').fetchone(),(11,100,200))
  profiles=existing.discover()['profiles'];self.assertEqual(len(profiles),2);self.assertEqual(next(x for x in profiles if x['email']=='old-one')['id'],p['id'])
 def test_delete_one_xui_client_removes_shares_without_touching_other_clients(self):
  p=self.resource()
  with existing.connect() as db:db.execute('insert into existing_shares values(?,?,?)',('test-token',p['id'],time.time()+100))
  with self.assertRaises(ValueError):existing.delete(p['id'],{'confirmName':'wrong'})
  self.assertEqual(self.rows()['clients'],self.clients)
  self.assertTrue(existing.delete(p['id'],{'confirmName':p['name']})['deleted'])
  self.assertEqual(self.rows(),{'clients':[self.clients[1]],'decryption':'none','extra':'keep'})
  self.assertEqual(len(existing.discover()['profiles']),1)
  with existing.connect() as db:self.assertEqual(db.execute('select count(*) from existing_shares').fetchone()[0],0)
  self.assertTrue(existing.delete(p['id'],{'confirmName':p['name']})['deleted'])
 def test_failed_legacy_delete_is_retryable_and_blocks_export(self):
  p=self.resource()
  with patch.object(existing,'runtime_client',side_effect=RuntimeError('temporary')):
   with self.assertRaises(RuntimeError):existing.delete(p['id'],{'confirmName':p['name']})
  self.assertEqual(existing.get(p['id'])['status'],'deleting')
  with self.assertRaises(ValueError):existing.export(p['id'])
  self.assertEqual(next(d for d in existing.discover()['profiles'] if d['id']==p['id'])['status'],'deleting')
  with patch.object(existing,'runtime_client'):existing.resume_deletions()
  self.assertEqual(self.rows()['clients'],[self.clients[1]])
 def test_delete_awg_calls_scoped_revocation_before_removing_saved_record(self):
  id=existing.upsert('awg','test:one','AWG test',{'container':'test','public':'one','block':'','allowed':'10.0.0.1/32'},True)
  with patch.object(existing,'awg_apply') as remove:
   existing.delete(id,{'confirmName':'AWG test'});self.assertEqual(remove.call_count,1)
   self.assertEqual(remove.call_args.args[0]['payload']['public'],'one');self.assertFalse(remove.call_args.args[1])
  with self.assertRaises(ValueError):existing.get(id)

if __name__=='__main__':unittest.main()
