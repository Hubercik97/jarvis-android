import { contextBridge, ipcRenderer } from 'electron';
import type { Bridge } from '../shared/types.js';
const bridge:Bridge={
 settings:()=>ipcRenderer.invoke('settings'),
 configure:config=>ipcRenderer.invoke('configure',config),
 api:(path,method='GET',body)=>ipcRenderer.invoke('api',path,method,body),
 control:action=>ipcRenderer.invoke('control',action),
 minimize:()=>ipcRenderer.invoke('minimize'),
 wakeSettings:preferences=>ipcRenderer.invoke('wake-settings',preferences),
 wakeControl:(action,error)=>ipcRenderer.invoke('wake-control',action,error),
 wakePcm:samples=>ipcRenderer.send('wake-pcm',samples),
 onWake:listener=>{const callback=(_event:Electron.IpcRendererEvent,status:import('../shared/types.js').WakeStatus)=>listener(status);ipcRenderer.on('wake-status',callback);return()=>ipcRenderer.removeListener('wake-status',callback);},
};
contextBridge.exposeInMainWorld('jarvis',bridge);
