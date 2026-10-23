// AudioWorklet: bounded 80 ms frames; no timers, disk, or networking.
class WakeCapture extends AudioWorkletProcessor{
 constructor(){super();this.buffer=new Float32Array(1280);this.offset=0;}
 process(inputs){
  const input=inputs[0]?.[0];if(!input)return true;
  for(let i=0;i<input.length;i++){
   this.buffer[this.offset++]=input[i];
   if(this.offset===1280){this.port.postMessage(this.buffer,[this.buffer.buffer]);this.buffer=new Float32Array(1280);this.offset=0;}
  }
  return true;
 }
}
registerProcessor('jarvis-wake-capture',WakeCapture);
