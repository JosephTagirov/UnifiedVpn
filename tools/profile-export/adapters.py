"""Bounded peer adapters. Never replace interfaces, inbounds, or existing clients."""
import zlib, secrets, grp
import rtc_carriers
import base64, contextlib, ipaddress, json, os, pathlib, re, sqlite3, subprocess, tempfile, time
from urllib.parse import urlencode, quote
from cryptography.hazmat.primitives.asymmetric.x25519 import X25519PrivateKey
from cryptography.hazmat.primitives.serialization import Encoding, PublicFormat, PrivateFormat, NoEncryption

PUBLIC_HOST=os.environ.get('UNIFIEDVPN_PUBLIC_HOST','panel.meesharr.space')

XRAY='/usr/local/x-ui/bin/xray-linux-amd64'
XUI='/etc/x-ui/x-ui.db'
CONTAINER='unifiedvpn-awg3'
INTERFACE='awg0'
PREFIX='uvpn443-'

def command(args, data=None, check=True):
    p=subprocess.run(args,input=data,capture_output=True,timeout=20)
    if check and p.returncode:
        # Private root-only diagnostic; never return keys or command output to HTTP clients.
        path=pathlib.Path('/var/lib/unifiedvpn-control/last-adapter-error.private.txt')
        path.write_bytes(p.stderr+p.stdout);path.chmod(0o600)
        raise RuntimeError('Protocol operation failed: '+args[0])
    return p

def awg(*args, data=None, check=True):
    return command(['docker','exec','-i',CONTAINER,'awg',*args],data,check)

def keypair():
    k=X25519PrivateKey.generate()
    return (base64.b64encode(k.private_bytes(Encoding.Raw,PrivateFormat.Raw,NoEncryption())).decode(),
            base64.b64encode(k.public_key().public_bytes(Encoding.Raw,PublicFormat.Raw)).decode())

def public_key(secret):
    return base64.urlsafe_b64encode(X25519PrivateKey.from_private_bytes(base64.urlsafe_b64decode(secret+'='*((-len(secret))%4))).public_key().public_bytes(Encoding.Raw,PublicFormat.Raw)).decode().rstrip('=')

def awg_source():
    doc=json.loads(command(['docker','inspect',CONTAINER]).stdout)[0]
    source=next(m['Source'] for m in doc['Mounts'] if m['Destination']=='/run/secrets/server.conf')
    return pathlib.Path(source)

def awg_parameters():
    text=awg_source().read_text()
    interface=text.split('[Peer]')[0]
    values={k.strip():v.strip() for k,v in (l.split('=',1) for l in interface.splitlines() if '=' in l)}
    values.pop('PrivateKey',None)
    values.pop('ListenPort',None)
    return text, values

def allocate_ip():
    text=awg_source().read_text()
    live=awg('show',INTERFACE,'allowed-ips').stdout.decode()
    for n in range(100,251):
        ip='10.73.31.'+str(n)
        if not re.search(r'(?<![\d.])'+re.escape(ip)+r'/32',text+'\n'+live): return ip
    raise ValueError('Нет свободных адресов AmneziaWG')

def persist_peer(device, enabled):
    path=awg_source()
    text=path.read_text()
    marker='# UnifiedVPN '+device['id']
    end='# End UnifiedVPN '+device['id']
    pattern=re.escape(marker)+r'\n.*?'+re.escape(end)+r'\n?'
    text=re.sub(pattern,'',text,flags=re.S)
    if enabled:
        text+='\n'+marker+'\n[Peer]\nPublicKey = '+device['awg_public']+'\nPresharedKey = '+device['awg_psk']+'\nAllowedIPs = '+device['awg_ip']+'/32\n'+end+'\n'
    # Preserve the inode of Docker's read-only single-file bind mount.
    with path.open('r+') as f:
        f.write(text);f.truncate();f.flush();os.fsync(f.fileno())

def awg_apply(device, enabled):
    if enabled:
        # awg reads the psk from stdin; keys never enter argv or logs.
        awg('set',INTERFACE,'peer',device['awg_public'],'preshared-key','/dev/stdin','allowed-ips',device['awg_ip']+'/32',data=(device['awg_psk']+'\n').encode())
    else: awg('set',INTERFACE,'peer',device['awg_public'],'remove')
    persist_peer(device,enabled)

def inbound(db):
    db.row_factory=sqlite3.Row
    settings=json.loads(pathlib.Path('/var/lib/unifiedvpn-control/vless443/settings.json').read_text())
    row=db.execute('select * from inbounds where id=?',(settings['id'],)).fetchone()
    if not row or not row['enable'] or row['protocol']!='vless' or row['port']!=10086 or row['listen']!='127.0.0.1':
        raise RuntimeError('Ожидаемый VLESS inbound изменён; автоматические изменения остановлены')
    stream=json.loads(row['stream_settings'])
    if stream.get('network')!='ws' or stream.get('security')!='none':
        raise RuntimeError('Параметры VLESS изменены')
    return row,stream

def xray_runtime(device, enabled):
    email=PREFIX+device['id']
    if not re.fullmatch(r'uvpn443-[a-f0-9]{32}',email): raise ValueError('Managed identity required')
    # Removing only the cabinet's own identity makes reconciliation idempotent.
    command([XRAY,'api','rmu','--server=127.0.0.1:62789','-tag=inbound-10086',email],check=False)
    removed=command([XRAY,'api','inbounduser','--server=127.0.0.1:62789','-tag=inbound-10086','-email='+email])
    if any(u.get('email')==email for u in json.loads(removed.stdout).get('users',[])):
        raise RuntimeError('Xray user removal was not confirmed')
    if enabled:
        payload={'inbounds':[{'tag':'inbound-10086','port':10086,'protocol':'vless','settings':{'decryption':'none','clients':[{'id':device['vless_id'],'email':email,'flow':''}]}}]}
        with tempfile.NamedTemporaryFile(mode='w',suffix='.json',dir='/run/unifiedvpn-control',delete=False) as f:
            os.chmod(f.name,0o600);json.dump(payload,f);name=f.name
        try:
            result=command([XRAY,'api','adu','--server=127.0.0.1:62789',name])
            if b'Added 1 user(s) in total' not in result.stdout:
                path=pathlib.Path('/var/lib/unifiedvpn-control/last-adapter-error.private.txt')
                path.write_bytes(result.stdout+result.stderr);path.chmod(0o600)
                raise RuntimeError('Xray did not confirm adding exactly one user')
            found=command([XRAY,'api','inbounduser','--server=127.0.0.1:62789','-tag=inbound-10086','-email='+email])
            if not any(u.get('email')==email for u in json.loads(found.stdout).get('users',[])):
                raise RuntimeError('Xray user activation was not confirmed')
        finally: pathlib.Path(name).unlink(missing_ok=True)

def vless_apply(device, enabled):
    email=PREFIX+device['id']
    with sqlite3.connect(XUI,timeout=15) as db:
        db.execute('begin immediate')
        row,_=inbound(db)
        settings=json.loads(row['settings'])
        matches=[c for c in settings['clients'] if c.get('email')==email or c.get('id')==device['vless_id']]
        if any(c.get('id')!=device['vless_id'] or c.get('email')!=email for c in matches):
            raise RuntimeError('Identity conflict; no existing client was changed')
        clients=[c for c in settings['clients'] if c.get('email')!=email]
        now=int(time.time()*1000)
        clients.append({'id':device['vless_id'],'email':email,'enable':bool(enabled),'flow':'',
                        'expiryTime':int(device['expires']*1000),'limitIp':0,'totalGB':0,'reset':0,
                        'subId':device['id'],'tgId':'','comment':'UnifiedVPN cabinet',
                        'created_at':int(device['created']*1000),'updated_at':now})
        settings['clients']=clients
        db.execute('update inbounds set settings=? where id=?',(json.dumps(settings),row['id']))
        if not db.execute('select 1 from client_traffics where email=?',(email,)).fetchone():
            db.execute('insert into client_traffics(inbound_id,enable,email,up,down,all_time,expiry_time,total,reset,last_online) values(?,?,?,0,0,0,?,0,0,0)',(row['id'],enabled,email,int(device['expires']*1000)))
        else: db.execute('update client_traffics set enable=?,expiry_time=? where email=?',(enabled,int(device['expires']*1000),email))
        xray_runtime(device,enabled)
        # Commit only after the runtime operation succeeds. Reconciliation retries on failures.

def apply(device, enabled):
    protocols=json.loads(device.get('protocols') or '["awg","vless","vkcalls","openflux"]')
    if ('openflux' in protocols and not device.get('openflux_key')) or ('vkcalls' in protocols and not device.get('vkcalls_key')):
        raise RuntimeError('Требуется миграция ключей устройства')
    try:
        if 'vless' in protocols:vless_apply(device,enabled)
        if 'awg' in protocols:awg_apply(device,enabled)
        if 'openflux' in protocols:openflux_apply(device,enabled)
        if 'vkcalls' in protocols:vkcalls_apply(device,enabled)
        for protocol in rtc_carriers.PROVIDERS:
            if protocol in protocols:rtc_carriers.apply(device,protocol,enabled)
    except Exception:
        if enabled:
            for protocol in rtc_carriers.PROVIDERS:
                if protocol in protocols:
                    try:rtc_carriers.apply(device,protocol,False)
                    except Exception:pass
            for protocol,adapter in [('openflux',openflux_apply),('awg',awg_apply),('vless',vless_apply),('vkcalls',vkcalls_apply)]:
                if protocol not in protocols:continue
                try:adapter(device,False)
                except Exception:pass
        raise

def purge_vless(device):
    """Remove exactly the already-revoked managed identity, retaining every other client."""
    email=PREFIX+device['id']
    xray_runtime(device,False)
    with sqlite3.connect(XUI,timeout=20) as db:
        db.execute('begin immediate');row,_=inbound(db);settings=json.loads(row['settings'])
        matches=[c for c in settings['clients'] if c.get('email')==email]
        if any(c.get('id')!=device['vless_id'] for c in matches):raise RuntimeError('Managed identity changed; deletion stopped')
        settings['clients']=[c for c in settings['clients'] if c.get('email')!=email]
        db.execute('update inbounds set settings=? where id=?',(json.dumps(settings),row['id']))
        db.execute('delete from client_traffics where inbound_id=? and email=?',(row['id'],email))

def profiles(device):
    selected=json.loads(device.get('protocols') or '["awg","vless","vkcalls","openflux"]')
    if selected and all(p in rtc_carriers.PROVIDERS for p in selected):return {p:rtc_carriers.profile(device,p) for p in selected}
    _,values=awg_parameters()
    server_public=awg('show',INTERFACE,'public-key').stdout.decode().strip()
    config='[Interface]\nPrivateKey = '+device['awg_private']+'\nAddress = '+device['awg_ip']+'/32\nDNS = 1.1.1.1, 1.0.0.1\nMTU = 1280\n'
    config+=''.join(k+' = '+v+'\n' for k,v in values.items())
    config+='\n[Peer]\nPublicKey = '+server_public+'\nPresharedKey = '+device['awg_psk']+'\nEndpoint = '+PUBLIC_HOST+':8443\nAllowedIPs = 0.0.0.0/0, ::/0\nPersistentKeepalive = 25\n'
    with sqlite3.connect(XUI) as db: row,stream=inbound(db)
    q={'type':'ws','security':'tls','encryption':'none','fp':'chrome','sni':'panel.meesharr.space',
       'path':stream['wsSettings']['path'],'host':'panel.meesharr.space','alpn':'http/1.1'}
    uri='vless://'+device['vless_id']+'@'+PUBLIC_HOST+':443?'+urlencode(q)+'#'+quote('UnifiedVPN · '+device['name'])
    endpoint=PUBLIC_HOST+':'+str(device.get('openflux_port') or 50010)
    session={'name':'UnifiedVPN · '+device['name']+' · OpenFlux Direct','negotiate':True,'codec':'batched','secret':device['openflux_key'],'context':'tcp://'+endpoint,'mode':'','transports':[{'type':'direct','name':'','url':'','dial':endpoint,'priority':50}]}
    raw=json.dumps(session,separators=(',',':'),ensure_ascii=False).encode()
    compressor=zlib.compressobj(wbits=-15);payload=compressor.compress(raw)+compressor.flush()
    openflux='openflux://v1/'+base64.urlsafe_b64encode(payload).decode().rstrip('=')
    vk=json.loads(pathlib.Path('/etc/unifiedvpn-vkcalls-01/server.json').read_text())
    vkcalls='olcrtc://vkcalls?vp8channel<vp8-fps=60&vp8-batch=64>@'+vk['room']['id']+'#'+device['vkcalls_key']+'$UnifiedVPN · '+device['name']
    profiles={'awg':config,'vless':uri,'openflux':openflux,'vkcalls':vkcalls}
    protocols=json.loads(device.get('protocols') or '["awg","vless","vkcalls","openflux"]')
    profiles.update({p:rtc_carriers.profile(device,p) for p in protocols if p in rtc_carriers.PROVIDERS})
    return {p:profiles[p] for p in protocols}



def allocate_port():
    with sqlite3.connect('/var/lib/unifiedvpn-control/control.db') as db:
        used={r[0] for r in db.execute('select openflux_port from devices')}
    live=command(['ss','-H','-lnt']).stdout.decode()
    for port in range(50010,50110):
        if port not in used and not re.search(r':'+str(port)+r'\s',live):return port
    raise ValueError('Все 100 портов OpenFlux заняты')

def openflux_apply(device,enabled):
    id=device['id'];port=device['openflux_port'];key=device['openflux_key']
    if not re.fullmatch('[a-f0-9]{32}',id) or not isinstance(port,int) or not 50010<=port<50110 or not re.fullmatch('[a-f0-9]{64}',key):raise ValueError('Invalid managed OpenFlux identity')
    name='unifiedvpn-openflux@'+id+'.service'
    folder=pathlib.Path('/var/lib/unifiedvpn-control/openflux')/id
    if enabled:
        folder.mkdir(mode=0o700,parents=True,exist_ok=True)
        context='tcp://'+PUBLIC_HOST+':'+str(port)
        config='[Interface]\nRole = exit\nMode = l4\nCodec = batched\nEncryptionKeyFile = /run/credentials/'+name+'/key\nSessionContext = '+context+'\nCookieStore = /var/lib/uvpn-openflux-'+id+'/cookies.json\nDebug = 0\nSensitive = false\n\n[Transport "direct"]\nType = direct\nPriority = 50\nListen = 0.0.0.0:'+str(port)+'\n'
        changed=False
        for file,text in [('key',key+'\n'),('peer.conf',config)]:
            path=folder/file
            if not path.exists() or path.read_text()!=text:
                path.write_text(text);path.chmod(0o600);changed=True
        command(['systemctl','enable',name])
        command(['systemctl','restart' if changed else 'start',name])
        for _ in range(30):
            if re.search(r':'+str(port)+r'\s',command(['ss','-H','-lnt']).stdout.decode()):break
            time.sleep(.1)
        else:raise RuntimeError('OpenFlux listener not confirmed')
        command(['systemctl','is-active',name])
    else:
        command(['systemctl','disable','--now',name])

def vkcalls_apply(device,enabled):
    # DB changes precede reconciliation. Keep the original shared key and all
    # other active cabinet keys, adding/removing only the requested identity.
    legacy=pathlib.Path('/var/lib/unifiedvpn-control/vk-upgrade/legacy-key').read_text().strip()
    with sqlite3.connect('/var/lib/unifiedvpn-control/control.db') as db:
        keys={r[0] for r in db.execute('select vkcalls_key,protocols from devices where enabled=1 and (expires=0 or expires>?)',(time.time(),)) if r[0] and 'vkcalls' in json.loads(r[1] or '["vkcalls"]')}
    if enabled:keys.add(device['vkcalls_key'])
    else:keys.discard(device['vkcalls_key'])
    keys.add(legacy)
    if not all(re.fullmatch('[a-f0-9A-F]{64}',key) for key in keys):raise ValueError('Invalid VK keyring')
    path=pathlib.Path('/etc/unifiedvpn-vkcalls-01/keys-live.txt');text='\n'.join(sorted(keys))+'\n'
    if path.read_text()!=text:
        # Preserve the file inode and group readability for the running server.
        with path.open('r+') as f:f.write(text);f.truncate();f.flush();os.fsync(f.fileno())
        command(['systemctl','kill','--kill-who=main','--signal=HUP','unifiedvpn-vkcalls-01.service'])
        command(['systemctl','is-active','unifiedvpn-vkcalls-01.service'])
