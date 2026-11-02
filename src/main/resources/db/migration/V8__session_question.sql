-- Session sans Question : celle qui trace la Map (D-6).
ALTER TABLE session ADD COLUMN question_id INTEGER REFERENCES question (id) ON DELETE SET NULL;
