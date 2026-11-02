# Clôture de Session et cycle de vie

Type: task
Status: resolved
Jalon: J2 · Map et cycle de vie des Questions
Blocked by: 08
Exigences: E-13
Décisions: D-20

## Quoi

L'utilisateur clôt une Session. La Question passe à `resolved` si au moins une Décision a été confirmée, sinon elle revient à `open`.

## Critères d'acceptation

- [x] Clôture avec au moins une Décision : `resolved`, et la frontière se met à jour (les Questions débloquées apparaissent).
- [x] Clôture sans Décision : retour à `open`.

## Comments

- 2026-11-02 : fait. `POST /api/sessions/{id}/clore` : Session `close` ; sa Question passe `resolved` si au moins une Décision confirmée dans la Session, sinon `open` (seulement si elle est encore `claimed` : une Question sortie du périmètre entre-temps le reste). Une Session close refuse messages, « Réessayer » et confirmations (409). UI : bouton « Clore la Session » (avec confirmation), retour à la page Projet ; Session close en lecture seule. Testé : `SessionTest.cloreResoutOuRouvreLaQuestion`.
