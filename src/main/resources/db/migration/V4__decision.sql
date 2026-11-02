-- Seules les Décisions confirmées sont en base (D-17). La source (message, indice) relie chacune
-- à sa proposition dans l'appel record, pour ne plus la reproposer.
CREATE TABLE decision (
    id           INTEGER PRIMARY KEY,
    projet_id    INTEGER NOT NULL REFERENCES projet (id) ON DELETE CASCADE,
    numero       INTEGER NOT NULL,
    texte        TEXT    NOT NULL,
    session_id   INTEGER NOT NULL REFERENCES session (id) ON DELETE CASCADE,
    message_id   INTEGER NOT NULL REFERENCES message (id) ON DELETE CASCADE,
    indice       INTEGER NOT NULL,
    confirmed_at TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%SZ', 'now')),
    UNIQUE (projet_id, numero),
    UNIQUE (message_id, indice)
);
