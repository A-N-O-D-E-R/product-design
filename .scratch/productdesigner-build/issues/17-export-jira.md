# Export Jira : Epics et Stories

Type: task
Status: resolved
Jalon: J5 · Export Jira
Blocked by: 16
Exigences: E-26, E-27, E-31
Décisions: D-10, D-11, D-27

## Quoi

Exporter la Roadmap vers un projet Jira Cloud (clé du projet saisie dans l'UI ; site, email et token pris dans la config locale). Le type Epic est trouvé par `hierarchyLevel == 1` via `createmeta/{projet}/issuetypes`, le type Story au niveau 0 (« Story », sinon le premier de ce niveau).

L'Epic est créé via `POST /issue`, les Stories via `POST /issue/bulk` (50 au plus par appel) avec `parent: {key}`. La description est en ADF. Les `jira_key` sont stockés.

## Critères d'acceptation

- [x] Après export, Jira contient un Epic par Jalon et une Story par Ticket, chaque Story sous le bon Epic.
- [x] Les descriptions s'affichent formatées dans Jira (titres, listes).
- [x] Config Jira absente : message explicite.

## Comments

- 2026-11-02 : fait.
  - `JiraClient` (`RestClient`, Basic email:token) ; config `productdesigner.jira.site|email|token` ; absente : « Config Jira absente… » (`GET /api/jira/status` pour l'UI).
  - Types par `createmeta/{projet}/issuetypes` (paginé) : Epic = `hierarchyLevel` 1, Story = « Story » de niveau 0, sinon le premier de niveau 0.
  - `POST /api/specs/{id}/export-jira {projet}` : Epic par Jalon (`POST /issue`), Stories par lots de 50 (`POST /issue/bulk`) avec `parent`, description ADF (`Adf.document` : titres, paragraphes, listes, cases, gras, code ; exigences en pied). `jira_key` stockés ; projet Jira mémorisé sur la Spec (V13).
  - Tests : `JiraTest` (faux Jira), `AdfTest`. **Non testé contre un vrai Jira Cloud.**
