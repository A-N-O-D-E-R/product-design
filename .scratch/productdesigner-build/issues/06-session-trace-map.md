# Session de tracé de la Map

Type: task
Status: resolved
Jalon: J2 · Map et cycle de vie des Questions
Blocked by: 05
Exigences: E-1, E-2
Décisions: D-6, D-16

## Quoi

La 1re Session d'un Projet applique la Méthode wayfinder et remplit la Map : Destination, Questions typées avec `blocked_by`, Brouillard, Hors périmètre. Elle s'appuie sur les champs de `record` déclarés par wayfinder.

## Critères d'acceptation

- [x] Créer un Projet ouvre la Session de tracé.
- [x] À la fin, la Map contient Destination, Questions (numéro, type, `blocked_by`), Brouillard et Hors périmètre, visibles dans une vue Map.

## Comments

- 2026-11-01 : fait.
  - Tables `question` (numéro et titre uniques par Projet, type, statut), `question_bloquee`, `brouillard`, `hors_perimetre` (V7).
  - Schéma `record` étendu : `questions[].blocked_by` (titres exacts) et `map{destination, brouillard, hors_perimetre}`. Toujours un seul schéma (D-16) ; grilling laisse ces champs vides.
  - `MapController.appliquer` applique chaque appel record à la Map **sans confirmation** (seules les Décisions en demandent, D-17). Question déjà présente (même titre) ignorée ; `blocked_by` résolu par titre après création du lot.
  - Créer un Projet crée aussi sa Session `wayfinder` (`sessionTraceId`) et l'UI y redirige. Méthode `methodes/wayfinder.md` adaptée du mode « Chart the map » (sans sous-agents ni tracker).
  - Vue Map sur la page Projet ; le panneau de Session liste les Questions `open`.
  - Testé (`SessionTest.creerUnProjetOuvreLaSessionDeTraceQuiRemplitLaMap`) + captures headless.
