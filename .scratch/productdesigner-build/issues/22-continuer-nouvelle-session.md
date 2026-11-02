# Continuer dans une nouvelle Session

Type: task
Status: resolved
Jalon: J2 · Map et cycle de vie des Questions
Blocked by: 06
Exigences: E-32
Décisions: D-28

## Quoi

Calculer le remplissage du contexte depuis `usage`. Au-delà du seuil configurable (80 % par défaut), afficher un bandeau « Continuer dans une nouvelle Session ». La nouvelle Session, liée par `continued_from`, est amorcée avec la Map et les Décisions confirmées.

## Critères d'acceptation

- [x] Avec un seuil abaissé en config, le bandeau apparaît.
- [x] La nouvelle Session reprend la Map et les Décisions sans l'historique, et poursuit la même Question.
- [x] La Session d'origine est close sans changer le statut de la Question.

## Comments

- 2026-11-02 : fait.
  - `GET /api/sessions/{id}/contexte` : taille du prompt du dernier appel (input + cache lu + cache écrit) / `productdesigner.contexte.fenetre` (1 000 000 par défaut, fenêtre d'Opus 5.5), `seuilAtteint` si ≥ `productdesigner.contexte.seuil` (0,8).
  - `POST /api/sessions/{id}/continuer` : nouvelle Session (même Méthode, même Question, `continued_from`, V9), l'ancienne passe `close` sans toucher au statut de la Question.
  - Le contexte système d'une Session de suite contient un résumé texte de la Map (Questions, statuts, blocages, Brouillard, Hors périmètre) et des Décisions confirmées ; l'historique n'est pas repris.
  - UI : bandeau avec « Continuer dans une nouvelle Session ». Testé : `SessionTest.continuerDansUneNouvelleSession` + capture headless avec un seuil abaissé.
  - Limite : la fenêtre est un seul réglage, pas par modèle ; à revoir si on change de modèle.
