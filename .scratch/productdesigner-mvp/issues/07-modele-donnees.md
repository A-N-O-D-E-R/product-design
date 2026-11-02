# Modèle de données

Type: grilling
Status: resolved
Blocked by: 06

## Question

Quelles entités (Projet, Map, Question, Session, Décision, Spec et versions, Roadmap, Jalon, Ticket, pièces jointes), relations et états (cycle de vie d'une Question : open/claimed/resolved/hors périmètre) ?

## Answer

**Entités**

- `Projet` 1–1 `Map` (destination, notes).
- `Map` 1–n `Brouillard` (texte), 1–n `HorsPérimètre` (texte), 1–n `Question` (numéro, titre, corps, type, statut, `blocked_by` n–n vers Question).
- `Session` : projet, question optionnelle (absente = Session qui trace la Map), méthode, statut `active`/`close`.
- `Session` 1–n `Message` : rôle, blocs JSON bruts du SDK, conservés tels quels pour le replay, `encrypted_content` du web search compris.
- `Décision` : numéro global `D-n` par Projet, texte, question, session, `confirmed_at`.
- `Spec` : projet, version, markdown, date.
- `Jalon` (spec, ordre, titre, `jira_key`) 1–n `Ticket` (ordre, titre, corps, `jira_key`, `blocked_by`).
- `Artefact` : session, type `prototype_html`/`research_md`, contenu.

**Règles**

- **Pas d'entité Roadmap.** La Roadmap est la liste ordonnée des Jalons d'une version de Spec.
- **Statuts d'une Question :**
  - `open` → `claimed` à l'ouverture d'une Session.
  - `claimed` → `resolved` à la clôture de la Session, s'il y a au moins une Décision.
  - Clôture sans Décision : retour à `open`.
  - `hors_périmètre` : atteignable depuis n'importe quel statut.
  - Une Question peut porter plusieurs Décisions.
- **Brouillard → Question :** suppression de la ligne de Brouillard, création d'une Question. Aucun lien conservé entre les deux.
- **Spec ↔ Décisions :** la Spec cite les Décisions par `D-n` dans son markdown, sans table de jointure. Si une Décision change, la Spec est régénérée en nouvelle version.
