// Runs the built Worker in real workerd with local D1/KV/assets. External paid
// providers are the ONLY substitutes; every outbound request is intercepted.
import {Miniflare,Log,LogLevel,convertV4MiniflareOptions} from 'miniflare';
import {readFile} from 'node:fs/promises';
import {fileURLToPath} from 'node:url';
import {randomUUID} from 'node:crypto';
import assert from 'node:assert/strict';
const backend=new URL('../',import.meta.url);
export async function startLocalWorker(){
 const token='local-worker-'+randomUUID(),key='provider-fixture-'+randomUUID();
 const calls={stt:0,chat:0},tts=[],outbound=[];
 const audio=(await readFile(new URL('./fixtures/tts-tone.mp3',import.meta.url))).toString('base64');
 const config=await readFile(new URL('wrangler.toml',backend),'utf8');
 const routes=JSON.parse(config.match(/run_worker_first\s*=\s*(\[[^\]]+\])/)[1]);
 const runtime=new Miniflare(convertV4MiniflareOptions({
  rootPath:fileURLToPath(new URL('../../',import.meta.url)),host:'127.0.0.1',port:0,log:new Log(LogLevel.ERROR),workers:[{
  name:'jarvis-local-contract',modules:true,scriptPath:fileURLToPath(new URL('.wrangler/dry-run/entry.js',backend)),
  compatibilityDate:'2026-10-01',
  bindings:{APP_TOKEN:token,OPENAI_API_KEY:key,GOOGLE_TTS_API_KEY:key,AI_PROVIDER:'openai',DAILY_MODEL_CALL_LIMIT:'20'},
  d1Databases:['DB'],kvNamespaces:['MEMORY'],
  assets:{directory:fileURLToPath(new URL('public/',backend)),binding:'ASSETS',run_worker_first:routes,routerConfig:{has_user_worker:true}},
  outboundService:async request=>{
   const url=new URL(request.url);outbound.push(url.hostname+url.pathname);
   if(url.origin==='https://api.openai.com'&&url.pathname==='/v1/audio/transcriptions'){
    assert.equal(request.headers.get('authorization'),'Bearer '+key);
    const body=await request.formData();assert.equal(body.get('language'),'pl');assert.ok(body.get('file').size>200);
    calls.stt++;return Response.json({text:'Przygotuj plan projektu'});
   }
   if(url.origin==='https://api.openai.com'&&url.pathname==='/v1/chat/completions'){
    assert.equal(request.headers.get('authorization'),'Bearer '+key);
    const body=await request.json();assert.equal(body.messages.at(-1).content,'Przygotuj plan projektu');
    calls.chat++;return Response.json({choices:[{message:{content:'Plan testowy jest gotowy.'}}]});
   }
   if(url.origin==='https://texttospeech.googleapis.com'&&url.pathname==='/v1/text:synthesize'){
    assert.equal(url.searchParams.get('key'),key);const body=await request.json();tts.push(body.input.text);
    return Response.json({audioContent:audio});
   }
   throw new Error('External network is forbidden in the local Worker integration test');
  },}],
 }));
 try{
  const origin=(await runtime.ready).origin,db=await runtime.getD1Database('DB');
  for(const name of ['0001_cloud.sql','0002_desktop.sql','0003_projects.sql']){
   const sql=await readFile(new URL('migrations/'+name,backend),'utf8');
   // D1 exec consumes complete statements per line; remove comments and join.
   const statements=sql.replace(/^\s*--.*$/gm,'').split(';').map(s=>s.trim().replace(/\s+/g,' ')).filter(Boolean);
   for(const statement of statements)await db.exec(statement);
  }
  return {origin,token,calls,tts,outbound,db,close:()=>runtime.dispose()};
 }catch(error){await runtime.dispose();throw error;}
}
