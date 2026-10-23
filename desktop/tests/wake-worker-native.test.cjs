// Executes the packaged offline DSP and classifier. WAVs are synthetic test data.
const test=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs/promises');
const path=require('node:path');
const {Worker}=require('node:worker_threads');
const {WakeGate}=require('../dist-electron/electron/wake-policy.cjs');
const directory=path.resolve(__dirname,'../resources/wake');
function waitFor(worker,predicate){
 return new Promise((resolve,reject)=>{
  const timer=setTimeout(()=>finish(new Error('Native wake worker response timed out')),10000);
  const finish=(error,value)=>{clearTimeout(timer);worker.off('message',message);worker.off('error',failure);worker.off('exit',exit);error?reject(error):resolve(value);};
  const message=value=>{if(value.type==='error')finish(new Error(value.message));else if(predicate(value))finish(null,value);};
  const failure=error=>finish(error),exit=code=>finish(new Error(`Native worker exited: ${code}`));
  worker.on('message',message);worker.on('error',failure);worker.on('exit',exit);
 });
}
function pcmOf(wav){
 assert.equal(wav.toString('ascii',0,4),'RIFF');assert.equal(wav.toString('ascii',8,12),'WAVE');
 let data=null,format=null;
 for(let at=12;at+8<=wav.length;){const name=wav.toString('ascii',at,at+4),length=wav.readUInt32LE(at+4),chunk=wav.subarray(at+8,at+8+length);if(name==='fmt ')format=chunk;if(name==='data')data=chunk;at+=8+length+(length%2);}
 assert.ok(format&&data);assert.equal(format.readUInt16LE(0),1);assert.equal(format.readUInt16LE(2),1);assert.equal(format.readUInt32LE(4),16000);assert.equal(format.readUInt16LE(14),16);
 const result=new Float32Array(data.length/2);for(let i=0;i<result.length;i++)result[i]=data.readInt16LE(i*2)/32767;return result;
}

test('native offline worker recognizes both phrase spellings and rejects shipped similar words and its own acknowledgement', {timeout:60000},async t=>{
 const manifest=JSON.parse(await fs.readFile(path.join(directory,'fixtures/manifest.json'),'utf8'));
 assert.ok(manifest.filter(f=>f.expected).length>=2);assert.ok(manifest.filter(f=>!f.expected).length>=3);
 const worker=new Worker(path.resolve(__dirname,'../dist-electron/electron/wake-worker.cjs'),{workerData:{directory}});
 t.after(()=>worker.terminate());await waitFor(worker,m=>m.type==='ready');
 let frame=0,epoch=0;const times=[],results=[];
 const infer=async samples=>{
  worker.postMessage({type:'reset',epoch:++epoch});const scores=[];
  for(let offset=0;offset+1280<=samples.length;offset+=1280){
   const chunk=samples.slice(offset,offset+1280),id=++frame;
   const response=waitFor(worker,m=>m.type==='score'&&m.epoch===epoch&&m.frame===id);
   worker.postMessage({type:'pcm',samples:chunk.buffer,epoch,frame:id},[chunk.buffer]);
   const value=await response;assert.ok(Number.isFinite(value.score)&&value.score>=0&&value.score<=1);scores.push(value.score);times.push(value.ms);
  }
  return scores;
 };
 let first=null,firstScores=null;
 for(const fixture of manifest){
  const wav=pcmOf(await fs.readFile(path.join(directory,'fixtures',fixture.file)));const scores=await infer(wav);
  const score=Math.max(0,...scores.slice(1).map((s,i)=>Math.min(s,scores[i])));
  assert.equal(score>=new WakeGate().threshold,fixture.expected,`${fixture.voice}: ${fixture.text}, two-frame score=${score}`);
  results.push({file:fixture.file,expected:fixture.expected,score:Number(score.toFixed(5))});
  if(first===null){first=wav;firstScores=scores;}
 }
 // Clearing state must really erase the previous phrase, not just the gate counter.
 const repeated=await infer(first);assert.equal(repeated.length,firstScores.length);
 repeated.forEach((s,i)=>assert.ok(Math.abs(s-firstScores[i])<1e-6,`reset must reproduce frame ${i}`));
 const silence=await infer(new Float32Array(16000*4));assert.ok(silence.every(s=>s<new WakeGate().threshold));
 // Real standby followed by speech must resume immediately from the cheap path.
 const resumedPcm=new Float32Array(16000*6+first.length);resumedPcm.set(first,16000*6);
 const resumed=await infer(resumedPcm);assert.ok(resumed.every(s=>s<new WakeGate().threshold),'first fixture is a similar-word negative');
 const positive=manifest.find(f=>f.expected);const phrase=pcmOf(await fs.readFile(path.join(directory,'fixtures',positive.file)));
 const afterSilence=new Float32Array(16000*6+phrase.length);afterSilence.set(phrase,16000*6);const restored=await infer(afterSilence);
 assert.ok(restored.some((s,i)=>i>0&&Math.min(s,restored[i-1])>=new WakeGate().threshold),'wake phrase must still work after extended quiet');
 // Generated non-speech background signals, never microphone recordings.
 let seed=419;const noise=new Float32Array(16000*4),tone=new Float32Array(16000*4);
 for(let i=0;i<noise.length;i++){
  seed=(Math.imul(seed,1664525)+1013904223)>>>0;noise[i]=(seed/0xffffffff-.5)*.3;
  tone[i]=Math.sin(i*2*Math.PI*440/16000)*.2+Math.sin(i*2*Math.PI*1510/16000)*.08;
 }
 for(const background of [noise,tone]){const scores=await infer(background);assert.ok(scores.every(s=>s<new WakeGate().threshold),'non-speech tone/noise must not wake the assistant');}
 const sorted=times.toSorted((a,b)=>a-b),mean=times.reduce((a,b)=>a+b,0)/times.length;
 console.log('Native offline wake fixtures:',JSON.stringify(results));
 console.log('Native inference measurement (this CI/cloud CPU, not physical N4000):',JSON.stringify({frames:times.length,meanMs:Number(mean.toFixed(3)),p95Ms:Number(sorted[Math.floor(sorted.length*.95)].toFixed(3)),rssMiB:Number((process.memoryUsage().rss/1024/1024).toFixed(1))}));
});

test('native detector can stop and start again in one application process', {timeout:30000},async()=>{
 // A fresh process ensures the preceding raw-worker fixture test cannot pin the
 // addon accidentally and hide a missing production lifetime fix.
 const {execFile}=require('node:child_process'),{promisify}=require('node:util');
 const script=`
 const assert=require('node:assert/strict'),path=require('node:path');
 const {WakeService}=require(process.argv[1]);let notify=null;
 const service=new WakeService(path.join(path.dirname(process.argv[1]),'wake-worker.cjs'),process.argv[2],status=>notify?.(status));
 (async()=>{for(let run=0;run<3;run++){
  const started=new Promise((resolve,reject)=>{const timer=setTimeout(()=>reject(new Error('native detector restart timed out')),10000);notify=status=>{if(status.phase==='starting'){clearTimeout(timer);resolve();}if(status.phase==='error'){clearTimeout(timer);reject(new Error(status.message));}};});
  service.start(.5);await started;service.ready();assert.equal(service.status.phase,'armed');service.stop();assert.equal(service.status.phase,'off');
 }console.log('three native start/stop cycles passed');})().catch(error=>{console.error(error);service.stop();process.exitCode=1;});`;
 const {stdout}=await promisify(execFile)(process.execPath,['-e',script,path.resolve(__dirname,'../dist-electron/electron/wake-service.cjs'),directory],{timeout:25000});
 assert.match(stdout,/three native start\/stop cycles passed/);
});
