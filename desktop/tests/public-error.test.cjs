const test=require('node:test'),assert=require('node:assert/strict');const {publicError}=require('../dist-electron/electron/public-error.cjs');
test('IPC/public errors discard wrapper and redact known, encoded and common API tokens',()=>{
 const secret='private-app-token-123456789';const message=publicError(new Error("Error invoking remote method 'control': Error: "+secret+' sk-test1234567890 ghp_abcdef0123456789'),[secret]);
 assert.doesNotMatch(message,/private-app-token|sk-test|ghp_|invoking/);assert.match(message,/UKRYTO/);
 assert.doesNotMatch(publicError(new Error(encodeURIComponent('token with spaces 12345')),['token with spaces 12345']),/token%20/);
});
