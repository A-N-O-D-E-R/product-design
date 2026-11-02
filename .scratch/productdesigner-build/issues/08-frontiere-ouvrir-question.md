# Frontière et ouverture d'une Question

Type: task
Status: resolved
Jalon: J2 · Map et cycle de vie des Questions
Blocked by: 06
Exigences: E-4, E-6
Décisions: D-4, D-6, D-20

## Quoi

La frontière liste les Questions `open` dont toutes les `blocked_by` sont `resolved`, triées par numéro. Ouvrir une Question crée une Session avec la Méthode de son type et passe la Question à `claimed`.

## Critères d'acceptation

- [x] Une Question bloquée par une Question non résolue n'apparaît pas dans la frontière.
- [x] Ouvrir une Question grilling lance une Session avec la Méthode grilling ; la Question passe à `claimed`.

## Comments

- 2026-11-02 : fait.
  - `session.question_id` (V8). `MapController.frontiere` : Questions `open` dont toutes les bloquantes sont `resolved`, par numéro ; exposé en `ouvrable` dans la Map.
  - `POST /api/projets/{p}/questions/{q}/sessions` : 409 hors frontière ; Méthode selon le type (grilling, research, prototype ; tâche → grilling) ; Question `claimed` dans la même transaction.
  - Le contexte système de la Session ajoute la Destination et la Question à résoudre (titre + corps).
  - Page Projet : section Frontière avec « Ouvrir » ; le bouton « Nouvelle Session grilling » est retiré (l'endpoint reste, utilisé par les tests).
  - Limite : ouvrir une Question research ou prototype renvoie « Méthode inconnue » tant que les Tickets 10 et 12 ne sont pas faits.
  - Testé : `MapTest.frontiereEtOuvertureDUneQuestion`, `SessionTest.sessionSurUneQuestionLaPorteDansLeContexte` ; capture headless.
