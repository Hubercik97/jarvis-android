// Actual Electron, microphone, native wake model AND bundled workerd backend/D1.
// Only external model/transcription/TTS providers return local test responses.
const {_electron}=require('playwright'),assert=require('node:assert/strict'),fs=require('node:fs/promises'),path=require('node:path'),os=require('node:os');
(async()=>{
 const {startLocalWorker}=await import('../../backend/testing/local-worker.mjs');
 const worker=await startLocalWorker(),profile=await fs.mkdtemp(path.join(os.tmpdir(),'jarvis-worker-api-'));let app;
 try{
  app=await _electron.launch({args:[path.resolve(__dirname,'..'),`--user-data-dir=${profile}`,'--use-fake-ui-for-media-stream','--use-fake-device-for-media-stream',`--use-file-for-fake-audio-capture=${path.resolve(__dirname,'../resources/wake/fixtures/darkman-1-1-3.wav')}`,...(process.platform==='linux'?['--no-sandbox','--use-gl=angle','--use-angle=swiftshader','--enable-unsafe-swiftshader']:[])],timeout:45000});
  const page=await app.firstWindow();await page.waitForSelector('h1');
  await page.evaluate(()=>{window.__wake=[];window.jarvis.onWake(s=>window.__wake.push(s.phase));});
  await page.getByRole('button',{name:'Ustawienia',exact:false}).click();
  await page.getByLabel('Adres API',{exact:true}).fill(worker.origin);await page.getByLabel('Token aplikacji',{exact:true}).fill(worker.token);
  await page.locator('select:has(option[value="google"])').selectOption('google');await page.getByRole('button',{name:'Zapisz i sprawdź połączenie'}).click();
  await page.waitForFunction(()=>document.querySelector('.status').textContent.includes('Połączono'));
  assert.equal((await page.evaluate(()=>window.jarvis.diagnostics())).cloud.state,'authenticated');
  await page.getByLabel('Włącz wybudzanie głosowe',{exact:true}).check();await page.getByRole('button',{name:'Zapisz ustawienia wybudzania',exact:true}).click();
  await page.waitForFunction(()=>window.__wake.includes('armed')||window.__wake.includes('error'),null,{timeout:45000});
  assert.deepEqual(worker.calls,{stt:0,chat:0},'standby cannot invoke any paid provider');assert.equal(worker.tts.length,0);
  await app.evaluate(({BrowserWindow})=>{const w=BrowserWindow.getAllWindows()[0];w.minimize();if(process.platform==='linux')w.hide();});
  await page.waitForFunction(()=>window.__wake.filter(s=>s==='armed').length>=2||window.__wake.includes('error'),null,{timeout:60000});
  const events=await page.evaluate(()=>window.__wake);assert.ok(!events.includes('error'),JSON.stringify(events));
  assert.equal(events.filter(s=>s==='detected').length,1);assert.deepEqual(worker.calls,{stt:1,chat:1});
  assert.deepEqual(worker.tts,['Jestem, słucham cię','Plan testowy jest gotowy.']);
  assert.equal(await app.evaluate(({BrowserWindow})=>{const w=BrowserWindow.getAllWindows()[0];return w.isMinimized()||!w.isVisible();}),true);
  const conversations=await worker.db.prepare('SELECT id FROM conversations').all();assert.equal(conversations.results.length,1);
  const messages=await worker.db.prepare('SELECT role,content FROM conversation_messages ORDER BY id').all();
  assert.deepEqual(messages.results,[{role:'user',content:'Przygotuj plan projektu'},{role:'assistant',content:'Plan testowy jest gotowy.'}]);
  assert.equal((await worker.db.prepare('SELECT calls FROM daily_usage').first()).calls,4,'both TTS calls, STT and AI reserve the actual durable budget');
  await page.evaluate(()=>window.jarvis.wakeControl('stop'));assert.equal((await page.evaluate(()=>window.jarvis.diagnostics())).wake.phase,'off');
  assert.ok(!(await fs.readFile(path.join(profile,'settings.json'),'utf8')).includes(worker.token));
  console.log('PASS: Pobudka Jarvis -> real local ONNX -> acknowledgement -> Polish STT -> Worker OpenAI adapter -> persisted D1 conversation -> actual MP3 playback -> local rearm while minimized. No production or paid calls.');
 }finally{if(app)await app.close();await worker.close();await fs.rm(profile,{recursive:true,force:true,maxRetries:5,retryDelay:200});}
})().catch(error=>{console.error(error);process.exitCode=1;});
