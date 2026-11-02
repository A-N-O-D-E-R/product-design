-- Tâche de fond d'une Session research (D-26) : NULL pour les Sessions interactives.
ALTER TABLE session ADD COLUMN tache TEXT CHECK (tache IN ('en_cours', 'terminee', 'echec'));
ALTER TABLE session ADD COLUMN progression TEXT;

CREATE TABLE artefact (
    id         INTEGER PRIMARY KEY,
    session_id INTEGER NOT NULL REFERENCES session (id) ON DELETE CASCADE,
    type       TEXT    NOT NULL CHECK (type IN ('research_md', 'prototype_html')),
    contenu    TEXT    NOT NULL,
    created_at TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%SZ', 'now'))
);
