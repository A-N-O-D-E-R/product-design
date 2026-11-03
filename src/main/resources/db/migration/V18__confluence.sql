-- Une page Confluence par Projet ; chaque version de Spec publiée en est une version Confluence.
ALTER TABLE projet ADD COLUMN confluence_espace TEXT;
ALTER TABLE projet ADD COLUMN confluence_page_id TEXT;
ALTER TABLE spec ADD COLUMN confluence_version INTEGER;
