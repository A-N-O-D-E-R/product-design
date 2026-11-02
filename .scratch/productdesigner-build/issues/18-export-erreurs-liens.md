# Export Jira : liens et erreurs

Type: task
Status: resolved
Jalon: J5 · Export Jira
Blocked by: 17
Exigences: E-26, E-30
Décisions: D-10, D-11, D-23

## Quoi

Créer les liens « Blocks » à partir des `blocked_by`. Lire `errors[]` même sur un 201 et afficher les échecs Ticket par Ticket ; rejouer seulement les éléments en erreur. Sur un 429, attendre `Retry-After`, sinon backoff exponentiel avec jitter (4 tentatives au plus). Sur un 401, afficher « token Jira expiré ou invalide ».

## Critères d'acceptation

- [x] Les dépendances apparaissent comme liens « Blocks » dans Jira.
- [x] Échec partiel simulé : seuls les Tickets en échec sont signalés et rejoués.
- [x] Token invalide : le message dédié s'affiche.

## Comments

- 2026-11-02 : fait.
  - Liens « Blocks » via `POST /issueLink` (`outwardIssue` = le bloquant, `inwardIssue` = le bloqué ; la doc ne tranche pas explicitement le sens, **à vérifier sur un vrai Jira**). `ticket_bloque.jira_lie` (V14) évite les doublons.
  - Bulk : `errors[]` lus même sur 201 (et corps du 400), échecs listés par Ticket ; l'export étant idempotent (seuls les éléments sans `jira_key` sont créés), le relancer ne rejoue que les échecs.
  - 429 : `Retry-After` sinon backoff 2 s × 2^n × jitter 0,7–1,3, plafond 30 s, 4 tentatives. 401 : « Token Jira expiré ou invalide. »
  - Tests : `JiraTest.echecPartielSignaleEtSeulementRejoue`, `tokenInvalideEtLimiteDeDebit`, `delaiRetryAfterPuisBackoff`.
