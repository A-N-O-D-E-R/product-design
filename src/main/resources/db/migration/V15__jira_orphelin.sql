-- Issues Jira dont l'élément local a été supprimé (ou remplacé par une régénération) : jamais supprimées dans Jira (D-23), listées à l'export.
CREATE TABLE jira_orphelin (
    id      INTEGER PRIMARY KEY,
    spec_id INTEGER NOT NULL REFERENCES spec (id) ON DELETE CASCADE,
    cle     TEXT    NOT NULL,
    titre   TEXT    NOT NULL,
    UNIQUE (spec_id, cle)
);
