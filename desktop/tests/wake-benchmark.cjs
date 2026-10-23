// Realtime, silence-only comparison: no microphone, recording, cloud or token.
const {Worker}=require('node:worker_threads'),path=require('node:path');
const wait=(w,p)=>new Promise((resolve,reject)=>{const timer=setTimeout(()=>{w.off('message',on);reject(new Error('benchmark timeout'));},10000);function on(m){if(m.type==='error'){clearTimeout(timer);w.off('message',on);reject(new Error('worker failed'));}else if(p(m)){clearTimeout(timer);w.off('message',on);resolve(m);}}w.on('message',on);});
async function measure(quietGate){
 const worker=new Worker(path.resolve(__dirname,'../dist-electron/electron/wake-worker.cjs'),{workerData:{directory:path.resolve(__dirname,'../resources/wake'),quietGate}});
 try{
  await wait(worker,m=>m.type==='ready');worker.postMessage({type:'reset',epoch:1});const rows=[];
  let deadline=performance.now();
  for(let frame=1;frame<=100;frame++){
   deadline+=80;await new Promise(r=>setTimeout(r,Math.max(0,deadline-performance.now())));
   const response=wait(worker,m=>m.type==='score'&&m.frame===frame);worker.postMessage({type:'pcm',samples:new Float32Array(1280).buffer,epoch:1,frame});rows.push(await response);
  }
  const steady=rows.slice(50),times=rows.map(r=>r.ms).sort((a,b)=>a-b),cpu=steady.map(r=>r.workerThreadCpuPct).filter(n=>typeof n==='number');
  return {quietGate,frames:rows.length,neuralFrames:rows.filter(r=>!r.quietSkipped).length,quietSkipped:rows.filter(r=>r.quietSkipped).length,meanMs:rows.reduce((s,r)=>s+r.ms,0)/rows.length,p95Ms:times[Math.floor(times.length*.95)],steadyThreadCpuPct:cpu.length?cpu.reduce((s,n)=>s+n,0)/cpu.length:null,workerJsHeapMiB:rows.at(-1).workerJsHeapMiB,mainProcessRssMiB:rows.at(-1).mainProcessRssMiB};
 }finally{await worker.terminate();}
}
(async()=>{require('onnxruntime-node');const baseline=await measure(false),optimized=await measure(true);console.log(JSON.stringify({machine:{cpu:require('node:os').cpus()[0]?.model,node:process.version,platform:process.platform},scenario:'8 s real-time silence; cloud/CI CPU, not a physical Celeron N4000 measurement',baseline,optimized},null,2));})().catch(e=>{console.error(e.message);process.exitCode=1;});
