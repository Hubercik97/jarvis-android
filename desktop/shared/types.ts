export type Settings = {endpoint:string;hasToken:boolean;rememberToken:boolean;startup:boolean;localControl:boolean;repository:string;tts:'system'|'google';voice:string;lowPower:boolean;latitude:number;longitude:number};
export type Configure = Omit<Settings,'hasToken'> & {token?:string};
export type LocalAction = 'open_chrome'|'open_documents'|'open_project';
export type ApiMethod = 'GET'|'POST'|'DELETE';
export interface Bridge {
 settings():Promise<Settings>;
 configure(config:Configure):Promise<Settings>;
 api(path:string,method?:ApiMethod,body?:unknown):Promise<any>;
 control(action:LocalAction):Promise<{ok:boolean;message:string}>;
 minimize():Promise<void>;
}
declare global { interface Window { jarvis:Bridge } }
