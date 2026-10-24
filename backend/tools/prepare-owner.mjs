// Read-only Cloudflare discovery + a local, reviewable deployment package.
// This file has no deployment, secret-write or resource-creation operation.
import {readFile,mkdir,writeFile,copyFile,cp} from 'node:fs/promises';
import {pathToFileURL,fileURLToPath} from 'node:url';
import path from 'node:path';
const NAME='jarvis-android',UUID=/^[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$/i;
export function ownerPlan({settings,secretNames,databases,selectedDatabaseId,workersDevEnabled,routes,sourceSecrets={}}){
 if(!Array.isArray(settings?.bindings)||!Array.isArray(secretNames))throw new Error('Read-only Worker metadata is incomplete.');
 const config={name:NAME,main:'bundle/entry.js',compatibility_date:'2026-10-01',workers_dev:true,keep_vars:true,
  observability:{enabled:false},assets:{directory:'public',binding:'ASSETS',run_worker_first:routes}};
 const d1=[],kv=[];
 for(const b of settings.bindings){
  if(['plain_text','json','secret_text','secret_key'].includes(b.type))continue; // keep_vars / preserved secrets, no values copied
  if(b.type==='assets'&&b.name==='ASSETS')continue;
  if(b.type==='d1'){
   const database=databases.find(d=>d.uuid===b.id);if(!database||!UUID.test(database.uuid))throw new Error('An existing D1 binding could not be verified.');
   d1.push({binding:b.name,database_name:database.name,database_id:database.uuid,migrations_dir:'migrations'});continue;
  }
  if(b.type==='kv_namespace'&&/^[a-f0-9]{32}$/i.test(b.namespace_id)){
   kv.push({binding:b.name,id:b.namespace_id});continue;
  }
  throw new Error('Existing unsupported binding: review and preserve it manually before packaging.');
 }
 const current=d1.find(d=>d.binding==='DB');
 if(selectedDatabaseId){
  if(!UUID.test(selectedDatabaseId))throw new Error('The selected existing D1 identifier is invalid.');
  if(current&&current.database_id!==selectedDatabaseId)throw new Error('Refusing to replace the existing DB binding.');
  if(!current){const database=databases.find(d=>d.uuid===selectedDatabaseId);if(!database)throw new Error('Selected existing D1 could not be verified.');d1.push({binding:'DB',database_name:database.name,database_id:database.uuid,migrations_dir:'migrations'});}
 }
 if(d1.length)config.d1_databases=d1;if(kv.length)config.kv_namespaces=kv;
 const limitBinding=settings.bindings.find(b=>b.name==='DAILY_MODEL_CALL_LIMIT');
 const limit=limitBinding?Number(limitBinding.text):30;
 if(!Number.isSafeInteger(limit)||limit<1)throw new Error('Existing daily budget configuration requires review.');
 config.vars={DAILY_MODEL_CALL_LIMIT:String(limit)};
 const missing=[];
 if(!d1.some(d=>d.binding==='DB'))missing.push('DB');
 for(const name of ['APP_TOKEN','OPENAI_API_KEY'])if(!secretNames.includes(name))missing.push(name);
 return {config,report:{worker:NAME,origin:'https://jarvis-android.lunvra-golf.workers.dev',readOnly:true,productionChanged:false,
  missing,sourceSecrets:{APP_TOKEN:sourceSecrets.APP_TOKEN===true,OPENAI_API_KEY:sourceSecrets.OPENAI_API_KEY===true},
  historyDatabaseSelected:d1.some(d=>d.binding==='DB'),memoryBound:kv.some(k=>k.binding==='MEMORY'),
  workersDevEnabled:typeof workersDevEnabled==='boolean'?workersDevEnabled:null,
  migrationsApplied:false,requiresMigrationReview:true,requiresOwnerApproval:true,configurationComplete:missing.length===0,readyToDeploy:false}};
}
export async function prepareOwner(env=process.env,fetcher=fetch){
 if(!env.CLOUDFLARE_API_TOKEN||!env.CLOUDFLARE_ACCOUNT_ID)throw new Error('Read-only Cloudflare access is unavailable.');
 const base='https://api.cloudflare.com/client/v4/accounts/'+encodeURIComponent(env.CLOUDFLARE_ACCOUNT_ID);
 async function get(route){
  const response=await fetcher(base+route,{method:'GET',headers:{authorization:'Bearer '+env.CLOUDFLARE_API_TOKEN},redirect:'error',signal:AbortSignal.timeout(15000)});
  if(!response.ok)throw new Error('Read-only Cloudflare metadata request failed.');
  const body=await response.json();if(body.success!==true)throw new Error('Cloudflare metadata could not be verified.');return body.result;
 }
 const script='/workers/scripts/'+NAME;
 const settings=await get(script+'/settings'),secrets=await get(script+'/secrets');
 if(!Array.isArray(secrets))throw new Error('Secret names could not be verified.');
 let workersDevEnabled=null;try{workersDevEnabled=(await get(script+'/subdomain')).enabled;}catch{}
 const ids=new Set((settings.bindings||[]).filter(b=>b.type==='d1').map(b=>b.id));
 if(env.JARVIS_D1_DATABASE_ID)ids.add(env.JARVIS_D1_DATABASE_ID);
 const databases=[];for(const id of ids){if(!UUID.test(id))throw new Error('Existing D1 identifier requires review.');const database=await get('/d1/database/'+id);databases.push(database);}
 const backend=new URL('../',import.meta.url),toml=await readFile(new URL('wrangler.toml',backend),'utf8');
 const routes=JSON.parse(toml.match(/run_worker_first\s*=\s*(\[[^\]]+\])/)[1]);
 const plan=ownerPlan({settings,secretNames:secrets.map(s=>s.name),databases,selectedDatabaseId:env.JARVIS_D1_DATABASE_ID,workersDevEnabled,routes,
  sourceSecrets:{APP_TOKEN:Boolean(env.JARVIS_SOURCE_APP_TOKEN),OPENAI_API_KEY:Boolean(env.JARVIS_SOURCE_OPENAI_KEY)}});
 const output=fileURLToPath(new URL('.wrangler/owner-review/',backend));await mkdir(path.join(output,'bundle'),{recursive:true});
 await copyFile(new URL('.wrangler/dry-run/entry.js',backend),path.join(output,'bundle/entry.js'));
 await cp(new URL('public/',backend),path.join(output,'public'),{recursive:true});
 await cp(new URL('migrations/',backend),path.join(output,'migrations'),{recursive:true});
 await writeFile(path.join(output,'wrangler.owner.json'),JSON.stringify(plan.config,null,2)+'\n');
 await writeFile(path.join(output,'review.json'),JSON.stringify(plan.report,null,2)+'\n');
 await writeFile(path.join(output,'README.txt'),`REVIEW ONLY — NO PRODUCTION DEPLOYMENT HAS OCCURRED.\nWorker: ${NAME}\nMissing configuration: ${plan.report.missing.join(', ')||'none detected'}\nThe owner must approve deployment and review existing D1 migrations. Never reset an existing database.\nMissing secrets must be configured in Cloudflare without logging their values; existing secrets must not be rotated.\nNo database, KV namespace, queue or paid service was created by preparation.\nOptional factual memory requires an existing MEMORY KV binding.\nReview only: npx wrangler@4.152.0 deploy --config wrangler.owner.json --dry-run\nDO NOT remove --dry-run until deployment is explicitly approved and blockers are resolved.\nDAILY_MODEL_CALL_LIMIT is a call count, not a 50 PLN monetary cap.\n`);
 return plan.report;
}
if(process.argv[1]&&import.meta.url===pathToFileURL(process.argv[1]).href){
 try{
  const report=await prepareOwner();console.log(JSON.stringify(report,null,2));
  if(process.env.GITHUB_OUTPUT)await writeFile(process.env.GITHUB_OUTPUT,'summary=Owner package: missing '+(report.missing.join(', ')||'none')+'; DB selected '+report.historyDatabaseSelected+'; workers.dev '+report.workersDevEnabled+'; production unchanged\n',{flag:'a'});
 }catch{console.error('Owner package preparation stopped: inspect access, existing bindings and D1 selection; no production changes were made.');process.exitCode=1;}
}
