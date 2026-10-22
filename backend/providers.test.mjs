import test, { afterEach } from 'node:test';
import assert from 'node:assert/strict';
import worker, { resetRateLimit } from './worker.js';
import { providerConfig, openaiPayload, normalizeOpenai } from './providers.js';
const originalFetch = globalThis.fetch;
afterEach(() => { globalThis.fetch = originalFetch; resetRateLimit(); });
const env = { APP_TOKEN: 'test-token', OPENAI_API_KEY: 'test-key' };
const request = (body = {}) => new Request('https://jarvis.test/chat', { method: 'POST', headers: { 'x-jarvis-token': env.APP_TOKEN }, body: JSON.stringify({ messages: [{ role: 'user', content: 'Cześć' }], ...body }) });

test('OpenAI is primary, legacy Anthropic installations remain compatible', () => {
  assert.equal(providerConfig({}).provider, 'openai');
  assert.equal(providerConfig({ ANTHROPIC_API_KEY: 'legacy' }).provider, 'anthropic');
  assert.equal(providerConfig({ ...env, ANTHROPIC_API_KEY: 'legacy' }).provider, 'openai');
  assert.equal(providerConfig({ ...env, AI_PROVIDER: 'anthropic' }).provider, 'anthropic');
  assert.throws(() => providerConfig({ AI_PROVIDER: 'unknown' }));
});
test('OpenAI converts images, tool calls, and matching results without exposing system role from clients', () => {
  const data = openaiPayload({ model: 'test', max_tokens: 50, system: 'rules', messages: [
    { role: 'user', content: [{ type: 'image', source: { media_type: 'image/png', data: 'aGVq' } }, { type: 'text', text: 'photo' }] },
    { role: 'assistant', content: [{ type: 'tool_use', id: 'call_1', name: 'web_search', input: { query: 'test' } }] },
    { role: 'user', content: [{ type: 'tool_result', tool_use_id: 'call_1', content: 'result' }] },
  ], tools: [{ name: 'web_search', description: 'search', input_schema: { type: 'object' } }], tool_choice: { type: 'none' } });
  assert.equal(data.messages[1].content[0].image_url.url, 'data:image/png;base64,aGVq');
  assert.equal(data.messages[2].tool_calls[0].function.arguments, '{"query":"test"}');
  assert.equal(data.messages[3].tool_call_id, 'call_1');
  assert.equal(data.tool_choice, 'none');
  assert.equal(data.parallel_tool_calls, false);
});
test('OpenAI chat preserves Android reply and keeps API key server side', async () => {
  globalThis.fetch = async (url, init) => {
    assert.equal(url, 'https://api.openai.com/v1/chat/completions');
    assert.equal(init.headers.authorization, 'Bearer test-key');
    assert.equal(JSON.parse(init.body).model, 'gpt-4.1-mini');
    assert.ok(init.signal);
    return Response.json({ choices: [{ message: { content: 'Witaj.' } }], usage: { prompt_tokens: 20 } });
  };
  const response = await worker.fetch(request(), env);
  assert.equal(response.status, 200);
  assert.deepEqual(await response.json(), { reply: 'Witaj.' });
});
test('OpenAI tool proposals preserve local confirmation boundary', async () => {
  globalThis.fetch = async () => Response.json({ choices: [{ message: { content: null, tool_calls: [{ id: 'call_1', function: { name: 'set_timer', arguments: '{"seconds":60}' } }] } }] });
  const response = await worker.fetch(request({ tools: ['set_timer'] }), env);
  assert.deepEqual((await response.json()).actions, [{ id: 'call_1', tool: 'set_timer', args: { seconds: 60 } }]);
});
test('malformed tool arguments and missing model response fail closed', () => {
  assert.throws(() => normalizeOpenai({}));
  assert.throws(() => normalizeOpenai({ choices: [{ message: { tool_calls: [{ id: 'x', function: { name: 'x', arguments: '[]' } }] } }] }));
});
test('provider error does not expose secret details or trigger paid retries', async () => {
  let calls = 0;
  globalThis.fetch = async () => { calls++; return Response.json({ secret: 'provider-secret' }, { status: 401 }); };
  const response = await worker.fetch(request(), env);
  assert.equal(response.status, 502);
  assert.equal(calls, 1);
  assert.doesNotMatch(await response.text(), /provider-secret|test-key/);
});
test('invalid provider configuration rejects before network access', async () => {
  globalThis.fetch = () => { throw new Error('network must not be called'); };
  assert.equal((await worker.fetch(request(), { ...env, AI_PROVIDER: 'unknown' })).status, 503);
});
test('daily model cap rejects missing D1 rather than spending without accounting', async () => {
  globalThis.fetch = () => { throw new Error('network must not be called'); };
  assert.equal((await worker.fetch(request(), { ...env, DAILY_MODEL_CALL_LIMIT: '5' })).status, 503);
});
test('daily model cap exhausted returns 429 before provider request', async () => {
  globalThis.fetch = () => { throw new Error('network must not be called'); };
  const DB = { prepare: () => ({ bind: () => ({ first: async () => null }) }) };
  assert.equal((await worker.fetch(request(), { ...env, DB, DAILY_MODEL_CALL_LIMIT: '5' })).status, 429);
});
test('cloud endpoints require token and persistent storage', async () => {
  for (const path of ['/conversations', '/tasks']) {
    assert.equal((await worker.fetch(new Request('https://jarvis.test' + path), env)).status, 401);
    assert.equal((await worker.fetch(new Request('https://jarvis.test' + path, { headers: { 'x-jarvis-token': env.APP_TOKEN } }), env)).status, 503);
  }
});

// The server must not forward the newly supported provider key to web search.
test('OpenAI secrets are rejected in research queries', async () => {
  const { validateQuery } = await import('./research.js');
  assert.ok(validateQuery('search for sk-example123', { OPENAI_API_KEY: 'sk-example123' }).error);
});
