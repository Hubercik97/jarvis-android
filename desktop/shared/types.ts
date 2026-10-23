export type WakePreferences={enabled:boolean;autoStart:boolean;sensitivity:number;deviceId:string;confirmation:boolean};
export type WakeStatus={phase:'off'|'loading'|'starting'|'armed'|'paused'|'detected'|'error';message:string};
export type Settings = {endpoint:string;hasToken:boolean;rememberToken:boolean;startup:boolean;localControl:boolean;repository:string;tts:'system'|'google';voice:string;lowPower:boolean;latitude:number;longitude:number;wake:WakePreferences};
export type Configure = Omit<Settings,'hasToken'> & {token?:string};
export type LocalAction = 'open_chrome'|'open_documents'|'open_project';
export type ApiMethod = 'GET'|'POST'|'DELETE';
export interface Bridge {
 settings():Promise<Settings>;
 configure(config:Configure):Promise<Settings>;
 api(path:string,method?:ApiMethod,body?:unknown):Promise<any>;
 control(action:LocalAction):Promise<{ok:boolean;message:string}>;
 minimize():Promise<void>;
 wakeSettings(preferences:WakePreferences):Promise<WakePreferences>;
 wakeControl(action:'start'|'stop'|'pause'|'resume'|'ready'|'status',error?:string):Promise<WakeStatus>;
 wakePcm(samples:Float32Array):void;
 onWake(listener:(status:WakeStatus)=>void):()=>void;
}
declare global { interface Window { jarvis:Bridge } }
