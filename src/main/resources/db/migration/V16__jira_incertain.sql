-- Création Jira à l'issue inconnue (5xx, timeout, réseau) : l'issue existe peut-être. Le ré-export la cherche avant de recréer.
ALTER TABLE jalon ADD COLUMN jira_incertain INTEGER NOT NULL DEFAULT 0;
ALTER TABLE ticket ADD COLUMN jira_incertain INTEGER NOT NULL DEFAULT 0;
