# Générer la Spec

Type: task
Status: resolved
Jalon: J4 · Spec et Roadmap
Blocked by: 09
Exigences: E-19, E-20, E-21
Décisions: D-7, D-13, D-14

## Quoi

Le bouton « Générer la Spec » (génération forcée) produit une Spec markdown au gabarit : les sections 1 à 7 sont toujours présentes (« non décidé » si rien n'est tranché), les sections optionnelles seulement si des Décisions les couvrent. Les exigences sont numérotées `E-n` et citent leurs `D-n`.

## Critères d'acceptation

- [x] La Spec générée contient les 7 sections du socle dans l'ordre.
- [x] Chaque exigence porte un `E-n` et au moins une référence `D-n` existante.

## Comments

- 2026-11-02 : fait.
  - `POST /api/projets/{id}/specs` streame en SSE ; prompt `prompts/spec.md` (gabarit D-13, le quoi pas le comment, `E-n` citant des `D-n`) ; entrée = Destination + résumé de la Map + Décisions confirmées (`MapController.resume`, partagé avec la reprise de Session). Effort `high`.
  - Le gabarit n'est pas laissé au modèle : `SpecController.normaliser` réordonne les 7 sections du socle (titre exact ou même numéro), ajoute « Non décidé. » aux absentes et place les optionnelles après. `verifier` signale chaque `E-n` sans `D-n` existante (avertissements affichés, Spec quand même enregistrée).
  - Tests : `SpecGabaritTest` (normalisation, avertissements) + `SessionTest.genererLaSpecDepuisLaMapEtLesDecisions`.
  - Rendu en texte brut (pas de markdown), comme les rapports de research.
