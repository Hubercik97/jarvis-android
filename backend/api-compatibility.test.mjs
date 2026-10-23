import test from 'node:test';
import assert from 'node:assert/strict';
import worker from './worker.js';
import {probeApi,inspectWorkerSettings} from './tools/probe-api.mjs';
const origin='https://jarvis-android.lunvra-golf.workers.dev';
test('reserved API routes return method errors instead of SPA HTML while preserving Android POST auth',async()=>{
 const env={APP_TOKEN:'private-app-token',ASSETS:{fetch:()=>new Response('<html>SPA</html>',{headers:{'content-type':'text/html'}})}};
 for(const route of ['/chat','/tts','/stt']){
  const res=await worker.fetch(new Request(origin+route,{headers:{'x-jarvis-token':env.APP_TOKEN}}),env);assert.equal(res.status,405);assert.equal(res.headers.get('allow'),'POST');assert.ok((await res.json()).error);
  const post=await worker.fetch(new Request(origin+route,{method:'POST',body:'{}'}),env);assert.equal(post.status,401);
 }
 assert.equal((await worker.fetch(new Request(origin),env)).headers.get('content-type'),'text/html');
});
test('Cloudflare configuration audit reads only binding presence and never emits values or identifiers',async()=>{
 const result=await inspectWorkerSettings('private-cloud-token','private-account',async(url,options)=>{
  assert.ok(url.endsWith('/workers/scripts/jarvis-android/settings'));assert.equal(options.method,'GET');
  return Response.json({success:true,result:{bindings:[{name:'APP_TOKEN',type:'secret_text',value:'private-app'},{name:'DB',type:'d1',id:'private-db'},{name:'OTHER',type:'plain_text',text:'private-value'}]}});
 });
 assert.equal(result.appTokenSecret,true);assert.equal(result.db,true);assert.equal(result.openaiSecret,false);assert.ok(!JSON.stringify(result).includes('private'));
});
test('health advertises actual bindings without falsely declaring storage available',async()=>{
 const base={APP_TOKEN:'private-app-token',OPENAI_API_KEY:'private-provider-key'};
 for(const db of [undefined,{}]){
  const body=await(await worker.fetch(new Request(origin+'/health',{headers:{'x-jarvis-token':base.APP_TOKEN}}),{...base,DB:db})).json();
  assert.equal(body.api_version,1);assert.equal(body.auth,'ok');assert.equal(body.capabilities.history,Boolean(db));assert.equal(body.capabilities.stt,true);
  assert.ok(!JSON.stringify(body).includes('private-'));
 }
});
test('public probe never sends credentials to a static or incompatible Worker',async()=>{
 let calls=0;
 const report=await probeApi(origin,'sensitive-app-token',async(_url,options)=>{calls++;assert.equal(options.method,'GET');assert.equal(options.headers['x-jarvis-token'],undefined);return new Response('<html>SPA</html>',{headers:{'content-type':'text/html'}});});
 assert.equal(calls,8);assert.equal(report.checks[0].html,true);assert.ok(!JSON.stringify(report).includes('sensitive'));
 await assert.rejects(probeApi('https://other.example','sensitive'),/wyłącznie/);
});
