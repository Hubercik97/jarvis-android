import test from 'node:test';import assert from 'node:assert/strict';
import {ownerPlan} from './tools/prepare-owner.mjs';
const uuid='12345678-1234-1234-1234-123456789abc',routes=['/health','/chat','/stt'];
const input={settings:{bindings:[]},secretNames:[],databases:[],routes};
test('empty production Worker produces an explicit blocked plan, never dummy credentials or database identifiers',()=>{
 const plan=ownerPlan(input);assert.equal(plan.config.name,'jarvis-android');assert.equal(plan.config.workers_dev,true);
 assert.equal(plan.config.main,'bundle/entry.js');assert.equal(plan.report.readyToDeploy,false);assert.deepEqual(plan.report.missing,['DB','APP_TOKEN','OPENAI_API_KEY']);
 assert.equal(plan.config.d1_databases,undefined);assert.equal(plan.report.productionChanged,false);
});
test('verified existing D1/KV and secrets are preserved while plaintext values never enter the package',()=>{
 const plan=ownerPlan({...input,settings:{bindings:[{type:'d1',name:'DB',id:uuid},{type:'kv_namespace',name:'MEMORY',namespace_id:'a'.repeat(32)},{type:'plain_text',name:'OTHER',text:'private-plaintext'},{type:'secret_text',name:'APP_TOKEN',text:'private-token'}]},databases:[{uuid,name:'existing'}],secretNames:['APP_TOKEN','OPENAI_API_KEY'],workersDevEnabled:true});
 assert.equal(plan.report.configurationComplete,true);assert.equal(plan.report.readyToDeploy,false);assert.equal(plan.config.keep_vars,true);assert.equal(plan.config.d1_databases[0].database_id,uuid);assert.equal(plan.config.kv_namespaces[0].id,'a'.repeat(32));
 assert.ok(!JSON.stringify(plan).includes('private-'));assert.equal(plan.report.migrationsApplied,false);
});
test('selected D1 must be verified and cannot replace existing bindings; unknown bindings fail closed',()=>{
 assert.throws(()=>ownerPlan({...input,selectedDatabaseId:uuid}),/verified/);
 assert.throws(()=>ownerPlan({...input,selectedDatabaseId:'unverified'}),/invalid/);
 assert.throws(()=>ownerPlan({...input,settings:{bindings:[{type:'durable_object_namespace',name:'EXISTING'}]}}),/preserve/);
 assert.throws(()=>ownerPlan({...input,settings:{bindings:[{type:'d1',name:'DB',id:uuid}]},databases:[{uuid,name:'existing'}],selectedDatabaseId:'23456789-1234-1234-1234-123456789abc'}),/replace/);
});
test('existing daily budget is retained; invalid budget values are never echoed',()=>{
 const plan=ownerPlan({...input,settings:{bindings:[{type:'plain_text',name:'DAILY_MODEL_CALL_LIMIT',text:'12'}]}});assert.equal(plan.config.vars.DAILY_MODEL_CALL_LIMIT,'12');
 assert.throws(()=>ownerPlan({...input,settings:{bindings:[{type:'plain_text',name:'DAILY_MODEL_CALL_LIMIT',text:'private-token'}]}}),e=>!e.message.includes('private-token'));
});
