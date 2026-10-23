const test=require('node:test');
const assert=require('node:assert/strict');
const {EventEmitter}=require('node:events');
const {WakeGate}=require('../dist-electron/electron/wake-policy.cjs');
const {WakeService}=require('../dist-electron/electron/wake-service.cjs');
class FakeWorker extends EventEmitter{
 sent=[];terminated=false;
 postMessage(value){this.sent.push(value);}
 async terminate(){this.terminated=true;return 0;}
 score(frame,score=.99){this.emit('message',{type:'score',frame:frame.frame,epoch:frame.epoch,score,ms:2});}
 get lastFrame(){return this.sent.findLast(m=>m.type==='pcm');}
}
function fixture(){
 const worker=new FakeWorker();const states=[];let now=10000;
 const service=new WakeService('unused','unused',s=>states.push(s.phase),()=>worker,()=>now+=80);
 service.start(.5);worker.emit('message',{type:'ready'});service.ready();
 return {worker,service,states};
}
const pcm=()=>new Float32Array(1280);

test('complete-phrase policy requires adjacent valid scores and rejects gaps, invalid values and cooldown repeats',()=>{
 const gate=new WakeGate();assert.ok(Math.abs(gate.threshold-.90)<1e-9);
 assert.equal(gate.accept(.99,10000),false);
 assert.equal(gate.accept(.10,10080),false);
 assert.equal(gate.accept(.99,10160),false);
 assert.equal(gate.accept(.99,10700),false,'a gap cannot join two isolated spikes');
 assert.equal(gate.accept(.99,10780),true);
 assert.equal(gate.accept(.99,11000),false);
 assert.equal(gate.accept(.99,11080),false);
 for(const bad of [NaN,Infinity,-1,2])assert.equal(gate.accept(bad,15000),false);
 assert.equal(gate.accept(.99,15100),false);assert.equal(gate.accept(.99,15180),true);
 gate.sensitivity=0;assert.ok(Math.abs(gate.threshold-.97)<1e-9);gate.sensitivity=1;assert.ok(Math.abs(gate.threshold-.83)<1e-9);
 gate.sensitivity=NaN;assert.ok(Math.abs(gate.threshold-.90)<1e-9);
});

test('microphone must become ready; one complete detection pauses scores until an explicit rearm',t=>{
 const {worker,service,states}=fixture();t.after(()=>service.stop());
 assert.equal(service.status.phase,'armed');
 service.pcm(pcm());worker.score(worker.lastFrame);assert.equal(service.status.phase,'armed');
 service.pcm(pcm());worker.score(worker.lastFrame);assert.equal(service.status.phase,'detected');
 const count=worker.sent.length;service.pcm(pcm());assert.equal(worker.sent.length,count);
 assert.equal(states.filter(s=>s==='detected').length,1);
 service.resume();assert.equal(service.status.phase,'starting');
 service.pcm(pcm());assert.equal(worker.sent.at(-1).type,'reset');
 service.ready();assert.equal(service.status.phase,'armed');
});

test('pause/rearm rejects stale in-flight scores and clears partial phrase evidence',t=>{
 const {worker,service}=fixture();t.after(()=>service.stop());
 service.pcm(pcm());worker.score(worker.lastFrame);
 service.pcm(pcm());const stale=worker.lastFrame;
 service.pause();assert.equal(service.status.phase,'paused');
 service.resume();service.ready();
 worker.score(stale);assert.equal(service.status.phase,'armed');
 service.pcm(pcm());worker.score(worker.lastFrame);assert.equal(service.status.phase,'armed','old score must not complete the phrase');
 service.pcm(pcm());worker.score(worker.lastFrame);assert.equal(service.status.phase,'detected');
});

test('brief worker jitter is buffered, while overload drops PCM and resets evidence without growing the queue',t=>{
 const {worker,service}=fixture();t.after(()=>service.stop());
 service.pcm(pcm());const first=worker.lastFrame;
 for(let i=0;i<3;i++)service.pcm(pcm());
 assert.equal(worker.sent.filter(m=>m.type==='pcm').length,1,'only one frame may be in flight');
 worker.score(first,.01);
 assert.equal(worker.sent.filter(m=>m.type==='pcm').length,2,'bounded backlog drains after completion');
 const pending=worker.lastFrame;
 for(let i=0;i<1000;i++)service.pcm(pcm());
 assert.equal(service.status.phase,'armed','one temporary delay must not terminate the detector');
 assert.equal(worker.sent.filter(m=>m.type==='pcm').length,2,'flood cannot create a worker queue');
 worker.score(pending);
 assert.equal(worker.sent.at(-1).type,'reset','discontinuous audio cannot keep old phrase evidence');
 service.pcm(pcm());worker.score(worker.lastFrame);assert.equal(service.status.phase,'armed');
});

test('stop releases the worker, rejects its late messages, and microphone failure remains visible',()=>{
 const {worker,service}=fixture();service.pcm(pcm());const stale=worker.lastFrame;
 service.stop();assert.ok(worker.terminated);assert.equal(service.status.phase,'off');
 worker.score(stale);worker.emit('message',{type:'error',message:'old worker'});assert.equal(service.status.phase,'off');
 const next=fixture();next.service.fail('Mikrofon odłączony');assert.ok(next.worker.terminated);assert.equal(next.service.status.phase,'error');assert.match(next.service.status.message,/Mikrofon/);
 next.service.resume();assert.equal(next.service.status.phase,'error','permission loss must not start an automatic retry loop');next.service.stop();
});

test('invalid or nonfinite audio never reaches the native model',t=>{
 const {worker,service}=fixture();t.after(()=>service.stop());
 assert.throws(()=>service.pcm(new Float32Array(128)),/ramka/);
 const bad=pcm();bad[4]=NaN;assert.throws(()=>service.pcm(bad),/ramka/);
 assert.equal(worker.sent.filter(m=>m.type==='pcm').length,0);
});

test('a stalled inference releases the microphone state with an explicit error instead of listening indefinitely',t=>{
 t.mock.timers.enable({apis:['setTimeout']});
 const {worker,service}=fixture();t.after(()=>service.stop());
 service.pcm(pcm());t.mock.timers.tick(3001);
 assert.equal(service.status.phase,'error');assert.ok(worker.terminated);assert.match(service.status.message,/nie odpowiada/);
});

test('repeated IPC bursts with fast inference recover without falsely blaming the computer',t=>{
 const {worker,service}=fixture();t.after(()=>service.stop());
 for(let run=0;run<10;run++){
  service.pcm(pcm());const frame=worker.lastFrame;
  for(let i=0;i<20;i++)void service.pcm(pcm());
  worker.score(frame,.01);
  assert.equal(service.status.phase,'armed');
 }
 assert.equal(worker.terminated,false);assert.equal(service.diagnostics().resets,10);assert.ok(service.diagnostics().discarded>0);
});
test('sustained measured inference debt stops safely, independently of queue bursts',async t=>{
 const {worker,service}=fixture();t.after(()=>service.stop());
 for(let run=0;run<13;run++){
  const receipt=service.pcm(pcm()),frame=worker.lastFrame;
  worker.emit('message',{type:'score',frame:frame.frame,epoch:frame.epoch,score:.01,ms:210});
  await receipt;
  if(service.status.phase==='error')break;
 }
 assert.equal(service.status.phase,'error');assert.ok(worker.terminated);assert.match(service.status.message,/bez hasła/);
 assert.ok(service.diagnostics().meanInferenceMs>=200);
});
test('acknowledgement waits for inference and is released on pause/stop',async t=>{
 const {worker,service}=fixture();t.after(()=>service.stop());let completed=false;
 const first=service.pcm(pcm()).then(r=>{completed=true;return r;});await Promise.resolve();assert.equal(completed,false);
 worker.score(worker.lastFrame,.01);assert.deepEqual(await first,{accepted:true,reset:false});
 const cancelled=service.pcm(pcm());service.pause();assert.deepEqual(await cancelled,{accepted:false,reset:true});
 service.resume();service.ready();const stopped=service.pcm(pcm());service.stop();assert.equal((await stopped).accepted,false);
});
test('an old inference still busy after reset cannot keep the microphone armed indefinitely',async t=>{
 const {worker,service}=fixture();t.after(()=>service.stop());
 for(let run=0;run<45&&service.status.phase==='armed';run++){
  const receipt=service.pcm(pcm()),frame=worker.lastFrame;
  worker.emit('message',{type:'skipped',frame:frame.frame,epoch:frame.epoch});await receipt;
 }
 assert.equal(service.status.phase,'error');assert.ok(worker.terminated);assert.match(service.status.message,/nie kończy obliczeń/);
});
