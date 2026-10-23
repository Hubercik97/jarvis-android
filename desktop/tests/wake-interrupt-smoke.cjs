// Real Electron audio ownership regression: manual interruption and recorder failure.
// All cloud endpoints are local fixtures. No paid API, user recording or OS action.
const {_electron}=require('playwright');const assert=require('node:assert/strict');
const http=require('node:http'),fs=require('node:fs/promises'),path=require('node:path'),os=require('node:os');
const {randomUUID}=require('node:crypto');
function wave(){const n=16000*3,b=Buffer.alloc(44+n*2);b.write('RIFF');b.writeUInt32LE(b.length-8,4);b.write('WAVEfmt ',8);b.writeUInt32LE(16,16);b.writeUInt16LE(1,20);b.writeUInt16LE(1,22);b.writeUInt32LE(16000,24);b.writeUInt32LE(32000,28);b.writeUInt16LE(2,32);b.writeUInt16LE(16,34);b.write('data',36);b.writeUInt32LE(n*2,40);for(let i=0;i<n;i++)b.writeInt16LE(Math.round(Math.sin(i*2*Math.PI*170/16000)*7000),44+i*2);return b;}
(async()=>{
 const token='wake-interrupt-'+randomUUID(),calls={tts:0,stt:0,chat:0};const audio=wave();
 const server=http.createServer(async(req,res)=>{
  res.setHeader('content-type','application/json');if(req.headers['x-jarvis-token']!==token){res.writeHead(401);res.end('{}');return;}
  for await(const _ of req){}let data={};
  if(req.url==='/health')data={auth:'ok',configured:true,provider:'local-test',model:'fixture'};
  else if(req.url==='/tasks')data={tasks:[]};
  else if(req.url==='/tts'){calls.tts++;data={audio:audio.toString('base64'),format:'wav'};}
  else if(req.url==='/stt'){calls.stt++;data={text:'Nigdy nie powinno być wysłane'};}
  else if(req.url==='/chat'){calls.chat++;data={reply:'unexpected'};}
  else{res.writeHead(404);res.end('{}');return;}res.end(JSON.stringify(data));
 });
 await new Promise(r=>server.listen(0,'127.0.0.1',r));
 const profile=await fs.mkdtemp(path.join(os.tmpdir(),'jarvis-wake-interrupt-'));let application;
 try{
  const fixture=path.resolve(__dirname,'../resources/wake/fixtures/darkman-1-1-3.wav');
  application=await _electron.launch({args:[path.resolve(__dirname,'..'),`--user-data-dir=${profile}`,'--use-fake-ui-for-media-stream','--use-fake-device-for-media-stream',`--use-file-for-fake-audio-capture=${fixture}`,...(process.platform==='linux'?['--no-sandbox','--use-gl=angle','--use-angle=swiftshader','--enable-unsafe-swiftshader']:[])],timeout:45000});
  const page=await application.firstWindow(),errors=[];page.on('pageerror',e=>errors.push(e.message));await page.waitForSelector('h1');
  await page.evaluate(()=>{
   window.__events=[];window.__streams=[];window.__recorders=[];window.__recordingStarts=0;
   window.jarvis.onWake(s=>window.__events.push(s));
   const getUserMedia=navigator.mediaDevices.getUserMedia.bind(navigator.mediaDevices);
   navigator.mediaDevices.getUserMedia=async options=>{const stream=await getUserMedia(options);window.__streams.push(stream);return stream;};
   const RealRecorder=window.MediaRecorder;
   window.MediaRecorder=class extends RealRecorder{constructor(...args){super(...args);window.__recorders.push(this);}start(...args){window.__recordingStarts++;return super.start(...args);}};
  });
  await page.getByRole('button',{name:'Ustawienia',exact:false}).click();await page.getByLabel('Adres API',{exact:true}).fill(`http://127.0.0.1:${server.address().port}`);await page.getByLabel('Token aplikacji',{exact:true}).fill(token);
  await page.locator('select:has(option[value="google"])').selectOption('google');await page.getByRole('button',{name:'Zapisz i sprawdź połączenie'}).click();await page.waitForFunction(()=>document.querySelector('.status').textContent.includes('Połączono'));
  await page.evaluate(async()=>{const p=(await window.jarvis.settings()).wake;await window.jarvis.wakeSettings({...p,enabled:true,confirmation:true});});
  await page.waitForFunction(()=>document.querySelector('.scene-canvas')?.dataset.audioSource==='tts',{},{timeout:20000,polling:50});
  assert.equal(calls.tts,1);
  await page.getByRole('button',{name:'Mikrofon',exact:true}).click();
  await page.waitForFunction(()=>document.querySelector('.composer button[aria-label="Mikrofon"]').classList.contains('recording'),{},{polling:50});
  // Longer than both the acknowledgement tail and rearm delay. The interrupted
  // wake turn must not start a second recorder or rearm during manual capture.
  await page.waitForTimeout(1600);
  assert.equal(await page.evaluate(()=>window.__recordingStarts),1);
  assert.equal(await page.evaluate(()=>window.__events.at(-1).phase),'paused');
  assert.equal(calls.stt,0);assert.equal(calls.chat,0);
  await page.getByRole('button',{name:'Wyłącz nasłuch natychmiast',exact:true}).click();
  await page.waitForFunction(()=>window.__streams.every(s=>s.getTracks().every(t=>t.readyState==='ended')),{},{polling:50});
  assert.equal(calls.stt,0,'cancelled manual capture cannot upload audio');
  // Trigger another real wake, allow acknowledgement to finish, then deliver the
  // same error event emitted by a failing browser MediaRecorder.
  await page.evaluate(()=>window.jarvis.wakeControl('start'));
  try{await page.waitForFunction(()=>window.__recordingStarts===2,{},{timeout:25000,polling:50});}
  catch(error){console.error('Second-turn diagnostics:',JSON.stringify(await page.evaluate(()=>({events:window.__events,starts:window.__recordingStarts,status:document.querySelector('.status').textContent,streams:window.__streams.map(s=>s.getTracks().map(t=>({ready:t.readyState,muted:t.muted})))}))));throw error;}
  await page.evaluate(()=>window.__recorders.at(-1).dispatchEvent(new Event('error')));
  await page.waitForFunction(()=>document.querySelector('.wake-bar').classList.contains('phase-error'),{},{polling:50});
  await page.waitForTimeout(1000);
  assert.equal(calls.stt,0,'error followed by dataavailable/stop cannot transcribe partial audio');assert.equal(calls.chat,0);
  assert.equal(await page.evaluate(()=>window.__streams.every(s=>s.getTracks().every(t=>t.readyState==='ended'))),true);
  assert.deepEqual(errors,[]);
  console.log('PASS: manual microphone interrupts acknowledgement without duplicate capture or rearm; recorder error stops tracks and suppresses later partial-audio upload');
 }finally{if(application)await application.close();await new Promise(r=>server.close(r));await fs.rm(profile,{recursive:true,force:true,maxRetries:5,retryDelay:200});}
})().catch(error=>{console.error(error);process.exitCode=1;});
