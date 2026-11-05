"""Administer discovered profiles without recreating identities or whole VPN services."""
import base64, contextlib, hashlib, ipaddress, json, os, pathlib, re, sqlite3, tempfile, time
from urllib.parse import quote, urlencode
import adapters

GROUPS={
 'unifiedvpn-awg3':('awg','awg0','/run/secrets/server.conf'),
 'amnezia-awg2':('awg','awg0','/opt/amnezia/awg/awg0.conf'),
 'olcbox-amnezia-awg':('wg','wg0','/opt/amnezia/awg/wg0.conf'),
}
BASE=None
DB=None

@contextlib.contextmanager
def database(path):
 db=sqlite3.connect(path,timeout=20);db.row_factory=sqlite3.Row
 try:
  with db:yield db
 finally:db.close()

def connect():return database(DB)

def init(base):
 global BASE,DB
 BASE=pathlib.Path(base);DB=BASE/'control.db'
 with connect() as db:db.executescript('''
 create table if not exists existing_profiles(id text primary key,kind text not null,locator text not null,
 name text not null,expires real not null default 0,enabled integer not null,status text not null,
 payload text not null,seen real not null);
 create table if not exists existing_shares(token text primary key,profile text not null,expires real not null);
 create table if not exists existing_deleted(id text primary key,name text not null,at real not null);
 ''')

def identity(kind,locator):return hashlib.sha256((kind+':'+locator).encode()).hexdigest()[:32]

def values(text):
 return {k.strip():v.strip() for k,v in (line.split('=',1) for line in text.splitlines() if '=' in line and not line.lstrip().startswith('#'))}

def peer_blocks(text):
 matches=list(re.finditer(r'^\s*\[Peer\][ \t]*\r?$',text,re.M));out=[]
 for i,m in enumerate(matches):
  end=matches[i+1].start() if i+1<len(matches) else len(text)
  block=text[m.start():end];lines=block.splitlines(keepends=True);last=0;offset=0
  for line in lines:
   offset+=len(line)
   if '=' in line and not line.lstrip().startswith('#'):last=offset
  block=block[:last];v=values(block)
  if 'PublicKey' in v:out.append((m.start(),m.start()+last,block,v))
 return out

def docker(name,*args,data=None,check=True):
 if name not in GROUPS:raise ValueError('Неизвестный контейнер AWG')
 return adapters.command(['docker','exec','-i',name,*args],data=data,check=check)

def read_config(name):
 if name=='unifiedvpn-awg3':return adapters.awg_source().read_text()
 return docker(name,'cat',GROUPS[name][2]).stdout.decode()

def write_config(name,text):
 if name=='unifiedvpn-awg3':
  with adapters.awg_source().open('r+') as f:f.write(text);f.truncate();f.flush();os.fsync(f.fileno())
 else:
  # Fixed allowlisted paths, preserving a possible bind-mounted inode.
  docker(name,'sh','-c','cat > '+GROUPS[name][2],data=text.encode())

def upsert(kind,locator,name,payload,enabled,expires=0):
 id=identity(kind,locator)
 with connect() as db:
  row=db.execute('select * from existing_profiles where kind=? and locator=?',(kind,locator)).fetchone()
  if row:id=row['id']
  if not row:
   db.execute('insert into existing_profiles values(?,?,?,?,?,?,?,?,?)',(id,kind,locator,name,expires,int(enabled),'observed',json.dumps(payload),time.time()))
  else:
   # Preserve owner labels, expiry and disabled-peer backup; discovery never changes a VPN.
   db.execute('update existing_profiles set payload=?,seen=? where id=?',(json.dumps(payload),time.time(),id))
 return id

def known_device_keys():
 with connect() as db:
  return {r[0] for r in db.execute('select awg_public from devices')},{r[0] for r in db.execute('select vless_id from devices')}

def discover():
 awg_keys,vless_ids=known_device_keys();seen={};errors=[]
 with database(adapters.XUI) as db:
  db.row_factory=sqlite3.Row
  traffic={r['email']:dict(r) for r in db.execute('select * from client_traffics')}
  for r in db.execute('select * from inbounds'):
   r=dict(r);settings=json.loads(r['settings'] or '{}')
   for c in settings.get('clients',[]):
    credential=c.get('id') or c.get('password') or c.get('email')
    if not credential or (r['id']==11 and c.get('id') in vless_ids):continue
    locator=str(r['id'])+':'+credential
    payload={'inbound':r['id'],'credential':credential,'client':c}
    id=upsert('xui',locator,c.get('email') or 'Клиент x-ui',payload,c.get('enable',True),c.get('expiryTime',0)/1000)
    t=traffic.get(c.get('email'),{});stream=json.loads(r['stream_settings'] or '{}')
    seen[id]={'kind':'xui','group':r['remark'] or r['protocol'],'inbound':r['id'],'protocol':r['protocol'],
     'port':r['port'],'network':stream.get('network','tcp'),'inboundEnabled':bool(r['enable']),
     'enabled':bool(c.get('enable',True)),'expires':c.get('expiryTime',0)/1000,
     'used':int(t.get('up',0))+int(t.get('down',0)),'total':int(c.get('totalGB',0)),
     'canExport':r['protocol']=='vless','email':c.get('email',''),'comment':c.get('comment','')}
 for name in GROUPS:
  try:
   text=read_config(name);binary,interface,_=GROUPS[name]
   dump=docker(name,binary,'show',interface,'dump').stdout.decode().splitlines()
   live={line.split('\t')[0]:line.split('\t') for line in dump[1:]}
   labels={}
   if name=='amnezia-awg2':
    q=docker(name,'cat','/opt/amnezia/awg/clientsTable',check=False)
    if q.returncode==0:
     for c in json.loads(q.stdout):labels[c.get('clientId')]=c.get('userData',{}).get('clientName','')
   for _,_,block,v in peer_blocks(text):
    public=v['PublicKey']
    if name=='unifiedvpn-awg3' and public in awg_keys:continue
    allowed=v.get('AllowedIPs','');ip=allowed.split(',')[0].strip()
    payload={'container':name,'public':public,'block':block,'allowed':allowed}
    id=upsert('awg',name+':'+public,labels.get(public) or 'AWG · '+ip,payload,public in live)
    stats=live.get(public,[])
    seen[id]={'kind':'awg','group':name,'protocol':'AmneziaWG','address':allowed,'enabled':public in live,
     'inboundEnabled':True,'used':sum(int(stats[i]) for i in (5,6)) if len(stats)>6 else 0,
     'lastHandshake':int(stats[4]) if len(stats)>4 else 0,'total':0,'canExport':export_path(id).exists()}
  except Exception:errors.append(name)
 result=[]
 with connect() as db:rows=db.execute('select * from existing_profiles order by kind,name').fetchall()
 for row in rows:
  row=dict(row);s=seen.get(row['id'])
  if s is None:
   if row['kind']=='awg' and (not row['enabled'] or row['status']=='expired'):
    p=json.loads(row['payload']);s={'kind':'awg','group':p['container'],'protocol':'AmneziaWG','address':p['allowed'],
     'enabled':False,'inboundEnabled':True,'used':0,'total':0,'canExport':export_path(row['id']).exists()}
   else:s={'kind':row['kind'],'group':'Недоступен','enabled':False,'inboundEnabled':False,'canExport':False,'missing':True,'used':0,'total':0}
  expires=s.get('expires',row['expires']);expired=expires>0 and expires<time.time();quota=s['total']>0 and s['used']>=s['total']
  result.append({'id':row['id'],'name':row['name'],**s,'expires':expires,
   'status':'deleting' if row['status']=='deleting' else 'missing' if s.get('missing') else 'expired' if expired else 'quota' if quota else 'active' if s['enabled'] and s['inboundEnabled'] else 'disabled'})
 return {'profiles':result,'warnings':errors}

def get(id):
 with connect() as db:r=db.execute('select * from existing_profiles where id=?',(id,)).fetchone()
 if not r:raise ValueError('Профиль не найден; обнови список')
 r=dict(r);r['payload']=json.loads(r['payload']);return r

def name_value(value):
 if not isinstance(value,str) or not 1<=len(value.strip())<=80 or any(ord(c)<32 for c in value):raise ValueError('Название: 1–80 символов')
 return value.strip()

def changed_client(client,action,data):
 c=dict(client)
 if action=='disable':c['enable']=False
 elif action=='enable':
  if c.get('expiryTime',0)>0 and c['expiryTime']<time.time()*1000:raise ValueError('Сначала продли срок')
  c['enable']=True
 elif action=='extend':
  days=data.get('days',30)
  if type(days)!=int or not 1<=days<=365:raise ValueError('Срок: 1–365 дней')
  c['expiryTime']=max(int(time.time()*1000),c.get('expiryTime',0))+days*86400000
 elif action=='save':
  if 'totalGB' in data:
   n=data['totalGB']
   if type(n) not in (int,float) or not 0<=n<=100000:raise ValueError('Лимит: 0–100000 ГиБ')
   c['totalGB']=int(n*1024**3)
  if 'expires' in data:
   n=data['expires']
   if type(n) not in (int,float) or n<0 or n>4102444800:raise ValueError('Некорректный срок')
   c['expiryTime']=int(n*1000)
  if 'comment' in data:
   comment=data['comment']
   if not isinstance(comment,str) or len(comment)>256 or any(ord(x)<32 for x in comment):raise ValueError('Заметка: до 256 символов')
   c['comment']=comment
 else:raise ValueError('Неизвестная операция')
 return c

def runtime_client(row,client,enabled):
 if not row['enable']:return # Never enable an inbound that was disabled in x-ui.
 email=client.get('email','');tag=row['tag']
 if not email or not tag:raise ValueError('У клиента нет имени для Xray API')
 adapters.command([adapters.XRAY,'api','rmu','--server=127.0.0.1:62789','-tag='+tag,email],check=False)
 found=adapters.command([adapters.XRAY,'api','inbounduser','--server=127.0.0.1:62789','-tag='+tag,'-email='+email])
 if any(u.get('email')==email for u in json.loads(found.stdout).get('users',[])):raise RuntimeError('Xray removal was not confirmed')
 if not enabled:return
 account={k:client[k] for k in ['id','email','flow','password','security','alterId','method'] if k in client}
 settings=json.loads(row['settings']);settings['clients']=[account]
 payload={'inbounds':[{'tag':tag,'port':row['port'],'protocol':row['protocol'],'settings':settings}]}
 with tempfile.NamedTemporaryFile(mode='w',suffix='.json',dir='/run/unifiedvpn-control',delete=False) as f:
  os.chmod(f.name,0o600);json.dump(payload,f);path=f.name
 try:
  result=adapters.command([adapters.XRAY,'api','adu','--server=127.0.0.1:62789',path])
  if b'Added 1 user(s) in total' not in result.stdout:raise RuntimeError('Xray activation was not confirmed')
  found=adapters.command([adapters.XRAY,'api','inbounduser','--server=127.0.0.1:62789','-tag='+tag,'-email='+email])
  if not any(u.get('email')==email for u in json.loads(found.stdout).get('users',[])):raise RuntimeError('Xray user missing')
 finally:pathlib.Path(path).unlink(missing_ok=True)

def xui_modify(resource,action,data):
 p=resource['payload']
 with database(adapters.XUI) as db:
  db.row_factory=sqlite3.Row;db.execute('begin immediate')
  row=db.execute('select * from inbounds where id=?',(p['inbound'],)).fetchone()
  if not row:raise ValueError('Вход удалён в x-ui')
  row=dict(row);settings=json.loads(row['settings']);clients=settings.get('clients',[])
  matches=[i for i,c in enumerate(clients) if (c.get('id') or c.get('password') or c.get('email'))==p['credential']]
  if len(matches)!=1:raise ValueError('Клиент изменён в x-ui; обнови список')
  i=matches[0];old=clients[i];new=changed_client(old,action,data)
  traffic=db.execute('select * from client_traffics where inbound_id=? and email=?',(row['id'],old.get('email',''))).fetchone()
  used=int(traffic['up'])+int(traffic['down']) if traffic else 0
  active=lambda c:bool(c.get('enable',True) and not (c.get('expiryTime',0)>0 and c['expiryTime']<time.time()*1000) and not (c.get('totalGB',0)>0 and used>=c['totalGB']))
  if new==old:return
  # Metadata-only edits do not disturb live sessions.
  if active(new)!=active(old):
   try:runtime_client(row,new,active(new))
   except Exception:
    try:runtime_client(row,old,active(old))
    except Exception:pass
    raise
  clients[i]=new;settings['clients']=clients
  db.execute('update inbounds set settings=? where id=?',(json.dumps(settings),row['id']))
  db.execute('update client_traffics set enable=?,expiry_time=?,total=? where inbound_id=? and email=?',
   (int(active(new)),new.get('expiryTime',0),new.get('totalGB',0),row['id'],old.get('email','')))
 with connect() as db:db.execute('update existing_profiles set enabled=?,expires=? where id=?',(int(new.get('enable',True)),new.get('expiryTime',0)/1000,resource['id']))

def repair_vless(id):
 """Move one existing client to the verified HTTPS inbound; retain UUID/subId/counters."""
 r=get(id)
 if r['status']=='deleting':raise ValueError('Профиль удаляется')
 if r['kind']!='xui':raise ValueError('Перенос доступен для VLESS')
 p=r['payload']
 if p['inbound']==11:return {'ok':True}
 with database(adapters.XUI) as db:
  db.execute('attach database ? as cabinet',(str(DB),));db.execute('begin immediate');source=db.execute('select * from inbounds where id=?',(p['inbound'],)).fetchone()
  if not source or source['protocol']!='vless':raise ValueError('Перенос доступен для VLESS')
  source=dict(source);destination,_=adapters.inbound(db);destination=dict(destination)
  old_settings=json.loads(source['settings']);matches=[c for c in old_settings['clients'] if c.get('id')==p['credential']]
  if len(matches)!=1:raise ValueError('Клиент изменён в x-ui; обнови список')
  old=matches[0];new={**old,'flow':''};new_settings=json.loads(destination['settings'])
  if any(c.get('id')==old['id'] or c.get('email')==old.get('email') for c in new_settings['clients']):raise ValueError('Клиент уже есть во входе 443; автоматический перенос остановлен')
  t=db.execute('select * from client_traffics where inbound_id=? and email=?',(source['id'],old.get('email'))).fetchone()
  used=(t['up']+t['down']) if t else 0
  active=bool(old.get('enable',True) and not (old.get('expiryTime',0)>0 and old['expiryTime']<time.time()*1000) and not (old.get('totalGB',0)>0 and used>=old['totalGB']))
  backup=BASE/'existing-migrations';backup.mkdir(mode=0o700,exist_ok=True)
  record={'source':source,'destination':destination,'client':old,'traffic':dict(t) if t else None}
  path=backup/(id+'-'+str(time.time_ns())+'.json');path.write_text(json.dumps(record));path.chmod(0o600)
  try:
   runtime_client(destination,new,active)
   runtime_client(source,old,False)
   old_settings['clients']=[c for c in old_settings['clients'] if c.get('id')!=old['id']];new_settings['clients'].append(new)
   db.execute('update inbounds set settings=? where id=?',(json.dumps(old_settings),source['id']))
   db.execute('update inbounds set settings=? where id=?',(json.dumps(new_settings),destination['id']))
   db.execute('update client_traffics set inbound_id=? where inbound_id=? and email=?',(destination['id'],source['id'],old.get('email')))
   payload={'inbound':destination['id'],'credential':old['id'],'client':new}
   db.execute('update cabinet.existing_profiles set locator=?,payload=? where id=?',(str(destination['id'])+':'+old['id'],json.dumps(payload),id))
  except Exception:
   try:runtime_client(destination,new,False);runtime_client(source,old,active)
   except Exception:pass
   raise
 return {'ok':True}

def awg_apply(resource,enabled):
 p=resource['payload'];name=p['container'];binary,interface,_=GROUPS[name]
 text=read_config(name);matches=[b for b in peer_blocks(text) if b[3]['PublicKey']==p['public']]
 if len(matches)>1:raise ValueError('Дублирующий ключ AWG; изменения остановлены')
 if matches:_,_,block,v=matches[0]
 else:block=p['block'];v=values(block)
 if enabled:
  # Reject address collisions instead of stealing another peer's route.
  requested={ipaddress.ip_network(x.strip(),strict=False) for x in v.get('AllowedIPs','').split(',') if x.strip()}
  for _,_,_,other in peer_blocks(text):
   if other['PublicKey']==p['public']:continue
   networks=[ipaddress.ip_network(x.strip(),strict=False) for x in other.get('AllowedIPs','').split(',') if x.strip()]
   if any(a.version==b.version and a.overlaps(b) for a in requested for b in networks):raise ValueError('Адрес AWG уже занят другим профилем')
  args=[binary,'set',interface,'peer',p['public'],'allowed-ips',v['AllowedIPs']]
  psk=v.get('PresharedKey');data=None
  if psk:args+=['preshared-key','/dev/stdin'];data=(psk+'\n').encode()
  if v.get('PersistentKeepalive'):args+=['persistent-keepalive',v['PersistentKeepalive']]
  docker(name,*args,data=data)
  if not matches:write_config(name,text.rstrip()+'\n\n'+block.rstrip()+'\n')
 else:
  # Save the exact peer before removing only that public key.
  if matches:
   p={**p,'block':block,'allowed':v.get('AllowedIPs','')}
   with connect() as db:db.execute('update existing_profiles set payload=? where id=?',(json.dumps(p),resource['id']))
  docker(name,binary,'set',interface,'peer',p['public'],'remove')
  if matches:
   start,end,_,_=matches[0];write_config(name,text[:start]+text[end:])
 peers=docker(name,binary,'show',interface,'peers').stdout.decode().splitlines()
 if (p['public'] in peers)!=enabled:raise RuntimeError('AWG operation not confirmed')

def modify(id,action,data):
 r=get(id)
 if r['status']=='deleting':raise ValueError('Профиль удаляется')
 if action=='save':
  name=name_value(data.get('name',r['name']))
  # Validate every requested value before touching VPN state.
  if r['kind']=='awg':
   expires=data.get('expires',r['expires'])
   if type(expires) not in (int,float) or not 0<=expires<=4102444800:raise ValueError('Некорректный срок')
  else:changed_client(r['payload']['client'],'save',data)
 if r['kind']=='xui':xui_modify(r,action,data)
 else:
  expires=r['expires'];enabled=r['enabled']
  if action=='disable':awg_apply(r,False);enabled=0
  elif action=='enable':
   if expires>0 and expires<time.time():raise ValueError('Сначала продли срок')
   awg_apply(r,True);enabled=1
  elif action=='extend':
   days=data.get('days',30)
   if type(days)!=int or not 1<=days<=365:raise ValueError('Срок: 1–365 дней')
   expires=max(time.time(),expires)+days*86400
  elif action=='save':expires=data.get('expires',expires)
  else:raise ValueError('Неизвестная операция')
  state='expired' if r['status']=='expired' and action in ['save','extend'] else 'observed'
  with connect() as db:db.execute('update existing_profiles set enabled=?,expires=?,status=? where id=?',(enabled,expires,state,id))
 if action=='save':
  with connect() as db:db.execute('update existing_profiles set name=? where id=?',(name,id))
 with connect() as db:
  if action=='disable':db.execute('delete from existing_shares where profile=?',(id,))
 return {'ok':True}

def export_path(id):
 if not re.fullmatch('[a-f0-9]{32}',id):raise ValueError('Недопустимый профиль')
 return BASE/'existing-exports'/(id+'.conf')

def portable_awg_config(text):
 peers=peer_blocks(text)
 if len(peers)!=1:raise ValueError('В сохранённом конфиге AWG нужен один сервер')
 hosts=json.loads(os.environ.get('UNIFIEDVPN_AWG_HOSTS','{}'))
 host=hosts.get(peers[0][3]['PublicKey'],os.environ.get('UNIFIEDVPN_PUBLIC_HOST','panel.meesharr.space'))
 if ':' in host and not host.startswith('['):host='['+host+']'
 # Keep the original peer port, keys and transport parameters. Saved imports
 # are archival originals and must not be rewritten during an export.
 pattern=r'(?im)^([ \t]*Endpoint[ \t]*=[ \t]*)(?:\[[^\]\r\n]+\]|[^\s:#]+):([0-9]{1,5})([ \t]*(?:#[^\r\n]*)?)(?=\r?$)'
 updated,count=re.subn(pattern,lambda m:m[1]+host+':'+m[2]+m[3],text)
 if count!=1:raise ValueError('В сохранённом конфиге AWG нужен один корректный Endpoint')
 return updated

def export(id):
 r=get(id)
 if r['status']=='deleting':raise ValueError('Профиль удаляется')
 if r['kind']=='awg':
  path=export_path(id)
  if not path.exists():raise ValueError('Приватный ключ не сохранён. Импортируй исходный .conf в карточке профиля')
  with path.open(newline='') as source:text=portable_awg_config(source.read())
  return {'text':text,'filename':'AmneziaWG-'+id[:8]+'.conf','kind':'awg'}
 p=r['payload']
 with database(adapters.XUI) as db:
  db.row_factory=sqlite3.Row;row=db.execute('select * from inbounds where id=?',(p['inbound'],)).fetchone()
  if not row or row['protocol']!='vless':raise ValueError('Экспорт доступен для VLESS и AWG')
  settings=json.loads(row['settings']);clients=[c for c in settings['clients'] if c.get('id')==p['credential']]
  if len(clients)!=1:raise ValueError('Клиент изменён в x-ui')
  c=clients[0];stream=json.loads(row['stream_settings']);network=stream.get('network','tcp');security=stream.get('security','none')
  host=os.environ.get('UNIFIEDVPN_PUBLIC_HOST','panel.meesharr.space');port=row['port'];q={'type':network,'security':security,'encryption':'none'}
  # Original Reality clients share public 443 with HTTPS through the SNI gateway.
  if row['id']==1 and port==10443 and network in ['tcp','raw'] and security=='reality':port=443
  if c.get('flow'):q['flow']=c['flow']
  if security=='reality':
   reality=stream['realitySettings'];extra=reality.get('settings',{})
   q.update({'sni':(reality.get('serverNames') or [''])[0],'pbk':extra.get('publicKey') or adapters.public_key(reality['privateKey']),
    'sid':(reality.get('shortIds') or [''])[0],'fp':extra.get('fingerprint') or 'chrome','spx':extra.get('spiderX') or '/'})
  if network=='xhttp':
   x=stream.get('xhttpSettings',{});q.update({'path':x.get('path','/'),'mode':x.get('mode','auto')})
   if x.get('host'):q['host']=x['host']
  if network=='ws':
   x=stream.get('wsSettings',{});q['path']=x.get('path','/');q['host']=x.get('headers',{}).get('Host','')
  if row['id']==11:port=443;q.update({'security':'tls','sni':'panel.meesharr.space','host':'panel.meesharr.space','fp':'chrome','alpn':'http/1.1'})
  return {'text':'vless://'+c['id']+'@'+host+':'+str(port)+'?'+urlencode(q)+'#'+quote(r['name']),'filename':'VLESS-'+id[:8]+'.txt','kind':'xui'}

def import_config(id,data):
 r=get(id)
 if r['status']=='deleting':raise ValueError('Профиль удаляется')
 if r['kind']!='awg':raise ValueError('Импорт предназначен для AWG')
 text=data.get('text')
 if not isinstance(text,str) or not 1<len(text)<16000:raise ValueError('Нужен исходный клиентский .conf')
 header=text.split('[Peer]')[0];v=values(header);peers=peer_blocks(text)
 private=v.get('PrivateKey','')
 try:public=base64.b64encode(base64.urlsafe_b64decode(adapters.public_key(private)+'=')).decode()
 except Exception:raise ValueError('Некорректный приватный ключ AWG')
 p=r['payload']
 if public!=p['public'] or len(peers)!=1:raise ValueError('Конфиг относится к другому клиенту')
 server_public=docker(p['container'],GROUPS[p['container']][0],'show',GROUPS[p['container']][1],'public-key').stdout.decode().strip()
 if peers[0][3].get('PublicKey')!=server_public:raise ValueError('Конфиг относится к другому серверу AWG')
 address={str(ipaddress.ip_interface(x.strip()).ip) for x in v.get('Address','').split(',') if x.strip()}
 allowed={str(ipaddress.ip_network(x.strip(),strict=False).network_address) for x in p['allowed'].split(',') if x.strip()}
 if not address or not address.issubset(allowed):raise ValueError('Адрес клиента не совпадает с сервером')
 if peers[0][3].get('PresharedKey','')!=values(p['block']).get('PresharedKey',''):raise ValueError('PresharedKey не совпадает с сервером')
 path=export_path(id);path.parent.mkdir(mode=0o700,exist_ok=True);path.write_text(text);path.chmod(0o600)
 # Preserve the filename as an initial friendly label, never as a filesystem path.
 if data.get('name'):
  with connect() as db:db.execute('update existing_profiles set name=? where id=?',(name_value(data['name']),id))
 return {'ok':True}

def expire():
 with connect() as db:rows=db.execute("select * from existing_profiles where kind='awg' and enabled=1 and expires>0 and expires<? and status not in ('expired','deleting')",(time.time(),)).fetchall()
 for row in rows:
  try:
   r=dict(row);r['payload']=json.loads(r['payload']);awg_apply(r,False)
   with connect() as db:db.execute("update existing_profiles set status='expired' where id=?",(r['id'],))
  except Exception:pass
 with connect() as db:db.execute('delete from existing_shares where expires<?',(time.time(),))

def delete(id,data):
 with connect() as db:
  row=db.execute('select * from existing_profiles where id=?',(id,)).fetchone()
  done=db.execute('select name from existing_deleted where id=?',(id,)).fetchone()
 if not row:
  if done and done['name']==data.get('confirmName'):return {'deleted':True}
  raise ValueError('Профиль не найден')
 r=get(id)
 if data.get('confirmName')!=r['name']:raise ValueError('Для удаления введи название профиля')
 if r['status']!='deleting':
  backup=BASE/'existing-deleted';backup.mkdir(mode=0o700,exist_ok=True)
  path=backup/(id+'-'+str(time.time_ns())+'.json');path.write_text(json.dumps(r));path.chmod(0o600)
  with connect() as db:
   db.execute("update existing_profiles set status='deleting' where id=?",(id,));db.execute('delete from existing_shares where profile=?',(id,))
 try:finish_delete(get(id))
 except Exception:raise RuntimeError('Удаление сохранено; отзыв выбранного ключа будет повторён сервером') from None
 return {'deleted':True}

def finish_delete(r):
 p=r['payload'];id=r['id']
 if r['kind']=='awg':awg_apply(r,False)
 else:
  with database(adapters.XUI) as db:
   db.execute('begin immediate');row=db.execute('select * from inbounds where id=?',(p['inbound'],)).fetchone()
   if row:
    row=dict(row);settings=json.loads(row['settings']);clients=settings.get('clients',[])
    matching=[c for c in clients if (c.get('id') or c.get('password') or c.get('email'))==p['credential']]
    if len(matching)>1:raise ValueError('Дублирующий ключ x-ui; удаление остановлено')
    client=matching[0] if matching else p['client'];email=client.get('email','')
    if any(c.get('email')==email and (c.get('id') or c.get('password') or c.get('email'))!=p['credential'] for c in clients):raise ValueError('Имя x-ui занято другим ключом; удаление остановлено')
    runtime_client(row,client,False)
    settings['clients']=[c for c in clients if (c.get('id') or c.get('password') or c.get('email'))!=p['credential']]
    db.execute('update inbounds set settings=? where id=?',(json.dumps(settings),row['id']))
    db.execute('delete from client_traffics where inbound_id=? and email=?',(row['id'],email))
 with connect() as db:
  db.execute('insert or replace into existing_deleted values(?,?,?)',(id,r['name'],time.time()))
  db.execute('delete from existing_shares where profile=?',(id,));db.execute('delete from existing_profiles where id=?',(id,))
 export_path(id).unlink(missing_ok=True)

def resume_deletions():
 with connect() as db:ids=[r[0] for r in db.execute("select id from existing_profiles where status='deleting'")]
 for id in ids:
  try:finish_delete(get(id))
  except Exception:pass
