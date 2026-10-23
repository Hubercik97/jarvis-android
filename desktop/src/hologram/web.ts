import {HologramEngine} from './engine';
import {AudioSampler,silentFrame,type VisualState,type SignalFrame} from './signal';
export {AudioSampler,playbackSampler,measureSignal} from './signal';
export {HologramEngine} from './engine';
const host=document.getElementById('hologramHost'),core=document.getElementById('coreBtn');
if(host&&core){
 let state:VisualState='IDLE',signal:SignalFrame=silentFrame(),sampler:AudioSampler|null=null,stream:MediaStream|null=null,generation=0;
 const text=(id:string,value:string)=>{const node=document.getElementById(id);if(node&&node.textContent!==value)node.textContent=value;};
 const scene=new HologramEngine(host,()=>({state,signal,lowPower:innerWidth<800||navigator.hardwareConcurrency<=4}),metrics=>text('hudRenderer',`${metrics.renderer} / ${metrics.quality} · ${metrics.particles} PTS · ${metrics.paused?'PAUZA':metrics.fps+' FPS'}`));
 const stop=()=>{generation++;stream?.getTracks().forEach(t=>t.stop());stream=null;sampler?.dispose();void sampler?.context.close();sampler=null;signal=silentFrame();};
 async function mic(){const current=++generation;try{const source=await navigator.mediaDevices.getUserMedia({audio:true,video:false});if(current!==generation){source.getTracks().forEach(t=>t.stop());return;}stream=source;const context=new AudioContext();sampler=new AudioSampler(context,context.createMediaStreamSource(source),'microphone');text('hudAudioNote','Rzeczywisty sygnał mikrofonu');}catch{text('hudAudioNote','Analiza mikrofonu niedostępna; rozpoznawanie mowy pozostaje bez zmian');}}
 const update=()=>{const value=core.dataset.state;const next:VisualState=value==='listening'||value==='watching'?'LISTENING':value==='thinking'?'THINKING':value==='speaking'?'SPEAKING':'IDLE';if(next!==state){stop();state=next;host.dataset.state=state;if(state==='LISTENING')void mic();else text('hudAudioNote',state==='SPEAKING'?'Głos systemowy • brak dostępu do PCM':'Brak aktywnego sygnału audio');}text('hudMode',state);};
 const observer=new MutationObserver(update);observer.observe(core,{attributes:true,attributeFilter:['data-state']});update();
 let previousCount=0;
 const interval=setInterval(()=>{
  if(document.hidden)return;
  const now=new Date();text('hudClock',now.toLocaleTimeString('pl-PL',{hour:'2-digit',minute:'2-digit'}));text('hudDate',now.toLocaleDateString('pl-PL',{weekday:'short',day:'numeric',month:'short',year:'numeric'}));
  if(sampler)signal=sampler.sample();
  const live=signal.available&&state==='LISTENING';text('hudAudioLevel',live?`${Math.round(signal.rms*100)}% RMS`:'—');
  const path=document.querySelector('#hudWave path');path?.setAttribute('d',live?signal.wave.map((v,i)=>`${i?'L':'M'}${i*240/47},${30-v*100}`).join(' '):'M0 30 L240 30');
  const log=document.getElementById('log'),recent=document.getElementById('hudRecent');
  if(log&&recent&&log.children.length!==previousCount){previousCount=log.children.length;recent.replaceChildren();Array.from(log.querySelectorAll('.msg-user')).slice(-4).reverse().forEach(message=>{const li=document.createElement('li');li.textContent=message.textContent?.replace(/^TY/,'')||'';recent.append(li);});if(!recent.childElementCount){const li=document.createElement('li');li.textContent='Brak poleceń w tej rozmowie';recent.append(li);}}
  const label=document.getElementById('stateLabel')?.textContent||'';text('hudActivity',label);
  const alert=document.getElementById('alert');if(alert&&!alert.hidden&&alert.dataset.level==='error'&&state==='IDLE'){state='ERROR';text('hudMode',state);}else if(state==='ERROR'&&(!alert||alert.hidden)){state='IDLE';text('hudMode',state);}
 },80);
 const clockInterval=setInterval(()=>{if(document.hidden&&stream)stop();},500);
 document.getElementById('hudWeatherRefresh')?.addEventListener('click',async()=>{
  const cloud=(window as any).jarvisCloud;if(!cloud?.enabled){text('hudWeather','Połącz chmurę, aby pobrać pogodę');return;}
  try{const data=await cloud.weather();text('hudWeather',`${data.temperature}°C · ${data.source}`);text('hudWeatherTime',`Pobrano o ${new Date().toLocaleTimeString('pl-PL',{hour:'2-digit',minute:'2-digit'})}`);}catch{text('hudWeather','Pogoda niedostępna');}
 });
 window.addEventListener('pagehide',()=>{stop();clearInterval(interval);clearInterval(clockInterval);observer.disconnect();scene.dispose();},{once:true});
}
