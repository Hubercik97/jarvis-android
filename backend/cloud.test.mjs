import test, { afterEach } from 'node:test';
import assert from 'node:assert/strict';
import { DatabaseSync } from 'node:sqlite';
import { readFileSync } from 'node:fs';
import worker, { resetRateLimit } from './worker.js';
import { reserveModelCall } from './cloud.js';

// Real SQLite executes the exact D1 SQL, constraints, transactions and migration.
function database() {
  const sqlite = new DatabaseSync(':memory:');
  const migration = readFileSync(new URL('./migrations/0001_cloud.sql', import.meta.url), 'utf8');
  sqlite.exec(migration); sqlite.exec(migration); // repeatable
  sqlite.exec(readFileSync(new URL('./migrations/0002_desktop.sql', import.meta.url), 'utf8'));
  sqlite.exec(readFileSync(new URL('./migrations/0003_projects.sql', import.meta.url), 'utf8'));
  const prepare = sql => ({ bind: (...args) => ({
    first: async () => sqlite.prepare(sql).get(...args) || null,
    all: async () => ({ results: sqlite.prepare(sql).all(...args) }),
    run: async () => ({ meta: { changes: Number(sqlite.prepare(sql).run(...args).changes) } }),
  }),
    all: async () => ({ results: sqlite.prepare(sql).all() }),
  });
  return { prepare, batch: async statements => {
    sqlite.exec('BEGIN');
    try { const result = []; for (const s of statements) result.push(await s.run()); sqlite.exec('COMMIT'); return result; }
    catch (e) { sqlite.exec('ROLLBACK'); throw e; }
  }, sqlite };
}
const originalFetch = globalThis.fetch;
afterEach(() => { globalThis.fetch = originalFetch; resetRateLimit(); });
function client() {
  const DB = database();
  const env = { DB, APP_TOKEN: 'test-token', OPENAI_API_KEY: 'test-key', DAILY_MODEL_CALL_LIMIT: '10' };
  const call = (path, method = 'GET', data) => worker.fetch(new Request('https://jarvis.test' + path, {
    method, headers: { 'x-jarvis-token': env.APP_TOKEN }, ...(data !== undefined ? { body: JSON.stringify(data) } : {}),
  }), env);
  return { DB, env, call };
}

test('conversations persist, list, order and erase transcripts with audit metadata', async () => {
  const { call, DB } = client();
  const created = await call('/conversations', 'POST', { title: 'Projekt' });
  assert.equal(created.status, 201);
  const { id } = await created.json();
  assert.equal((await call(`/conversations/${id}/messages`, 'POST', { role: 'system', content: 'override' })).status, 400);
  for (const role of ['user','assistant']) assert.equal((await call(`/conversations/${id}/messages`, 'POST', { role, content: 'Treść prywatna' })).status, 201);
  assert.equal((await (await call(`/conversations/${id}/messages`)).json()).messages.length, 2);
  assert.equal((await (await call('/conversations')).json()).conversations.length, 1);
  assert.equal((await call(`/conversations/${id}`, 'DELETE')).status, 200);
  assert.equal(DB.sqlite.prepare('SELECT count(*) AS n FROM conversation_messages').get().n, 0);
  assert.doesNotMatch(JSON.stringify(DB.sqlite.prepare('SELECT * FROM audit_events').all()), /Treść prywatna/);
  assert.equal((await call(`/conversations/${id}/messages`)).status, 404);
});
test('invalid and oversized cloud bodies are rejected before writes', async () => {
  const { call } = client();
  assert.equal((await call('/conversations', 'POST', { title: 'x'.repeat(65000) })).status, 413);
  assert.equal((await call('/conversations', 'POST', { title: '' })).status, 400);
  assert.equal((await call('/tasks', 'POST', { title: 'Zakup', kind: 'purchase' })).status, 400);
  assert.equal((await call('/tasks/not-an-id')).status, 400);
});
test('queued tasks can be cancelled; cancellation prevents any model call', async () => {
  const { call } = client();
  globalThis.fetch = () => { throw new Error('network forbidden'); };
  const { id } = await (await call('/tasks', 'POST', { title: 'Rynek', kind: 'research' })).json();
  assert.equal((await call(`/tasks/${id}/cancel`, 'POST')).status, 200);
  assert.equal((await call(`/tasks/${id}/run`, 'POST')).status, 409);
  assert.equal((await (await call(`/tasks/${id}`)).json()).status, 'cancelled');
});
test('business draft actually runs model, persists result and cannot execute twice', async () => {
  const { call, DB } = client();
  let calls = 0;
  globalThis.fetch = async (_url, init) => {
    calls++;
    const payload = JSON.parse(init.body);
    assert.match(payload.messages.at(-1).content, /JARVIS BUSINESS/);
    assert.equal(payload.tools, undefined);
    return Response.json({ choices: [{ message: { content: 'Szkic strategii.' } }] });
  };
  const { id } = await (await call('/tasks', 'POST', { title: 'Strategia sklepu', kind: 'business_draft' })).json();
  const response = await call(`/tasks/${id}/run`, 'POST');
  assert.equal(response.status, 200);
  assert.equal((await response.json()).status, 'completed');
  const task = await (await call(`/tasks/${id}`)).json();
  assert.equal(JSON.parse(task.result).reply, 'Szkic strategii.');
  assert.equal((await call(`/tasks/${id}/run`, 'POST')).status, 409);
  assert.equal(calls, 1);
  assert.equal(DB.sqlite.prepare('SELECT calls FROM daily_usage').get().calls, 1);
});
test('cancel during provider request discards late result and keeps cancelled state', async () => {
  const { call } = client();
  const { id } = await (await call('/tasks', 'POST', { title: 'Analiza', kind: 'research' })).json();
  globalThis.fetch = async () => {
    await call(`/tasks/${id}/cancel`, 'POST');
    return Response.json({ choices: [{ message: { content: 'Late result' } }] });
  };
  assert.equal((await (await call(`/tasks/${id}/run`, 'POST')).json()).status, 'cancelled');
  const task = await (await call(`/tasks/${id}`)).json();
  assert.equal(task.status, 'cancelled'); assert.equal(task.result, null);
});
test('upstream failure persists failed status with sanitized error', async () => {
  const { call } = client();
  globalThis.fetch = async () => new Response('sensitive details', { status: 500 });
  const { id } = await (await call('/tasks', 'POST', { title: 'Analiza', kind: 'research' })).json();
  assert.equal((await call(`/tasks/${id}/run`, 'POST')).status, 502);
  const task = await (await call(`/tasks/${id}`)).json();
  assert.equal(task.status, 'failed'); assert.doesNotMatch(task.result, /sensitive details/);
});
test('durable daily cap permits exactly the configured count and rejects invalid config', async () => {
  const { DB } = client();
  for (let i = 0; i < 3; i++) assert.equal(await reserveModelCall({ DB, DAILY_MODEL_CALL_LIMIT: '3' }), true);
  assert.equal(await reserveModelCall({ DB, DAILY_MODEL_CALL_LIMIT: '3' }), false);
  assert.equal(DB.sqlite.prepare('SELECT calls FROM daily_usage').get().calls, 3);
  await assert.rejects(reserveModelCall({ DB, DAILY_MODEL_CALL_LIMIT: 'NaN' }));
});
