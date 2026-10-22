// Local-only functional check. Never invokes a paid model.
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
const token = process.env.APP_TOKEN || readFileSync(new URL('./.dev.vars', import.meta.url), 'utf8').match(/^APP_TOKEN=(.+)$/m)?.[1];
assert.ok(token, 'Local APP_TOKEN is required');
const base = 'http://127.0.0.1:8787';
const call = (path, method = 'GET', body) => fetch(base + path, { method,
  headers: { 'x-jarvis-token': token, ...(body ? { 'content-type': 'application/json' } : {}) },
  ...(body ? { body: JSON.stringify(body) } : {}),
});
assert.equal((await fetch(base + '/tasks')).status, 401);
const health = await (await call('/health')).json();
assert.equal(health.auth, 'ok');
assert.equal(health.configured, false, 'Run this test only without a real model key');
assert.equal((await fetch(base + '/')).status, 200);
assert.equal((await fetch(base + '/cloud-ui.js')).status, 200);
// A static asset deployment must not expose backend source or secrets.
for (const path of ['/worker.js', '/.dev.vars', '/package.json']) {
  const response = await fetch(base + path);
  assert.ok(response.status === 404 || (response.headers.get('content-type') || '').includes('text/html'));
}
const created = await call('/conversations', 'POST', { title: 'local-smoke' });
assert.equal(created.status, 201);
const { id } = await created.json();
assert.equal((await call(`/conversations/${id}/messages`, 'POST', { role: 'user', content: 'Test lokalny' })).status, 201);
assert.equal((await (await call(`/conversations/${id}/messages`)).json()).messages[0].content, 'Test lokalny');
assert.equal((await call(`/conversations/${id}`, 'DELETE')).status, 200);
assert.equal((await call(`/conversations/${id}/messages`)).status, 404);
const fact = await (await call('/memory','POST',{ text: 'local-smoke ' + crypto.randomUUID() })).json();
assert.ok(fact.fact.id);
assert.equal((await call('/memory/' + fact.fact.id, 'DELETE')).status, 200);
const task = await (await call('/tasks','POST',{ title: 'local-smoke', kind: 'business_draft' })).json();
assert.equal((await call(`/tasks/${task.id}/cancel`,'POST')).status, 200);
assert.equal((await call(`/tasks/${task.id}/run`,'POST')).status, 409);
assert.equal((await call('/chat','POST',{ messages: [{ role: 'user', content: 'test' }] })).status, 503);
console.log('PASS: real local Worker, auth, static assets, D1 conversations, KV facts, cancellation, missing-key fail-closed');
