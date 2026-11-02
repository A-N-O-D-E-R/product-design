# Research en échec au redémarrage

Type: task
Status: resolved
Jalon: J3 · Research et prototype
Blocked by: 10
Exigences: E-16
Décisions: D-26

## Quoi

Au démarrage, toute research restée `active` passe en échec. L'utilisateur peut la relancer.

## Critères d'acceptation

- [x] Tuer l'app pendant une research puis la relancer : la Session est marquée en échec et un bouton « Relancer » est disponible.

## Comments

- 2026-11-02 : fait. Au `ApplicationReadyEvent`, toute Session `tache = en_cours` passe `echec` (« Interrompue par un redémarrage de l'application. »). `POST /api/sessions/{id}/relancer` (409 si pas en échec) : supprime tous les messages sauf la consigne de départ, puis relance la research après commit. Repartir de zéro évite de rejouer une fin d'historique côté assistant (préremplissage refusé par Opus 5.5). UI : bouton « Relancer » à côté de la progression. Testé : `SessionTest.researchInterrompueParRedemarragePuisRelancee`.
