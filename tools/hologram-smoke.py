"""Real browser/WebGL + Web Audio checks. No provider APIs or synthetic production data."""
import json
import shutil
import os
from pathlib import Path
from playwright.sync_api import sync_playwright
ROOT=Path(__file__).resolve().parents[1]
ARTIFACTS=ROOT/'desktop'/'release'
ARTIFACTS.mkdir(parents=True,exist_ok=True)
with sync_playwright() as p:
 browser=p.chromium.launch(executable_path=os.environ.get('JARVIS_BROWSER') or shutil.which('chromium') or shutil.which('google-chrome'),headless=True,args=['--no-sandbox','--use-gl=angle','--use-angle=swiftshader','--enable-unsafe-swiftshader','--autoplay-policy=no-user-gesture-required'])
 context=browser.new_context(viewport={'width':1440,'height':960},device_scale_factor=1,locale='pl-PL',timezone_id='Europe/Berlin')
 page=context.new_page();errors=[];page.on('pageerror',lambda e:errors.append(str(e)))
 page.goto('http://127.0.0.1:8000/',wait_until='networkidle')
 page.wait_for_function("Number(document.querySelector('#hologramHost').dataset.fps)>0")
 assert page.locator('#hologramHost').get_attribute('data-renderer')=='WebGL'
 before=page.locator('#hologramHost canvas').screenshot();page.wait_for_timeout(300);after=page.locator('#hologramHost canvas').screenshot();assert before!=after,'3D scene must animate'
 page.locator('#textInput').fill('Powiedz hologram działa');page.locator('#textForm').evaluate('(form)=>form.requestSubmit()')
 page.wait_for_function("document.querySelector('.msg-jarvis')?.textContent.includes('Hologram działa')")
 page.wait_for_function("document.querySelector('#hudRecent').textContent.includes('hologram działa')")
 metrics=page.locator('#hologramHost').evaluate('(e)=>({...e.dataset})')
 page.wait_for_timeout(4500) # Measure adaptive quality over complete frame-rate windows.
 adapted=page.locator('#hologramHost').evaluate('(e)=>({...e.dataset})')
 page.screenshot(path=str(ARTIFACTS/'hologram-web-desktop.png'),full_page=True)
 result=page.evaluate("""async()=>{
  const {AudioSampler,HologramEngine}=await import('/hologram/viewer.js');
  const context=new AudioContext();await context.resume();
  const oscillator=context.createOscillator(),gain=context.createGain();oscillator.frequency.value=220;gain.gain.value=.3;oscillator.connect(gain);
  const meter=new AudioSampler(context,gain,'tts',false),mute=context.createGain();mute.gain.value=0;meter.analyser.connect(mute);mute.connect(context.destination);oscillator.start();
  const host=document.createElement('div');host.style.cssText='position:fixed;left:20px;top:20px;width:300px;height:300px;background:#020913;z-index:999';document.body.append(host);
  const engine=new HologramEngine(host,()=>({state:'SPEAKING',signal:meter.sample(),lowPower:true}));
  const wait=condition=>new Promise((resolve,reject)=>{const start=performance.now();const check=()=>{if(condition())resolve();else if(performance.now()-start>8000)reject(Error('audio condition timeout'));else setTimeout(check,30);};check();});
  await wait(()=>Number(host.dataset.audioLevel)>.3);const audible=meter.sample();
  if(audible.rms<.15||audible.rms>.26)throw Error('Unexpected real PCM RMS: '+audible.rms);
  gain.gain.value=0;await wait(()=>Number(host.dataset.audioLevel)<.01);const silence=meter.sample();if(silence.rms>.005)throw Error('Silence was not silent');
  engine.dispose();if(host.childElementCount)throw Error('Canvas not released');host.remove();oscillator.stop();meter.dispose();await context.close();
  return {toneRms:audible.rms,silenceRms:silence.rms};
 }""")
 # Context loss must produce a visible animated 3D projection, rather than an empty HUD.
 page.evaluate("document.querySelector('#hologramHost canvas').getContext('webgl2').getExtension('WEBGL_lose_context').loseContext()")
 page.wait_for_function("document.querySelector('#hologramHost').dataset.renderer==='Canvas 3D'")
 assert page.locator('#hologramHost canvas:visible').count()==1
 page.screenshot(path=str(ARTIFACTS/'hologram-fallback.png'),full_page=True)
 page.set_viewport_size({'width':430,'height':932})
 page.wait_for_function('document.documentElement.scrollWidth<=innerWidth')
 page.screenshot(path=str(ARTIFACTS/'hologram-web-mobile.png'),full_page=True)
 assert page.locator('#hologramHost').bounding_box()['height']>=300
 assert not errors,errors
 print(json.dumps({'result':'PASS','initialScene':metrics,'adaptedScene':adapted,'webAudio':result,'checks':['animated WebGL','existing text command','real PCM -> orb deformation','silence -> no deformation','GPU loss fallback','mobile overflow','dispose resources']},ensure_ascii=False))
 browser.close()
