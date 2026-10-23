import {Worker} from 'node:worker_threads';
import type {WakeStatus} from '../shared/types.js';
import {WakeGate} from './wake-policy.cjs';

type WorkerFactory=(file:string,directory:string)=>Worker;
const defaultWorker:WorkerFactory=(file,directory)=>{
 // Keep the N-API shared library registered in this long-lived Node environment.
 // Loading it only inside a disposable worker makes a second worker fail with
 // ERR_DLOPEN_FAILED after the first environment unloads the native addon.
 // This is lazy; no model or inference runs in the main process.
 require('onnxruntime-node');
 return new Worker(file,{workerData:{directory}});
};
/** Owns a single offline worker and a bounded queue (at most 240 ms of PCM).
 * Epoch/frame IDs make scores from a previous microphone or pause unusable.
 */
export class WakeService{
 private worker:Worker|null=null;private gate=new WakeGate();private pending:number|null=null;private generation=0;private epoch=0;private nextFrame=0;private queued:Float32Array[]=[];private discontinuous=false;private overruns=0;private timeout:ReturnType<typeof setTimeout>|undefined;private frameTimeout:ReturnType<typeof setTimeout>|undefined;
 status:WakeStatus={phase:'off',message:'Wybudzanie wyłączone. Mikrofon nie nasłuchuje hasła.'};
 constructor(private workerFile:string,private directory:string,private publish:(s:WakeStatus)=>void,private createWorker:WorkerFactory=defaultWorker,private now:()=>number=()=>performance.now()){}
 private emit(phase:WakeStatus['phase'],message:string){this.status={phase,message};this.publish(this.status);}
 private resetPipeline(){clearTimeout(this.frameTimeout);this.epoch++;this.pending=null;this.queued=[];this.discontinuous=false;this.gate.reset();this.worker?.postMessage({type:'reset',epoch:this.epoch});}
 start(sensitivity:number){
  this.stop();const generation=++this.generation;this.gate.sensitivity=sensitivity;this.emit('loading','Ładowanie lokalnego detektora „Pobudka Jarvis”…');
  try{
   const worker=this.worker=this.createWorker(this.workerFile,this.directory);
   this.timeout=setTimeout(()=>{if(generation===this.generation)this.fail('Przekroczono czas ładowania modelu.');},20000);
   worker.on('message',m=>{
    if(generation!==this.generation)return;
    if(m.type==='ready'&&this.status.phase==='loading'){clearTimeout(this.timeout);this.resetPipeline();this.emit('starting','Model gotowy · oczekiwanie na dostęp do mikrofonu.');}
    if(m.type==='score'||m.type==='skipped'){
     if(m.epoch!==this.epoch||m.frame!==this.pending)return;
     clearTimeout(this.frameTimeout);
     this.pending=null;
     if(this.discontinuous||m.type==='skipped'){
      this.overruns++;this.resetPipeline();
      if(this.overruns>=5)this.fail('Komputer nie nadąża z lokalnym nasłuchem. Zamknij obciążające programy i spróbuj ponownie.');
      return;
     }
     if(!Number.isFinite(m.score)||m.score<0||m.score>1){this.fail('Lokalny detektor zwrócił nieprawidłowy wynik.');return;}
     if(this.status.phase==='armed'&&this.gate.accept(m.score,this.now())){
      this.resetPipeline();this.emit('detected','Pobudka Jarvis · aktywacja.');return;
     }
     const next=this.queued.shift();if(next&&this.status.phase==='armed')this.dispatch(next);else this.overruns=Math.max(0,this.overruns-1);
    }
    if(m.type==='error')this.fail(typeof m.message==='string'?m.message:'Błąd lokalnego modelu wybudzania.');
   });
   worker.on('error',()=>{if(generation===this.generation)this.fail('Lokalny detektor został zatrzymany z powodu błędu.');});
   worker.on('exit',()=>{if(generation===this.generation)this.fail('Lokalny detektor został nieoczekiwanie zamknięty.');});
  }catch{this.fail('Nie udało się uruchomić lokalnego detektora.');}
 }
 private dispatch(value:Float32Array){
  if(!this.worker)return;const samples=value.slice().buffer;const frame=++this.nextFrame;this.pending=frame;
  const epoch=this.epoch;
  this.frameTimeout=setTimeout(()=>{if(this.epoch===epoch&&this.pending===frame)this.fail('Lokalny detektor nie odpowiada. Włącz nasłuch ponownie.');},3000);
  this.worker.postMessage({type:'pcm',samples,epoch:this.epoch,frame},[samples]);
 }
 pcm(value:unknown){
  if(this.status.phase!=='armed'||!this.worker)return;
  if(!(value instanceof Float32Array)||value.length!==1280||!value.every(Number.isFinite))throw new Error('Nieprawidłowa ramka mikrofonu');
  if(this.pending!==null){
   if(this.discontinuous)return;
   if(this.queued.length<3)this.queued.push(value.slice());
   else{this.queued=[];this.discontinuous=true;this.gate.reset();}
   return;
  }
  this.dispatch(value);
 }
 pause(){if(!this.worker||this.status.phase==='loading'||this.status.phase==='paused')return;this.resetPipeline();this.emit('paused','Nasłuch hasła wstrzymany na czas rozmowy lub głosu Jarvisa.');}
 ready(){if(this.status.phase==='starting')this.emit('armed','Mikrofon aktywny · lokalnie czekam na „Pobudka Jarvis”.');}
 resume(){if(!this.worker||!['paused','detected'].includes(this.status.phase))return;this.resetPipeline();this.emit('starting','Wznawianie lokalnego mikrofonu…');}
 fail(message:string){this.stop();this.emit('error',message);}
 stop(){this.generation++;clearTimeout(this.timeout);const worker=this.worker;this.worker=null;if(worker)void worker.terminate();this.resetPipeline();this.overruns=0;this.emit('off','Wybudzanie wyłączone. Mikrofon nie nasłuchuje hasła.');}
}
