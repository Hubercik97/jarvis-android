// Provider adapters preserve the existing Android/tool protocol.
export const OPENAI_MODEL = 'gpt-4.1-mini';
export function providerConfig(env) {
  // Preserve existing installations that only have an Anthropic binding.
  const provider = env.AI_PROVIDER || (env.OPENAI_API_KEY || !env.ANTHROPIC_API_KEY ? 'openai' : 'anthropic');
  if (!['openai', 'anthropic'].includes(provider)) throw new Error('Nieznany dostawca AI');
  return {
    provider,
    key: provider === 'openai' ? env.OPENAI_API_KEY : env.ANTHROPIC_API_KEY,
    model: env.MODEL || (provider === 'openai' ? OPENAI_MODEL : 'claude-haiku-5-5'),
  };
}

export function openaiPayload(payload) {
  const messages = [{ role: 'system', content: payload.system }];
  for (const m of payload.messages) {
    if (typeof m.content === 'string') { messages.push(m); continue; }
    const blocks = m.content || [];
    const results = blocks.filter(b => b.type === 'tool_result');
    if (results.length) {
      for (const b of results) messages.push({ role: 'tool', tool_call_id: b.tool_use_id, content: b.content });
      continue;
    }
    const calls = blocks.filter(b => b.type === 'tool_use');
    const content = blocks.flatMap(b => {
      if (b.type === 'text') return [{ type: 'text', text: b.text }];
      if (b.type === 'image') return [{ type: 'image_url', image_url: { url: `data:${b.source.media_type};base64,${b.source.data}` } }];
      return [];
    });
    messages.push({ role: m.role, content: content.length ? content : null,
      ...(calls.length ? { tool_calls: calls.map(b => ({ id: b.id, type: 'function', function: { name: b.name, arguments: JSON.stringify(b.input) } })) } : {}) });
  }
  return {
    model: payload.model, messages, max_completion_tokens: payload.max_tokens,
    ...(payload.tools?.length ? {
      tools: payload.tools.map(t => ({ type: 'function', function: { name: t.name, description: t.description, parameters: t.input_schema } })),
      ...(payload.tool_choice?.type === 'none' ? { tool_choice: 'none' } : {}),
      parallel_tool_calls: false,
    } : {}),
  };
}

export function normalizeOpenai(data) {
  const message = data?.choices?.[0]?.message;
  if (!message || typeof message !== 'object') throw new Error('Invalid model response');
  const content = [];
  if (typeof message.content === 'string' && message.content.trim()) content.push({ type: 'text', text: message.content });
  for (const call of (message.tool_calls || [])) {
    const input = JSON.parse(call.function.arguments);
    if (!input || typeof input !== 'object' || Array.isArray(input) || typeof call.id !== 'string') throw new Error('Invalid tool call');
    content.push({ type: 'tool_use', id: call.id, name: call.function.name, input });
  }
  return { content, usage: { input_tokens: data.usage?.prompt_tokens || 0, output_tokens: data.usage?.completion_tokens || 0 } };
}

export async function callOpenai(apiKey, payload) {
  // No retry: an ambiguous network error may already have consumed a paid completion.
  const response = await fetch('https://api.openai.com/v1/chat/completions', {
    method: 'POST', signal: AbortSignal.timeout(25_000),
    headers: { 'content-type': 'application/json', authorization: `Bearer ${apiKey}` },
    body: JSON.stringify(openaiPayload(payload)),
  });
  if (!response.ok) return response;
  return Response.json(normalizeOpenai(await response.json()));
}
