import React,{useEffect,useRef,useState} from 'react';
import type {Bridge,Settings,DesktopDiagnostics,CloudCheck} from '../shared/types';

const cloudLabels:Record<CloudCheck['state'],string>={not_configured:'Uzupełnij adres API i token w konfiguracji chmury.',authenticated:'HTTPS/API: uwierzytelniono, model skonfigurowany.',provider_not_ready:'Token poprawny, ale dostawca AI wymaga konfiguracji na serwerze.',unauthorized:'Token odrzucony lub brak uprawnień do API.',network_error:'Nie udało się połączyć. Sprawdź HTTPS, certyfikat i internet.',invalid_response:'Serwer nie zwrócił poprawnej odpowiedzi API Jarvisa.'};
export function LocalControlSettings({bridge,settings,onSettings}:{bridge:Bridge;settings:Settings;onSettings:(settings:Settings)=>void}){
 const [saving,setSaving]=useState(false);const [requested,setRequested]=useState<boolean|null>(null);const [message,setMessage]=useState('');
 return <section className="card control-settings" aria-label="Sterowanie lokalne"><h3>Sterowanie komputerem · Twoja zgoda</h3>
  <label className="check"><input type="checkbox" aria-label="Włącz sterowanie lokalne" aria-busy={saving} checked={requested??settings.localControl} disabled={saving} onChange={async e=>{const enabled=e.target.checked;setRequested(enabled);setSaving(true);try{onSettings(await bridge.localControl(enabled));setMessage(enabled?'Zgoda zapisana. Każda operacja nadal wymaga potwierdzenia.':'Sterowanie lokalne wyłączone.');}catch{setMessage('Nie udało się zapisać zgody. Spróbuj ponownie.');}finally{setSaving(false);setRequested(null);}}}/>Włącz sterowanie lokalne</label>
  <p>Zgoda zapisuje się od razu, niezależnie od formularza API. Pozwala wyłącznie otwierać Chrome, Dokumenty i skonfigurowany projekt GitHub. Każda akcja wymaga uwierzytelnienia w chmurze oraz osobnego zatwierdzenia.</p>
  <p role="note">{saving?'Zapisuję zgodę…':message||`Sterowanie lokalne: ${settings.localControl?'włączone':'wyłączone'}.`}</p>
 </section>;
}
type MicCheck={state:string;inputRate?:number;contextRate?:number;channels?:number;rms:number;peak:number;muted:boolean;permission?:string};
const initialMic:MicCheck={state:'Nie uruchomiono testu mikrofonu.',rms:0,peak:0,muted:false};
export function DiagnosticsPanel({bridge,deviceId,onPrepareAudio,onVoiceTest,onAudioBusy,onCloudCheck}:{bridge:Bridge;deviceId:string;onPrepareAudio:()=>Promise<void>;onVoiceTest:()=>Promise<void>;onAudioBusy:(busy:boolean)=>void;onCloudCheck:(check:CloudCheck)=>void}){
 const [data,setData]=useState<DesktopDiagnostics|null>(null);const [mic,setMic]=useState<MicCheck>(initialMic);const [audioBusy,setAudioBusy]=useState(false);const [checking,setChecking]=useState(false);const [showReport,setShowReport]=useState(false);const [error,setError]=useState('');
 const resources=useRef<{stream:MediaStream;context:AudioContext;interval:ReturnType<typeof setInterval>}|null>(null);const generation=useRef(0);
 function release(){generation.current++;const r=resources.current;resources.current=null;if(r){clearInterval(r.interval);r.stream.getTracks().forEach(t=>{t.onended=null;t.stop();});void r.context.close();}onAudioBusy(false);setAudioBusy(false);}
 useEffect(()=>{let live=true;const refresh=()=>bridge.diagnostics().then(d=>{if(live)setData(d);}).catch(()=>{if(live)setError('Diagnostyka procesu jest niedostępna.');});void refresh();const interval=setInterval(()=>void refresh(),1000);return()=>{live=false;clearInterval(interval);release();};},[]);
 async function testMic(){
  release();const run=generation.current;setError('');setAudioBusy(true);onAudioBusy(true);setMic({...initialMic,state:'Oczekiwanie na mikrofon…'});
  let openedStream:MediaStream|null=null;let openedContext:AudioContext|null=null;
  try{
   await onPrepareAudio();if(run!==generation.current)return;
   const stream=openedStream=await navigator.mediaDevices.getUserMedia({audio:{...(deviceId?{deviceId:{exact:deviceId}}:{}),channelCount:1,echoCancellation:true,noiseSuppression:true},video:false});
   if(run!==generation.current){stream.getTracks().forEach(t=>t.stop());return;}
   const context=openedContext=new AudioContext();const analyser=context.createAnalyser();analyser.fftSize=512;
   context.createMediaStreamSource(stream).connect(analyser);await context.resume();
   if(run!==generation.current){stream.getTracks().forEach(t=>t.stop());await context.close();return;}
   const track=stream.getAudioTracks()[0],settings=track.getSettings(),wave=new Float32Array(512);let max=0,count=0;
   const sample=()=>{
    if(run!==generation.current)return;analyser.getFloatTimeDomainData(wave);let sum=0,peak=0;for(const value of wave){sum+=value*value;peak=Math.max(peak,Math.abs(value));}max=Math.max(max,peak);count++;
    const check:MicCheck={state:count>=50?(max>.001?'Test zakończony · wykryto sygnał.':'Test zakończony · cisza lub bardzo niski sygnał.'):'Mikrofon aktywny · mów przez 5 sekund.',inputRate:settings.sampleRate,contextRate:context.sampleRate,channels:settings.channelCount,rms:Math.sqrt(sum/wave.length),peak:max,muted:track.muted,permission:'granted'};
    setMic(check);if(count>=50)release();
   };
   resources.current={stream,context,interval:setInterval(sample,100)};
   track.onended=()=>{release();setMic(m=>({...m,state:'Utracono dostęp do mikrofonu.'}));};sample();
  }catch(e){openedStream?.getTracks().forEach(t=>t.stop());if(openedContext&&openedContext.state!=='closed')void openedContext.close();if(run!==generation.current)return;release();const name=e instanceof DOMException?e.name:'';setMic({...initialMic,state:name==='NotAllowedError'?'Brak zgody na mikrofon. Sprawdź prywatność mikrofonu w Windows.':name==='NotFoundError'||name==='OverconstrainedError'?'Wybrany mikrofon jest niedostępny. Wybierz działające urządzenie.':'Nie można uruchomić mikrofonu. Sprawdź urządzenie i uprawnienia.',permission:name==='NotAllowedError'?'denied':'unknown'});}
 }
 const number=(value:number|null|undefined,suffix='')=>typeof value==='number'?`${value.toFixed(1)}${suffix}`:'Brak pomiaru';
 return <section className="card diagnostics" aria-label="Diagnostyka Jarvisa"><h3>Diagnostyka · mikrofon, nasłuch i chmura</h3>
  <p>Test mikrofonu jest lokalny, trwa 5 sekund i nie zapisuje ani nie wysyła dźwięku. Zatrzymuje nasłuch hasła na czas sprawdzania.</p>
  <div className="diagnostic-buttons"><button type="button" disabled={audioBusy} onClick={()=>void testMic()}>Testuj mikrofon lokalnie</button>{audioBusy&&<button type="button" onClick={()=>{release();setMic(m=>({...m,state:'Test mikrofonu zatrzymany.'}));}}>Zatrzymaj test mikrofonu</button>}<button type="button" disabled={audioBusy} onClick={async()=>{release();await onVoiceTest();}}>Rozmowa głosowa bez hasła</button></div>
  <p aria-live="polite">{mic.state}</p><meter min="0" max="1" value={mic.rms} aria-label="Poziom mikrofonu"/><p>Sygnał RMS: {number(mic.rms*100,'%')} · szczyt: {number(mic.peak*100,'%')} · wejście: {number(mic.inputRate,' Hz')} · kontekst: {number(mic.contextRate,' Hz')}{mic.muted?' · mikrofon wstrzymany':''}</p>
  <p>Rozmowa bez hasła wyłącza detektor i otwiera zwykły mikrofon w czacie. Zakończ nagranie kliknięciem mikrofonu, sprawdź transkrypcję i wybierz „Wyślij”. Dopiero ten test korzysta z istniejącego STT/AI w chmurze.</p>
  <p>Test API sprawdza zapisany adres i token. Zmiany adresu oraz tokenu zapisz najpierw w formularzu chmury poniżej.</p><button type="button" disabled={checking} onClick={async()=>{setChecking(true);setError('');try{const check=await bridge.testCloud();onCloudCheck(check);setData(await bridge.diagnostics());}catch{setError('Nie udało się wykonać testu chmury.');}finally{setChecking(false);}}}>{checking?'Sprawdzam API…':'Testuj połączenie API'}</button>
  <p>{data?cloudLabels[data.cloud.state]:'Odczyt diagnostyki…'} {data?.cloud.hasToken?'Token: zapisany w procesie.':'Token: brak.'}</p>
  {data&&<><p>{data.hardware.cpu} · {data.hardware.logicalCpus} procesory logiczne · RAM urządzenia {number(data.hardware.totalMemoryGiB,' GiB')}</p><dl className="diagnostic-values"><dt>Silnik</dt><dd>{data.wake.engine}</dd><dt>Czas ramki średni / p95</dt><dd>{number(data.wake.meanInferenceMs,' ms')} / {number(data.wake.p95InferenceMs,' ms')} (budżet 80 ms)</dd><dt>CPU wątku detektora</dt><dd>{number(data.wake.workerThreadCpuPct,'% jednego rdzenia')}</dd><dt>CPU procesów Desktop (Electron)</dt><dd>{number(data.desktopCpuPct,'%')}</dd><dt>Pamięć JS wątku</dt><dd>{number(data.wake.workerJsHeapMiB,' MiB')}</dd><dt>RSS procesu głównego z natywnym ONNX</dt><dd>{number(data.wake.mainProcessRssMiB,' MiB')}</dd><dt>Suma zestawów roboczych Desktop</dt><dd>{number(data.desktopWorkingSetMiB,' MiB')}</dd><dt>Ramki / pominięta cisza / odrzucone</dt><dd>{data.wake.processed} / {data.wake.quietSkipped} / {data.wake.discarded}</dd><dt>Kolejka / opóźnienie przechwytywania</dt><dd>{data.wake.queueDepth} / {number(data.wake.bufferedMs,' ms')}</dd></dl><p>Pomiar dotyczy tego komputera. Pamięć JS nie obejmuje wszystkich natywnych buforów ONNX; zestawy robocze procesów mogą zawierać współdzielone strony.</p></>}
  {error&&<p role="alert">{error}</p>}<button type="button" onClick={()=>setShowReport(v=>!v)}>{showReport?'Ukryj raport':'Pokaż raport bez sekretów'}</button>{showReport&&<pre className="diagnostic-report" aria-label="Raport diagnostyczny">{JSON.stringify({version:'0.5.1',...data,microphone:mic},null,2)}</pre>}
 </section>;
}
