"""Independent, verified RTC relays with per-device revocation and owner setup."""
import contextlib,hashlib,json,os,pathlib,re,secrets,socket,sqlite3,subprocess,tempfile,time,urllib.request
try:import grp
except ImportError:grp=None
from urllib.parse import urlsplit

PROVIDERS={'salutejazz':'Салют Jazz','mtslink':'МТС Линк','jitsi':'Jitsi · Riddlerx'}
PORTS={'salutejazz':19851,'mtslink':19852,'jitsi':19853}
ROOT=pathlib.Path('/etc/unifiedvpn-rtc')
STATE=pathlib.Path('/var/lib/unifiedvpn-control/rtc')
DB=pathlib.Path('/var/lib/unifiedvpn-control/control.db')
BINARY='/opt/unifiedvpn-rtc/olcrtc'
ORIGIN='https://panel.meesharr.space'
JITSI_HOST='meet.riddlerx.org'


def directory(protocol):
    if protocol not in PROVIDERS:raise ValueError('Неизвестный канал')
    return ROOT/protocol


def unit(protocol):
    directory(protocol)
    return 'unifiedvpn-rtc@'+protocol+'.service'


def private_write(path,text,shared=False):
    path.parent.mkdir(parents=True,exist_ok=True,mode=0o700)
    path.write_text(text,encoding='utf-8');path.chmod(0o640 if shared else 0o600)
    if shared and path.stat().st_gid!=grp.getgrnam('unifiedvpn-rtc').gr_gid:os.chown(path,0,grp.getgrnam('unifiedvpn-rtc').gr_gid)


def command(args,timeout=25):
    result=subprocess.run(args,capture_output=True,timeout=timeout)
    if result.returncode:
        private_write(STATE/'last-command-error.private.log',result.stderr.decode(errors='replace'))
        raise RuntimeError('Не удалось применить настройки нового канала')
    return result


def valid_mts_room(value):
    if not isinstance(value,str):return False
    try:
        u=urlsplit(value)
        return (u.scheme=='https' and u.hostname=='my.mts-link.ru' and u.port in [None,443]
            and not u.username and not u.password and not u.query and not u.fragment
            and bool(re.fullmatch(r'/j/[0-9]{1,20}/[0-9]{1,20}(?:/session/[0-9]{1,20})?/?',u.path)))
    except ValueError:return False


def unavailable_reason(protocol):
    if protocol!='jitsi':return None
    path=STATE/'jitsi-probe.private.log'
    try:
        with path.open('rb') as f:
            f.seek(0,os.SEEK_END);f.seek(max(0,f.tell()-8192));text=f.read().decode(errors='replace')
        if 'IP restriction' in text and JITSI_HOST in text:
            return JITSI_HOST+' отклоняет IP сервера (IP restriction). Нужен разрешённый провайдером доступ; затем повтори проверку.'
    except OSError:pass
    return None


def status(protocol):
    path=directory(protocol)/'server.json'
    result={'name':PROVIDERS[protocol],'ready':False,'configured':path.is_file(),'needsRoom':protocol=='mtslink'}
    reason=unavailable_reason(protocol)
    if reason:result['reason']=reason
    proof=STATE/(protocol+'-verified.json')
    if not path.is_file() or not proof.is_file():return result
    try:
        verified=json.loads(proof.read_text())
        with urllib.request.build_opener(urllib.request.ProxyHandler({})).open('http://127.0.0.1:'+str(PORTS[protocol])+'/stats',timeout=1) as r:live=json.load(r)
        current_hash=hashlib.sha256(path.read_bytes()).hexdigest()
        matching=verified.get('configurationHash')==current_hash if protocol=='jitsi' else verified.get('configurationHash',current_hash)==current_hash
        result.update(ready=bool(matching and verified.get('httpsVerified') and live.get('link')=='up'),checkedAt=verified.get('checkedAt'))
        if result['ready']:result.pop('reason',None)
    except (OSError,ValueError):pass
    return result


def statuses():return {p:status(p) for p in PROVIDERS}


def require_available(protocol):
    if protocol in PROVIDERS:
        current=status(protocol)
        if not current['ready']:raise ValueError(current.get('reason') or PROVIDERS[protocol]+': сначала настрой и проверь канал во вкладке «Каналы»')


def live_keys(protocol):
    health=(STATE/(protocol+'-health.key')).read_text().strip()
    with contextlib.closing(sqlite3.connect(DB)) as db:
        rows=db.execute('select '+protocol+'_key,protocols from devices where enabled=1 and (expires=0 or expires>?)',(time.time(),))
        keys={key for key,protocols in rows if key and protocol in json.loads(protocols or '[]')}
    keys.add(health)
    if not all(re.fullmatch('[a-fA-F0-9]{64}',key) for key in keys):raise ValueError('Некорректное кольцо ключей RTC')
    return keys


def write_keyring(protocol,keys):
    path=directory(protocol)/'keys-live.txt';text='\n'.join(sorted(keys))+'\n'
    if path.exists() and path.read_text()==text:return False
    # Keep the inode and group readability used by the running daemon.
    with path.open('w') as f:f.write(text);f.flush();os.fsync(f.fileno())
    path.chmod(0o640)
    if path.stat().st_gid!=grp.getgrnam('unifiedvpn-rtc').gr_gid:os.chown(path,0,grp.getgrnam('unifiedvpn-rtc').gr_gid)
    return True


def jitsi_device_rooms():
    return (directory('jitsi')/'device-rooms.enabled').is_file()


def jitsi_device_tag(key):
    return hashlib.sha256(key.encode('ascii')).hexdigest()[:24]


def jitsi_device_room(cfg,key):
    return cfg['room']['id']+'-d'+jitsi_device_tag(key)


def apply_jitsi_device(cfg,key,enabled):
    # The Jitsi engine reads one remote video track. Give each device its own
    # two-participant room, so another client's track cannot capture the latch.
    tag=jitsi_device_tag(key)
    path=directory('jitsi')/'devices'/(tag+'.json')
    service='unifiedvpn-jitsi@'+tag+'.service'
    if not enabled:
        if path.exists():
            command(['systemctl','disable','--now',service])
            path.unlink()
        return
    worker=json.loads(json.dumps(cfg));worker.pop('jitsi_device_rooms',None)
    worker['room']={'id':jitsi_device_room(cfg,key)}
    worker['crypto']={'key':key};worker.pop('stats',None)
    path.parent.mkdir(parents=True,exist_ok=True,mode=0o750)
    path.parent.chmod(0o750)
    os.chown(path.parent,0,grp.getgrnam('unifiedvpn-rtc').gr_gid)
    text=json.dumps(worker)
    changed=path.exists() and path.read_text()!=text
    private_write(path,text,shared=True)
    command(['systemctl','enable','--now',service])
    if changed:command(['systemctl','restart',service])


def apply(device,protocol,enabled):
    if not (directory(protocol)/'server.json').is_file():
        if enabled:require_available(protocol)
        return
    key=device.get(protocol+'_key')
    if not key:
        if enabled:raise ValueError('Нужен отдельный ключ нового канала')
        return
    keys=live_keys(protocol)
    if enabled:keys.add(key)
    else:keys.discard(key)
    if write_keyring(protocol,keys):
        command(['systemctl','kill','--kill-who=main','--signal=HUP',unit(protocol)])
        command(['systemctl','is-active',unit(protocol)])
    if protocol=='jitsi':
        cfg=json.loads((directory(protocol)/'server.json').read_text())
        if jitsi_device_rooms():apply_jitsi_device(cfg,key,enabled)


def profile(device,protocol):
    cfg=json.loads((directory(protocol)/'server.json').read_text())
    key=device.get(protocol+'_key')
    if not key or not re.fullmatch('[a-fA-F0-9]{64}',key):raise ValueError('Не найден ключ канала')
    transport=cfg.get('net',{}).get('transport','datachannel' if protocol=='salutejazz' else 'vp8channel')
    if transport=='vp8channel':
        vp8=cfg.get('vp8',{});transport+='<'+'vp8-fps='+str(vp8.get('fps',60))+'&vp8-batch='+str(vp8.get('batch_size',64))+'>'
    elif transport!='datachannel':raise ValueError('Неподдерживаемый транспорт RTC')
    room=jitsi_device_room(cfg,key) if protocol=='jitsi' and jitsi_device_rooms() else cfg['room']['id']
    return 'olcrtc://'+protocol+'?'+transport+'@'+room+'#'+key+'$'+PROVIDERS[protocol]+' · '+device['name']


def new_jazz_room():
    cfg=STATE/'gen.json'
    private_write(cfg,json.dumps({'mode':'gen','auth':{'provider':'salutejazz'},'gen':{'amount':1},'net':{'transport':'datachannel','dns':'1.1.1.1:53'}}))
    r=command([BINARY,str(cfg)],60)
    rooms=[s.strip() for s in r.stdout.decode().splitlines() if re.fullmatch('[A-Za-z0-9_-]{1,128}:[A-Za-z0-9_-]{1,256}',s.strip())]
    if len(rooms)!=1:raise RuntimeError('Провайдер не вернул новую комнату Jazz')
    return rooms[0]


def probe(protocol):
    configuration=(directory(protocol)/'server.json').read_bytes()
    cfg=json.loads(configuration)
    cfg['mode']='cnc';cfg.pop('stats',None)
    cfg['crypto']={'key':(STATE/(protocol+'-health.key')).read_text().strip()}
    with socket.socket() as s:s.bind(('127.0.0.1',0));port=s.getsockname()[1]
    cfg['socks']={'host':'127.0.0.1','port':port,'user':'','pass':''}
    env={k:v for k,v in os.environ.items() if k.lower() not in ['http_proxy','https_proxy','all_proxy','no_proxy']};env['GOMEMLIMIT']='128MiB'
    with tempfile.TemporaryDirectory(prefix='probe-',dir=STATE) as d:
        config=pathlib.Path(d)/'client.json';private_write(config,json.dumps(cfg))
        with (STATE/(protocol+'-probe.private.log')).open('wb') as log:
            os.chmod(log.name,0o600)
            proc=subprocess.Popen([BINARY,str(config)],stdout=log,stderr=subprocess.STDOUT,env=env)
            try:
                end=time.monotonic()+50
                while True:
                    if proc.poll() is not None or time.monotonic()>end:raise RuntimeError(unavailable_reason(protocol) or 'RTC-клиент не подключился к провайдеру')
                    try:
                        with socket.create_connection(('127.0.0.1',port),timeout=.3):break
                    except OSError:time.sleep(.2)
                r=subprocess.run(['curl','--silent','--fail','--noproxy','','--proxy','socks5h://127.0.0.1:'+str(port),'--connect-timeout','8','--max-time','30',ORIGIN+'/vpn-control/health'],env=env,capture_output=True,timeout=35)
                if r.returncode or not json.loads(r.stdout).get('ready'):raise RuntimeError('Канал не передал проверочный HTTPS-запрос')
            finally:
                proc.terminate()
                try:proc.wait(3)
                except subprocess.TimeoutExpired:proc.kill();proc.wait(3)
    proof={'httpsVerified':True,'checkedAt':int(time.time()),'configurationHash':hashlib.sha256(configuration).hexdigest()}
    private_write(STATE/(protocol+'-verified.json'),json.dumps(proof))
    return proof


def configure(protocol,data):
    path=directory(protocol)/'server.json';STATE.mkdir(parents=True,exist_ok=True,mode=0o700)
    previous=path.read_bytes() if path.exists() else None
    value=data.get('room','')
    if not isinstance(value,str):raise ValueError('Ссылка встречи должна быть строкой')
    room=value.strip() if protocol=='mtslink' else None
    if protocol=='mtslink' and data.get('checkOnly') is True and previous:room=json.loads(previous)['room']['id']
    if protocol=='mtslink' and not valid_mts_room(room):raise ValueError('Вставь ссылку постоянной встречи: https://my.mts-link.ru/j/123/456')
    if previous:
        old=json.loads(previous)
        if protocol in ['salutejazz','jitsi'] or old['room']['id']==room:
            was_active=subprocess.run(['systemctl','is-active',unit(protocol)],capture_output=True,timeout=5).returncode==0
            try:
                command(['systemctl','start',unit(protocol)])
                probe(protocol)
                if protocol=='jitsi':command(['systemctl','enable',unit(protocol)])
                return status(protocol)
            except Exception:
                if not was_active:subprocess.run(['systemctl','stop',unit(protocol)],capture_output=True,timeout=25)
                raise
        with contextlib.closing(sqlite3.connect(DB)) as db:
            if any(protocol in json.loads(row[0] or '[]') for row in db.execute('select protocols from devices')):
                raise ValueError('Встреча уже используется профилями. Сначала убери этот канал из них, чтобы изменить ссылку')
    if protocol=='salutejazz':room=new_jazz_room()
    if protocol=='jitsi':room='https://'+JITSI_HOST+'/uvpn-'+secrets.token_hex(16)
    health=STATE/(protocol+'-health.key')
    if not health.exists():private_write(health,secrets.token_hex(32))
    cfg={'mode':'srv','auth':{'provider':protocol},'room':{'id':room},'crypto':{'keys_file':str(directory(protocol)/'keys-live.txt')},
         'net':{'transport':'datachannel' if protocol=='salutejazz' else 'vp8channel','dns':'1.1.1.1:53'},
         'dtls':{'profile':'chrome-linux-138-compat-v1'},'vp8':{'fps':60,'batch_size':64},'liveness':{'interval':'10s','timeout':'15s','failures':4},'stats':{'listen':'127.0.0.1:'+str(PORTS[protocol])},'debug':False}
    try:
        directory(protocol).mkdir(parents=True,exist_ok=True,mode=0o750)
        if directory(protocol).stat().st_gid!=grp.getgrnam('unifiedvpn-rtc').gr_gid:os.chown(directory(protocol),0,grp.getgrnam('unifiedvpn-rtc').gr_gid)
        private_write(path,json.dumps(cfg),shared=True)
        if protocol=='jitsi':private_write(path.parent/'device-rooms.enabled','')
        write_keyring(protocol,live_keys(protocol))
        command(['systemctl','enable','--now',unit(protocol)])
        if previous:command(['systemctl','restart',unit(protocol)])
        probe(protocol)
        return status(protocol)
    except Exception:
        if previous:
            private_write(path,previous.decode(),shared=True)
            command(['systemctl','restart',unit(protocol)])
        else:
            subprocess.run(['systemctl','disable','--now',unit(protocol)],capture_output=True,timeout=25)
            path.unlink(missing_ok=True)
        raise RuntimeError(unavailable_reason(protocol) or 'Канал не прошёл HTTPS-проверку. Настройки не активированы; действующие VPN сохранены') from None
