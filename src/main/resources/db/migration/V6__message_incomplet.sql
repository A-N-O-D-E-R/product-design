-- Réponse coupée en plein stream (D-30) : texte partiel affiché, jamais renvoyé à l'API.
ALTER TABLE message ADD COLUMN incomplet boolean NOT NULL DEFAULT false;
