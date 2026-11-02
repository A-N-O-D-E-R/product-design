-- Messages techniques (relance de record) : envoyés au LLM, masqués dans le fil.
ALTER TABLE message ADD COLUMN visible INTEGER NOT NULL DEFAULT 1;
