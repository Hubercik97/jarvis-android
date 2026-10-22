CREATE TABLE IF NOT EXISTS projects (
 id TEXT PRIMARY KEY,
 name TEXT NOT NULL CHECK(length(name) BETWEEN 1 AND 120),
 repository TEXT NOT NULL UNIQUE,
 summary TEXT NOT NULL CHECK(length(summary) <= 4000),
 created_at TEXT NOT NULL,
 updated_at TEXT NOT NULL
);
