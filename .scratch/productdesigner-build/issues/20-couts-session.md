# Tokens et coût par Session

Type: task
Status: resolved
Jalon: J1 · Socle et Session grilling de bout en bout
Blocked by: 03
Exigences: E-33
Décisions: D-29

## Quoi

Persister le `usage` (tokens d'entrée, de sortie et lus en cache) sur chaque Message. Afficher les tokens et le coût estimé par Session et par Projet, calculés à partir d'une table de tarifs en config.

## Critères d'acceptation

- [x] Après un échange, la Session affiche ses tokens et son coût estimé.
- [x] Le total du Projet est égal à la somme de ses Sessions.
- [x] Changer la table de tarifs en config change le coût affiché.

## Comments

- 2026-11-01 : fait. Colonnes `modele`, `input_tokens`, `output_tokens`, `cache_read_tokens`, `cache_write_tokens` sur `message` (V5), remplies depuis `Message.usage()`. `GET /api/sessions/{id}/consommation` et `/api/projets/{id}/consommation` ; tarifs sous `productdesigner.tarifs.<modèle>` dans `application.yml` (surchargeables dans `~/.productdesigner/application.yml`). Modèle sans tarif : coût `null`, affiché « coût inconnu ». Testé dans `SessionTest.consommationParSessionEtParProjet`.
