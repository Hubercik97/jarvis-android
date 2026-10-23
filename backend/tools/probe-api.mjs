// Read-only API audit. Never prints response bodies, headers, tokens or private records.
import {pathToFileURL} from 'node:url';
import {appendFileSync} from 'node:fs';
const TARGET='https://jarvis-android.lunvra-golf.workers.dev';
const routes=['/health','/conversations','/memory','/tasks','/projects','/stt','/tts','/chat'];
export async function probeApi(origin=TARGET,token='',fetcher=fetch){
 const url=new URL(origin);
 if(url.origin!==TARGET||url.pathname!=='/'||url.search||url.hash||url.username||url.password)throw new Error('Dozwolony jest wyłącznie wskazany Worker HTTPS.');
 async function inspect(route,credential=''){
  try{
   const res=await fetcher(url.origin+route,{method:'GET',headers:{accept:'application/json',...(credential?{'x-jarvis-token':credential}:{})},redirect:'error',signal:AbortSignal.timeout(15000)});
   const bytes=await res.text();let data=null;try{if(bytes.length<200000)data=JSON.parse(bytes);}catch{}
   const object=data&&typeof data==='object'&&!Array.isArray(data);
   const row={route,status:res.status,json:Boolean(object),html:/text\/html/i.test(res.headers.get('content-type')||'')||/^\s*<!doctype html/i.test(bytes),authenticated:Boolean(credential)};
   if(route==='/health'&&object){
    row.jarvis=['ok','invalid','missing'].includes(data.auth)&&typeof data.configured==='boolean';
    row.auth=['ok','invalid','missing'].includes(data.auth)?data.auth:'unknown';
    row.modelConfigured=data.configured===true;
    row.historyAvailable=data.capabilities?.history===true;
    row.apiVersion=Number.isSafeInteger(data.api_version)?data.api_version:null;
   }
   return row;
  }catch{return {route,status:null,json:false,authenticated:Boolean(credential),networkError:true};}
 }
 const checks=[];for(const route of routes)checks.push(await inspect(route));
 // A credential is sent only after the public endpoint proves the expected protocol.
 if(token&&checks[0].jarvis&&checks[0].status===200){
  const authenticated=await inspect('/health',token);checks.push(authenticated);
  if(authenticated.auth==='ok')for(const route of ['/conversations','/memory','/tasks','/projects'])checks.push(await inspect(route,token));
 }
 return {origin:url.origin,checkedAt:new Date().toISOString(),readOnly:true,checks};
}
if(process.argv[1]&&import.meta.url===pathToFileURL(process.argv[1]).href){
 try{
  const report=await probeApi(TARGET,process.env.JARVIS_PROBE_TOKEN||'');console.log(JSON.stringify(report,null,2));
  if(process.env.GITHUB_ACTIONS==='true'){
   console.error('::notice title=Cloud API audit::'+JSON.stringify(report));
   const summary=report.checks.map(c=>`${c.authenticated?'AUTH ':''}${c.route}=${c.status??'NETWORK_ERROR'}/${c.html?'HTML':c.json?'JSON':'other'}${c.auth?'/'+c.auth:''}`).join('; ');
   if(process.env.GITHUB_OUTPUT)appendFileSync(process.env.GITHUB_OUTPUT,`summary=API audit: ${summary}\n`);
  }
 }
 catch{console.error('Nie można wykonać audytu wskazanego Workera.');process.exitCode=1;}
}
