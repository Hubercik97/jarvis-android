import type {CloudCheck} from '../shared/types.js';
export class CloudProtocolError extends Error{
 constructor(readonly state:CloudCheck['state'],message:string){super(message);}
}
// Never include response bodies or headers in errors: proxies and providers can echo credentials.
export function decodeCloudResponse(status:number,contentType:string,text:string,health:boolean){
 if(status===401||status===403)throw new CloudProtocolError('unauthorized','Token aplikacji został odrzucony lub serwer blokuje dostęp. Sprawdź APP_TOKEN Workera i token w Ustawieniach.');
 if(health&&status===404)throw new CloudProtocolError('endpoint_not_api','Pod tym adresem nie ma GET /health. Worker wymaga backendu Jarvisa; sama strona WWW nie udostępnia API.');
 if(status===429)throw new CloudProtocolError('http_error','Limit wywołań API został wykorzystany.');
 let result:any;try{result=JSON.parse(text);}catch{
  if(status>=400)throw new CloudProtocolError('http_error',`Usługa chmurowa jest niedostępna (HTTP ${status}).`);
  if(health&&(/text\/html/i.test(contentType)||/^\s*(?:<!doctype html|<html)/i.test(text)))throw new CloudProtocolError('endpoint_not_api','GET /health zwraca stronę HTML zamiast JSON. Sprawdź kod Workera, main = "entry.js" i assets.run_worker_first dla ścieżek API.');
  throw new CloudProtocolError('invalid_response','Serwer nie zwrócił poprawnego JSON API Jarvisa.');
 }
 if(!result||typeof result!=='object'||Array.isArray(result))throw new CloudProtocolError('invalid_response','Nieprawidłowy format odpowiedzi API Jarvisa.');
 if(health&&result.auth==='missing')throw new CloudProtocolError('server_token_missing','Worker nie ma sekretu APP_TOKEN. Ustaw go w tym Workerze; nie zmieniaj istniejącego tokenu, jeżeli jest już skonfigurowany.');
 if(status<200||status>=300)throw new CloudProtocolError('http_error',`Usługa chmurowa jest niedostępna (HTTP ${status}).`);
 if(health){
  if(!['ok','invalid'].includes(result.auth)||typeof result.configured!=='boolean')throw new CloudProtocolError('invalid_response','GET /health zwraca JSON bez pól auth i configured wymaganych przez Desktop. Zaktualizuj backend zgodny z Androidem i Windows.');
  if(result.auth!=='ok')throw new CloudProtocolError('unauthorized','Token aplikacji nie zgadza się z APP_TOKEN tego Workera. Klucz OpenAI nie jest tokenem aplikacji.');
 }
 return result;
}
