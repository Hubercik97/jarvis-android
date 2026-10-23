// Offline-only DSP/inference worker. No network, microphone, filesystem writes or recordings.
import {parentPort,workerData} from 'node:worker_threads';
import path from 'node:path';
import * as ort from 'onnxruntime-node';
const port=parentPort!;
let mel:ort.InferenceSession,embedding:ort.InferenceSession,classifier:ort.InferenceSession;
let previous=new Float32Array(480),spectrum=new Float32Array(76*32).fill(1),features=new Float32Array(24*96),frames=0;
let busy=false,epoch=0,resetPending=false,quietFrames=0;
let stage='initializing';
const quietGate=workerData.quietGate!==false;
const threadUsage=()=>{try{return typeof process.threadCpuUsage==='function'?process.threadCpuUsage():null;}catch{return null;}};
let lastCpu=threadUsage(),lastCpuAt=performance.now(),cpuPct:number|null=null;
function telemetry(){
 const now=performance.now();if(now-lastCpuAt>=1000){const cpu=threadUsage();if(cpu&&lastCpu)cpuPct=100*(cpu.user+cpu.system-lastCpu.user-lastCpu.system)/((now-lastCpuAt)*1000);lastCpu=cpu;lastCpuAt=now;}
 const memory=process.memoryUsage();return {workerThreadCpuPct:cpuPct,workerJsHeapMiB:memory.heapUsed/1048576,mainProcessRssMiB:memory.rss/1048576};
}
function reset(){previous.fill(0);spectrum.fill(1);features.fill(0);frames=0;resetPending=false;quietFrames=0;}
async function load(){
 const options:ort.InferenceSession.SessionOptions={executionProviders:['cpu'],intraOpNumThreads:1,interOpNumThreads:1,graphOptimizationLevel:'all'};
 const progress=(value:string)=>{stage=value;port.postMessage({type:'progress',stage:value});};
 progress('loading_mel');
 mel=await ort.InferenceSession.create(path.join(workerData.directory,'melspectrogram.onnx'),options);
 progress('loading_embedding');
 embedding=await ort.InferenceSession.create(path.join(workerData.directory,'embedding_model.onnx'),options);
 progress('loading_classifier');
 classifier=await ort.InferenceSession.create(path.join(workerData.directory,'pobudka-jarvis.onnx'),options);
 // Warm each native graph with synthetic data before requesting the microphone.
 // This pays lazy kernel initialization outside the real-time frame deadline.
 progress('warming_mel');
 await mel.run({input:new ort.Tensor('float32',new Float32Array(1760),[1,1760])});
 progress('warming_embedding');
 await embedding.run({input_1:new ort.Tensor('float32',new Float32Array(76*32).fill(1),[1,76,32,1])});
 progress('warming_classifier');
 await classifier.run({[classifier.inputNames[0]]:new ort.Tensor('float32',new Float32Array(24*96),[1,24*96])});
 reset();stage='ready';
 port.postMessage({type:'ready'});
}
port.on('message',async(message)=>{
 if(message.type==='reset'){
  epoch=message.epoch;resetPending=true;
  // An ONNX run may still read the backing arrays. Never mutate them mid-run.
  if(!busy)reset();return;
 }
 if(message.type!=='pcm'||message.epoch!==epoch||!classifier)return;
 if(busy){port.postMessage({type:'skipped',epoch:message.epoch,frame:message.frame});return;}
 busy=true;
 try{
  const start=performance.now(),chunk=new Float32Array(message.samples),frameEpoch=message.epoch;
  if(chunk.length!==1280||!chunk.every(Number.isFinite))throw new Error('Invalid PCM frame');
  let energy=0;for(const sample of chunk)energy+=sample*sample;
  quietFrames=Math.sqrt(energy/1280)<.0005?quietFrames+1:0;
  // Only almost-silent input (-66 dBFS), after 2.56 s of normal processing.
  // At this point all 24 embeddings contain silence. Preserve that quiet context
  // and resume full inference immediately on any audible input, with no VAD model.
  if(quietGate&&quietFrames>32&&frames>=24){
   for(let i=0;i<480;i++)previous[i]=chunk[800+i]*32767;
   port.postMessage({type:'score',score:0,quietSkipped:true,ms:performance.now()-start,epoch:frameEpoch,frame:message.frame,...telemetry()});return;
  }
  const input=new Float32Array(1760);input.set(previous);for(let i=0;i<1280;i++)input[i+480]=Math.max(-1,Math.min(1,chunk[i]))*32767;
  previous=input.slice(-480);
  stage='mel';const m=await mel.run({input:new ort.Tensor('float32',input,[1,1760])});
  if(frameEpoch!==epoch)return;
  const raw=m[mel.outputNames[0]].data as Float32Array;
  const count=raw.length;if(count!==8*32)throw new Error('Unexpected mel dimensions');
  spectrum.copyWithin(0,count);for(let i=0;i<count;i++)spectrum[spectrum.length-count+i]=raw[i]/10+2;
  stage='embedding';const e=await embedding.run({input_1:new ort.Tensor('float32',spectrum,[1,76,32,1])});
  if(frameEpoch!==epoch)return;
  const values=e[embedding.outputNames[0]].data as Float32Array;
  if(values.length!==96)throw new Error('Unexpected embedding dimensions');
  features.copyWithin(0,96);features.set(values,features.length-96);frames++;
  let score=0;
  if(frames>=24){stage='classifier';const c=await classifier.run({[classifier.inputNames[0]]:new ort.Tensor('float32',features,[1,24*96])});score=Number(c[classifier.outputNames[1]].data[1]);}
  if(frameEpoch===epoch)port.postMessage({type:'score',score,quietSkipped:false,ms:performance.now()-start,epoch:frameEpoch,frame:message.frame,...telemetry()});
 }catch{port.postMessage({type:'error',code:`inference_${stage}_failed`,message:'Błąd lokalnego modelu wybudzania.'});}
 finally{busy=false;if(resetPending)reset();}
});
void load().catch(()=>port.postMessage({type:'error',code:`startup_${stage}_failed`,message:'Nie można wczytać lokalnego modelu wybudzania. Napraw instalację Jarvisa.'}));
