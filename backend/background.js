import { handleCloud } from './cloud.js';
export const validId = id => typeof id === 'string' && /^[a-f0-9-]{36}$/.test(id);
// D1 dispatch lease + idempotent execution protect against duplicate queue delivery.
export async function dispatchTask(env, id, now = new Date()) {
  const lease = now.toISOString();
  const stale = new Date(now.getTime() - 120_000).toISOString();
  const claimed = await env.DB.prepare(`UPDATE tasks SET dispatched_at = ? WHERE id = ? AND status = 'queued'
    AND (dispatched_at IS NULL OR dispatched_at < ?) RETURNING id`).bind(lease, id, stale).first();
  if (!claimed) return false;
  try { await env.TASK_QUEUE.send({ id }); return true; }
  catch (error) {
    await env.DB.prepare('UPDATE tasks SET dispatched_at = NULL WHERE id = ? AND dispatched_at = ?').bind(id, lease).run();
    throw error;
  }
}
export async function scheduledTasks(env) {
  if (!env.DB || !env.TASK_QUEUE) return;
  const now = new Date();
  // A crashed runner is failed explicitly; never repeat an ambiguous paid operation.
  await env.DB.prepare(`UPDATE tasks SET status = 'failed', result = ?, updated_at = ?
    WHERE status = 'running' AND updated_at < ?`).bind(JSON.stringify({ error: 'Przerwane wykonanie; utwórz nowe zadanie, aby ponowić.' }), now.toISOString(), new Date(now.getTime()-15*60_000).toISOString()).run();
  const tasks = await env.DB.prepare(`SELECT id FROM tasks WHERE status = 'queued'
    AND ((scheduled_at IS NOT NULL AND scheduled_at <= ?) OR dispatched_at IS NOT NULL)
    AND (dispatched_at IS NULL OR dispatched_at < ?) LIMIT 50`).bind(now.toISOString(), new Date(now.getTime()-120_000).toISOString()).all();
  for (const task of tasks.results) await dispatchTask(env, task.id, now);
}
export async function consumeTasks(batch, env, runChat) {
  for (const message of batch.messages) {
    if (!validId(message.body?.id)) { message.ack(); continue; }
    try {
      const task = await env.DB.prepare('SELECT status FROM tasks WHERE id = ?').bind(message.body.id).first();
      if (task?.status !== 'queued') { message.ack(); continue; }
      const request = new Request(`https://jarvis.internal/tasks/${message.body.id}/run`, {
        method: 'POST', headers: { 'x-jarvis-token': env.APP_TOKEN },
      });
      const response = await handleCloud(request, { ...env, INTERNAL_QUEUE: true }, new URL(request.url), (data,status=200)=>Response.json(data,{status}), runChat);
      // Persisted failure is terminal; a D1 error before a claim can be retried safely.
      if (response.status >= 500) {
        const current = await env.DB.prepare('SELECT status FROM tasks WHERE id = ?').bind(message.body.id).first();
        if (current?.status === 'queued') { message.retry({ delaySeconds: 60 }); continue; }
      }
      message.ack();
    } catch { message.retry({ delaySeconds: 60 }); }
  }
}
