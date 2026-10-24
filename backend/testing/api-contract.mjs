import assert from 'node:assert/strict';
import {startLocalWorker} from './local-worker.mjs';
const server=await startLocalWorker();
try{
 const call=(route,method='GET',body,token=server.token)=>fetch(server.origin+route,{method,headers:{...(token?{'x-jarvis-token':token}:{}),...(body?{'content-type':'application/json'}:{})},...(body?{body:JSON.stringify(body)}:{})});
 let response=await call('/health');const health=await response.json();
 assert.equal(response.status,200);assert.match(response.headers.get('content-type'),/application\/json/);
 assert.equal(health.auth,'ok');assert.equal(health.configured,true);assert.equal(health.capabilities.history,true);assert.equal(health.stt,true);
 assert.equal((await(await call('/health','GET',undefined,'wrong-token')).json()).auth,'invalid');
 for(const route of ['/chat','/stt','/tts'])assert.equal((await call(route,'POST',{},'wrong-token')).status,401);
 assert.deepEqual(server.calls,{stt:0,chat:0});assert.equal(server.tts.length,0);
 assert.match(await(await call('/')).text(),/<!doctype html/i,'static assets must coexist with JSON /health');
 for(const route of ['/chat','/stt','/tts'])assert.equal((await call(route)).status,405);
 const {id}=await(await call('/conversations','POST',{title:'Integracyjny test głosu'})).json();
 response=await call('/stt','POST',{audio:Buffer.alloc(512,1).toString('base64'),mime:'audio/webm'});
 assert.equal(response.status,200);const {text}=await response.json();assert.equal(text,'Przygotuj plan projektu');
 await call(`/conversations/${id}/messages`,'POST',{role:'user',content:text});
 response=await call('/chat','POST',{messages:[{role:'user',content:text}]});
 assert.equal(response.status,200);const {reply}=await response.json();assert.equal(reply,'Plan testowy jest gotowy.');
 await call(`/conversations/${id}/messages`,'POST',{role:'assistant',content:reply});
 const transcript=await(await call(`/conversations/${id}/messages`)).json();
 assert.deepEqual(transcript.messages.map(m=>({role:m.role,content:m.content})),[{role:'user',content:text},{role:'assistant',content:reply}]);
 response=await call('/tts','POST',{text:reply});assert.equal(response.status,200);assert.equal((await response.json()).format,'mp3');
 assert.deepEqual(server.calls,{stt:1,chat:1});assert.deepEqual(server.tts,[reply]);
 assert.equal((await server.db.prepare('SELECT calls FROM daily_usage').first()).calls,3);
 console.log('PASS: actual workerd + bundled entry.js + static assets + local D1/KV; /health JSON, APP_TOKEN rejection, Android /chat contract, Polish /stt, TTS, transcript and durable budget. Only paid providers substituted; no external calls.');
}finally{await server.close();}
