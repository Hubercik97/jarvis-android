// One turn at a time. The detector stays paused through acknowledgement,
// speaker-tail cooldown, command transcription, AI execution and reply playback.
export type WakeStage='idle'|'flash'|'acknowledging'|'listening';
export class WakeSession{
 private generation=0;private running=false;
 private dependencies:{stage:(value:WakeStage)=>void;speak:()=>Promise<void>;listen:()=>Promise<void>;rearm:()=>Promise<unknown>;wait?:(ms:number)=>Promise<void>};
 constructor(dependencies:WakeSession['dependencies']){this.dependencies=dependencies;}
 get active(){return this.running;}
 cancel(){this.generation++;this.running=false;this.dependencies.stage('idle');}
 async activate(confirmation:boolean){
  if(this.running)return;
  const generation=++this.generation;this.running=true;
  const current=()=>generation===this.generation;
  const wait=this.dependencies.wait??(ms=>new Promise(resolve=>setTimeout(resolve,ms)));
  try{
   this.dependencies.stage('flash');await wait(450);if(!current())return;
   if(confirmation){this.dependencies.stage('acknowledging');await this.dependencies.speak();if(!current())return;await wait(600);if(!current())return;}
   this.dependencies.stage('listening');await this.dependencies.listen();
  }finally{
   if(current()){
    this.dependencies.stage('idle');
    try{await wait(600);if(current())await this.dependencies.rearm();}
    finally{if(current())this.running=false;}
   }
  }
 }
}
