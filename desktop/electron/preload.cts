import { contextBridge, ipcRenderer } from 'electron';
import type { Bridge } from '../shared/types.js';
async function invoke(channel:string,...args:unknown[]){
 try{return await ipcRenderer.invoke(channel,...args);}
 catch(error){const message=error instanceof Error?error.message:'Operacja nie powiodła się.';throw new Error(message.replace(/^Error invoking remote method '[^']+':\s*(?:Error:\s*)?/,''));}
}
const bridge:Bridge={
 settings:()=>invoke('settings'),
 configure:config=>invoke('configure',config),
 api:(path,method='GET',body)=>invoke('api',path,method,body),
 control:action=>invoke('control',action),
 minimize:()=>invoke('minimize'),
 wakeSettings:preferences=>invoke('wake-settings',preferences),
 wakeControl:(action,error)=>invoke('wake-control',action,error),
 wakePcm:(samples,telemetry)=>invoke('wake-pcm',samples,telemetry),
 onWake:listener=>{const callback=(_event:Electron.IpcRendererEvent,status:import('../shared/types.js').WakeStatus)=>listener(status);ipcRenderer.on('wake-status',callback);return()=>ipcRenderer.removeListener('wake-status',callback);},
 localControl:enabled=>invoke('local-control',enabled),
 diagnostics:()=>invoke('diagnostics'),
 testCloud:()=>invoke('test-cloud'),
};
contextBridge.exposeInMainWorld('jarvis',bridge);
