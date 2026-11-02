CREATE TABLE projet (
    id          INTEGER PRIMARY KEY,
    nom         TEXT    NOT NULL,
    description TEXT    NOT NULL DEFAULT '',
    created_at  TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%SZ', 'now'))
);

-- Exactement une Map par Projet : la clé primaire est celle du Projet.
CREATE TABLE map (
    projet_id   INTEGER PRIMARY KEY REFERENCES projet (id) ON DELETE CASCADE,
    destination TEXT    NOT NULL DEFAULT '',
    notes       TEXT    NOT NULL DEFAULT ''
);
