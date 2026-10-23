import React,{useEffect,useRef,useState} from 'react';
import {HologramEngine,type SceneMetrics} from './engine';
import {silentFrame,type SignalFrame,type VisualState} from './signal';
export function Hologram({state,signal,lowPower,onPress}:{state:VisualState;signal:React.RefObject<SignalFrame>;lowPower:boolean;onPress:()=>void}){
 const host=useRef<HTMLDivElement>(null);const input=useRef({state,signal,lowPower});input.current={state,signal,lowPower};
 const [metrics,setMetrics]=useState<SceneMetrics|null>(null);
 useEffect(()=>{if(!host.current)return;const scene=new HologramEngine(host.current,()=>({state:input.current.state,signal:input.current.signal.current||silentFrame(),lowPower:input.current.lowPower}),setMetrics);return()=>scene.dispose();},[]);
 return <section className={`hologram-scene state-${state.toLowerCase()}`} aria-label="Holograficzny rdzeń AI">
  <div className="scene-top"><span>NEURAL INTERFACE</span><span>J / CORE 01</span></div>
  <div className="scene-crosshair" aria-hidden="true"/>
  <div className="scene-canvas" ref={host}/>
  <button className="scene-trigger" onClick={onPress} aria-label={state==='SPEAKING'?'Przerwij wypowiedź':'Rozpocznij lub zakończ nagrywanie'} title="Mikrofon / przerwij głos"/>
  <div className="scene-caption"><span className="state-led"/>{state}<small>{state==='WAKE_WORD_DETECTED'?'POBUDKA · AKTYWACJA':state==='SPEAKING'?'ODPOWIEDŹ GŁOSOWA':state==='LISTENING'?'SŁUCHAM TWOJEGO POLECENIA':state==='THINKING'?'PRZETWARZANIE POLECENIA':state==='ERROR'?'SPRAWDŹ KOMUNIKAT SYSTEMU':'GOTOWY NA TWOJE POLECENIE'}</small></div>
  <div className="scene-metrics"><span>{metrics?`${metrics.renderer} / ${metrics.quality}`:'INICJALIZACJA'}</span><span>{metrics?`${metrics.particles} PTS · ${metrics.paused?'PAUZA':metrics.fps+' FPS'}`:'—'}</span></div>
 </section>;
}
export function SignalPanel({signal,state,sync}:{signal:React.RefObject<SignalFrame>;state:VisualState;sync:string}){
 const [frame,setFrame]=useState(silentFrame());
 useEffect(()=>{const interval=setInterval(()=>{if(!document.hidden)setFrame({...signal.current});},100);return()=>clearInterval(interval);},[signal]);
 const live=frame.available&&((state==='LISTENING'&&frame.source==='microphone')||(state==='SPEAKING'&&frame.source==='tts'));
 const path=live?frame.wave.map((v,i)=>`${i?'L':'M'}${(i*240/47).toFixed(1)},${(30-v*100).toFixed(1)}`).join(' '):'M0,30 L240,30';
 return <div className="hud-panel signal-panel"><h3>SYGNAŁ AUDIO <span>{live?'LIVE':'—'}</span></h3><svg viewBox="0 0 240 60" role="img" aria-label={live?'Rzeczywisty przebieg audio':'Brak sygnału audio'}><path d={path}/></svg><div className="signal-readout"><span>{live?(frame.source==='tts'?'WYJŚCIE TTS':'MIKROFON'):'BRAK AKTYWNEGO PCM'}</span><span>{live?`${Math.round(frame.rms*100)}% RMS`:'—'}</span></div><p>{sync}</p></div>;
}
export function HudPanel({title,children,className=''}:{title:string;children:React.ReactNode;className?:string}){return <section className={`hud-panel ${className}`}><h3>{title}<span className="panel-corner">⌖</span></h3>{children}</section>;}
