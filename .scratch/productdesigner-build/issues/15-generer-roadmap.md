# Générer la Roadmap

Type: task
Status: resolved
Jalon: J4 · Spec et Roadmap
Blocked by: 13
Exigences: E-23, E-24
Décisions: D-21, D-22

## Quoi

Générer depuis une version de Spec (structured output `roadmap`) des Jalons ordonnés par valeur et par risque, de 3 à 10 Tickets chacun. Chaque Ticket est un vertical slice d'au plus un jour, avec ses critères d'acceptation (dans le corps), ses exigences `E-n` et ses `blocked_by`.

## Critères d'acceptation

- [x] La Roadmap est rattachée à la version de Spec source.
- [x] Chaque Ticket affiche ses critères, ses `E-n` et ses dépendances.

## Comments

- 2026-11-02 : fait.
  - Tables `jalon`, `ticket` (critères d'acceptation en cases à cocher dans le corps, `exigences` en colonne), `ticket_bloque` (V12), rattachées à une version de Spec.
  - `POST /api/specs/{id}/roadmap` : structured output `RoadmapGeneree` (schéma dérivé des records Java par le SDK), effort `high`, en streaming SSE (`progression` en caractères reçus, le JSON n'étant pas lisible au fil de l'eau). Prompt `prompts/roadmap.md` adapté du skill to-tickets (vertical slices, Jalons démontrables de 3 à 10 Tickets, ordre valeur/risque).
  - `blockedBy` résolu par titre. Avertissements : Jalon hors 3–10, exigence absente de la Spec, prérequis inconnu, exigences de la Spec non couvertes.
  - Régénérer remplace la Roadmap de la version (confirmation côté UI).
  - Testé : `SessionTest.genererLaRoadmapDUneVersionDeSpec` + capture headless.
