// Credit-based, bounded AudioWorklet. A frame leaves only after the previous
// frame has been processed, not merely delivered to Electron's IPC queue.
class WakeCapture extends AudioWorkletProcessor{
 constructor(){
  super();this.buffer=new Float32Array(1280);this.offset=0;this.credit=true;this.queue=[];this.discarded=0;this.gap=false;
  this.port.onmessage=event=>{if(event.data?.type==='ack'){this.credit=true;this.flush();}};
 }
 flush(){
  if(!this.credit||!this.queue.length)return;this.credit=false;
  const next=this.queue.shift();const message={samples:next.samples,bufferedMs:Math.max(0,(currentTime-next.at)*1000),discardedFrames:this.discarded,reset:this.gap};
  this.discarded=0;this.gap=false;this.port.postMessage(message,[next.samples.buffer]);
 }
 process(inputs){
  const input=inputs[0]?.[0];if(!input)return true;
  for(let i=0;i<input.length;i++){
   this.buffer[this.offset++]=input[i];
   if(this.offset===1280){
    // 1.6 s maximum retained audio. An actual gap resets neural phrase evidence.
    if(this.queue.length>=20){this.discarded+=this.queue.length;this.queue=[];this.gap=true;}
    this.queue.push({samples:this.buffer,at:currentTime});this.buffer=new Float32Array(1280);this.offset=0;this.flush();
   }
  }
  return true;
 }
}
registerProcessor('jarvis-wake-capture',WakeCapture);
