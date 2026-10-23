export type WakePreferences={enabled:boolean;autoStart:boolean;sensitivity:number;deviceId:string;confirmation:boolean};
export type WakeStatus={phase:'off'|'loading'|'starting'|'armed'|'paused'|'detected'|'error';message:string};
export type WakeReceipt={accepted:boolean;reset:boolean};
export type WakeTelemetry={bufferedMs?:number;discardedFrames?:number;reset?:boolean};
export type CloudCheck={state:'not_configured'|'authenticated'|'provider_not_ready'|'unauthorized'|'network_error'|'invalid_response';https:boolean;hasToken:boolean;checkedAt:string|null};
export type WakeMetrics={engine:string;frameMs:number;phase:WakeStatus['phase'];received:number;processed:number;quietSkipped:number;discarded:number;resets:number;queueDepth:number;bufferedMs:number;meanInferenceMs:number;p95InferenceMs:number;inferenceBudgetPct:number;workerThreadCpuPct:number|null;workerJsHeapMiB:number|null;mainProcessRssMiB:number|null;events:{time:string;code:string}[]};
export type DesktopDiagnostics={wake:WakeMetrics;cloud:CloudCheck;localControl:boolean;hardware:{platform:string;arch:string;cpu:string;logicalCpus:number;totalMemoryGiB:number};desktopWorkingSetMiB:number;desktopCpuPct:number};
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
 wakePcm(samples:Float32Array,telemetry?:WakeTelemetry):Promise<WakeReceipt>;
 onWake(listener:(status:WakeStatus)=>void):()=>void;
 localControl(enabled:boolean):Promise<Settings>;
 diagnostics():Promise<DesktopDiagnostics>;
 testCloud():Promise<CloudCheck>;
}
declare global { interface Window { jarvis:Bridge } }
