import { DatabaseSync } from 'node:sqlite';
import { readFileSync } from 'node:fs';
export function database() {
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
