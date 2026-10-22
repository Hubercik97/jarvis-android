const test=require('node:test');const assert=require('node:assert/strict');
const {endpointOf,repositoryOf,actionOf,apiPath}=require('../dist-electron/electron/policy.cjs');
test('cloud endpoint requires HTTPS and rejects credentials, alternate paths and private IPs',()=>{
 assert.equal(endpointOf('https://jarvis.example.com/'),'https://jarvis.example.com');
 for(const url of ['http://example.com','https://user:pass@example.com','https://example.com/redirect','https://example.com?key=x','https://127.0.0.1','https://intranet.local','https://[::1]'])assert.throws(()=>endpointOf(url));
 assert.equal(endpointOf('http://127.0.0.1:8787',true),'http://127.0.0.1:8787');
});
test('control has a closed allowlist and rejects arbitrary commands and paths',()=>{
 for(const action of ['open_chrome','open_documents','open_project'])assert.equal(actionOf(action),action);
 for(const action of ['delete_file','send_email','powershell','cmd.exe','C:\\Users','open_url',{action:'open_chrome'}])assert.throws(()=>actionOf(action));
});
test('GitHub repository cannot become an arbitrary URL or shell argument',()=>{
 assert.equal(repositoryOf('Hubercik97/jarvis-android'),'Hubercik97/jarvis-android');
 for(const repo of ['https://evil.com','owner/repo;calc','../repo','owner/..','owner/repo?token=x','owner/repo/tree/main'])assert.throws(()=>repositoryOf(repo));
});
test('IPC API cannot turn the main process into an arbitrary authenticated proxy',()=>{
 for(const [p,m] of [['/chat','POST'],['/health','GET'],['/tasks','GET'],['/stt','POST'],['/developer/project?repo=Hubercik97%2Fjarvis-android','GET']])assert.equal(apiPath(p,m),p);
 for(const p of ['https://evil.com','//evil.com','/chat/../../secret','/secret','/health?redirect=https://evil.com','/tasks\\..\\secret','/developer/project?repo=x&url=evil'])assert.throws(()=>apiPath(p,'GET'));
 assert.throws(()=>apiPath('/health','POST'));assert.throws(()=>apiPath('/memory','PUT'));
});
