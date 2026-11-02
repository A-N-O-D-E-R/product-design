# Décisions proposées et panneau latéral

Type: task
Status: resolved
Jalon: J1 · Socle et Session grilling de bout en bout
Blocked by: 04
Exigences: E-10, E-11
Décisions: D-15, D-17, D-19

## Quoi

Les Décisions renvoyées par `record` apparaissent dans « Décisions proposées » (confirmer / modifier). Une Décision n'est persistée qu'à sa confirmation, avec un numéro `D-n` par Projet.

Le panneau latéral affiche trois blocs : Décisions proposées, Décisions, Questions ouvertes.

## Critères d'acceptation

- [x] Une Décision non confirmée n'est pas en base.
- [x] Confirmer attribue le numéro `D-n` suivant du Projet et déplace la Décision dans « Décisions ».
- [x] Modifier permet d'éditer le texte avant de confirmer.

## Comments

- 2026-11-01 : fait.
  - Table `decision` (V4) : seules les Décisions confirmées y sont, avec `numero` unique par Projet et leur source `(message_id, indice)` dans l'appel record.
  - Les propositions sont dérivées des messages à la volée (`GET /api/sessions/{id}/decisions`), sans stockage ; `POST` confirme (texte éventuellement modifié), attribue `max(numero)+1` et renvoie 409 si déjà confirmée.
  - Panneau : Décisions proposées (Confirmer / Modifier), Décisions du Projet (`D-n`), Questions ouvertes (vide en attendant la Map, Ticket 06).
  - Correctif : Boot 4 lit `spring.web.error.include-message` (et non `server.error.*`) ; sans lui, les messages d'erreur (dont « clé Anthropic absente ») n'arrivaient pas au front.
  - Pas de bouton « Ignorer » : une proposition non voulue reste listée. À ajouter si ça gêne.
