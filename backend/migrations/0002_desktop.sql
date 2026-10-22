ALTER TABLE tasks ADD COLUMN scheduled_at TEXT;
ALTER TABLE tasks ADD COLUMN dispatched_at TEXT;
CREATE INDEX IF NOT EXISTS tasks_schedule ON tasks(status, scheduled_at, dispatched_at);
