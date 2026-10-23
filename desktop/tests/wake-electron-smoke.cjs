// Actual Electron + AudioWorklet + native ONNX + Polish WAV. Only command STT,
// cloud AI and TTS are local HTTP fixtures; never use paid APIs in this test.
const {_electron}=require('playwright');const assert=require('node:assert/strict');
const http=require('node:http');const fs=require('node:fs/promises');const path=require('node:path');const os=require('node:os');const {randomUUID}=require('node:crypto');
function tone(){const n=16000,buffer=Buffer.alloc(44+n*2);buffer.write('RIFF');buffer.writeUInt32LE(buffer.length-8,4);buffer.write('WAVEfmt ',8);buffer.writeUInt32LE(16,16);buffer.writeUInt16LE(1,20);buffer.writeUInt16LE(1,22);buffer.writeUInt32LE(16000,24);buffer.writeUInt32LE(32000,28);buffer.writeUInt16LE(2,32);buffer.writeUInt16LE(16,34);buffer.write('data',36);buffer.writeUInt32LE(n*2,40);for(let i=0;i<n;i++)buffer.writeInt16LE(Math.round(Math.sin(i*2*Math.PI*180/16000)*8000),44+i*2);return buffer;}
(async()=>{
 const token='wake-smoke-'+randomUUID(),conversationId=randomUUID(),tts=[],calls={stt:0,chat:0};
 const server=http.createServer(async(req,res)=>{
  res.setHeader('content-type','application/json');if(req.headers['x-jarvis-token']!==token){res.writeHead(401);res.end('{}');return;}
  let raw='';for await(const chunk of req)raw+=chunk;const body=raw?JSON.parse(raw):{};let data={};
  if(req.url==='/health')data={auth:'ok',configured:true,provider:'local-test',model:'fixture',background:true};
  else if(req.url==='/tasks')data={tasks:[]};
  else if(req.url==='/tts'){tts.push(body.text);data={audio:tone().toString('base64'),format:'wav'};}
  else if(req.url==='/stt'){calls.stt++;assert.equal(body.mime,'audio/webm');assert.ok(Buffer.from(body.audio,'base64').length>200);data={text:'Przygotuj plan projektu'};}
  else if(req.url==='/chat'){calls.chat++;assert.equal(body.messages.at(-1).content,'Przygotuj plan projektu');data={reply:'Plan testowy jest gotowy.'};}
  else if(req.url==='/conversations')data={id:conversationId};
  else if(req.url.includes('/messages'))data={ok:true};
  else{res.writeHead(404);res.end('{}');return;}res.end(JSON.stringify(data));
 });
 await new Promise(r=>server.listen(0,'127.0.0.1',r));
 const profile=await fs.mkdtemp(path.join(os.tmpdir(),'jarvis-wake-smoke-'));let app;
 try{
  const fixture=path.resolve(__dirname,'../resources/wake/fixtures/darkman-1-1-3.wav');await fs.access(fixture);
  app=await _electron.launch({args:[path.resolve(__dirname,'..'),`--user-data-dir=${profile}`,'--use-fake-ui-for-media-stream','--use-fake-device-for-media-stream',`--use-file-for-fake-audio-capture=${fixture}`,...(process.platform==='linux'?['--no-sandbox','--use-gl=angle','--use-angle=swiftshader','--enable-unsafe-swiftshader']:[])],timeout:45000});
  const page=await app.firstWindow();const errors=[];page.on('pageerror',e=>errors.push(e.message));
  await page.waitForSelector('h1');
  await page.evaluate(()=>{
   window.__wake=[];window.__streams=[];window.jarvis.onWake(s=>window.__wake.push({...s,at:Date.now()}));
   const original=navigator.mediaDevices.getUserMedia.bind(navigator.mediaDevices);
   navigator.mediaDevices.getUserMedia=async options=>{const stream=await original(options);window.__streams.push(stream);return stream;};
  });
  await page.getByRole('button',{name:'Ustawienia',exact:false}).click();
  await page.getByLabel('Adres API',{exact:true}).fill(`http://127.0.0.1:${server.address().port}`);await page.getByLabel('Token aplikacji',{exact:true}).fill(token);
  await page.locator('select:has(option[value="google"])').selectOption('google');await page.getByRole('button',{name:'Zapisz i sprawdź połączenie'}).click();
  await page.waitForFunction(()=>document.querySelector('.status').textContent.includes('Połączono'));
  await page.getByLabel('Włącz wybudzanie głosowe',{exact:true}).check();await page.getByLabel('Uruchamiaj nasłuchiwanie wraz z Jarvisem',{exact:true}).check();await page.getByRole('button',{name:'Zapisz ustawienia wybudzania',exact:true}).click();
  await page.waitForFunction(()=>window.__wake.some(s=>s.phase==='armed')||window.__wake.some(s=>s.phase==='error'),{},{timeout:30000,polling:100});
  let events=await page.evaluate(()=>window.__wake);assert.ok(!events.some(s=>s.phase==='error'),JSON.stringify(events));
  assert.equal(calls.stt,0);assert.equal(calls.chat,0);
  await app.evaluate(({BrowserWindow})=>{const w=BrowserWindow.getAllWindows()[0];w.minimize();if(process.platform==='linux')w.hide();});
  await page.waitForFunction(()=>window.__wake.some(s=>s.phase==='detected'),{},{timeout:20000,polling:100});
  await page.waitForFunction(()=>window.__wake.filter(s=>s.phase==='armed').length>=2||window.__wake.some(s=>s.phase==='error'),{},{timeout:45000,polling:100});
  events=await page.evaluate(()=>window.__wake);assert.ok(!events.some(s=>s.phase==='error'),JSON.stringify(events));
  assert.equal(calls.stt,1,JSON.stringify(events));assert.equal(calls.chat,1,JSON.stringify(events));assert.deepEqual(tts,['Jestem, słucham cię','Plan testowy jest gotowy.']);
  assert.equal(events.filter(s=>s.phase==='detected').length,1,'no feedback activation during acknowledgement/reply');
  assert.equal(await app.evaluate(({BrowserWindow})=>{const w=BrowserWindow.getAllWindows()[0];return w.isMinimized()||!w.isVisible();}),true);
  // Simulate the browser's device-loss event on the actual capture track.
  await page.evaluate(()=>{const track=window.__streams.at(-1).getAudioTracks()[0];track.dispatchEvent(new Event('ended'));});
  await page.waitForFunction(()=>document.querySelector('.wake-bar').classList.contains('phase-error'),{},{polling:100});
  assert.equal(calls.chat,1,'microphone loss cannot execute another command');
  await page.evaluate(()=>window.jarvis.wakeControl('stop'));
  await page.waitForFunction(()=>window.__streams.every(s=>s.getTracks().every(t=>t.readyState==='ended')),{},{polling:100});
  assert.equal(await page.evaluate(()=>document.querySelector('.wake-bar').classList.contains('phase-off')),true);
  assert.deepEqual(errors,[]);
  const workingSet=await app.evaluate(({app})=>Math.round(app.getAppMetrics().reduce((sum,p)=>sum+p.memory.workingSetSize,0)/1024));
  await app.evaluate(({BrowserWindow})=>{const w=BrowserWindow.getAllWindows()[0];w.restore();w.show();});
  await page.screenshot({path:path.resolve(__dirname,'../release/jarvis-wake-complete.png')});
  console.log('PASS: Polish WAV -> real AudioWorklet -> native ONNX -> acknowledgement -> command STT/AI/TTS fixture -> local rearm while minimized; stop releases every track');
  console.log(JSON.stringify({events:events.map(s=>({phase:s.phase,at:s.at})),calls,tts,workingSetMB:workingSet},null,2));
  await app.close();app=null;
  app=await _electron.launch({args:[path.resolve(__dirname,'..'),`--user-data-dir=${profile}`,'--hidden','--use-fake-ui-for-media-stream','--use-fake-device-for-media-stream',...(process.platform==='linux'?['--no-sandbox','--use-gl=angle','--use-angle=swiftshader','--enable-unsafe-swiftshader']:[])],timeout:45000});
  const restarted=await app.firstWindow();await restarted.waitForSelector('.wake-bar.phase-armed',{state:'attached',timeout:30000});
  assert.equal(await app.evaluate(({BrowserWindow})=>BrowserWindow.getAllWindows()[0].isVisible()),false);
  assert.equal((await restarted.evaluate(()=>window.jarvis.settings())).hasToken,false);
  await restarted.evaluate(()=>window.jarvis.wakeControl('stop'));
  console.log('PASS: saved opt-in autostart starts local microphone with hidden window after restart, without a cloud token');
 }finally{if(app)await app.close();await new Promise(r=>server.close(r));await fs.rm(profile,{recursive:true,force:true,maxRetries:5,retryDelay:200});}
})().catch(e=>{console.error(e);process.exitCode=1;});
