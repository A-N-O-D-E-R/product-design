CREATE TABLE question (
    id         INTEGER PRIMARY KEY,
    projet_id  INTEGER NOT NULL REFERENCES map (projet_id) ON DELETE CASCADE,
    numero     INTEGER NOT NULL,
    titre      TEXT    NOT NULL,
    corps      TEXT    NOT NULL DEFAULT '',
    type       TEXT    NOT NULL CHECK (type IN ('grilling', 'research', 'prototype', 'tache')),
    statut     TEXT    NOT NULL DEFAULT 'open' CHECK (statut IN ('open', 'claimed', 'resolved', 'hors_perimetre')),
    UNIQUE (projet_id, numero),
    UNIQUE (projet_id, titre)
);

CREATE TABLE question_bloquee (
    question_id    INTEGER NOT NULL REFERENCES question (id) ON DELETE CASCADE,
    bloquee_par_id INTEGER NOT NULL REFERENCES question (id) ON DELETE CASCADE,
    PRIMARY KEY (question_id, bloquee_par_id),
    CHECK (question_id <> bloquee_par_id)
);

CREATE TABLE brouillard (
    id        INTEGER PRIMARY KEY,
    projet_id INTEGER NOT NULL REFERENCES map (projet_id) ON DELETE CASCADE,
    texte     TEXT    NOT NULL
);

CREATE TABLE hors_perimetre (
    id        INTEGER PRIMARY KEY,
    projet_id INTEGER NOT NULL REFERENCES map (projet_id) ON DELETE CASCADE,
    texte     TEXT    NOT NULL
);
