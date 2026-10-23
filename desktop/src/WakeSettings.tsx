import React,{useEffect,useState} from 'react';
import type {WakePreferences,WakeStatus} from '../shared/types';
export function WakeSettings({preferences,status,onSave}:{preferences:WakePreferences;status:WakeStatus;onSave:(p:WakePreferences)=>Promise<void>}){
 const [draft,setDraft]=useState(preferences);const [devices,setDevices]=useState<MediaDeviceInfo[]>([]);const [message,setMessage]=useState('');const [saving,setSaving]=useState(false);
 useEffect(()=>setDraft(preferences),[preferences]);
 const refresh=async(request=false)=>{try{if(request){const stream=await navigator.mediaDevices.getUserMedia({audio:true});stream.getTracks().forEach(t=>t.stop());}setDevices((await navigator.mediaDevices.enumerateDevices()).filter(d=>d.kind==='audioinput'));setMessage('');}catch{setMessage('Nie można pobrać mikrofonów. Sprawdź uprawnienia Windows.');}};
 useEffect(()=>{void refresh();const change=()=>void refresh();navigator.mediaDevices?.addEventListener('devicechange',change);return()=>navigator.mediaDevices?.removeEventListener('devicechange',change);},[]);
 function update<K extends keyof WakePreferences>(key:K,value:WakePreferences[K]){setDraft(p=>({...p,[key]:value}));}
 return <section className="wake-settings card"><h3>Wybudzanie głosowe · Windows</h3><p>Hasło: <strong>„Pobudka Jarvis”</strong> lub „Pobudka, Dżarwis”. Detektor działa lokalnie, także w zasobniku. Nie zapisuje nagrań ani nie wysyła dźwięku hasła do chmury.</p>
  <form onSubmit={async e=>{e.preventDefault();setSaving(true);try{await onSave(draft);setMessage('Zapisano ustawienia lokalnego nasłuchu.');}catch(error){setMessage(error instanceof Error?error.message:String(error));}finally{setSaving(false);}}}>
   <label className="check"><input type="checkbox" checked={draft.enabled} onChange={e=>update('enabled',e.target.checked)}/>Włącz wybudzanie głosowe</label>
   <label className="check"><input type="checkbox" checked={draft.autoStart} onChange={e=>update('autoStart',e.target.checked)}/>Uruchamiaj nasłuchiwanie wraz z Jarvisem</label>
   <label>Czułość rozpoznawania hasła<input aria-label="Czułość rozpoznawania hasła" type="range" min="0" max="1" step="0.05" value={draft.sensitivity} onChange={e=>update('sensitivity',Number(e.target.value))}/><small>{Math.round(draft.sensitivity*100)}% · wyższa czułość zwiększa ryzyko przypadkowego wybudzenia.</small></label>
   <label>Mikrofon wybudzania i poleceń<select aria-label="Mikrofon wybudzania i poleceń" value={draft.deviceId} onChange={e=>update('deviceId',e.target.value)}><option value="">Domyślny mikrofon Windows</option>{draft.deviceId&&!devices.some(d=>d.deviceId===draft.deviceId)&&<option value={draft.deviceId}>Zapisany mikrofon · obecnie niedostępny</option>}{devices.filter(d=>d.deviceId!=='default'&&d.deviceId!=='communications').map((d,i)=><option key={d.deviceId} value={d.deviceId}>{d.label||`Mikrofon ${i+1}`}</option>)}</select></label>
   <button type="button" onClick={()=>void refresh(true)}>Udostępnij listę mikrofonów</button>
   <label className="check"><input type="checkbox" checked={draft.confirmation} onChange={e=>update('confirmation',e.target.checked)}/>Głosowe potwierdzenie: „Jestem, słucham cię”</label>
   <p>Po wybudzeniu krótkie polecenie trafia do istniejącej transkrypcji w chmurze i systemu AI. Obowiązują dotychczasowe uprawnienia i zgody na działania. Po odpowiedzi wracam do nasłuchu hasła.</p>
   <p>Zablokowany ekran: działanie zależy od Windows i uprawnień mikrofonu. Jarvis nie odblokowuje komputera. Nagrania poleceń pozostają wyłącznie w pamięci do czasu transkrypcji.</p>
   <p role="note">{status.message} {message}</p><button type="submit" disabled={saving}>{saving?'Zapisuję…':'Zapisz ustawienia wybudzania'}</button>
  </form></section>;
}
