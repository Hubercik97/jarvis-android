// Reproduce a blocked Electron main loop while the native worker can finish.
// Uses a negative synthetic phrase; no cloud, recording or provider credentials.
const {_electron}=require('playwright'),assert=require('node:assert/strict'),fs=require('node:fs/promises'),os=require('node:os'),path=require('node:path');
(async()=>{
 const profile=await fs.mkdtemp(path.join(os.tmpdir(),'jarvis-wake-stall-'));let app;
 try{
  app=await _electron.launch({args:[path.resolve(__dirname,'..'),`--user-data-dir=${profile}`,'--use-fake-ui-for-media-stream','--use-fake-device-for-media-stream',`--use-file-for-fake-audio-capture=${path.resolve(__dirname,'../resources/wake/fixtures/darkman-0-0-3.wav')}`,...(process.platform==='linux'?['--no-sandbox','--use-gl=angle','--use-angle=swiftshader','--enable-unsafe-swiftshader']:[])],timeout:45000});
  const page=await app.firstWindow();await page.waitForSelector('h1');
  async function waitForDiagnostics(predicate,timeout=45000,interval=40){
   const deadline=Date.now()+timeout;let last;
   do{last=await page.evaluate(()=>window.jarvis.diagnostics());if(predicate(last))return last;await page.waitForTimeout(interval);}while(Date.now()<deadline);
   throw new Error('Diagnostic condition timed out: '+JSON.stringify(last.wake));
  }
  await page.evaluate(async()=>{window.__wake=[];window.jarvis.onWake(s=>window.__wake.push(s.phase));const settings=await window.jarvis.settings();await window.jarvis.wakeSettings({...settings.wake,enabled:true,confirmation:false});});
  await waitForDiagnostics(d=>d.wake.processed>=5);
  const before=await page.evaluate(()=>window.jarvis.diagnostics());
  // Block immediately AFTER the next real PCM packet is dispatched. Polling a
  // ~2 ms inference window is unreliable with Windows timer granularity.
  await app.evaluate(()=>new Promise((resolve,reject)=>{
   const {Worker}=process.getBuiltinModule('worker_threads'),original=Worker.prototype.postMessage;
   const timeout=setTimeout(()=>{Worker.prototype.postMessage=original;reject(new Error('No PCM dispatch to stall'));},15000);
   Worker.prototype.postMessage=function(message,...args){
    const result=original.call(this,message,...args);
    if(message?.type==='pcm'){
     Worker.prototype.postMessage=original;clearTimeout(timeout);
     const until=Date.now()+9000;while(Date.now()<until){/* simulated CPU stall */}resolve();
    }
    return result;
   };
  }));
  await waitForDiagnostics(d=>d.wake.processed>before.wake.processed||d.wake.phase==='error',15000);
  const after=await page.evaluate(()=>window.jarvis.diagnostics());
  assert.equal(after.wake.phase,'armed',JSON.stringify(after.wake));assert.equal(after.wake.lastFailure,null);
  assert.ok(after.wake.processed>before.wake.processed);assert.equal(await page.evaluate(()=>window.__wake.includes('detected')),false);
  await page.evaluate(()=>window.jarvis.wakeControl('stop'));assert.equal((await page.evaluate(()=>window.jarvis.diagnostics())).wake.phase,'off');
  console.log('PASS: nine-second main-loop stall drains queued worker response without a false inference timeout; native processing recovers, no activation or cloud calls');
 }finally{if(app)await app.close();await fs.rm(profile,{recursive:true,force:true,maxRetries:5,retryDelay:200});}
})().catch(async e=>{if(process.env.GITHUB_OUTPUT)await fs.appendFile(process.env.GITHUB_OUTPUT,'stall_failure=Wake stall: '+String(e.message).replace(/[\r\n]/g,' ').slice(0,1200)+'\n');console.error(e);process.exitCode=1;});
