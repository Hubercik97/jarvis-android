export type VisualState='IDLE'|'LISTENING'|'THINKING'|'SPEAKING'|'ERROR';
export type SignalFrame={source:'none'|'microphone'|'tts';available:boolean;rms:number;peak:number;bands:number[];wave:number[]};
export const silentFrame=():SignalFrame=>({source:'none',available:false,rms:0,peak:0,bands:Array(24).fill(0),wave:Array(48).fill(0)});
export function measureSignal(wave:Float32Array,frequency:Uint8Array,source:SignalFrame['source']):SignalFrame{
 let sum=0,peak=0;for(const value of wave){sum+=value*value;peak=Math.max(peak,Math.abs(value));}
 const bands=Array.from({length:24},(_,i)=>{const start=Math.floor(i*frequency.length/24),end=Math.max(start+1,Math.floor((i+1)*frequency.length/24));let total=0;for(let j=start;j<end;j++)total+=frequency[j]/255;return total/(end-start);});
 return {source,available:true,rms:Math.sqrt(sum/Math.max(1,wave.length)),peak,bands,wave:Array.from({length:48},(_,i)=>wave[Math.min(wave.length-1,Math.floor(i*wave.length/48))]||0)};
}
/** Samples the actual Web Audio graph. No timer-driven imitation of speech. */
export class AudioSampler{
 readonly context:AudioContext;readonly source:SignalFrame['source'];readonly analyser:AnalyserNode;private wave:Float32Array<ArrayBuffer>;private frequency:Uint8Array<ArrayBuffer>;private input:AudioNode;
 constructor(context:AudioContext,input:AudioNode,source:SignalFrame['source'],playback=false){
  this.context=context;this.source=source;
  this.input=input;this.analyser=context.createAnalyser();this.analyser.fftSize=512;this.analyser.smoothingTimeConstant=.45;
  this.wave=new Float32Array(this.analyser.fftSize);this.frequency=new Uint8Array(this.analyser.frequencyBinCount);
  input.connect(this.analyser);if(playback)this.analyser.connect(context.destination);
 }
 sample():SignalFrame{this.analyser.getFloatTimeDomainData(this.wave);this.analyser.getByteFrequencyData(this.frequency);return measureSignal(this.wave,this.frequency,this.source);}
 dispose(){this.input.disconnect();this.analyser.disconnect();}
}
export async function playbackSampler(element:HTMLMediaElement){const context=new AudioContext();let sampler:AudioSampler|null=null;try{sampler=new AudioSampler(context,context.createMediaElementSource(element),'tts',true);await context.resume();return sampler;}catch(error){sampler?.dispose();await context.close();throw error;}}
