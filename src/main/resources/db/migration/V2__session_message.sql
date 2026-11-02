CREATE TABLE session (
    id         INTEGER PRIMARY KEY,
    projet_id  INTEGER NOT NULL REFERENCES projet (id) ON DELETE CASCADE,
    methode    TEXT    NOT NULL,
    statut     TEXT    NOT NULL DEFAULT 'active' CHECK (statut IN ('active', 'close')),
    created_at TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%SZ', 'now'))
);

-- contenu : MessageParam du SDK Anthropic en JSON, rejoué tel quel (blocs thinking compris).
CREATE TABLE message (
    id         INTEGER PRIMARY KEY,
    session_id INTEGER NOT NULL REFERENCES session (id) ON DELETE CASCADE,
    role       TEXT    NOT NULL CHECK (role IN ('user', 'assistant')),
    contenu    TEXT    NOT NULL,
    created_at TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%SZ', 'now'))
);
CREATE INDEX message_session ON message (session_id, id);
