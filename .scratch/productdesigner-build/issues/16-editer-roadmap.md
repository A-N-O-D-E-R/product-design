# Éditer la Roadmap

Type: task
Status: resolved
Jalon: J4 · Spec et Roadmap
Blocked by: 15
Exigences: E-25
Décisions: D-22

## Quoi

Avant export, l'utilisateur peut renommer, éditer, ajouter et supprimer des Tickets, et les déplacer entre Jalons. « Régénérer » remplace l'ensemble, après confirmation.

## Critères d'acceptation

- [x] Les modifications persistent.
- [x] Déplacer un Ticket change son Jalon et son ordre.
- [x] Régénérer demande une confirmation puis remplace tous les Jalons et Tickets de cette version.

## Comments

- 2026-11-02 : fait. `PUT /api/jalons/{id}` (renommer), `POST /api/jalons/{id}/tickets`, `PUT /api/tickets/{id}` (titre, corps, exigences, prérequis, Jalon : un Ticket déplacé passe en dernier dans le Jalon cible), `DELETE /api/tickets/{id}`. Prérequis limités à la même Roadmap. UI : Renommer, Modifier, Supprimer (confirmé), + Ajouter un Ticket, sélecteur de Jalon ; « Régénérer » demande confirmation. Testé : `RoadmapTest.editerAvantExport`. Pas de réordonnancement fin dans un Jalon (seulement via déplacement).
