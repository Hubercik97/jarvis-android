import test,{afterEach} from 'node:test';import assert from 'node:assert/strict';import worker,{resetRateLimit} from './worker.js';
const original=globalThis.fetch;afterEach(()=>{globalThis.fetch=original;resetRateLimit();});
const env={APP_TOKEN:'test-token',OPENAI_API_KEY:'test-key'};
const call=(path,method='GET',body,config=env)=>worker.fetch(new Request('https://jarvis.test'+path,{method,headers:{'x-jarvis-token':env.APP_TOKEN},...(body?{body:JSON.stringify(body)}:{})}),config);
test('desktop endpoints reject unauthenticated requests',async()=>{for(const path of ['/weather','/stt','/developer/project'])assert.equal((await worker.fetch(new Request('https://jarvis.test'+path),env)).status,401);});
test('STT forwards bounded audio as Polish transcription and keeps key on server',async()=>{
 globalThis.fetch=async(url,init)=>{assert.equal(url,'https://api.openai.com/v1/audio/transcriptions');assert.equal(init.headers.authorization,'Bearer test-key');assert.equal(init.body.get('language'),'pl');assert.equal(init.body.get('model'),'gpt-4o-mini-transcribe');assert.ok(init.signal);return Response.json({text:'Otwórz Chrome'});};
 const res=await call('/stt','POST',{audio:btoa('webm-fixture'),mime:'audio/webm'});assert.equal(res.status,200);assert.deepEqual(await res.json(),{text:'Otwórz Chrome'});
});
test('STT rejects invalid audio and disabled provider before network',async()=>{
 globalThis.fetch=()=>assert.fail('no network');assert.equal((await call('/stt','POST',{audio:'!!!',mime:'audio/webm'})).status,400);
 assert.equal((await call('/stt','POST',{}, {APP_TOKEN:'test-token'})).status,503);
 assert.equal((await call('/stt','POST',{audio:'a'.repeat(3000001),mime:'audio/webm'})).status,413);
});
test('weather validates coordinates and only fetches fixed provider URL without application auth',async()=>{
 globalThis.fetch=async(url,init)=>{assert.match(url,/^https:\/\/api.open-meteo.com\/v1\/forecast\?/);assert.equal(init.headers,undefined);return Response.json({current:{temperature_2m:12,weather_code:1,time:'2026-10-22'}});};
 assert.equal((await call('/weather?latitude=999&longitude=10')).status,400);
 assert.equal((await call('/weather')).status,400);
 const result=await(await call('/weather?latitude=52.23&longitude=21.01')).json();assert.equal(result.temperature,12);assert.equal(result.source,'Open-Meteo');
});
test('developer reads official GitHub API and rejects arbitrary URLs and path traversal',async()=>{
 globalThis.fetch=async(url,init)=>{assert.equal(url,'https://api.github.com/repos/Hubercik97/jarvis-android');assert.equal(init.redirect,'error');assert.equal(init.headers['x-jarvis-token'],undefined);return Response.json({full_name:'Hubercik97/jarvis-android',default_branch:'main',open_issues_count:2});};
 assert.equal((await call('/developer/project?repo=..%2Frepo')).status,400);
 const res=await call('/developer/project?repo=Hubercik97%2Fjarvis-android');assert.equal(res.status,200);assert.equal((await res.json()).mode,'read-only');
});
