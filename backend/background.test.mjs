import test,{afterEach} from 'node:test';import assert from 'node:assert/strict';
import worker,{resetRateLimit} from './worker.js';import {consumeTasks,dispatchTask,scheduledTasks} from './background.js';import {database} from './sqlite-fixture.mjs';
const original=globalThis.fetch;afterEach(()=>{globalThis.fetch=original;resetRateLimit();});
function client(){const DB=database();const sent=[];const env={DB,APP_TOKEN:'test-token',OPENAI_API_KEY:'test-key',TASK_QUEUE:{send:async body=>sent.push(body)},DAILY_MODEL_CALL_LIMIT:'20'};const call=(path,method='GET',body)=>worker.fetch(new Request('https://jarvis.test'+path,{method,headers:{'x-jarvis-token':env.APP_TOKEN},...(body?{body:JSON.stringify(body)}:{})}),env);return {DB,env,sent,call};}
test('queue dispatch returns 202 without running model and reserves only one dispatch lease',async()=>{
 const {call,sent}=client();globalThis.fetch=()=>{throw Error('must not call model in HTTP request');};
 const {id}=await(await call('/tasks','POST',{title:'Raport',kind:'business_draft'})).json();
 assert.equal((await call(`/tasks/${id}/run`,'POST')).status,202);assert.equal((await call(`/tasks/${id}/run`,'POST')).status,202);
 assert.deepEqual(sent,[{id}]);
});
test('consumer executes exactly once across duplicate delivery, after initiating client disconnects',async()=>{
 const {call,env,DB}=client();const {id}=await(await call('/tasks','POST',{title:'Raport',kind:'research'})).json();
 let calls=0,acks=0;const message=()=>({body:{id},ack:()=>acks++,retry:()=>assert.fail('retry not expected')});
 const runChat=async()=>{calls++;return Response.json({reply:'Raport zapisany.'});};
 await consumeTasks({messages:[message(),message()]},env,runChat);
 assert.equal(calls,1);assert.equal(acks,2);assert.equal(DB.sqlite.prepare('SELECT status FROM tasks WHERE id=?').get(id).status,'completed');
});
test('scheduled task waits until due and cancelled tasks are never dispatched',async()=>{
 const {call,env,DB,sent}=client();const scheduled=new Date(Date.now()+3600000).toISOString();
 const {id}=await(await call('/tasks','POST',{title:'Później',kind:'research',scheduled_at:scheduled})).json();
 await scheduledTasks(env);assert.equal(sent.length,0);
 DB.sqlite.prepare('UPDATE tasks SET scheduled_at=? WHERE id=?').run(new Date(Date.now()-1000).toISOString(),id);
 await scheduledTasks(env);assert.deepEqual(sent,[{id}]);
 await call(`/tasks/${id}/cancel`,'POST');let calls=0;
 await consumeTasks({messages:[{body:{id},ack(){},retry(){assert.fail();}}]},env,async()=>{calls++;return Response.json({reply:'x'});});assert.equal(calls,0);
});
test('queue send failure releases lease for retry; invalid delivery is acknowledged',async()=>{
 const {call,env,DB}=client();const {id}=await(await call('/tasks','POST',{title:'Raport',kind:'research'})).json();
 env.TASK_QUEUE.send=async()=>{throw Error('queue unavailable');};await assert.rejects(dispatchTask(env,id));
 assert.equal(DB.sqlite.prepare('SELECT dispatched_at FROM tasks WHERE id=?').get(id).dispatched_at,null);
 let ack=false;await consumeTasks({messages:[{body:{id:'invalid'},ack(){ack=true;}}]},env,()=>assert.fail());assert.ok(ack);
});
test('crashed runner fails explicitly without automatically repeating a paid operation',async()=>{
 const {call,env,DB}=client();const {id}=await(await call('/tasks','POST',{title:'Raport',kind:'research'})).json();
 DB.sqlite.prepare("UPDATE tasks SET status='running',updated_at=? WHERE id=?").run(new Date(Date.now()-3600000).toISOString(),id);
 await scheduledTasks(env);assert.equal(DB.sqlite.prepare('SELECT status FROM tasks WHERE id=?').get(id).status,'failed');
});
test('project memory survives updates with stable identity and supports deletion',async()=>{
 const {call}=client();const first=await(await call('/projects','POST',{name:'Jarvis',repository:'Hubercik97/jarvis-android',summary:'Desktop w budowie'})).json();
 assert.ok(first.id);const second=await(await call('/projects','POST',{name:'Jarvis Desktop',repository:'Hubercik97/jarvis-android',summary:'Testy przeszły'})).json();
 assert.equal(first.id,second.id);assert.equal(second.summary,'Testy przeszły');
 assert.equal((await(await call('/projects')).json()).projects.length,1);await call('/projects/'+first.id,'DELETE');assert.equal((await(await call('/projects')).json()).projects.length,0);
});
