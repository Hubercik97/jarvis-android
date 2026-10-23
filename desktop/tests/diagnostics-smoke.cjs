// Real Electron, IPC and browser microphone; external STT/AI/TTS are local fixtures.
const {_electron}=require('playwright'),assert=require('node:assert/strict'),http=require('node:http'),fs=require('node:fs/promises'),path=require('node:path'),os=require('node:os');
const {randomUUID}=require('node:crypto');
(async()=>{
 const token='diagnostic-'+randomUUID(),conversation=randomUUID(),counts={stt:0,chat:0};let mode='ok',app,stderr='';
 const server=http.createServer(async(req,res)=>{
  res.setHeader('content-type','application/json');if(req.headers['x-jarvis-token']!==token){res.writeHead(401);res.end('{}');return;}
  for await(const _ of req){}let data={};
  if(req.url==='/health'){
   if(mode==='unauthorized'){res.writeHead(401);res.end(JSON.stringify({error:token}));return;}
   if(mode==='invalid'){res.end('null');return;}
   if(mode==='html'){res.setHeader('content-type','text/html');res.end('<html>'+token+'</html>');return;}
   if(mode==='missing-route'){res.writeHead(404);res.end('{}');return;}
   if(mode==='missing-token'){res.end(JSON.stringify({auth:'missing',configured:true}));return;}
   if(mode==='no-storage'){res.end(JSON.stringify({auth:'ok',configured:true,capabilities:{history:false}}));return;}
   data={auth:'ok',configured:true,provider:'fixture',model:'local-test'};
  }else if(req.url==='/tasks')data={tasks:[]};
  else if(req.url==='/stt'){counts.stt++;data={text:'Ile jest dwa plus dwa?'};}
  else if(req.url==='/chat'){counts.chat++;data={reply:'Dwa plus dwa to cztery.'};}
  else if(req.url==='/conversations')data={id:conversation};
  else if(req.url.endsWith('/messages'))data={ok:true};
  else{res.writeHead(404);res.end('{}');return;}res.end(JSON.stringify(data));
 });
 await new Promise(r=>server.listen(0,'127.0.0.1',r));const endpoint=`http://127.0.0.1:${server.address().port}`;
 const profile=await fs.mkdtemp(path.join(os.tmpdir(),'jarvis-diagnostics-'));
 const args=[path.resolve(__dirname,'..'),`--user-data-dir=${profile}`,'--use-fake-ui-for-media-stream','--use-fake-device-for-media-stream',`--use-file-for-fake-audio-capture=${path.resolve(__dirname,'../resources/wake/fixtures/darkman-1-1-3.wav')}`,...(process.platform==='linux'?['--no-sandbox','--use-gl=angle','--use-angle=swiftshader','--enable-unsafe-swiftshader']:[])];
 try{
  app=await _electron.launch({args,timeout:45000});app.process().stderr.on('data',b=>stderr+=b.toString());
  const page=await app.firstWindow();await page.waitForSelector('h1');const errors=[];page.on('pageerror',e=>errors.push(e.message));
  async function waitForCloudState(expected){
   for(let i=0;i<100;i++){const state=await page.evaluate(async()=>(await window.jarvis.diagnostics()).cloud.state);if(state===expected)return;await page.waitForTimeout(50);}
   assert.equal((await page.evaluate(()=>window.jarvis.diagnostics())).cloud.state,expected);
  }
  await page.getByRole('button',{name:'Chrome ↗',exact:true}).click();await page.waitForSelector('.control-settings');
  assert.ok((await page.locator('.status').innerText()).includes('Włącz sterowanie lokalne'));assert.ok(!(await page.locator('.status').innerText()).includes('invoking'));
  await assert.rejects(page.evaluate(()=>window.jarvis.control('open_documents')),error=>error.message.includes('Sterowanie lokalne')&&!error.message.includes('invoking'),'IPC errors must explain the permission without the Electron wrapper');
  await page.getByLabel('Włącz sterowanie lokalne',{exact:true}).check();
  await page.waitForFunction(async()=>(await window.jarvis.settings()).localControl);
  assert.equal(JSON.parse(await fs.readFile(path.join(profile,'settings.json'),'utf8')).localControl,true,'consent saves without an API address');
  await assert.rejects(page.evaluate(()=>window.jarvis.control('open_documents')),'cloud authentication remains required');
  await assert.rejects(page.evaluate(()=>window.jarvis.localControl('yes')),'boolean consent is required');
  await page.getByLabel('Włącz sterowanie lokalne',{exact:true}).uncheck();await page.waitForFunction(async()=>!(await window.jarvis.settings()).localControl);
  await page.getByLabel('Adres API',{exact:true}).fill(endpoint);await page.getByLabel('Token aplikacji',{exact:true}).fill(token);await page.getByRole('button',{name:'Zapisz i sprawdź połączenie'}).click();
  await page.waitForFunction(()=>document.querySelector('.status').textContent.includes('Połączono'));assert.equal(await page.getByLabel('Token aplikacji',{exact:true}).inputValue(),'');
  await assert.rejects(page.evaluate(async()=>{const s=await window.jarvis.settings();await window.jarvis.configure({...s,token:'token-z-ą-1234567890'});}),/ASCII/);
  await page.evaluate(async()=>{const s=await window.jarvis.settings();await window.jarvis.configure({...s,localControl:true});});
  assert.equal((await page.evaluate(()=>window.jarvis.settings())).localControl,false,'unrelated cloud configuration cannot grant local access');
  await page.getByLabel('Włącz sterowanie lokalne',{exact:true}).check();await page.waitForFunction(async()=>(await window.jarvis.settings()).localControl);
  await app.evaluate(({dialog,shell})=>{globalThis.__opened=0;dialog.showMessageBox=()=>new Promise(resolve=>{globalThis.__approve=resolve;});shell.openPath=async()=>{globalThis.__opened++;return '';};});
  await page.evaluate(()=>{window.__pendingControl=window.jarvis.control('open_documents').catch(e=>e.message);});
  for(let i=0;i<30;i++){if(await app.evaluate(()=>typeof globalThis.__approve==='function'))break;await page.waitForTimeout(50);}
  await page.getByLabel('Włącz sterowanie lokalne',{exact:true}).uncheck();await page.waitForFunction(async()=>!(await window.jarvis.settings()).localControl);
  await app.evaluate(()=>globalThis.__approve({response:1}));assert.match(await page.evaluate(()=>window.__pendingControl),/cofnięta/);assert.equal(await app.evaluate(()=>globalThis.__opened),0);
  mode='unauthorized';await page.getByRole('button',{name:'Testuj połączenie API',exact:true}).click();await waitForCloudState('unauthorized');
  mode='invalid';await page.getByRole('button',{name:'Testuj połączenie API',exact:true}).click();await waitForCloudState('invalid_response');
  for(const [failure,state] of [['html','endpoint_not_api'],['missing-route','endpoint_not_api'],['missing-token','server_token_missing'],['no-storage','storage_not_ready']]){
   mode=failure;await page.getByRole('button',{name:'Testuj połączenie API',exact:true}).click();await waitForCloudState(state);
   assert.ok(!(await page.locator('body').innerText()).includes(token),'no token from an invalid Worker can enter the UI');
  }
  mode='ok';await page.getByRole('button',{name:'Testuj połączenie API',exact:true}).click();await waitForCloudState('authenticated');
  await page.evaluate(()=>{
   const original=navigator.mediaDevices.getUserMedia.bind(navigator.mediaDevices);window.__streams=[];window.__denyMic=true;
   navigator.mediaDevices.getUserMedia=async options=>{if(window.__denyMic){window.__denyMic=false;throw new DOMException('test-only','NotAllowedError');}const s=await original(options);window.__streams.push(s);return s;};
  });
  await page.getByRole('button',{name:'Testuj mikrofon lokalnie',exact:true}).click();await page.waitForFunction(()=>document.querySelector('.diagnostics').textContent.includes('Brak zgody na mikrofon'));
  await page.getByRole('button',{name:'Testuj mikrofon lokalnie',exact:true}).click();await page.waitForFunction(()=>document.querySelector('.diagnostics').textContent.includes('Test zakończony'),{},{timeout:10000});
  assert.ok(Number(await page.getByLabel('Poziom mikrofonu',{exact:true}).getAttribute('value'))>=0);
  assert.deepEqual(counts,{stt:0,chat:0},'local microphone check must not call cloud APIs');
  assert.equal(await page.evaluate(()=>window.__streams.every(s=>s.getTracks().every(t=>t.readyState==='ended'))),true);
  await page.getByRole('button',{name:'Pokaż raport bez sekretów',exact:true}).click();const report=await page.getByLabel('Raport diagnostyczny',{exact:true}).innerText();assert.ok(!report.includes(token),'report must not contain credentials');assert.ok(!report.includes(endpoint),'report excludes API addresses');assert.ok(JSON.parse(report).microphone.peak>.001,'local diagnostic must measure the real fake-device waveform');
  await page.getByRole('button',{name:'Rozmowa głosowa bez hasła',exact:true}).click();await page.waitForSelector('.composer .recording');await page.waitForTimeout(500);await page.getByRole('button',{name:'Mikrofon',exact:true}).click();
  await page.waitForFunction(()=>document.querySelector('.composer input').value.includes('dwa plus dwa'));
  await page.getByRole('button',{name:'Wyślij',exact:false}).click();await page.waitForFunction(()=>document.querySelector('.message.assistant')?.textContent.includes('cztery'));
  assert.deepEqual(counts,{stt:1,chat:1});assert.equal((await page.evaluate(()=>window.jarvis.diagnostics())).wake.received,0,'manual voice never starts the wake engine');
  await page.getByRole('button',{name:'Przerwij głos',exact:true}).click();await page.getByRole('button',{name:'Ustawienia',exact:false}).click();await page.getByLabel('Włącz sterowanie lokalne',{exact:true}).check();await page.waitForFunction(async()=>(await window.jarvis.settings()).localControl);
  await fs.mkdir(path.resolve(__dirname,'../release'),{recursive:true});await page.screenshot({path:path.resolve(__dirname,'../release/jarvis-diagnostics.png')});
  assert.deepEqual(errors,[]);assert.ok(!stderr.includes(token),'Electron logs must never contain the token');assert.ok(!(await fs.readFile(path.join(profile,'settings.json'),'utf8')).includes(token));
  await app.close();app=await _electron.launch({args,timeout:45000});const restarted=await app.firstWindow();await restarted.waitForSelector('h1');assert.equal((await restarted.evaluate(()=>window.jarvis.settings())).localControl,true);
  console.log('PASS: independent explicit local-control consent persists; cloud authentication and per-action consent remain required; revocation blocks pending action; API/microphone diagnostics are redacted; manual voice works without wake-word');
 }finally{if(app)await app.close();await new Promise(r=>server.close(r));await fs.rm(profile,{recursive:true,force:true,maxRetries:5,retryDelay:200});}
})().catch(e=>{console.error(e);process.exitCode=1;});
