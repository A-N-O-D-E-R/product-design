-- La Roadmap est la liste ordonnée des Jalons d'une version de Spec (D-19). Critères d'acceptation dans le corps du Ticket.
CREATE TABLE jalon (
    id       INTEGER PRIMARY KEY,
    spec_id  INTEGER NOT NULL REFERENCES spec (id) ON DELETE CASCADE,
    ordre    INTEGER NOT NULL,
    titre    TEXT    NOT NULL,
    jira_key TEXT
);

CREATE TABLE ticket (
    id         INTEGER PRIMARY KEY,
    jalon_id   INTEGER NOT NULL REFERENCES jalon (id) ON DELETE CASCADE,
    ordre      INTEGER NOT NULL,
    titre      TEXT    NOT NULL,
    corps      TEXT    NOT NULL DEFAULT '',
    exigences  TEXT    NOT NULL DEFAULT '',
    jira_key   TEXT
);

CREATE TABLE ticket_bloque (
    ticket_id     INTEGER NOT NULL REFERENCES ticket (id) ON DELETE CASCADE,
    bloque_par_id INTEGER NOT NULL REFERENCES ticket (id) ON DELETE CASCADE,
    PRIMARY KEY (ticket_id, bloque_par_id),
    CHECK (ticket_id <> bloque_par_id)
);
