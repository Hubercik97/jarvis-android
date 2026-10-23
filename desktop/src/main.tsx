import React,{useEffect,useRef,useState} from 'react';
import {createRoot} from 'react-dom/client';
import type {Settings,LocalAction,WakeStatus} from '../shared/types';
import {WakeMicrophone} from './wake';
import {WakeSession,type WakeStage} from './wake-session';
import {WakeSettings} from './WakeSettings';
import {localCommand,isProjectStatus} from './commands';
import './style.css';
import './hologram/hud.css';
import {Hologram,HudPanel,SignalPanel} from './hologram/Hud';
import {AudioSampler,playbackSampler,silentFrame,type SignalFrame,type VisualState} from './hologram/signal';
type Message={role:'user'|'assistant';content:string;time?:string};
type Task={id:string;title:string;kind:string;status:string;result:string|null;scheduled_at?:string};
type Fact={id:string;text:string};
type Conversation={id:string;title:string};
const nav=[['chat','◉','Centrum dowodzenia'],['tasks','☷','Zadania i raporty'],['memory','◇','Pamięć'],['developer','⌘','Developer'],['integrations','⌁','Integracje'],['settings','⚙','Ustawienia']] as const;
function App(){
 const [tab,setTab]=useState<string>('chat');
 const [settings,setSettings]=useState<Settings|null>(null);
 const [token,setToken]=useState('');const [endpoint,setEndpoint]=useState('');
 const [status,setStatus]=useState('Połącz chmurę w Ustawieniach.');const [online,setOnline]=useState(false);
 const [clock,setClock]=useState(new Date());const [weather,setWeather]=useState('Pogoda · odśwież');
 const [messages,setMessages]=useState<Message[]>([]);const [text,setText]=useState('');const [busy,setBusy]=useState(false);
 const [conversation,setConversation]=useState<string|null>(null);const [conversations,setConversations]=useState<Conversation[]>([]);
 const [tasks,setTasks]=useState<Task[]>([]);const [tasksKnown,setTasksKnown]=useState(false);const [transcribing,setTranscribing]=useState(false);const [taskTitle,setTaskTitle]=useState('');const [taskKind,setTaskKind]=useState('research');const [scheduled,setScheduled]=useState('');
 const [facts,setFacts]=useState<Fact[]>([]);const [fact,setFact]=useState('');const [project,setProject]=useState<any>(null);const [cloudProjects,setCloudProjects]=useState<any[]>([]);const [projectSummary,setProjectSummary]=useState('');
 const signal=useRef<SignalFrame>(silentFrame());const playback=useRef<AudioSampler|null>(null);const playbackFrame=useRef(0);
 const [errorState,setErrorState]=useState(false);const [microphoneStatus,setMicrophoneStatus]=useState('Oczekuje na zgodę');const [sync,setSync]=useState('Analiza audio nieaktywna');const [weatherAt,setWeatherAt]=useState<string|null>(null);
 const [speaking,setSpeaking]=useState(false);const [recording,setRecording]=useState(false);const [level,setLevel]=useState(0);const [voices,setVoices]=useState<SpeechSynthesisVoice[]>([]);
 const [wakeStatus,setWakeStatus]=useState<WakeStatus>({phase:'off',message:'Wybudzanie wyłączone. Mikrofon nie nasłuchuje hasła.'});const [wakeStage,setWakeStage]=useState<WakeStage>('idle');
 const wakeStatusRef=useRef(wakeStatus);wakeStatusRef.current=wakeStatus;const latestSettings=useRef(settings);latestSettings.current=settings;
 const wakeMicrophone=useRef<WakeMicrophone|null>(null);const wakeSession=useRef<WakeSession|null>(null);const wakeCallbacks=useRef({speak:async()=>{},listen:async()=>{}});
 const voiceDone=useRef<(()=>void)|null>(null);const voiceTimeout=useRef<ReturnType<typeof setTimeout>|undefined>(undefined);const captureDone=useRef<(()=>void)|null>(null);const captureGeneration=useRef(0);
 const silenceInterval=useRef<ReturnType<typeof setInterval>|undefined>(undefined);
 const audio=useRef<HTMLAudioElement|null>(null);const recorder=useRef<MediaRecorder|null>(null);const microphone=useRef<MediaStream|null>(null);const audioContext=useRef<AudioContext|null>(null);const timer=useRef<ReturnType<typeof setTimeout>|undefined>(undefined);const frame=useRef(0);const speechGeneration=useRef(0);const sending=useRef(false);const transcriptEnd=useRef<HTMLDivElement>(null);
 const bridge=window.jarvis;
 const report=(error:unknown)=>{setErrorState(true);setStatus(error instanceof Error?error.message:String(error));};
 const visualState:VisualState=wakeStage==='flash'?'WAKE_WORD_DETECTED':recording?'LISTENING':speaking?'SPEAKING':busy||transcribing?'THINKING':errorState||wakeStatus.phase==='error'?'ERROR':'IDLE';
 wakeCallbacks.current={speak:()=>speak('Jestem, słucham cię'),listen:()=>toggleRecording(true)};
 useEffect(()=>{
  if(!bridge)return;
  const mic=wakeMicrophone.current=new WakeMicrophone(bridge,message=>{void bridge.wakeControl('stop',message);});
  const cycle=wakeSession.current=new WakeSession({stage:setWakeStage,speak:()=>wakeCallbacks.current.speak(),listen:()=>wakeCallbacks.current.listen(),rearm:()=>bridge.wakeControl('resume')});
  let observed=0;
  const onStatus=(value:WakeStatus)=>{
   observed++;
   wakeStatusRef.current=value;setWakeStatus(value);
   if(value.phase==='starting')void mic.start(latestSettings.current?.wake.deviceId||'');
   else if(value.phase!=='armed')mic.stop();
   if(value.phase==='off'||value.phase==='error'){cycle.cancel();cancelCapture();stopVoice();}
   if(value.phase==='detected'){
    setStatus('Pobudka Jarvis · słucham kolejnego polecenia.');setErrorState(false);setTab('chat');
    void cycle.activate(latestSettings.current?.wake.confirmation!==false).catch(report);
   }
  };
  const remove=bridge.onWake(onStatus);const atSubscribe=observed;
  void bridge.wakeControl('status').then(value=>{if(observed===atSubscribe)onStatus(value);}).catch(report);
  return()=>{remove();cycle.cancel();mic.stop();void bridge.wakeControl('stop');};
 },[]);
 useEffect(()=>{
  if(!bridge)setStatus('Podgląd interfejsu Desktop. Funkcje komputera wymagają aplikacji Electron.');
  bridge?.settings().then(s=>{setSettings(s);setEndpoint(s.endpoint);if(s.hasToken)void health();}).catch(report);
  const interval=setInterval(()=>setClock(new Date()),1000);
  const loadVoices=()=>setVoices(window.speechSynthesis?.getVoices().filter(v=>v.lang.toLowerCase().startsWith('pl'))||[]);
  navigator.permissions?.query({name:'microphone' as PermissionName}).then(p=>{const update=()=>setMicrophoneStatus(p.state==='granted'?'Dostęp przyznany':p.state==='denied'?'Zablokowany':'Oczekuje na zgodę');update();p.onchange=update;}).catch(()=>setMicrophoneStatus('Stan nieznany'));
  loadVoices();window.speechSynthesis?.addEventListener('voiceschanged',loadVoices);
  return()=>{clearInterval(interval);window.speechSynthesis?.removeEventListener('voiceschanged',loadVoices);stopVoice();cleanupMic();};
 },[]);
 useEffect(()=>{transcriptEnd.current?.scrollIntoView({behavior:settings?.lowPower?'instant':'smooth'});},[messages]);
 useEffect(()=>{
  if(!['tasks','chat'].includes(tab)||!online)return;
  void refreshTasks();const interval=setInterval(()=>{if(!document.hidden)void refreshTasks();},5000);return()=>clearInterval(interval);
 },[tab,online]);
 useEffect(()=>{const hidden=()=>{if(document.hidden&&!['starting','armed','paused','detected','loading'].includes(wakeStatusRef.current.phase)){stopVoice();cancelCapture();}};document.addEventListener('visibilitychange',hidden);return()=>document.removeEventListener('visibilitychange',hidden);},[]);
 useEffect(()=>{
  if(wakeStatus.phase!=='paused'||speaking||busy||recording||transcribing||wakeSession.current?.active)return;
  const timeout=setTimeout(()=>void bridge?.wakeControl('resume'),600);return()=>clearTimeout(timeout);
 },[wakeStatus,speaking,busy,recording,transcribing,wakeStage]);
 async function health(){setErrorState(false);try{const h=await bridge.api('/health');const ok=h.auth==='ok'&&h.configured;setOnline(ok);setStatus(ok?`Połączono · ${h.provider} · ${h.model}${h.background?' · kolejka aktywna':''}`:'Serwer odpowiada, lecz token lub model nie jest skonfigurowany.');return ok;}catch(e){setOnline(false);report(e);return false;}}
 async function save(){if(!settings)return;try{const updated=await bridge.configure({...settings,endpoint,...(token?{token}:{})});setSettings(updated);setToken('');await health();}catch(e){report(e);}}
 async function refreshTasks(){try{setTasks((await bridge.api('/tasks')).tasks);setTasksKnown(true);}catch(e){report(e);}}
 async function refreshMemory(){try{setFacts((await bridge.api('/memory')).facts);setConversations((await bridge.api('/conversations')).conversations);}catch(e){report(e);}}
 function stopVoice(){speechGeneration.current++;clearTimeout(voiceTimeout.current);const done=voiceDone.current;voiceDone.current=null;done?.();cancelAnimationFrame(playbackFrame.current);playback.current?.dispose();void playback.current?.context.close();playback.current=null;signal.current=silentFrame();window.speechSynthesis?.cancel();if(audio.current){audio.current.onended=null;audio.current.onerror=null;audio.current.pause();audio.current.src='';audio.current=null;}setSpeaking(false);}
 async function speak(value:string){
  stopVoice();setErrorState(false);const generation=speechGeneration.current;
  if(['armed','starting','detected'].includes(wakeStatusRef.current.phase))await bridge.wakeControl('pause');
  if(generation!==speechGeneration.current)return;
  setSpeaking(true);const finished=new Promise<void>(resolve=>{voiceDone.current=resolve;});voiceTimeout.current=setTimeout(()=>{if(generation===speechGeneration.current)stopVoice();},90000);
  if(settings?.tts==='google'){
   try{const data=await bridge.api('/tts','POST',{text:value.slice(0,1500),voice:'Charon'});if(generation!==speechGeneration.current)return;
    const url=`data:audio/${data.format==='wav'?'wav':'mpeg'};base64,${data.audio}`;let output=new Audio(url);audio.current=output;
    try{const meter=await playbackSampler(output);if(generation!==speechGeneration.current){meter.dispose();await meter.context.close();return;}playback.current=meter;setSync('TTS • analiza rzeczywistego odtwarzania');}catch{if(generation!==speechGeneration.current)return;output.src='';output=new Audio(url);audio.current=output;setSync('TTS • analiza PCM niedostępna');}
    output.onended=()=>{if(generation===speechGeneration.current)stopVoice();};output.onerror=()=>{if(generation===speechGeneration.current){stopVoice();report(new Error('Nie udało się odtworzyć głosu.'));}};
    await output.play();if(generation!==speechGeneration.current){output.pause();return;}setSpeaking(true);
    const sample=()=>{if(generation!==speechGeneration.current)return;if(playback.current)signal.current=playback.current.sample();playbackFrame.current=requestAnimationFrame(sample);};playbackFrame.current=requestAnimationFrame(sample);return finished;
   }catch(e){report(e);setStatus('Głos chmurowy niedostępny. Używam głosu systemowego.');}
  }
  if(generation!==speechGeneration.current)return;
  setSync('Głos systemowy • brak dostępu do PCM');signal.current=silentFrame();
  const utterance=new SpeechSynthesisUtterance(value);utterance.lang='pl-PL';utterance.rate=.96;
  const voice=voices.find(v=>v.voiceURI===settings?.voice)||voices.find(v=>/\b(Adam|Marek|Male)\b/i.test(v.name))||voices[0];if(voice)utterance.voice=voice;
  utterance.onstart=()=>{if(generation===speechGeneration.current)setSpeaking(true);};utterance.onend=()=>{if(generation===speechGeneration.current)stopVoice();};utterance.onerror=()=>{if(generation===speechGeneration.current)stopVoice();};window.speechSynthesis.speak(utterance);return finished;
 }
 async function control(action:LocalAction){setErrorState(false);try{const result=await bridge.control(action);setStatus(result.message);setMessages(m=>[...m,{role:'assistant',content:result.message}]);}catch(e){report(e);}}
 async function send(value=text,stillAllowed:()=>boolean=()=>true){
  const input=value.trim();if(!input||sending.current)return;
  if(['armed','starting'].includes(wakeStatusRef.current.phase))await bridge.wakeControl('pause');
  setText('');stopVoice();setErrorState(false);
  const action=localCommand(input);if(action){setMessages(m=>[...m,{role:'user',content:input,time:new Date().toISOString()}]);await control(action);return;}
  if(!online){setStatus('Najpierw połącz aplikację z chmurą.');return;}
  if(isProjectStatus(input)){
   try{const data=await bridge.api(`/developer/project?repo=${encodeURIComponent(settings?.repository||'Hubercik97/jarvis-android')}`);setProject(data);setTab('developer');setStatus(`Projekt ${data.name}: gałąź ${data.branch}, otwarte zgłoszenia i PR: ${data.open_issues}.`);}catch(e){report(e);}return;
  }
  sending.current=true;setBusy(true);const updated:Message[]=[...messages,{role:'user',content:input,time:new Date().toISOString()}];setMessages(updated);
  try{
   let id=conversation;if(!id){id=(await bridge.api('/conversations','POST',{title:input.slice(0,120)})).id;setConversation(id);}
   await bridge.api(`/conversations/${id}/messages`,'POST',{role:'user',content:input});
   const data=await bridge.api('/chat','POST',{messages:updated.slice(-20)});
   if(typeof data.reply!=='string'||!data.reply)throw new Error('Chmura nie zwróciła odpowiedzi.');
   setMessages([...updated,{role:'assistant',content:data.reply,time:new Date().toISOString()}]);
   await bridge.api(`/conversations/${id}/messages`,'POST',{role:'assistant',content:data.reply});
   if(stillAllowed())await speak(data.reply);
  }catch(e){report(e);}finally{sending.current=false;setBusy(false);}
 }
 function cleanupMic(){clearTimeout(timer.current);clearInterval(silenceInterval.current);cancelAnimationFrame(frame.current);microphone.current?.getTracks().forEach(t=>{t.onended=null;t.stop();});microphone.current=null;void audioContext.current?.close();audioContext.current=null;setRecording(false);setLevel(0);signal.current=silentFrame();}
 function cancelCapture(){captureGeneration.current++;if(recorder.current){recorder.current.onstop=null;recorder.current.onerror=null;if(recorder.current.state==='recording')recorder.current.stop();recorder.current=null;}cleanupMic();setTranscribing(false);captureDone.current?.();captureDone.current=null;}
 function interruptWakeTurn(){if(wakeSession.current?.active){wakeSession.current.cancel();cancelCapture();stopVoice();}}
 function manualSpeak(value:string){interruptWakeTurn();if(recorder.current?.state==='recording')cancelCapture();void speak(value);}
 function manualRecord(){interruptWakeTurn();void toggleRecording();}
 function manualSend(){interruptWakeTurn();if(recorder.current?.state==='recording')cancelCapture();void send();}
 async function manualControl(action:LocalAction){interruptWakeTurn();cancelCapture();stopVoice();if(['armed','starting'].includes(wakeStatusRef.current.phase))await bridge.wakeControl('pause');setBusy(true);try{await control(action);}finally{setBusy(false);}}
 async function stopWake(){wakeSession.current?.cancel();wakeMicrophone.current?.stop();cancelCapture();stopVoice();await bridge.wakeControl('stop');}
 async function toggleRecording(auto=false){
  if(recorder.current?.state==='recording'){recorder.current.stop();return;}
  if(!online||busy){setStatus('Połącz chmurę i zaczekaj na zakończenie odpowiedzi.');return;}
  if(['armed','starting','detected'].includes(wakeStatusRef.current.phase))await bridge.wakeControl('pause');
  stopVoice();setErrorState(false);const generation=++captureGeneration.current;
  try{
   const deviceId=latestSettings.current?.wake.deviceId;
   const stream=await navigator.mediaDevices.getUserMedia({audio:{...(deviceId?{deviceId:{exact:deviceId}}:{}),echoCancellation:true,noiseSuppression:true},video:false});
   if(generation!==captureGeneration.current){stream.getTracks().forEach(t=>t.stop());return;}
   microphone.current=stream;setMicrophoneStatus('Dostęp przyznany');setSync('Mikrofon • rzeczywisty sygnał');
   const ctx=new AudioContext();audioContext.current=ctx;const meter=new AudioSampler(ctx,ctx.createMediaStreamSource(stream),'microphone');
   let last=0;const animate=(now:number)=>{if(now-last>40){signal.current=meter.sample();last=now;}frame.current=requestAnimationFrame(animate);};frame.current=requestAnimationFrame(animate);
   const rec=new MediaRecorder(stream,{mimeType:'audio/webm',audioBitsPerSecond:32000});recorder.current=rec;const chunks:Blob[]=[];let size=0;
   let resolveCapture!:()=>void;const completed=new Promise<void>(resolve=>{resolveCapture=resolve;captureDone.current=resolve;});
   const finish=()=>{resolveCapture();if(captureDone.current===resolveCapture)captureDone.current=null;};
   let voiceAt=0,startedAt=performance.now();
   // Runs while minimized as well: main disables background throttling only for active wake mode.
   const silenceTimer=silenceInterval.current=setInterval(()=>{
    if(!auto||rec.state!=='recording')return;const now=performance.now();signal.current=meter.sample();
    if(signal.current.rms>.014)voiceAt=now;
    if((voiceAt&&now-voiceAt>1300)||(now-startedAt>8000&&!voiceAt))rec.stop();
   },100);
   stream.getAudioTracks()[0].onended=()=>{if(generation!==captureGeneration.current){finish();return;}cancelCapture();report(new Error('Utracono dostęp do mikrofonu.'));if(auto)void bridge.wakeControl('stop','Utracono dostęp do mikrofonu podczas polecenia.');};
   rec.ondataavailable=event=>{chunks.push(event.data);size+=event.data.size;if(size>1_900_000&&rec.state==='recording')rec.stop();};
   rec.onstop=async()=>{clearInterval(silenceTimer);if(generation!==captureGeneration.current){finish();return;}stream.getAudioTracks()[0].onended=null;cleanupMic();recorder.current=null;if(!chunks.length||size>2_000_000||(auto&&!voiceAt)){setStatus(auto?'Nie usłyszałem polecenia. Wracam do nasłuchu hasła.':'Puste lub za długie nagranie.');finish();return;}setStatus('Transkrypcja polskiej mowy…');setTranscribing(true);try{
    const data=new Uint8Array(await new Blob(chunks,{type:'audio/webm'}).arrayBuffer());let binary='';for(let i=0;i<data.length;i+=8192)binary+=String.fromCharCode(...data.subarray(i,i+8192));
    const result=await bridge.api('/stt','POST',{audio:btoa(binary),mime:'audio/webm'});if(generation!==captureGeneration.current)return;
    if(auto){if(typeof result.text==='string'&&result.text.trim())await send(result.text,()=>generation===captureGeneration.current);else setStatus('Nie rozpoznano polecenia. Wracam do nasłuchu hasła.');}
    else{setText(result.text);setStatus('Rozpoznano mowę. Sprawdź tekst i naciśnij Wyślij.');}
   }catch(e){if(generation===captureGeneration.current)report(e);}finally{if(generation===captureGeneration.current)setTranscribing(false);finish();}};
   rec.onerror=()=>{if(generation!==captureGeneration.current){finish();return;}cancelCapture();setStatus('Błąd nagrywania mikrofonu.');if(auto)void bridge.wakeControl('stop','Błąd nagrywania polecenia. Włącz nasłuch ponownie.');};rec.start(250);setRecording(true);setStatus(auto?'Słucham polecenia · zakończę po chwili ciszy.':'Nagrywam · maksymalnie 30 sekund. Ponowne kliknięcie kończy nagranie.');timer.current=setTimeout(()=>{if(rec.state==='recording')rec.stop();},30000);return completed;
  }catch(e){if(generation!==captureGeneration.current)return;cleanupMic();setMicrophoneStatus(e instanceof DOMException&&e.name==='NotAllowedError'?'Zablokowany':'Niedostępny');report(e);if(auto)void bridge.wakeControl('stop','Mikrofon polecenia jest niedostępny. Sprawdź uprawnienia.');}
 }
 function setting<K extends keyof Settings>(key:K,value:Settings[K]){setSettings(s=>s?{...s,[key]:value}:s);}
 return <div className={`app holographic-app ${settings?.lowPower?'low-power':''}`}>
  <aside><div className="brand"><span className="brand-orb">J</span><div>J.A.R.V.I.S.<small>DESKTOP + CLOUD</small></div></div><div className="nav-label">TWOJA PRZESTRZEŃ</div><nav>{nav.map(([id,icon,label])=><button key={id} className={tab===id?'active':''} onClick={()=>{setTab(id);if(id==='memory')void refreshMemory();if(id==='developer')void bridge.api('/projects').then(data=>setCloudProjects(data.projects)).catch(report);}}><span>{icon}</span>{label}</button>)}</nav><div className="sidebar-bottom"><span className={`dot ${online?'online':''}`}></span>{online?'Chmura połączona':'Tryb niepołączony'}<small>AI pracuje w chmurze.<br/>Twój komputer pozostaje lekki.</small></div></aside>
  <div className="workspace"><header><div><span className="eyebrow">J.A.R.V.I.S. / INTELLIGENCE INTERFACE</span><h1>{nav.find(n=>n[0]===tab)?.[2]}</h1></div><div className="top-actions"><span className="clock">{clock.toLocaleTimeString('pl-PL',{hour:'2-digit',minute:'2-digit'})}<small>{clock.toLocaleDateString('pl-PL',{day:'numeric',month:'long'})}</small></span><button aria-label="Minimalizuj do zasobnika" title="Minimalizuj do zasobnika" onClick={()=>void bridge?.minimize()}>—</button></div></header>
  <div className="status" role="status"><span className={`dot ${online?'online':''}`}></span>{status}</div>
  <div className={`wake-bar phase-${wakeStatus.phase}`} role="status" aria-label="Status wybudzania"><span className="wake-indicator"/>{wakeStatus.message}{!['off','error'].includes(wakeStatus.phase)?<button onClick={()=>void stopWake()}>Wyłącz nasłuch natychmiast</button>:settings?.wake.enabled&&<button onClick={()=>void bridge.wakeControl('start').catch(report)}>Wznów lokalny nasłuch</button>}</div>
  {tab==='chat'&&<main className="command-center"><div className="hud-grid">
   <div className="hud-column hud-left">
    <HudPanel title="CZAS / WARUNKI" className="clock-panel"><time className="hud-time">{clock.toLocaleTimeString('pl-PL',{hour:'2-digit',minute:'2-digit'})}<span>{clock.toLocaleTimeString('pl-PL',{second:'2-digit'})}</span></time><p className="hud-date">{clock.toLocaleDateString('pl-PL',{weekday:'short',day:'numeric',month:'short',year:'numeric'})}</p><button className="weather" onClick={async()=>{try{const w=await bridge.api(`/weather?latitude=${settings?.latitude??52.23}&longitude=${settings?.longitude??21.01}`);setWeather(`${w.temperature}°C · Open-Meteo`);setWeatherAt(new Date().toLocaleTimeString('pl-PL',{hour:'2-digit',minute:'2-digit'}));setErrorState(false);}catch(e){report(e);}}}>☁ {weather}</button><small>{weatherAt?`Pomiar pobrany o ${weatherAt}`:'Brak pobranych danych pogodowych'}</small></HudPanel>
    <HudPanel title="STATUS SYSTEMU"><ul className="hud-status-list"><li><i className={online?'ready':''}/>Chmura <strong>{online?'Połączona':'Niepołączona'}</strong></li><li><i className={recording||wakeStatus.phase==='armed'?'ready':''}/>Mikrofon <strong>{recording?'Polecenie':wakeStatus.phase==='armed'?'Nasłuch hasła · lokalnie':microphoneStatus}</strong></li><li><i className={speaking?'ready':''}/>Głos <strong>{speaking?'Odtwarzanie':settings?.tts==='google'?'Google TTS':'Systemowy'}</strong></li><li><i className={busy?'ready':''}/>Tryb <strong>{visualState}</strong></li></ul></HudPanel>
    <HudPanel title="OSTATNIE POLECENIA"><ol className="hud-history">{messages.filter(m=>m.role==='user').slice(-4).reverse().map((m,i)=><li key={i}><time>{m.time?new Date(m.time).toLocaleTimeString('pl-PL',{hour:'2-digit',minute:'2-digit'}):'—'}</time><span>{m.content}</span></li>)}{!messages.some(m=>m.role==='user')&&<li>Brak poleceń w tej rozmowie</li>}</ol></HudPanel>
   </div>
   <Hologram state={visualState} signal={signal} lowPower={settings?.lowPower??true} onPress={()=>speaking?stopVoice():manualRecord()}/>
   <div className="hud-column hud-right">
    <HudPanel title="BIEŻĄCE ZADANIE"><div className="hud-task-state"><span className={`task-indicator ${busy?'working':''}`}/><strong>{transcribing?'Transkrypcja mowy':busy?'Rozmowa z modelem':tasks.find(t=>t.status==='running')?.title||'Brak wykonywanego zadania'}</strong></div><p>{transcribing?'Oczekiwanie na transkrypcję':busy?'Oczekiwanie na odpowiedź chmury':tasks.find(t=>t.status==='running')?'Wykonywane przez backend':tasksKnown?`${tasks.filter(t=>t.status==='queued').length} zadań w kolejce`:'Status zadań niepobrany'}</p>{(busy||transcribing)&&<div className="indeterminate" aria-label="Przetwarzanie, postęp nieznany"/>}<button className="text-button" onClick={()=>setTab('tasks')}>Otwórz panel zadań ↗</button></HudPanel>
    <SignalPanel signal={signal} state={visualState} sync={sync}/>
    <HudPanel title="AKTYWNOŚĆ / KONTROLA"><p className="hud-activity">{status}</p><div className="hud-shortcuts"><button onClick={()=>void manualControl('open_chrome')}>Chrome ↗</button><button onClick={()=>void manualControl('open_documents')}>Dokumenty ↗</button><button onClick={()=>void manualControl('open_project')}>GitHub ↗</button><button onClick={()=>{setTab('tasks');setTaskKind('business_draft');}}>Raport ↗</button></div></HudPanel>
   </div>
  </div>
   <section className="conversation"><div className="section-head"><h3>Rozmowa</h3><button disabled={busy} onClick={()=>{interruptWakeTurn();cancelCapture();stopVoice();setMessages([]);setConversation(null);}}>＋ Nowa rozmowa</button></div><div className="messages">{!messages.length&&<div className="empty"><span>✧</span><h3>Przestrzeń na Twoje pomysły</h3><p>Napisz wiadomość albo użyj mikrofonu.<br/>Historia rozmowy zostanie zapisana w Twojej chmurze.</p></div>}{messages.map((message,i)=><article className={`message ${message.role}`} key={i}><small>{message.role==='user'?'TY':'JARVIS'}</small><p>{message.content}</p>{message.role==='assistant'&&<button className="text-button" onClick={()=>manualSpeak(message.content)}>Odsłuchaj</button>}</article>)}<div ref={transcriptEnd}/></div><form className="composer" onSubmit={e=>{e.preventDefault();manualSend();}}><button type="button" className={recording?'recording':''} disabled={busy} aria-label="Mikrofon" onClick={manualRecord}>◉</button><input maxLength={12000} value={text} onChange={e=>setText(e.target.value)} placeholder="Napisz do Jarvisa…" aria-label="Wiadomość"/><button type="button" onClick={stopVoice} aria-label="Przerwij głos" title="Przerwij głos">■</button><button disabled={busy||!text.trim()} className="primary">Wyślij ↗</button></form></section>
  </main>}
  {tab==='tasks'&&<main className="panel"><h2>Zadania, które zostają w chmurze</h2><p>Przy aktywnej kolejce zadanie działa także po zamknięciu aplikacji. Bez kolejki serwer wykonuje je w czasie żądania.</p><form onSubmit={async e=>{e.preventDefault();try{await bridge.api('/tasks','POST',{title:taskTitle,kind:taskKind,...(scheduled?{scheduled_at:new Date(scheduled).toISOString()}:{})});setTaskTitle('');setScheduled('');await refreshTasks();}catch(error){report(error);}}}><input required maxLength={120} aria-label="Tytuł zadania" placeholder="Co mam przeanalizować?" value={taskTitle} onChange={e=>setTaskTitle(e.target.value)}/><select aria-label="Rodzaj zadania" value={taskKind} onChange={e=>setTaskKind(e.target.value)}><option value="research">Badanie internetu</option><option value="business_draft">Raport biznesowy</option></select><label>Termin (opcjonalny)<input type="datetime-local" value={scheduled} onChange={e=>setScheduled(e.target.value)}/></label><button className="primary">Utwórz zadanie</button></form><button onClick={()=>void refreshTasks()}>Odśwież</button><div className="cards">{tasks.map(task=><article className="card" key={task.id}><span className="eyebrow">{task.kind} · {task.status}</span><h3>{task.title}</h3>{task.scheduled_at&&<p>{new Date(task.scheduled_at).toLocaleString('pl-PL')}</p>}{task.status==='queued'&&<button onClick={async()=>{try{await bridge.api(`/tasks/${task.id}/run`,'POST');await refreshTasks();}catch(e){report(e);}}}>Uruchom</button>}{['queued','running'].includes(task.status)&&<button onClick={async()=>{try{await bridge.api(`/tasks/${task.id}/cancel`,'POST');await refreshTasks();}catch(e){report(e);}}}>Anuluj</button>}{task.result&&<p className="report">{(()=>{try{const data=JSON.parse(task.result);return data.reply||data.error;}catch{return 'Nieprawidłowy wynik';}})()}</p>}</article>)}</div></main>}
  {tab==='memory'&&<main className="panel"><h2>Pamięć Jarvisa</h2><p>Fakty zapisujesz świadomie. Możesz odczytać i usunąć swoje dane.</p><form onSubmit={async e=>{e.preventDefault();try{await bridge.api('/memory','POST',{text:fact});setFact('');await refreshMemory();}catch(error){report(error);}}}><input required maxLength={200} value={fact} aria-label="Nowy fakt" onChange={e=>setFact(e.target.value)} placeholder="Co mam zapamiętać?"/><button className="primary">Zapamiętaj</button></form>{facts.map(f=><div className="card" key={f.id}><p>{f.text}</p><button onClick={async()=>{if(!confirm('Usunąć ten fakt?'))return;try{await bridge.api(`/memory/${f.id}`,'DELETE');await refreshMemory();}catch(e){report(e);}}}>Usuń</button></div>)}<h2>Historia rozmów</h2>{conversations.map(c=><div className="card" key={c.id}><h3>{c.title}</h3><button disabled={busy} onClick={async()=>{try{const data=await bridge.api(`/conversations/${c.id}/messages`);setMessages(data.messages.map((m:Message&{created_at?:string})=>({role:m.role,content:m.content,time:m.created_at})));setConversation(c.id);setTab('chat');}catch(e){report(e);}}}>Otwórz rozmowę</button><button onClick={async()=>{if(!confirm('Trwale usunąć rozmowę?'))return;try{await bridge.api(`/conversations/${c.id}`,'DELETE');if(conversation===c.id){setConversation(null);setMessages([]);}await refreshMemory();}catch(e){report(e);}}}>Usuń</button></div>)}</main>}
  {tab==='developer'&&<main className="panel"><span className="eyebrow">JARVIS DEVELOPER</span><h2>Twoje centrum projektów</h2><p>Repozytorium: {settings?.repository}. Bieżąca wersja odczytuje stan projektu przez oficjalne API GitHub. Nie wykonuje kodu ani wdrożeń.</p><button className="primary" onClick={async()=>{try{setProject(await bridge.api(`/developer/project?repo=${encodeURIComponent(settings?.repository||'Hubercik97/jarvis-android')}`));}catch(e){report(e);}}}>Sprawdź stan projektu</button><button onClick={()=>void manualControl('open_project')}>Otwórz na GitHubie</button>{project&&<div className="card"><h3>{project.name}</h3><p>Gałąź: {project.branch} · Otwarte zgłoszenia i PR: {project.open_issues}</p><p>Ostatni push: {project.pushed_at}</p></div>}<h3>Pamięć projektów w chmurze</h3><form onSubmit={async e=>{e.preventDefault();try{await bridge.api('/projects','POST',{name:settings?.repository.split('/')[1]||'Projekt',repository:settings?.repository,summary:projectSummary});setCloudProjects((await bridge.api('/projects')).projects);setStatus('Stan projektu zapisany w D1.');}catch(error){report(error);}}}><input aria-label="Postęp projektu" maxLength={4000} value={projectSummary} onChange={e=>setProjectSummary(e.target.value)} placeholder="Postęp projektu, decyzje i następne kroki"/><button>Zapisz projekt w chmurze</button></form>{cloudProjects.map(p=><div className="card" key={p.id}><h3>{p.name}</h3><p>{p.repository}</p><p>{p.summary}</p><button onClick={async()=>{if(!confirm('Usunąć zapis projektu?'))return;try{await bridge.api(`/projects/${p.id}`,'DELETE');setCloudProjects((await bridge.api('/projects')).projects);}catch(e){report(e);}}}>Usuń zapis</button></div>)}<div className="card"><h3>Rozwój z Codexem</h3><p>Analiza kodu, poprawki, testy i tworzenie PR to następny etap: izolowane środowisko wykonawcze, ograniczone uprawnienia repozytorium i zatwierdzanie zmian. Brak automatycznych wdrożeń produkcyjnych.</p></div></main>}
  {tab==='integrations'&&<main className="panel"><h2>Połączenia pod Twoją kontrolą</h2><div className="cards">{[['OpenAI','Konfiguracja modelu i STT pozostaje w sekretach Workera.'],['Głos Google TTS','Opcjonalny męski głos Charon; wymaga klucza serwera i zgody na koszty.'],['GitHub','Odczyt stanu projektu. Zapis i PR nie są jeszcze dostępne.'],['Shopify / GarmiLabs','Planowane · brak połączenia i zakresów uprawnień.']].map(([title,description])=><article className="card" key={title}><h3>{title}</h3><p>{description}</p></article>)}</div><p>Brak wykonawców wiadomości, zakupów, usuwania plików i zmian systemowych. Lokalne otwieranie aplikacji wymaga zgody w osobnym oknie.</p></main>}
  {tab==='settings'&&<main className="panel settings"><h2>Połącz własną chmurę</h2><p>Wpisz adres Workera i token osobistej przestrzeni. Klucze OpenAI i TTS pozostają wyłącznie na serwerze. Ta wersja obsługuje jedną współdzieloną przestrzeń właściciela.</p>{settings&&<WakeSettings preferences={settings.wake} status={wakeStatus} onSave={async preferences=>{wakeSession.current?.cancel();cancelCapture();stopVoice();const wake=await bridge.wakeSettings(preferences);setSettings(s=>s?{...s,wake}:s);}}/>}{settings&&<form onSubmit={e=>{e.preventDefault();void save();}}><label>Adres API HTTPS<input aria-label="Adres API" value={endpoint} onChange={e=>setEndpoint(e.target.value)} placeholder="https://jarvis-test.example.workers.dev" required/></label><label>Token aplikacji<input type="password" autoComplete="off" aria-label="Token aplikacji" value={token} onChange={e=>setToken(e.target.value)} placeholder={settings.hasToken?'Zapisany w procesie aplikacji · pozostaw puste':'APP_TOKEN'}/></label><label className="check"><input type="checkbox" checked={settings.rememberToken} onChange={e=>setting('rememberToken',e.target.checked)}/>Zapamiętaj token w szyfrowanym magazynie systemu</label><label>Repozytorium GitHub<input value={settings.repository} onChange={e=>setting('repository',e.target.value)}/></label><label>Głos<select value={settings.tts} onChange={e=>setting('tts',e.target.value as Settings['tts'])}><option value="system">Systemowy · bez kosztów API</option><option value="google">Google Cloud · męski Charon</option></select></label><label>Głos systemowy<select value={settings.voice} onChange={e=>setting('voice',e.target.value)}><option value="">Automatycznie · preferuj głos męski</option>{voices.map(v=><option value={v.voiceURI} key={v.voiceURI}>{v.name}</option>)}</select></label><button type="button" onClick={()=>manualSpeak('Dzień dobry. Jestem Jarvis. Gotowy do działania.')}>Odsłuchaj głos</button><p>Jeśli brak polskiego głosu, doinstaluj go w ustawieniach mowy Windows. Mikrofon wysyła krótkie nagranie do OpenAI przez Twój Worker; może generować koszt. Nasłuch hasła w tle skonfiguruj w sekcji wybudzania głosowego.</p><label>Szerokość geograficzna<input type="number" min={-90} max={90} step="any" value={settings.latitude} onChange={e=>setting('latitude',Number(e.target.value))}/></label><label>Długość geograficzna<input type="number" min={-180} max={180} step="any" value={settings.longitude} onChange={e=>setting('longitude',Number(e.target.value))}/></label>{[['startup','Uruchamiaj razem z Windows'],['localControl','Zezwalaj na podstawowe sterowanie komputerem (każda akcja wymaga zatwierdzenia)'],['lowPower','Tryb oszczędny · mniej animacji i efektów']] .map(([key,label])=><label className="check" key={key}><input type="checkbox" checked={settings[key as 'startup'|'localControl'|'lowPower']} onChange={e=>setting(key as 'startup'|'localControl'|'lowPower',e.target.checked)}/>{label}</label>)}<button className="primary">Zapisz i sprawdź połączenie</button></form>}</main>}
  <footer><span>JARVIS DESKTOP <b>0.5 TEST</b></span><span>Kontrola należy do Ciebie.</span></footer></div></div>;
}
createRoot(document.getElementById('root')!).render(<App/>);
