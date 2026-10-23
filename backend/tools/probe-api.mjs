// Read-only API audit. Never prints response bodies, headers, tokens or private records.
import {pathToFileURL} from 'node:url';
import {appendFileSync} from 'node:fs';
const TARGET='https://jarvis-android.lunvra-golf.workers.dev';
const routes=['/health','/conversations','/memory','/tasks','/projects','/stt','/tts','/chat'];
export async function inspectWorkerSettings(apiToken,accountId,fetcher=fetch){
 if(!apiToken||!accountId)return {state:'credentials_unavailable'};
 try{
  const res=await fetcher(`https://api.cloudflare.com/client/v4/accounts/${encodeURIComponent(accountId)}/workers/scripts/jarvis-android/settings`,{method:'GET',headers:{authorization:`Bearer ${apiToken}`},redirect:'error',signal:AbortSignal.timeout(15000)});
  if(!res.ok)return {state:'unavailable',status:res.status};
  const body=await res.json();if(body.success!==true||!Array.isArray(body.result?.bindings))return {state:'invalid_response'};
  const bindings=body.result.bindings;
  const has=(name,type)=>bindings.some(b=>b.name===name&&b.type===type);
  // Use the dedicated secret-name endpoint too: settings can omit secret bindings.
  let secrets=null;
  try{
   const listed=await fetcher(`https://api.cloudflare.com/client/v4/accounts/${encodeURIComponent(accountId)}/workers/scripts/jarvis-android/secrets`,{method:'GET',headers:{authorization:`Bearer ${apiToken}`},redirect:'error',signal:AbortSignal.timeout(15000)});
   if(listed.ok){const value=await listed.json();if(value.success===true&&Array.isArray(value.result))secrets=value.result;}
  }catch{/* Unknown secret state must not be reported as absence. */}
  const secret=name=>has(name,'secret_text')||secrets?.some(s=>s.name===name&&s.type==='secret_text')===true?true:secrets===null?null:false;
  return {state:'inspected',appTokenSecret:secret('APP_TOKEN'),openaiSecret:secret('OPENAI_API_KEY'),anthropicSecret:secret('ANTHROPIC_API_KEY'),db:has('DB','d1'),memory:has('MEMORY','kv_namespace'),assets:has('ASSETS','assets')};
 }catch{return {state:'network_error'};}
}
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
  const report=await probeApi(TARGET,process.env.JARVIS_PROBE_TOKEN||'');
  report.workerSettings=await inspectWorkerSettings(process.env.CLOUDFLARE_API_TOKEN,process.env.CLOUDFLARE_ACCOUNT_ID);
  console.log(JSON.stringify(report,null,2));
  if(process.env.GITHUB_ACTIONS==='true'){
   console.error('::notice title=Cloud API audit::'+JSON.stringify(report));
   const summary=report.checks.map(c=>`${c.authenticated?'AUTH ':''}${c.route}=${c.status??'NETWORK_ERROR'}/${c.html?'HTML':c.json?'JSON':'other'}${c.auth?'/'+c.auth:''}`).join('; ')+'; Worker settings '+JSON.stringify(report.workerSettings);
   if(process.env.GITHUB_OUTPUT)appendFileSync(process.env.GITHUB_OUTPUT,`summary=API audit: ${summary}\n`);
  }
 }
 catch{console.error('Nie można wykonać audytu wskazanego Workera.');process.exitCode=1;}
}
