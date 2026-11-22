-- Clés d'API pd_... : d'autres applications (idea-generator) appellent l'API sans session ni CSRF. Seul le SHA-256 est gardé.
-- Pas de propriétaire : l'application n'a pas de données par utilisateur ; cree_par dit seulement qui l'a créée.
CREATE TABLE cle_api (
    id           TEXT PRIMARY KEY,
    nom          TEXT NOT NULL,
    hash         bytea NOT NULL UNIQUE,
    indice       TEXT NOT NULL,
    cree_par     TEXT NOT NULL,
    created_at   timestamptz NOT NULL DEFAULT now(),
    expire_le    timestamptz NOT NULL,
    utilisee_le  timestamptz,
    revoquee_le  timestamptz
);
