export function endpointOf(input:unknown,development=false):string {
 if(typeof input!=='string'||input.length>300) throw new Error('Nieprawidłowy adres chmury');
 const url=new URL(input);
 const local=['localhost','127.0.0.1'].includes(url.hostname);
 if((url.protocol!=='https:' && !(development&&local&&url.protocol==='http:'))||url.username||url.password||url.search||url.hash||url.pathname!=='/'||(!development&&local)) throw new Error('Wymagany adres HTTPS bez ścieżki, hasła i parametrów');
 if (!local && (/^\d+(\.\d+){3}$/.test(url.hostname)||url.hostname.includes(':')||!url.hostname.includes('.')||/\.(local|internal|localhost)$/.test(url.hostname))) throw new Error('Wymagana publiczna domena chmury');
 return url.origin;
}
export function repositoryOf(input:unknown):string {
 if(typeof input!=='string'||!/^[A-Za-z0-9_.-]{1,100}\/[A-Za-z0-9_.-]{1,100}$/.test(input)||input.split('/').some(p=>['.','..'].includes(p))) throw new Error('Podaj owner/repository');
 return input;
}
export function actionOf(input:unknown):'open_chrome'|'open_documents'|'open_project' {
 if(input!=='open_chrome'&&input!=='open_documents'&&input!=='open_project') throw new Error('Polecenie lokalne jest niedozwolone');
 return input;
}
export function apiPath(input:unknown,method:unknown):string {
 if(typeof input!=='string'||input.length>500||!['GET','POST','DELETE'].includes(String(method))) throw new Error('Niedozwolone żądanie');
 const id='[a-f0-9-]{36}';
 const rules:[RegExp,string[]][]=[
  [/^\/health$/,['GET']], [/^\/chat$/,['POST']], [/^\/tts$/,['POST']], [/^\/stt$/,['POST']],
  [/^\/memory$/,['GET','POST','DELETE']], [/^\/memory\/[a-f0-9]{12}$/,['DELETE']],
  [/^\/conversations$/,['GET','POST']], [new RegExp(`^/conversations/${id}$`),['DELETE']],
  [new RegExp(`^/conversations/${id}/messages$`),['GET','POST']],
  [/^\/projects$/,['GET','POST']], [new RegExp(`^/projects/${id}$`),['DELETE']],
  [/^\/tasks$/,['GET','POST']], [new RegExp(`^/tasks/${id}$`),['GET']], [new RegExp(`^/tasks/${id}/(run|cancel)$`),['POST']],
  [/^\/weather\?latitude=-?\d+(\.\d+)?&longitude=-?\d+(\.\d+)?$/,['GET']],
  [/^\/developer\/project\?repo=[A-Za-z0-9_.%-]+$/,['GET']],
 ];
 if(!rules.some(([pattern,methods])=>pattern.test(input)&&methods.includes(String(method)))) throw new Error('Ścieżka API jest niedozwolona');
 if(input.includes('..')||input.includes('\\')) throw new Error('Nieprawidłowa ścieżka');
 return input;
}
