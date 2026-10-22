import { contextBridge, ipcRenderer } from 'electron';
import type { Bridge } from '../shared/types.js';
const bridge:Bridge={
 settings:()=>ipcRenderer.invoke('settings'),
 configure:config=>ipcRenderer.invoke('configure',config),
 api:(path,method='GET',body)=>ipcRenderer.invoke('api',path,method,body),
 control:action=>ipcRenderer.invoke('control',action),
 minimize:()=>ipcRenderer.invoke('minimize'),
};
contextBridge.exposeInMainWorld('jarvis',bridge);
