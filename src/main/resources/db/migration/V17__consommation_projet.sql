-- usage des appels hors Session (génération de Spec et de Roadmap), comptés dans la consommation du Projet (D-29).
CREATE TABLE appel (
    id                 INTEGER PRIMARY KEY,
    projet_id          INTEGER NOT NULL REFERENCES projet (id) ON DELETE CASCADE,
    objet              TEXT    NOT NULL CHECK (objet IN ('spec', 'roadmap')),
    modele             TEXT    NOT NULL,
    input_tokens       INTEGER NOT NULL,
    output_tokens      INTEGER NOT NULL,
    cache_read_tokens  INTEGER NOT NULL,
    cache_write_tokens INTEGER NOT NULL,
    created_at         TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%SZ', 'now'))
);
