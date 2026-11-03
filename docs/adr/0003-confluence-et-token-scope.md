# 0003 — Spec publiée sur Confluence, token Atlassian scopé

Date : 2026-11-03 · Statut : acceptée · Complète : D-10, D-11

## Contexte

La Spec ne vivait que dans l'app. L'utilisateur travaille avec Jira et Confluence sur le même site Atlassian, et préfère un token API scopé (moindre privilège) à un token aux pleins droits du compte.

## Décision

- Publication de la Spec dans Confluence Cloud (API v2) : une page par Projet (« Spec — <nom> »), chaque version de Spec publiée en devient une version Confluence (`PUT`, numéro + 1, message de version). Corps en ADF (`atlas_doc_format`), avec le convertisseur déjà utilisé pour Jira. Page supprimée dans Confluence : recréée.
- `AtlassianClient` sert Jira et Confluence avec la même config (`productdesigner.jira.site|email|token`).
- Token scopé : avec `productdesigner.jira.cloud-id`, les appels passent par la passerelle `api.atlassian.com/ex/{jira|confluence}/{cloudId}`, seule voie qui l'accepte. Scopes minimaux, relevés dans les spécifications OpenAPI : `read:jira-work`, `write:jira-work`, `read:space:confluence`, `read:page:confluence`, `write:page:confluence`.

## Conséquences

- Vérifié en réel (site audioptictradeservices, projet PDTEST, espace personnel) : Epics et Stories sous le bon Epic, descriptions formatées, ré-export sans doublon, page Confluence créée puis versionnée.
- Liens « Blocks » : Jira lit `inwardIssue` comme celui qui bloque (la documentation ne le précise pas) ; corrigé après le premier export réel.
- Les tableaux markdown d'une Spec sortiraient en texte brut dans Confluence (ADF minimal).
