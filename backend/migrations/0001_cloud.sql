-- Personal workspace: all callers authenticated with APP_TOKEN share these records.
PRAGMA foreign_keys = ON;
CREATE TABLE IF NOT EXISTS conversations (
  id TEXT PRIMARY KEY,
  title TEXT NOT NULL CHECK(length(title) BETWEEN 1 AND 120),
  created_at TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS conversation_messages (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  conversation_id TEXT NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
  role TEXT NOT NULL CHECK(role IN ('user','assistant')),
  content TEXT NOT NULL CHECK(length(content) BETWEEN 1 AND 12000),
  created_at TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS messages_conversation ON conversation_messages(conversation_id, id);
CREATE TABLE IF NOT EXISTS tasks (
  id TEXT PRIMARY KEY,
  title TEXT NOT NULL CHECK(length(title) BETWEEN 1 AND 120),
  kind TEXT NOT NULL CHECK(kind IN ('research','business_draft')),
  status TEXT NOT NULL CHECK(status IN ('queued','running','completed','failed','cancelled')),
  result TEXT,
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS audit_events (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  event TEXT NOT NULL,
  resource_id TEXT NOT NULL,
  created_at TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS daily_usage (
  day TEXT PRIMARY KEY,
  calls INTEGER NOT NULL CHECK(calls >= 0)
);
