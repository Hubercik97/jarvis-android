// Windows CI: execute the app that NSIS actually installed, without cloud credentials.
// Fake capture is used only by this test; production never substitutes the microphone.
const {_electron}=require('playwright');const assert=require('node:assert/strict');const fs=require('node:fs/promises');const os=require('node:os');const path=require('node:path');
(async()=>{
 const executable=process.env.JARVIS_INSTALLED_EXE;assert.ok(executable,'JARVIS_INSTALLED_EXE is required');
 const profile=await fs.mkdtemp(path.join(os.tmpdir(),'jarvis-installed-smoke-'));let app;
 try{
  app=await _electron.launch({executablePath:executable,args:[`--user-data-dir=${profile}`,'--use-fake-ui-for-media-stream','--use-fake-device-for-media-stream',...(process.platform==='linux'?['--no-sandbox']:[])],timeout:45000});
  const page=await app.firstWindow();await page.waitForSelector('h1');
  assert.equal(await app.evaluate(({app})=>app.isPackaged),true);
  assert.equal(await page.evaluate(()=>typeof require),'undefined');
  const settings=await page.evaluate(()=>window.jarvis.settings());assert.equal(settings.hasToken,false);
  assert.equal(settings.wake.enabled,false,'A fresh installation must not open the wake microphone automatically');
  await assert.rejects(page.evaluate(async()=>{const s=await window.jarvis.settings();return window.jarvis.configure({...s,endpoint:'http://127.0.0.1:8787',token:'local-fixture-token'});}));
  await page.waitForFunction(()=>Number(document.querySelector('.scene-canvas')?.dataset.fps)>0);
  const composer=await page.locator('.composer').boundingBox();assert.ok(composer.y+composer.height<=await page.evaluate(()=>innerHeight));
  // These must be real filesystem resources, available without a first-run download.
  const resources=path.join(path.dirname(executable),'resources');
  for(const name of ['melspectrogram.onnx','embedding_model.onnx','pobudka-jarvis.onnx']){
   assert.ok((await fs.stat(path.join(resources,'wake',name))).size>1000,`${name} is missing from the installed application`);
  }
  const native=path.join(resources,'app.asar.unpacked','node_modules','onnxruntime-node','bin','napi-v6',process.platform,process.arch,'onnxruntime_binding.node');
  assert.ok((await fs.stat(native)).size>1000,'The native ONNX binding must be unpacked from ASAR');
  await page.evaluate(()=>{window.__installedWakeEvents=[];window.__installedWakeUnsubscribe=window.jarvis.onWake(status=>window.__installedWakeEvents.push(status));});
  await page.evaluate(async()=>{const s=await window.jarvis.settings();await window.jarvis.wakeSettings({...s.wake,enabled:true,autoStart:false,confirmation:false});});
  await page.waitForFunction(()=>window.__installedWakeEvents.some(s=>s.phase==='armed'||s.phase==='error'),null,{timeout:30000});
  let events=await page.evaluate(()=>window.__installedWakeEvents);
  assert.equal(events.at(-1).phase,'armed',`Packaged wake engine or microphone failed: ${JSON.stringify(events)}`);
  assert.ok(events.some(s=>s.phase==='starting'),'The actual packaged model must finish loading before capture starts');
  const throttled=()=>app.evaluate(({BrowserWindow})=>BrowserWindow.getAllWindows()[0].webContents.getBackgroundThrottling());
  assert.equal(await throttled(),false,'Wake processing must continue in the background');
  await page.evaluate(()=>window.jarvis.minimize());
  assert.equal(await app.evaluate(({BrowserWindow})=>BrowserWindow.getAllWindows()[0].isVisible()),false);
  // Let the installed native worker process multiple real AudioWorklet PCM frames.
  await page.waitForTimeout(650);
  events=await page.evaluate(()=>window.__installedWakeEvents);
  assert.ok(!events.some(s=>s.phase==='error'),`Background inference failed: ${JSON.stringify(events)}`);
  assert.equal(events.at(-1).phase,'armed','The fake microphone must not accidentally wake the assistant');
  assert.equal((await page.evaluate(()=>window.jarvis.wakeControl('stop'))).phase,'off');
  assert.equal(await throttled(),true,'Stopping wake capture must restore background throttling');
  await app.evaluate(({BrowserWindow})=>BrowserWindow.getAllWindows()[0].show());
  await page.evaluate(()=>window.__installedWakeUnsubscribe());
  await page.screenshot({path:path.resolve(__dirname,'../release/jarvis-installed.png')});
  console.log('PASS: installed application launches, enforces HTTPS and renderer isolation, loads offline ONNX models/native binding, captures PCM while minimized, and stops wake capture');
 }catch(error){
  // This test profile has no credentials. Expose the bounded assertion through
  // CI metadata as well as logs when an artifact host is inaccessible.
  if(process.env.GITHUB_OUTPUT)await fs.appendFile(process.env.GITHUB_OUTPUT,'installed_failure=Installed smoke: '+String(error.message).replace(/[\r\n]/g,' ').slice(0,1200)+'\n');
  throw error;
 }finally{if(app)await app.close();await fs.rm(profile,{recursive:true,force:true,maxRetries:5,retryDelay:200});}
})().catch(error=>{console.error(error);process.exitCode=1;});
