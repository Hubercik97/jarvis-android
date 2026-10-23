const test=require('node:test'),assert=require('node:assert/strict');
const {decodeCloudResponse,CloudProtocolError}=require('../dist-electron/electron/cloud-protocol.cjs');
const decode=(status,body,type='application/json')=>decodeCloudResponse(status,type,body,true);
test('health recognizes legacy Android-compatible API and the extended desktop contract',()=>{
 for(const value of [{auth:'ok',configured:true},{ok:true,auth:'ok',configured:true,api_version:1,capabilities:{history:true}}])assert.deepEqual(decode(200,JSON.stringify(value)),value);
});
test('cloud diagnosis separates HTML, missing routes, missing APP_TOKEN and invalid auth without exposing response bodies',()=>{
 const cases=[
  [200,'<html>secret-token</html>','text/html','endpoint_not_api'],[404,'secret-token','text/plain','endpoint_not_api'],
  [401,'secret-token','text/plain','unauthorized'],[403,'<html>secret-token</html>','text/html','unauthorized'],
  [200,JSON.stringify({auth:'missing',configured:true}),'application/json','server_token_missing'],
  [200,JSON.stringify({auth:'invalid',configured:true}),'application/json','unauthorized'],
  [503,JSON.stringify({auth:'missing'}),'application/json','server_token_missing'],
  [502,'secret-token','text/plain','http_error'],[429,'secret-token','text/plain','http_error'],
  [200,'null','application/json','invalid_response'],[200,'[]','application/json','invalid_response'],
  [200,'{}','application/json','invalid_response'],[200,'garbage secret-token','text/plain','invalid_response'],
 ];
 for(const [status,body,type,state] of cases)assert.throws(()=>decode(status,body,type),e=>e instanceof CloudProtocolError&&e.state===state&&!e.message.includes('secret-token'),`${status}/${state}`);
});
