-- Versions simples, sans fusion (D-7).
CREATE TABLE spec (
    id         INTEGER PRIMARY KEY,
    projet_id  INTEGER NOT NULL REFERENCES projet (id) ON DELETE CASCADE,
    version    INTEGER NOT NULL,
    contenu    TEXT    NOT NULL,
    created_at TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%SZ', 'now')),
    UNIQUE (projet_id, version)
);
