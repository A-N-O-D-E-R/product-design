# Ré-export Jira

Type: task
Status: resolved
Jalon: J5 · Export Jira
Blocked by: 17
Exigences: E-28, E-29
Décisions: D-23

## Quoi

Ré-exporter : `PUT` du titre et de la description pour les éléments qui ont un `jira_key`, création pour les autres. Aucune suppression dans Jira : l'app liste les clés des Tickets supprimés localement. Une Roadmap issue d'une nouvelle version de Spec n'a pas de clés : son export crée de nouveaux Epics.

## Critères d'acceptation

- [x] Modifier un Ticket exporté puis ré-exporter met à jour la Story existante, sans doublon.
- [x] Supprimer un Ticket exporté puis ré-exporter : la Story reste dans Jira et sa clé est listée en avertissement.
- [x] Nouvelle version de Spec : l'export crée de nouveaux Epics, les anciens sont intacts.

## Comments

- 2026-11-02 : fait. Ré-export : `PUT /issue/{clé}` pour chaque élément déjà exporté (titre ; description et Epic parent pour les Stories, un Ticket ayant pu changer de Jalon). ponytail: tout élément exporté est mis à jour à chaque export, sans détection de changement ; suffisant aux volumes visés. Supprimer un Ticket exporté, ou régénérer une Roadmap exportée, range ses clés dans `jira_orphelin` (V15) : listées à l'export, jamais supprimées dans Jira. Nouvelle version de Spec : nouvelle Roadmap sans clés, donc nouveaux Epics, anciens intacts. Tests : `JiraTest.reexportMetAJourSansDoublonEtListeLesOrphelines`, `nouvelleVersionDeSpecCreeDeNouveauxEpics`.
