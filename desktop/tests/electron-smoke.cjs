// Real Electron window and main/preload IPC; model/network responses are local fixtures.
const {_electron}=require('playwright');const assert=require('node:assert/strict');const http=require('node:http');const fs=require('node:fs/promises');const os=require('node:os');const path=require('node:path');const {randomUUID}=require('node:crypto');
(async()=>{
 const samples=48000*4,wav=Buffer.alloc(44+samples*2);wav.write('RIFF');wav.writeUInt32LE(wav.length-8,4);wav.write('WAVEfmt ',8);wav.writeUInt32LE(16,16);wav.writeUInt16LE(1,20);wav.writeUInt16LE(1,22);wav.writeUInt32LE(48000,24);wav.writeUInt32LE(96000,28);wav.writeUInt16LE(2,32);wav.writeUInt16LE(16,34);wav.write('data',36);wav.writeUInt32LE(samples*2,40);for(let i=0;i<samples;i++)wav.writeInt16LE(Math.round(Math.sin(i*2*Math.PI*440/48000)*10000),44+i*2);
 const fixtureToken='local-smoke-'+randomUUID();const conversations=[];const messages=new Map();let modelCalls=0;
 const server=http.createServer(async(req,res)=>{
  res.setHeader('content-type','application/json');
  if(req.headers['x-jarvis-token']!==fixtureToken){res.writeHead(401);res.end('{}');return;}
  let raw='';for await(const chunk of req)raw+=chunk;const body=raw?JSON.parse(raw):null;
  let data={};let status=200;
  if(req.url==='/health')data={ok:true,auth:'ok',configured:true,provider:'fixture',model:'test-only',background:true};
  else if(req.url==='/chat'){modelCalls++;assert.match(body.messages.at(-1).content,/plan projektu/);data={reply:'Plan testowy: audyt, implementacja, testy.'};}
  else if(req.url==='/tts')data={audio:wav.toString('base64'),format:'wav',voice:'fixture-waveform'};
  else if(req.url==='/conversations'&&req.method==='POST'){data={id:randomUUID(),title:body.title};conversations.push(data);messages.set(data.id,[]);status=201;}
  else if(req.url==='/conversations')data={conversations};
  else if(/^\/conversations\/.+\/messages$/.test(req.url)){const id=req.url.split('/')[2];if(req.method==='POST'){messages.get(id).push(body);data={ok:true};}else data={messages:messages.get(id)};}
  else if(req.url==='/memory')data={facts:[]};else if(req.url==='/tasks')data={tasks:[]};
  else if(req.url.startsWith('/weather'))data={temperature:14,source:'fixture'};
  else{status=404;data={error:'Unknown fixture route'};}
  res.writeHead(status);res.end(JSON.stringify(data));
 });
 await new Promise(r=>server.listen(0,'127.0.0.1',r));const endpoint=`http://127.0.0.1:${server.address().port}`;
 const profile=await fs.mkdtemp(path.join(os.tmpdir(),'jarvis-electron-smoke-'));
 let application;
 try{
  application=await _electron.launch({args:[path.resolve(__dirname,'..'),`--user-data-dir=${profile}`,...(process.platform==='linux'?['--no-sandbox']:[])],timeout:45000});
  const page=await application.firstWindow();await page.waitForSelector('h1');
  const errors=[];page.on('pageerror',e=>errors.push(e.message));
  assert.equal(await page.evaluate(()=>typeof require),'undefined');assert.equal(await page.evaluate(()=>typeof process),'undefined');
  const preferences=await application.evaluate(({BrowserWindow})=>{const p=BrowserWindow.getAllWindows()[0].webContents.getLastWebPreferences();return {nodeIntegration:p.nodeIntegration,contextIsolation:p.contextIsolation,sandbox:p.sandbox};});
  assert.deepEqual(preferences,{nodeIntegration:false,contextIsolation:true,sandbox:true});
  await page.getByRole('button',{name:'Ustawienia',exact:false}).click();
  await page.getByLabel('Adres API',{exact:true}).fill(endpoint);await page.getByLabel('Token aplikacji',{exact:true}).fill(fixtureToken);
  await page.getByRole('button',{name:'Zapisz i sprawdź połączenie'}).click();
  await page.waitForFunction(()=>document.querySelector('[role=status]').textContent.includes('Połączono'));
  assert.equal(await page.getByLabel('Token aplikacji',{exact:true}).inputValue(),'');
  const snapshot=await page.evaluate(()=>window.jarvis.settings());assert.equal(snapshot.hasToken,true);assert.equal(snapshot.token,undefined);
  const config=await fs.readFile(path.join(profile,'settings.json'),'utf8');assert.ok(!config.includes(fixtureToken));
  await assert.rejects(page.evaluate(()=>window.jarvis.api('//evil.example')));
  await assert.rejects(page.evaluate(()=>window.jarvis.control('delete_files')));
  await assert.rejects(page.evaluate(()=>window.jarvis.control('open_documents')));
  await page.getByRole('button',{name:'Centrum dowodzenia',exact:false}).click();
  await page.getByRole('button',{name:'Pogoda · odśwież',exact:false}).click();
  await page.waitForFunction(()=>document.querySelector('.weather').textContent.includes('14°C'));
  await page.getByLabel('Wiadomość',{exact:true}).fill('Przygotuj plan projektu');await page.getByRole('button',{name:'Wyślij',exact:false}).click();
  await page.waitForFunction(()=>document.querySelector('.message.assistant')?.textContent.includes('Plan testowy'));
  assert.equal(modelCalls,1);assert.equal(messages.get(conversations[0].id).length,2);
  await page.getByRole('button',{name:'Pamięć',exact:false}).click();await page.waitForSelector('.card');
  await page.getByRole('button',{name:'Otwórz rozmowę'}).click();await page.waitForSelector('.message.assistant');
  await page.getByRole('button',{name:'Ustawienia',exact:false}).click();
  await page.locator('select:has(option[value="google"])').selectOption('google');
  await page.getByRole('button',{name:'Zapisz i sprawdź połączenie'}).click();
  await page.waitForFunction(()=>document.querySelector('[role=status]').textContent.includes('Połączono'));
  await page.getByRole('button',{name:'Centrum dowodzenia',exact:false}).click();
  await page.getByRole('button',{name:'Odsłuchaj',exact:true}).click();
  await page.waitForFunction(()=>document.querySelector('.scene-canvas')?.dataset.audioSource==='tts'&&Number(document.querySelector('.scene-canvas').dataset.audioLevel)>.1);
  await page.getByRole('button',{name:'Przerwij głos',exact:true}).click();
  await page.waitForFunction(()=>document.querySelector('.scene-canvas')?.dataset.audioSource==='none'&&Number(document.querySelector('.scene-canvas').dataset.audioLevel)<.01);
  console.log('PASS: real media-element TTS waveform drives the orb; stop clears the signal');
  // Confirm-deny path exercises the native-agent boundary without touching the OS.
  await page.evaluate(()=>window.jarvis.localControl(true));
  await application.evaluate(({dialog})=>{dialog.showMessageBox=async()=>({response:0,checkboxChecked:false});});
  const cancelled=await page.evaluate(()=>window.jarvis.control('open_documents'));assert.equal(cancelled.ok,false);
  await application.evaluate(({dialog,shell})=>{dialog.showMessageBox=async()=>({response:1,checkboxChecked:false});shell.openPath=async()=>'';});
  assert.equal((await page.evaluate(()=>window.jarvis.control('open_documents'))).ok,true);
  await page.getByRole('button',{name:'Minimalizuj do zasobnika'}).click();
  assert.equal(await application.evaluate(({BrowserWindow})=>BrowserWindow.getAllWindows()[0].isVisible()),false);
  await application.evaluate(({BrowserWindow})=>BrowserWindow.getAllWindows()[0].show());
  await application.evaluate(({BrowserWindow})=>BrowserWindow.getAllWindows()[0].setSize(900,680));
  await page.waitForFunction(()=>window.innerWidth<=900);
  const composer=await page.locator('.composer').boundingBox();
  assert.ok(composer.y+composer.height<=await page.evaluate(()=>innerHeight),'composer must remain visible on a small laptop window');
  await application.evaluate(({BrowserWindow})=>BrowserWindow.getAllWindows()[0].setSize(1260,820));
  await page.waitForFunction(()=>window.innerWidth>1000);
  await fs.mkdir(path.resolve(__dirname,'../release'),{recursive:true});
  await page.waitForFunction(()=>Number(document.querySelector('.scene-canvas')?.dataset.fps)>0);
  await page.screenshot({path:path.resolve(__dirname,'../release/jarvis-desktop.png')});
  assert.deepEqual(errors,[]);
  console.log('Measured Electron working sets (MB):',await application.evaluate(({app})=>Math.round(app.getAppMetrics().reduce((n,p)=>n+p.memory.workingSetSize,0)/1024)));
  const canPersist=await application.evaluate(({safeStorage})=>safeStorage.isEncryptionAvailable()&&(process.platform!=='linux'||safeStorage.getSelectedStorageBackend()!=='basic_text'));
  if(canPersist)await page.evaluate(async()=>{const config=await window.jarvis.settings();await window.jarvis.configure({...config,rememberToken:true});});
  const stored=await fs.readFile(path.join(profile,'settings.json'),'utf8');assert.ok(!stored.includes(fixtureToken));
  await application.close();
  application=await _electron.launch({args:[path.resolve(__dirname,'..'),`--user-data-dir=${profile}`,...(process.platform==='linux'?['--no-sandbox']:[])],timeout:45000});
  const restored=await application.firstWindow();await restored.waitForSelector('h1');
  assert.equal((await restored.evaluate(()=>window.jarvis.settings())).hasToken,canPersist);
  console.log(canPersist?'PASS: system-encrypted token restored after process restart':'PASS: session-only token discarded after process restart');
  console.log('PASS: real Electron window, sandbox/isolation, token boundary, cloud chat fixture, history, local permission + confirmation, tray hide/show');
 }finally{if(application)await application.close();await new Promise(r=>server.close(r));await fs.rm(profile,{recursive:true,force:true,maxRetries:5,retryDelay:200});}
})().catch(error=>{console.error(error);process.exitCode=1;});
