// D1 storage is optional; never silently fall back to ephemeral memory.
const ID = /^[a-f0-9-]{36}$/;
const MAX_BODY = 64_000;
export async function readBody(request) {
  if (Number(request.headers.get('content-length')) > MAX_BODY) throw new Error('body_limit');
  const reader = request.body?.getReader();
  if (!reader) throw new Error('invalid_json');
  const chunks = [];
  let length = 0;
  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    length += value.byteLength;
    if (length > MAX_BODY) { await reader.cancel(); throw new Error('body_limit'); }
    chunks.push(value);
  }
  const bytes = new Uint8Array(length);
  let offset = 0;
  for (const chunk of chunks) { bytes.set(chunk, offset); offset += chunk.length; }
  const body = JSON.parse(new TextDecoder().decode(bytes));
  if (!body || typeof body !== 'object' || Array.isArray(body)) throw new Error('invalid_json');
  return body;
}
const titleOf = value => typeof value === 'string' && value.trim().length > 0 && value.trim().length <= 120 ? value.trim() : null;
export const audit = (db, event, id, now) => db.prepare('INSERT INTO audit_events (event, resource_id, created_at) VALUES (?, ?, ?)').bind(event, id, now);

// Reserve BEFORE every paid model call. Atomic even across Worker instances.
// Calls, not dollars: monetary enforcement must also be configured with the provider.
export async function reserveModelCall(env) {
  if (env.DAILY_MODEL_CALL_LIMIT === undefined) return true;
  const limit = Number(env.DAILY_MODEL_CALL_LIMIT);
  if (!Number.isSafeInteger(limit) || limit < 1 || !env.DB) throw new Error('budget_config');
  const day = new Date().toISOString().slice(0, 10);
  const row = await env.DB.prepare(`INSERT INTO daily_usage (day, calls) VALUES (?, 1)
    ON CONFLICT(day) DO UPDATE SET calls = calls + 1 WHERE calls < ? RETURNING calls`).bind(day, limit).first();
  return Boolean(row);
}

export async function handleCloud(request, env, url, json, runChat, dispatch) {
  if (!env.DB) return json({ error: 'Brak bazy D1 (wiązanie DB)' }, 503);
  const db = env.DB;
  const parts = url.pathname.split('/').filter(Boolean);
  const [resource, id, action] = parts;
  if (id && !ID.test(id)) return json({ error: 'Nieprawidłowy identyfikator' }, 400);
  if (parts.length > 3) return json({ error: 'Nie znaleziono' }, 404);
  const now = new Date().toISOString();
  try {
    if (resource === 'conversations') {
      if (parts.length === 1 && request.method === 'GET') {
        return json({ conversations: (await db.prepare('SELECT * FROM conversations ORDER BY created_at DESC LIMIT 100').all()).results });
      }
      if (parts.length === 1 && request.method === 'POST') {
        const title = titleOf((await readBody(request)).title);
        if (!title) return json({ error: 'Tytuł musi mieć 1–120 znaków' }, 400);
        const newId = crypto.randomUUID();
        await db.batch([db.prepare('INSERT INTO conversations VALUES (?, ?, ?)').bind(newId, title, now), audit(db, 'conversation.created', newId, now)]);
        return json({ id: newId, title, created_at: now }, 201);
      }
      if (id && parts.length === 2 && request.method === 'DELETE') {
        // Explicit child deletion also works when a local SQLite driver disables foreign keys.
        const result = await db.batch([
          db.prepare('DELETE FROM conversation_messages WHERE conversation_id = ?').bind(id),
          db.prepare('DELETE FROM conversations WHERE id = ?').bind(id), audit(db, 'conversation.deleted', id, now),
        ]);
        return json({ ok: true, deleted: result[1].meta.changes > 0 });
      }
      if (id && action === 'messages' && request.method === 'GET') {
        if (!await db.prepare('SELECT id FROM conversations WHERE id = ?').bind(id).first()) return json({ error: 'Nie znaleziono rozmowy' }, 404);
        return json({ messages: (await db.prepare('SELECT * FROM (SELECT * FROM conversation_messages WHERE conversation_id = ? ORDER BY id DESC LIMIT 100) ORDER BY id').bind(id).all()).results });
      }
      if (id && action === 'messages' && request.method === 'POST') {
        const body = await readBody(request);
        if (!['user','assistant'].includes(body.role) || typeof body.content !== 'string' || !body.content.trim() || body.content.length > 12000) return json({ error: 'Nieprawidłowa wiadomość' }, 400);
        // Stored transcript is user data, never evidence that the server performed an action.
        const result = await db.batch([
          db.prepare('INSERT INTO conversation_messages (conversation_id, role, content, created_at) SELECT id, ?, ?, ? FROM conversations WHERE id = ?').bind(body.role, body.content, now, id),
          audit(db, 'conversation.message_added', id, now),
        ]);
        return result[0].meta.changes ? json({ ok: true }, 201) : json({ error: 'Nie znaleziono rozmowy' }, 404);
      }
    }
    if (resource === 'projects') {
      if (parts.length === 1 && request.method === 'GET') return json({ projects: (await db.prepare('SELECT * FROM projects ORDER BY updated_at DESC LIMIT 100').all()).results });
      if (parts.length === 1 && request.method === 'POST') {
        const body = await readBody(request);
        const name = titleOf(body.name);
        if (!name || typeof body.repository !== 'string' || !/^[A-Za-z0-9_.-]{1,100}\/[A-Za-z0-9_.-]{1,100}$/.test(body.repository) || body.repository.split('/').some(p => p === '.' || p === '..') || typeof body.summary !== 'string' || body.summary.length > 4000) return json({error:'Nieprawidłowe dane projektu'},400);
        const newId = crypto.randomUUID();
        await db.batch([
          db.prepare('INSERT INTO projects VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT(repository) DO UPDATE SET name=excluded.name,summary=excluded.summary,updated_at=excluded.updated_at').bind(newId,name,body.repository,body.summary,now,now),
          audit(db,'project.saved',body.repository,now),
        ]);
        return json(await db.prepare('SELECT * FROM projects WHERE repository = ?').bind(body.repository).first(),201);
      }
      if (id && parts.length === 2 && request.method === 'DELETE') {
        await db.batch([db.prepare('DELETE FROM projects WHERE id = ?').bind(id),audit(db,'project.deleted',id,now)]);
        return json({ok:true});
      }
    }
    if (resource === 'tasks') {
      if (parts.length === 1 && request.method === 'GET') return json({ tasks: (await db.prepare('SELECT * FROM tasks ORDER BY created_at DESC LIMIT 100').all()).results });
      if (parts.length === 1 && request.method === 'POST') {
        const body = await readBody(request);
        const title = titleOf(body.title);
        if (!title || !['research','business_draft'].includes(body.kind)) return json({ error: 'Dozwolone są tylko research i business_draft; tytuł 1–120 znaków' }, 400);
        let scheduled = null;
        if (body.scheduled_at !== undefined && body.scheduled_at !== null) {
          const date = new Date(body.scheduled_at);
          if (typeof body.scheduled_at !== 'string' || !Number.isFinite(date.getTime()) || date <= new Date(now) || date.getTime() > Date.now() + 366*86400_000) return json({ error: 'Nieprawidłowy termin zadania' }, 400);
          if (!env.TASK_QUEUE) return json({ error: 'Harmonogram wymaga kolejki TASK_QUEUE' }, 503);
          scheduled = date.toISOString();
        }
        const newId = crypto.randomUUID();
        await db.batch([db.prepare('INSERT INTO tasks (id,title,kind,status,result,created_at,updated_at,scheduled_at) VALUES (?, ?, ?, ?, NULL, ?, ?, ?)').bind(newId, title, body.kind, 'queued', now, now, scheduled), audit(db, 'task.created', newId, now)]);
        return json({ id: newId, title, kind: body.kind, status: 'queued', scheduled_at: scheduled, background: Boolean(env.TASK_QUEUE), execution_available: true }, 201);
      }
      if (id && action === 'run' && request.method === 'POST') {
        const task = await db.prepare('SELECT * FROM tasks WHERE id = ?').bind(id).first();
        if (!task) return json({ error: 'Nie znaleziono zadania' }, 404);
        if (task.scheduled_at && task.scheduled_at > now) return json({ id, status: task.status, scheduled_at: task.scheduled_at }, 202);
        if (env.TASK_QUEUE && !env.INTERNAL_QUEUE) {
          if (task.status !== 'queued') return json({ error: 'Zadanie już uruchomione lub zakończone' }, 409);
          if (!dispatch) return json({ error: 'Brak wykonawcy kolejki' }, 503);
          await dispatch(env, id);
          return json({ id, status: 'queued', background: true }, 202);
        }
        // Only one runner may claim a task; a cancelled/completed task cannot restart.
        const claim = await db.prepare("UPDATE tasks SET status = 'running', updated_at = ? WHERE id = ? AND status = 'queued' RETURNING id").bind(now, id).first();
        if (!claim) return json({ error: 'Zadanie już uruchomione lub zakończone' }, 409);
        try {
          await audit(db, 'task.started', id, now).run();
          const prompt = task.kind === 'business_draft'
            ? 'JARVIS BUSINESS: przygotuj szkic analizy lub strategii. Oddziel fakty, założenia i ryzyka. Nie publikuj, nie wysyłaj wiadomości i nie dokonuj zakupów. Temat użytkownika: '
            : 'Przeprowadź badanie. Jeśli wyszukiwanie jest niedostępne, wyraźnie zaznacz brak aktualnych źródeł. Nie wykonuj działań zewnętrznych. Temat użytkownika: ';
          const chat = new Request(new URL('/chat', url), { method: 'POST', headers: request.headers,
            body: JSON.stringify({ messages: [{ role: 'user', content: prompt + task.title }] }) });
          const response = await runChat(chat, { ...env, INTERNAL_TASK_ID: id });
          const data = await response.json();
          const status = response.ok ? 'completed' : 'failed';
          const ended = new Date().toISOString();
          const result = JSON.stringify(response.ok ? { reply: data.reply, sources: data.sources || [] } : { error: data.error });
          const saved = await db.batch([
            db.prepare("UPDATE tasks SET status = ?, result = ?, updated_at = ? WHERE id = ? AND status = 'running'").bind(status, result, ended, id),
            audit(db, 'task.run_finished', id, ended),
          ]);
          // Cancellation wins: discard results of a call that finished after cancellation.
          return saved[0].meta.changes ? json({ id, status, result: JSON.parse(result) }, response.ok ? 200 : response.status) : json({ id, status: 'cancelled' });
        } catch {
          await db.prepare("UPDATE tasks SET status = 'failed', updated_at = ? WHERE id = ? AND status = 'running'").bind(new Date().toISOString(), id).run();
          return json({ error: 'Uruchomienie zadania nie powiodło się' }, 503);
        }
      }
      if (id && action === 'cancel' && request.method === 'POST') {
        const result = await db.batch([
          db.prepare("UPDATE tasks SET status = 'cancelled', updated_at = ? WHERE id = ? AND status IN ('queued','running')").bind(now, id), audit(db, 'task.cancel_requested', id, now),
        ]);
        return result[0].meta.changes ? json({ ok: true, status: 'cancelled' }) : json({ error: 'Brak aktywnego zadania' }, 409);
      }
      if (id && parts.length === 2 && request.method === 'GET') {
        const task = await db.prepare('SELECT * FROM tasks WHERE id = ?').bind(id).first();
        return task ? json(task) : json({ error: 'Nie znaleziono zadania' }, 404);
      }
    }
    return json({ error: 'Nie znaleziono' }, 404);
  } catch (e) {
    if (e.message === 'body_limit') return json({ error: 'Za duże żądanie' }, 413);
    if (e instanceof SyntaxError || e.message === 'invalid_json') return json({ error: 'Nieprawidłowy JSON' }, 400);
    return json({ error: 'Błąd zapisu lub odczytu danych' }, 503);
  }
}
