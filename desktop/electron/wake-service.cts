import {Worker} from 'node:worker_threads';
import type {WakeStatus,WakeReceipt,WakeTelemetry,WakeMetrics} from '../shared/types.js';
import {WakeGate} from './wake-policy.cjs';
type WorkerFactory=(file:string,directory:string)=>Worker;
const defaultWorker:WorkerFactory=(file,directory)=>{
 // Pin N-API in the long-lived process; no inference or model is loaded here.
 require('onnxruntime-node');return new Worker(file,{workerData:{directory}});
};
type Packet={samples:Float32Array;resolve:(receipt:WakeReceipt)=>void};
const rejected=():WakeReceipt=>({accepted:false,reset:true});
export class WakeService{
 private worker:Worker|null=null;private gate=new WakeGate();private pending:{packet:Packet;frame:number}|null=null;
 private generation=0;private epoch=0;private nextFrame=0;private queued:Packet[]=[];private discontinuous=false;
 private slowDebt=0;private slowFrames=0;private timeout:ReturnType<typeof setTimeout>|undefined;private frameTimeout:ReturnType<typeof setTimeout>|undefined;
 private timings:number[]=[];private metrics={received:0,processed:0,quietSkipped:0,discarded:0,resets:0,bufferedMs:0,workerThreadCpuPct:null as number|null,workerJsHeapMiB:null as number|null,mainProcessRssMiB:null as number|null};
 private events:{time:string;code:string}[]=[];
 status:WakeStatus={phase:'off',message:'Wybudzanie wyłączone. Mikrofon nie nasłuchuje hasła.'};
 constructor(private workerFile:string,private directory:string,private publish:(s:WakeStatus)=>void,private createWorker:WorkerFactory=defaultWorker,private now:()=>number=()=>performance.now()){}
 private event(code:string){this.events.push({time:new Date().toISOString(),code});this.events=this.events.slice(-20);}
 diagnostics():WakeMetrics{
  const sorted=[...this.timings].sort((a,b)=>a-b);const mean=this.timings.reduce((s,n)=>s+n,0)/Math.max(1,this.timings.length);
  return {engine:'ONNX CPU · Google/openWakeWord + polski MLP',frameMs:80,phase:this.status.phase,...this.metrics,queueDepth:this.queued.length+(this.pending?1:0),meanInferenceMs:Number(mean.toFixed(2)),p95InferenceMs:Number((sorted[Math.floor(sorted.length*.95)]||0).toFixed(2)),inferenceBudgetPct:Number((mean/80*100).toFixed(1)),events:this.events.map(e=>({...e}))};
 }
 private emit(phase:WakeStatus['phase'],message:string){this.status={phase,message};this.publish(this.status);}
 private resetPipeline(){
  clearTimeout(this.frameTimeout);this.epoch++;this.pending?.packet.resolve(rejected());this.pending=null;
  for(const p of this.queued)p.resolve(rejected());this.queued=[];this.discontinuous=false;this.gate.reset();this.worker?.postMessage({type:'reset',epoch:this.epoch});
 }
 start(sensitivity:number){
  this.stop();this.timings=[];this.slowDebt=0;this.slowFrames=0;
  this.metrics={received:0,processed:0,quietSkipped:0,discarded:0,resets:0,bufferedMs:0,workerThreadCpuPct:null,workerJsHeapMiB:null,mainProcessRssMiB:null};
  const generation=++this.generation;this.gate.sensitivity=sensitivity;this.event('start');this.emit('loading','Ładowanie lokalnego detektora „Pobudka Jarvis”…');
  try{
   const worker=this.worker=this.createWorker(this.workerFile,this.directory);
   this.timeout=setTimeout(()=>{if(generation===this.generation)this.fail('Przekroczono czas ładowania modelu.');},20000);
   worker.on('message',m=>{
    if(generation!==this.generation)return;
    if(m.type==='ready'&&this.status.phase==='loading'){clearTimeout(this.timeout);this.resetPipeline();this.event('model_ready');this.emit('starting','Model gotowy · oczekiwanie na dostęp do mikrofonu.');}
    if(m.type==='score'||m.type==='skipped'){
     if(!this.pending||m.epoch!==this.epoch||m.frame!==this.pending.frame)return;
     clearTimeout(this.frameTimeout);const packet=this.pending.packet;this.pending=null;
     if(this.discontinuous||m.type==='skipped'){
      this.metrics.discarded++;this.metrics.resets++;this.event('audio_gap_reset');packet.resolve(rejected());this.resetPipeline();return;
     }
     if(!Number.isFinite(m.score)||m.score<0||m.score>1){packet.resolve(rejected());this.fail('Lokalny detektor zwrócił nieprawidłowy wynik.');return;}
     const ms=Number.isFinite(m.ms)?Math.max(0,m.ms):0;this.timings.push(ms);if(this.timings.length>128)this.timings.shift();
     this.metrics.processed++;if(m.quietSkipped)this.metrics.quietSkipped++;
     for(const key of ['workerThreadCpuPct','workerJsHeapMiB','mainProcessRssMiB'] as const)if(Number.isFinite(m[key]))this.metrics[key]=Math.max(0,m[key]);
     // IPC/UI bursts are not evidence of a slow model. Use measured inference debt.
     this.slowDebt=Math.max(0,this.slowDebt+ms-80);this.slowFrames=ms>80?this.slowFrames+1:Math.max(0,this.slowFrames-1);
     if(this.slowDebt>1500&&this.slowFrames>=12){packet.resolve(rejected());this.event('sustained_inference_overload');this.fail('Lokalny silnik zbyt długo przetwarza dźwięk. Użyj rozmowy bez hasła i sprawdź diagnostykę nasłuchu.');return;}
     if(this.status.phase==='armed'&&this.gate.accept(m.score,this.now())){
      packet.resolve({accepted:true,reset:false});this.resetPipeline();this.event('detected');this.emit('detected','Pobudka Jarvis · aktywacja.');return;
     }
     packet.resolve({accepted:true,reset:false});const next=this.queued.shift();if(next&&this.status.phase==='armed')this.dispatch(next);
    }
    if(m.type==='error')this.fail('Błąd lokalnego modelu wybudzania. Sprawdź instalację i diagnostykę.');
   });
   worker.on('error',()=>{if(generation===this.generation)this.fail('Lokalny detektor został zatrzymany z powodu błędu.');});
   worker.on('exit',()=>{if(generation===this.generation)this.fail('Lokalny detektor został nieoczekiwanie zamknięty.');});
  }catch{this.fail('Nie udało się uruchomić detektora. Sprawdź instalację i Microsoft Visual C++ x64.');}
 }
 private dispatch(packet:Packet){
  if(!this.worker){packet.resolve(rejected());return;}const samples=packet.samples.slice().buffer;const frame=++this.nextFrame;this.pending={packet,frame};const epoch=this.epoch;
  this.frameTimeout=setTimeout(()=>{if(this.epoch===epoch&&this.pending?.frame===frame)this.fail('Lokalny detektor nie odpowiada. Włącz nasłuch ponownie.');},3000);
  this.worker.postMessage({type:'pcm',samples,epoch,frame},[samples]);
 }
 pcm(value:unknown,telemetry:WakeTelemetry={}):Promise<WakeReceipt>{
  if(this.status.phase!=='armed'||!this.worker)return Promise.resolve(rejected());
  if(!(value instanceof Float32Array)||value.length!==1280||!value.every(Number.isFinite))throw new Error('Nieprawidłowa ramka mikrofonu');
  this.metrics.received++;
  if(Number.isFinite(telemetry.bufferedMs))this.metrics.bufferedMs=Math.max(0,Math.min(10000,telemetry.bufferedMs!));
  if(Number.isFinite(telemetry.discardedFrames))this.metrics.discarded+=Math.max(0,Math.min(10000,Math.floor(telemetry.discardedFrames!)));
  if(telemetry.reset===true){this.metrics.resets++;this.event('capture_gap_reset');this.resetPipeline();}
  return new Promise(resolve=>{
   const packet={samples:value,resolve};
   if(this.pending){
    if(this.discontinuous){this.metrics.discarded++;resolve(rejected());return;}
    if(this.queued.length<12)this.queued.push({samples:value.slice(),resolve});
    else{this.metrics.discarded+=this.queued.length+1;for(const p of this.queued)p.resolve(rejected());this.queued=[];this.discontinuous=true;this.gate.reset();resolve(rejected());}
   }else this.dispatch(packet);
  });
 }
 pause(){if(!this.worker||this.status.phase==='loading'||this.status.phase==='paused')return;this.resetPipeline();this.event('paused');this.emit('paused','Nasłuch hasła wstrzymany na czas rozmowy lub głosu Jarvisa.');}
 ready(){if(this.status.phase==='starting'){this.event('microphone_ready');this.emit('armed','Mikrofon aktywny · lokalnie czekam na „Pobudka Jarvis”.');}}
 resume(){if(!this.worker||!['paused','detected'].includes(this.status.phase))return;this.resetPipeline();this.emit('starting','Wznawianie lokalnego mikrofonu…');}
 fail(message:string){this.stop();this.event('stopped_with_error');this.emit('error',message);}
 stop(){this.generation++;clearTimeout(this.timeout);const worker=this.worker;this.worker=null;if(worker)void worker.terminate();this.resetPipeline();this.slowDebt=0;this.slowFrames=0;this.emit('off','Wybudzanie wyłączone. Mikrofon nie nasłuchuje hasła.');}
}
