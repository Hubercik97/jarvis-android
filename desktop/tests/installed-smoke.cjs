// Windows CI: execute the app that NSIS actually installed, without cloud credentials.
const {_electron}=require('playwright');const assert=require('node:assert/strict');const fs=require('node:fs/promises');const os=require('node:os');const path=require('node:path');
(async()=>{
 const executable=process.env.JARVIS_INSTALLED_EXE;assert.ok(executable,'JARVIS_INSTALLED_EXE is required');
 const profile=await fs.mkdtemp(path.join(os.tmpdir(),'jarvis-installed-smoke-'));let app;
 try{
  app=await _electron.launch({executablePath:executable,args:[`--user-data-dir=${profile}`],timeout:45000});
  const page=await app.firstWindow();await page.waitForSelector('h1');
  assert.equal(await app.evaluate(({app})=>app.isPackaged),true);
  assert.equal(await page.evaluate(()=>typeof require),'undefined');
  const settings=await page.evaluate(()=>window.jarvis.settings());assert.equal(settings.hasToken,false);
  await assert.rejects(page.evaluate(async()=>{const s=await window.jarvis.settings();return window.jarvis.configure({...s,endpoint:'http://127.0.0.1:8787',token:'local-fixture-token'});}));
  await page.waitForFunction(()=>Number(document.querySelector('.scene-canvas')?.dataset.fps)>0);
  const composer=await page.locator('.composer').boundingBox();assert.ok(composer.y+composer.height<=await page.evaluate(()=>innerHeight));
  await page.screenshot({path:path.resolve(__dirname,'../release/jarvis-installed.png')});
  console.log('PASS: NSIS-installed application launches, loads packaged preload/assets, isolates renderer and enforces HTTPS');
 }finally{if(app)await app.close();await fs.rm(profile,{recursive:true,force:true,maxRetries:5,retryDelay:200});}
})().catch(error=>{console.error(error);process.exitCode=1;});
