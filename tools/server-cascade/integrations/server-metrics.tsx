'use client';
import {useEffect,useRef,useState} from 'react';
import {Cpu,HardDrive,MemoryStick,RefreshCw} from 'lucide-react';
import {controlApi} from './control-api';

type Usage={percent:number,usedBytes:number,totalBytes:number,availableBytes:number};
type Cluster={primary:string,standby:string,standbyReachable?:boolean,automaticTrafficFailover?:boolean,localBackupAt?:number,offsiteBackupAt?:number,offsiteBackupPending?:boolean,localBackupBytes?:number,checkedAt?:number};
type Cascade={checkedAt:number,entry:string,exit:string,cascadeEnabled:boolean,stale:boolean,privateRttMs?:number,rateLimitMbit:number};
type Metrics={cascade?:Cascade,cluster?:Cluster,capturedAt:number,cpu:{percent:number,cores:number}|null,memory:Usage|null,disk:(Usage&{mount:string})|null};
const percent=(value:number)=>value.toLocaleString('ru-RU',{maximumFractionDigits:1})+'%';
function bytes(value:number){
 const unit=value>=1024**3?1024**3:1024**2;
 return (value/unit).toLocaleString('ru-RU',{maximumFractionDigits:1})+(unit===1024**3?' ГиБ':' МиБ');
}
function Meter({name,value,detail,children}:{name:string,value:number|null,detail:string,children:React.ReactNode}){
 const level=value!==null&&value>=90?'high':value!==null&&value>=75?'medium':'normal';
 return <article className={'resource-card '+level}><div className="resource-heading">{children}<h3>{name}</h3><span className="resource-level">{value===null?'Нет данных':value>=90?'Высокая загрузка':value>=75?'Повышенная загрузка':'В норме'}</span></div><strong className="resource-value">{value===null?'—':percent(value)}</strong>{value!==null&&<progress max={100} value={value} aria-label={name+' · '+percent(value)}/>}<p>{detail}</p></article>;
}
export default function ServerMetrics({apiBase}:{apiBase:string}){
 const [data,setData]=useState<Metrics|null>(null),[error,setError]=useState(''),[busy,setBusy]=useState(false);
 const request=useRef<(()=>Promise<void>)|null>(null);
 useEffect(()=>{
  let disposed=false,inFlight=false;
  async function refresh(){
   if(disposed||inFlight)return;
   inFlight=true;setBusy(true);
   try{const result:Metrics=await controlApi(apiBase,'/server-metrics');if(!disposed){setData(result);setError('')}}
   catch(e){if(!disposed)setError((e as Error).message)}
   finally{inFlight=false;if(!disposed)setBusy(false)}
  }
  request.current=refresh;
  const visible=()=>{if(document.visibilityState==='visible')void refresh()};
  void refresh();
  const timer=window.setInterval(visible,5000);
  document.addEventListener('visibilitychange',visible);
  return()=>{disposed=true;request.current=null;window.clearInterval(timer);document.removeEventListener('visibilitychange',visible)};
 },[apiBase]);
 const cpu=data?.cpu,memory=data?.memory,disk=data?.disk;
 const usage=(item:Usage|undefined|null)=>item?bytes(item.usedBytes)+' из '+bytes(item.totalBytes)+' · доступно '+bytes(item.availableBytes):'Показатель временно недоступен';
 return <section className="server-metrics" aria-labelledby="server-title"><div className="section-title"><h2 id="server-title">Ресурсы сервера</h2><button className="icon-button" aria-label="Обновить показатели сервера" disabled={busy} onClick={()=>void request.current?.()}><RefreshCw size={18}/></button></div><p className="existing-intro">Общая загрузка VPS, включая все VPN-подключения и службы.</p>{data?.cluster&&<div className="note"><h3>Серверы и резервные копии</h3><p>Основной: <strong>{data.cluster.primary}</strong><br/>Резерв: <strong>{data.cluster.standby}</strong> · {data.cluster.standbyReachable?'доступен для синхронизации':'недоступен'}</p><p>{data.cluster.automaticTrafficFailover?'Автоматическое переключение входного сервера включено.':'Автоматическое переключение входного сервера ещё не настроено.'}</p><p>{data.cluster.localBackupAt?'Локальная копия: '+new Date(data.cluster.localBackupAt*1000).toLocaleString('ru-RU')+'. ':''}{data.cluster.offsiteBackupAt?'Копия на резервном сервере: '+new Date(data.cluster.offsiteBackupAt*1000).toLocaleString('ru-RU')+'.':'Передача копии на резервный сервер ожидает подключения.'}</p>{data.cluster.checkedAt&&<p className="footnote">Проверено: {new Date(data.cluster.checkedAt*1000).toLocaleString('ru-RU')}</p>}</div>}{data?.cascade&&<div className="note"><h3>Каскадная маршрутизация</h3><p>{data.cascade.stale?'Нет свежих данных о маршруте.':data.cascade.cascadeEnabled?'Выбранный VPN-трафик новых соединений идёт через второй сервер.':'Новые VPN-соединения выходят напрямую с основного сервера: проверка доступности или защита ресурсов.'}</p><p>Вход: <strong>{data.cascade.entry}</strong><br/>Выход каскада: <strong>{data.cascade.exit}</strong></p><p>Лимит межсерверного канала: {data.cascade.rateLimitMbit} Мбит/с.{data.cascade.privateRttMs!=null?' Проверка второго узла: '+data.cascade.privateRttMs+' мс.':''}</p><p className="footnote">Активные соединения сохраняют выбранный маршрут. VLESS и Telegram внутри VPN выходят с основного сервера. Telegram-боты и служебный доступ идут напрямую. Проверено: {new Date(data.cascade.checkedAt*1000).toLocaleString('ru-RU')}.</p></div>}{error&&<div className="error" role="alert">{data?'Не удалось обновить. Ниже — последние полученные значения. ':''}{error}</div>}{!data&&!error&&<p role="status" className="footnote">Измеряю загрузку сервера…</p>}{data&&<div className="resource-grid"><Meter name="Процессор" value={cpu?.percent??null} detail={cpu?cpu.cores+' логических ядер · загрузка за интервал между замерами':'Показатель временно недоступен'}><Cpu size={20}/></Meter><Meter name="Оперативная память" value={memory?.percent??null} detail={usage(memory)}><MemoryStick size={20}/></Meter><Meter name="Диск сервера" value={disk?.percent??null} detail={usage(disk)}><HardDrive size={20}/></Meter></div>}<p className="footnote" role="status">{data?'Последний замер: '+new Date(data.capturedAt*1000).toLocaleTimeString('ru-RU')+'. ':''}Автообновление каждые 5 секунд, пока вкладка открыта.</p><p className="footnote">ОЗУ учитывает доступную для приложений память, включая освобождаемый кеш. Диск — основной раздел /.</p></section>;
}
