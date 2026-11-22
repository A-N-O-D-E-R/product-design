-- D'où vient le Projet quand il est importé d'une idée d'idea-generator (POST /api/projets/import) : l'URL de l'idée
-- source (traçabilité jusqu'à la PR) et sa provenance en JSON (idée, tableau, résultat de la recherche ai-scientist).
ALTER TABLE projet ADD COLUMN source_idea_url TEXT;
ALTER TABLE projet ADD COLUMN source_idea TEXT;
