-- Lien « Blocks » déjà créé dans Jira : un ré-export ne le recrée pas.
ALTER TABLE ticket_bloque ADD COLUMN jira_lie INTEGER NOT NULL DEFAULT 0;
