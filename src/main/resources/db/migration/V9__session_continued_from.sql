-- Session ouverte quand la précédente approchait la limite de contexte (D-28).
ALTER TABLE session ADD COLUMN continued_from bigint REFERENCES session (id) ON DELETE SET NULL;
