const {test}=require('node:test');const assert=require('node:assert/strict');
const load=()=>import('../src/wake-session.ts');
const deferred=()=>{let resolve;const promise=new Promise(r=>resolve=r);return {promise,resolve};};
test('wake waits for actual acknowledgement completion and speaker-tail silence before command capture',async()=>{
 const {WakeSession}=await load();const ack=deferred(),command=deferred(),events=[];
 const session=new WakeSession({stage:s=>events.push(s),speak:()=>{events.push('tts');return ack.promise;},listen:()=>{events.push('capture');return command.promise;},rearm:async()=>events.push('rearmed'),wait:async ms=>events.push(ms)});
 const turn=session.activate(true);await new Promise(r=>setImmediate(r));
 assert.deepEqual(events,['flash',450,'acknowledging','tts']);
 await session.activate(true);assert.equal(events.filter(e=>e==='tts').length,1);
 ack.resolve();await new Promise(r=>setImmediate(r));assert.deepEqual(events.slice(-3),[600,'listening','capture']);
 assert.ok(!events.includes('rearmed'));command.resolve();await turn;
 assert.deepEqual(events.slice(-3),['idle',600,'rearmed']);assert.equal(session.active,false);
});
test('disable during acknowledgement prevents recording and automatic rearm',async()=>{
 const {WakeSession}=await load();const ack=deferred(),events=[];
 const session=new WakeSession({stage:s=>events.push(s),speak:()=>ack.promise,listen:async()=>events.push('capture'),rearm:async()=>events.push('rearmed'),wait:async()=>{}});
 const turn=session.activate(true);await new Promise(r=>setImmediate(r));session.cancel();ack.resolve();await turn;
 assert.ok(!events.includes('capture'));assert.ok(!events.includes('rearmed'));assert.equal(session.active,false);
});
test('confirmation disabled skips speech, and failed command re-arms after safe cooldown',async()=>{
 const {WakeSession}=await load();const events=[];
 const session=new WakeSession({stage:s=>events.push(s),speak:async()=>events.push('tts'),listen:async()=>{throw new Error('microphone lost');},rearm:async()=>events.push('rearmed'),wait:async ms=>events.push(ms)});
 await assert.rejects(session.activate(false),/microphone lost/);assert.ok(!events.includes('tts'));assert.deepEqual(events.slice(-3),['idle',600,'rearmed']);
});
test('manual interruption during final speaker-tail cooldown cancels pending rearm',async()=>{
 const {WakeSession}=await load();const tail=deferred(),events=[];
 const session=new WakeSession({stage:s=>events.push(s),speak:async()=>{},listen:async()=>{},rearm:async()=>events.push('rearmed'),wait:async ms=>{if(ms===600)await tail.promise;}});
 const turn=session.activate(false);await new Promise(r=>setImmediate(r));assert.equal(session.active,true);
 session.cancel();tail.resolve();await turn;assert.ok(!events.includes('rearmed'));assert.equal(session.active,false);
});
