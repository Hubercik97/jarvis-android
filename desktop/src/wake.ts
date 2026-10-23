import type {Bridge} from '../shared/types';
export class WakeMicrophone{
 private stream:MediaStream|null=null;private context:AudioContext|null=null;private node:AudioWorkletNode|null=null;private generation=0;
 constructor(private bridge:Bridge,private failure:(message:string)=>void){}
 async start(deviceId:string){
  this.stop();const generation=this.generation;
  try{
   const stream=await navigator.mediaDevices.getUserMedia({audio:{...(deviceId?{deviceId:{exact:deviceId}}:{}),channelCount:1,echoCancellation:true,noiseSuppression:true},video:false});
   if(generation!==this.generation){stream.getTracks().forEach(t=>t.stop());return;}
   this.stream=stream;
   stream.getAudioTracks()[0].onended=()=>{if(generation===this.generation){this.stop();this.failure('Utracono dostęp do mikrofonu. Wybierz urządzenie i włącz nasłuch ponownie.');}};
   stream.getAudioTracks()[0].onmute=()=>{if(generation===this.generation){this.stop();this.failure('Windows wstrzymał mikrofon. Sprawdź urządzenie i włącz nasłuch ponownie.');}};
   const ctx=this.context=new AudioContext({sampleRate:16000});
   if(ctx.sampleRate!==16000)throw new Error('Mikrofon nie obsługuje wymaganego próbkowania 16 kHz.');
   await ctx.audioWorklet.addModule(new URL('../public/wake-capture.js',import.meta.url).href);
   if(generation!==this.generation)return;
   const node=this.node=new AudioWorkletNode(ctx,'jarvis-wake-capture');
   node.port.onmessage=event=>{if(generation===this.generation)this.bridge.wakePcm(event.data);};
   const muted=ctx.createGain();muted.gain.value=0;
   ctx.createMediaStreamSource(stream).connect(node).connect(muted).connect(ctx.destination);
   await ctx.resume();
   ctx.onstatechange=()=>{if(generation===this.generation&&ctx.state!=='running'){this.stop();this.failure('System wstrzymał przetwarzanie mikrofonu. Włącz nasłuch ponownie.');}};
   if(generation===this.generation)await this.bridge.wakeControl('ready');
  }catch(error){if(generation===this.generation){this.stop();this.failure(error instanceof Error?error.message:'Mikrofon niedostępny.');}}
 }
 stop(){this.generation++;if(this.node){this.node.port.onmessage=null;this.node.disconnect();this.node=null;}this.stream?.getTracks().forEach(t=>{t.onended=null;t.onmute=null;t.stop();});this.stream=null;if(this.context)this.context.onstatechange=null;void this.context?.close();this.context=null;}
}
