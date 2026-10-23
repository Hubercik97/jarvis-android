import { app, BrowserWindow, ipcMain, safeStorage, dialog, shell, Tray, Menu, nativeImage, session } from 'electron';
import { readFile, writeFile, appendFile, mkdir } from 'node:fs/promises';
import path from 'node:path';
import { pathToFileURL } from 'node:url';
import {cpus,totalmem} from 'node:os';
import {publicError} from './public-error.cjs';
import { endpointOf, repositoryOf, actionOf, apiPath } from './policy.cjs';
import {WakeService} from './wake-service.cjs';
import type { Settings, Configure, WakePreferences, CloudCheck } from '../shared/types.js';
let window:BrowserWindow;
let tray:Tray;
let quitting=false;
let token='';
const defaults:Settings={endpoint:'',hasToken:false,rememberToken:false,startup:false,localControl:false,repository:'Hubercik97/jarvis-android',tts:'system',voice:'',lowPower:true,latitude:52.23,longitude:21.01,wake:{enabled:false,autoStart:false,sensitivity:.5,deviceId:'',confirmation:true}};
let settings={...defaults};
let encryptedToken='';
let trustedUrl='';
let controlBusy=false;
let wake:WakeService;
let wakeRequest=0;
let cloudCheck:CloudCheck={state:'not_configured',https:false,hasToken:false,checkedAt:null};
function wakePreferences(value:unknown):WakePreferences{
 const p=value as WakePreferences;
 if(!p||typeof p!=='object'||typeof p.enabled!=='boolean'||typeof p.autoStart!=='boolean'||typeof p.confirmation!=='boolean'||typeof p.deviceId!=='string'||p.deviceId.length>256||!Number.isFinite(p.sensitivity)||p.sensitivity<0||p.sensitivity>1)throw new Error('Nieprawidłowe ustawienia wybudzania');
 return {enabled:p.enabled,autoStart:p.autoStart,sensitivity:p.sensitivity,deviceId:p.deviceId,confirmation:p.confirmation};
}
const configFile=()=>path.join(app.getPath('userData'),'settings.json');
const snapshot=()=>({...settings,hasToken:Boolean(token)});
async function save(){
 await mkdir(app.getPath('userData'),{recursive:true});
 await writeFile(configFile(),JSON.stringify({...settings,hasToken:undefined,encryptedToken: settings.rememberToken ? encryptedToken : ''}),{mode:0o600});
}
function trusted(event:Electron.IpcMainInvokeEvent){
 if(event.sender!==window.webContents || event.senderFrame!==window.webContents.mainFrame || event.senderFrame.url!==trustedUrl) throw new Error('Niezaufany nadawca IPC');
}
function handle(name:string,fn:(...args:any[])=>unknown){ipcMain.handle(name,async(event,...args)=>{trusted(event);try{return await fn(...args);}catch(error){throw new Error(publicError(error,[token]));}});}
async function boot(){
 try{
  const saved=JSON.parse(await readFile(configFile(),'utf8'));
  settings={...defaults,endpoint:saved.endpoint?endpointOf(saved.endpoint,!app.isPackaged):'',repository:repositoryOf(saved.repository||defaults.repository),startup:saved.startup===true,rememberToken:saved.rememberToken===true,localControl:saved.localControl===true,tts:saved.tts==='google'?'google':'system',voice:typeof saved.voice==='string'?saved.voice.slice(0,200):'',lowPower:saved.lowPower!==false,latitude:Number.isFinite(saved.latitude)?saved.latitude:52.23,longitude:Number.isFinite(saved.longitude)?saved.longitude:21.01};
  if(saved.wake)try{settings.wake=wakePreferences(saved.wake);}catch{/* Invalid optional preferences stay disabled. */}
  if(settings.rememberToken && typeof saved.encryptedToken==='string' && safeStorage.isEncryptionAvailable() && (process.platform!=='linux'||safeStorage.getSelectedStorageBackend()!=='basic_text')){
   encryptedToken=saved.encryptedToken;token=safeStorage.decryptString(Buffer.from(encryptedToken,'base64'));
  }
 }catch{/* Missing, invalid or undecryptable settings start disconnected. */}
 window=new BrowserWindow({width:1260,height:820,minWidth:850,minHeight:640,backgroundColor:'#050c16',autoHideMenuBar:true,title:'Jarvis Desktop',webPreferences:{preload:path.join(__dirname,'preload.cjs'),contextIsolation:true,nodeIntegration:false,sandbox:true,webSecurity:true,backgroundThrottling:true}});
 trustedUrl=pathToFileURL(path.join(__dirname,'../../dist/index.html')).href;
 wake=new WakeService(path.join(__dirname,'wake-worker.cjs'),app.isPackaged?path.join(process.resourcesPath,'wake'):path.join(app.getAppPath(),'resources/wake'),status=>{
  if(!window.isDestroyed()&&!window.webContents.isDestroyed()){
   window.webContents.send('wake-status',status);
   window.webContents.setBackgroundThrottling(status.phase==='off'||status.phase==='error');
  }
  if(tray){tray.setToolTip(status.phase==='armed'?'Jarvis · mikrofon nasłuchuje lokalnie: Pobudka Jarvis':`Jarvis · ${status.message}`);refreshTray();}
 });
 window.webContents.setWindowOpenHandler(()=>({action:'deny'}));
 window.webContents.on('will-navigate',event=>event.preventDefault());
 session.defaultSession.setPermissionRequestHandler((contents,permission,callback,details)=>{
  callback(contents===window.webContents && permission==='media' && details.requestingUrl===trustedUrl && 'mediaTypes' in details && Array.isArray(details.mediaTypes) && details.mediaTypes.length > 0 && details.mediaTypes.every((t:string)=>t==='audio'));
 });
 session.defaultSession.setPermissionCheckHandler((contents,permission,_origin,details)=>contents===window.webContents&&permission==='media'&&details.requestingUrl===trustedUrl);
 handle('settings',()=>snapshot());
 handle('local-control',async(enabled:unknown)=>{
  if(typeof enabled!=='boolean')throw new Error('Nieprawidłowa zgoda na sterowanie lokalne');
  settings={...settings,localControl:enabled};
  try{await save();}catch{settings={...settings,localControl:false};throw new Error('Nie udało się zapisać zgody. Sterowanie lokalne zostało wyłączone.');}
  return snapshot();
 });
 handle('diagnostics',()=>{const processes=app.getAppMetrics();return {wake:wake.diagnostics(),cloud:{...cloudCheck,https:settings.endpoint.startsWith('https://'),hasToken:Boolean(token)},localControl:settings.localControl,hardware:{platform:process.platform,arch:process.arch,cpu:cpus()[0]?.model||'Nieznany',logicalCpus:cpus().length,totalMemoryGiB:Number((totalmem()/1073741824).toFixed(1))},desktopWorkingSetMiB:Math.round(processes.reduce((sum,p)=>sum+p.memory.workingSetSize,0)/1024),desktopCpuPct:processes.reduce((sum,p)=>sum+p.cpu.percentCPUUsage,0)};});
 handle('test-cloud',async()=>{
  cloudCheck={state:'not_configured',https:settings.endpoint.startsWith('https://'),hasToken:Boolean(token),checkedAt:new Date().toISOString()};
  if(!settings.endpoint||!token)return cloudCheck;
  try{await cloudRequest('/health');}catch{if(cloudCheck.state==='not_configured')cloudCheck={...cloudCheck,state:'network_error'};}
  return {...cloudCheck};
 });
 handle('wake-settings',async(value:unknown)=>{const next=wakePreferences(value);const request=++wakeRequest;settings={...settings,wake:next};wake.stop();await save();if(request===wakeRequest&&next.enabled)wake.start(next.sensitivity);return next;});
 handle('wake-control',(action:unknown,error:unknown)=>{
  if(action==='stop'){wakeRequest++;if(typeof error==='string')wake.fail(error.slice(0,250));else wake.stop();}
  else if(action==='start'){wakeRequest++;if(!settings.wake.enabled)throw new Error('Włącz wybudzanie głosowe w Ustawieniach');wake.start(settings.wake.sensitivity);}
  else if(action==='pause')wake.pause();
  else if(action==='ready')wake.ready();
  else if(action==='resume'){if(settings.wake.enabled)wake.resume();}
  else if(action==='status'){/* Read-only state replay for renderer startup. */}
  else throw new Error('Nieprawidłowe sterowanie nasłuchem');
  return wake.status;
 });
 handle('wake-pcm',(value,telemetry)=>wake.pcm(value,telemetry));
 handle('configure',async(input:Configure)=>{
  if(!input||typeof input!=='object') throw new Error('Nieprawidłowe ustawienia');
  const endpoint=endpointOf(input.endpoint,!app.isPackaged);
  const repository=repositoryOf(input.repository);
  if(!['system','google'].includes(input.tts)||typeof input.voice!=='string'||input.voice.length>200||!Number.isFinite(input.latitude)||!Number.isFinite(input.longitude)||Math.abs(input.latitude)>90||Math.abs(input.longitude)>180) throw new Error('Nieprawidłowe ustawienia głosu lub pogody');
  if(input.token!==undefined && (typeof input.token!=='string'||! /^[\x21-\x7e]{16,500}$/.test(input.token))) throw new Error('Token musi mieć 16–500 znaków ASCII bez spacji.');
  if(endpoint!==settings.endpoint && !input.token) throw new Error('Nowy serwer wymaga ponownego wpisania tokenu');
  const nextToken=input.token||token;
  const remember=input.rememberToken===true;
  if(remember&&(!safeStorage.isEncryptionAvailable()||(process.platform==='linux'&&safeStorage.getSelectedStorageBackend()==='basic_text'))) throw new Error('Szyfrowany magazyn systemowy jest niedostępny; wyłącz zapamiętywanie tokenu');
  encryptedToken=remember&&nextToken?safeStorage.encryptString(nextToken).toString('base64'):'';
  token=nextToken;
  settings={...settings,endpoint,repository,rememberToken:remember,startup:input.startup===true,tts:input.tts,voice:input.voice,lowPower:input.lowPower!==false,latitude:input.latitude,longitude:input.longitude};
  cloudCheck={state:'not_configured',https:endpoint.startsWith('https://'),hasToken:Boolean(token),checkedAt:null};
  if(process.platform==='win32') app.setLoginItemSettings({openAtLogin:settings.startup,path:process.execPath,args:['--hidden']});
  await save();return snapshot();
 });
 async function cloudRequest(route:unknown,method='GET',body?:unknown){
  const api=apiPath(route,method);
  if(!settings.endpoint||!token) throw new Error('Połącz aplikację z chmurą w Ustawieniach');
  const data=body===undefined?undefined:JSON.stringify(body);
  if(data&&Buffer.byteLength(data)>(api==='/stt'?3_000_000:64_000)) throw new Error('Za duże żądanie');
  let response:Response;
  try{response=await fetch(settings.endpoint+api,{method,headers:{'x-jarvis-token':token,...(data?{'content-type':'application/json'}:{})},body:data,redirect:'error',signal:AbortSignal.timeout(api==='/health'?8_000:100_000)});}
  catch{if(api==='/health')cloudCheck={state:'network_error',https:settings.endpoint.startsWith('https://'),hasToken:Boolean(token),checkedAt:new Date().toISOString()};throw new Error('Brak połączenia z API. Sprawdź adres HTTPS, internet i certyfikat serwera.');}
  const text=await response.text();
  if(Buffer.byteLength(text)>8_000_000) throw new Error('Za duża odpowiedź serwera');
  let result;try{result=JSON.parse(text);}catch{if(api==='/health')cloudCheck={...cloudCheck,state:'invalid_response',checkedAt:new Date().toISOString()};throw new Error('Serwer nie udostępnia poprawnego API Jarvisa');}
  if(!result||typeof result!=='object'||Array.isArray(result)){if(api==='/health')cloudCheck={...cloudCheck,state:'invalid_response',checkedAt:new Date().toISOString()};throw new Error('Nieprawidłowa odpowiedź API Jarvisa');}
  if(api==='/health')cloudCheck={state:response.status===401||response.status===403||result.auth==='invalid'?'unauthorized':!response.ok||result.auth!=='ok'?'invalid_response':result.configured?'authenticated':'provider_not_ready',https:settings.endpoint.startsWith('https://'),hasToken:Boolean(token),checkedAt:new Date().toISOString()};
  if(!response.ok)throw new Error(response.status===401||response.status===403?'Nieprawidłowy token lub brak dostępu do API.':response.status===429?'Limit wywołań API został wykorzystany.':`Usługa chmurowa jest niedostępna (HTTP ${response.status}).`);
  if(api==='/health')for(const name of ['provider','model'])if(typeof result[name]==='string')result[name]=publicError(new Error(result[name]),[token]);
  return result;
 }
 handle('api',cloudRequest);
 handle('control',async(input:unknown)=>{
  const action=actionOf(input);
  if(!settings.localControl) throw new Error('Sterowanie lokalne jest wyłączone w Ustawieniach');
  if(controlBusy) throw new Error('Zatwierdź lub odrzuć poprzednią operację');
  controlBusy=true;
  try{
   const health=await cloudRequest('/health');
   if(health.auth!=='ok')throw new Error('Najpierw uwierzytelnij połączenie z chmurą');
   if(!settings.localControl)throw new Error('Zgoda na sterowanie lokalne została cofnięta.');
   const labels={open_chrome:'Otworzyć Google Chrome?',open_documents:'Otworzyć folder Dokumenty?',open_project:`Otworzyć https://github.com/${settings.repository}?`};
   const approval=await dialog.showMessageBox(window,{type:'question',message:labels[action],detail:'Lokalny agent wykona tylko tę jedną operację.',buttons:['Anuluj','Zezwól'],defaultId:0,cancelId:0,noLink:true});
   if(approval.response!==1) return {ok:false,message:'Operacja anulowana.'};
   if(!settings.localControl)throw new Error('Zgoda na sterowanie lokalne została cofnięta.');
   if(action==='open_project') await shell.openExternal(`https://github.com/${settings.repository}`);
   else if(action==='open_documents'){
    const error=await shell.openPath(app.getPath('documents'));if(error)throw new Error('Nie udało się otworzyć Dokumentów');
   }else{
    if(process.platform!=='win32')throw new Error('Otwieranie Chrome jest dostępne w wersji Windows');
    const candidates=[path.join(process.env.PROGRAMFILES||'C:\\Program Files','Google/Chrome/Application/chrome.exe'),path.join(process.env['PROGRAMFILES(X86)']||'C:\\Program Files (x86)','Google/Chrome/Application/chrome.exe'),path.join(app.getPath('home'),'AppData/Local/Google/Chrome/Application/chrome.exe')];
    let opened=false;for(const executable of candidates){if(!await shell.openPath(executable)){opened=true;break;}}
    if(!opened)throw new Error('Nie znaleziono instalacji Chrome');
   }
   await appendFile(path.join(app.getPath('userData'),'actions.jsonl'),JSON.stringify({time:new Date().toISOString(),action,status:'completed'})+'\n');
   return {ok:true,message:'Polecenie wykonane.'};
  }finally{controlBusy=false;}
 });
 handle('minimize',()=>{window.hide();});
 // A lightweight SVG-free tray image is decoded from a generated PNG at build time.
 tray=new Tray(nativeImage.createFromPath(path.join(__dirname,'../../dist/tray.png')));
 tray.setToolTip('Jarvis Desktop');
 function refreshTray(){tray.setContextMenu(Menu.buildFromTemplate([{label:'Pokaż Jarvisa',click:()=>{window.show();window.focus();}},{label:wake.status.phase==='armed'?'Mikrofon aktywny · Pobudka Jarvis':wake.status.message,enabled:false},{label:'Natychmiast wyłącz nasłuch',click:()=>{wakeRequest++;wake.stop();}},{label:'Zakończ',click:()=>{quitting=true;app.quit();}}]));}
 refreshTray();
 tray.on('double-click',()=>window.show());
 window.on('close',event=>{if(!quitting){event.preventDefault();window.hide();}});
 window.on('show',()=>window.webContents.send('visibility',true));
 await window.loadFile(path.join(__dirname,'../../dist/index.html'));
 if(settings.wake.enabled&&settings.wake.autoStart)wake.start(settings.wake.sensitivity);
 if(process.argv.includes('--hidden'))window.hide();
}
if(!app.requestSingleInstanceLock())app.quit();
else{
 app.on('second-instance',()=>{if(window){window.show();window.focus();}});
 app.on('before-quit',()=>{quitting=true;wake?.stop();});
 app.whenReady().then(boot).catch(()=>{dialog.showErrorBox('Jarvis','Nie udało się uruchomić aplikacji. Sprawdź uprawnienia do katalogu ustawień.');app.quit();});
}
