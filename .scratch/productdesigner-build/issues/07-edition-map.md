# Édition de la Map

Type: task
Status: resolved
Jalon: J2 · Map et cycle de vie des Questions
Blocked by: 06
Exigences: E-3, E-5
Décisions: D-20

## Quoi

La vue Map est éditable à la main : Destination, notes, Questions, Brouillard, Hors périmètre. Promouvoir un Brouillard supprime la ligne et crée une Question. Toute Question peut passer en `hors_périmètre`.

## Critères d'acceptation

- [x] Les éditions persistent après rechargement.
- [x] Promouvoir un Brouillard : la ligne disparaît et une nouvelle Question `open` apparaît.
- [x] Passer une Question en `hors_périmètre` est possible depuis n'importe quel statut.

## Comments

- 2026-11-02 : fait.
  - API (`MapController`) : `PUT /map` (destination, notes) ; `POST/PUT /questions` (titre unique par Projet, 409 sinon ; `blockedBy` par numéros) ; `POST /questions/{id}/hors-perimetre` depuis tout statut ; `POST/PUT/DELETE /brouillard` et `/hors-perimetre` ; `POST /brouillard/{id}/promouvoir` (supprime la ligne, crée une Question `open` titrée par le texte, type `grilling` par défaut, modifiable ensuite). Chaque écriture renvoie la Map à jour.
  - Vue Map éditable sur la page Projet.
  - `MapTest` couvre les 3 critères. Rendu vérifié par capture headless ; les clics de l'UI ne sont pas testés automatiquement.
  - Non fait : suppression d'une Question (passer hors périmètre suffit pour l'instant).
