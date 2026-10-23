const test=require('node:test'),assert=require('node:assert/strict'),vm=require('node:vm'),fs=require('node:fs');
function capture(){let Processor;const sent=[];const scope={AudioWorkletProcessor:class{port={postMessage:m=>sent.push(m),onmessage:null};},currentTime:0,Float32Array,registerProcessor:(_name,p)=>Processor=p};vm.runInNewContext(fs.readFileSync(require('node:path').resolve(__dirname,'../public/wake-capture.js'),'utf8'),scope);const node=new Processor();const feed=n=>{for(let frame=0;frame<n;frame++){scope.currentTime+=.08;for(let i=0;i<10;i++)node.process([[new Float32Array(128).fill(.02)]]);}};return {node,sent,feed};}
test('worklet waits for processing credit and buffers a temporary UI stall without flooding IPC',()=>{
 const {node,sent,feed}=capture();feed(8);assert.equal(sent.length,1);assert.equal(node.queue.length,7);
 for(let i=0;i<7;i++)node.port.onmessage({data:{type:'ack'}});
 assert.equal(sent.length,8);assert.ok(sent.every(m=>m.samples.length===1280&&!m.reset));assert.ok(sent[1].bufferedMs>=400);
});
test('worklet retains at most 1.6s and declares a gap after a real prolonged stall',()=>{
 const {node,sent,feed}=capture();feed(1000);assert.equal(sent.length,1);assert.ok(node.queue.length<=20);
 node.port.onmessage({data:{type:'ack'}});assert.equal(sent.length,2);assert.equal(sent[1].reset,true);assert.ok(sent[1].discardedFrames>=980);
});
