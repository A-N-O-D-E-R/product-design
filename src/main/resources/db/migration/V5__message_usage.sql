-- usage renvoyé par l'API pour chaque message assistant (D-29) ; NULL sur les messages utilisateur.
ALTER TABLE message ADD COLUMN modele TEXT;
ALTER TABLE message ADD COLUMN input_tokens INTEGER;
ALTER TABLE message ADD COLUMN output_tokens INTEGER;
ALTER TABLE message ADD COLUMN cache_read_tokens INTEGER;
ALTER TABLE message ADD COLUMN cache_write_tokens INTEGER;
