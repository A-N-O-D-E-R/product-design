# Research en tâche de fond

Type: task
Status: resolved
Jalon: J3 · Research et prototype
Blocked by: 08
Exigences: E-15, E-17
Décisions: D-8, D-24, D-26

## Quoi

Une Question research s'exécute en tâche de fond (`@Async`, virtual threads), avec l'outil serveur web search. L'app gère `pause_turn` et renvoie les blocs `encrypted_content` à l'identique.

La progression est écrite en base et lue par polling. Le résultat est un Artefact `research_md`, et ses Décisions se confirment comme dans le ticket 05.

## Critères d'acceptation

- [x] Lancer une research rend la main tout de suite ; la progression est visible.
- [x] Une recherche qui déclenche `pause_turn` aboutit quand même.
- [x] L'Artefact markdown est consultable ; ses Décisions proposées sont confirmables.

## Comments

- 2026-11-02 : fait.
  - Ouvrir une Question research crée la Session `tache = en_cours`, enregistre une consigne utilisateur masquée et lance la research **après commit** sur un virtual thread (pas de `@Async` : un `Thread.ofVirtual()` suffit). La réponse HTTP revient tout de suite.
  - Appel : tool `web_search_20260209` (`max_uses` 15), effort `high`, Méthode `methodes/research.md` (sources primaires, rapport final en un message, décisions via record).
  - `pause_turn` : relancé avec l'historique tel quel (au plus 10 fois) ; le test vérifie que `encrypted_content` est renvoyé à l'identique.
  - Fin : le texte du dernier message devient l'Artefact `research_md` (table `artefact`, V10) ; record appliqué à la Map ; progression « Terminée : N recherche(s) web. ». Échec : `tache = echec` + message.
  - UI : progression avec polling toutes les 2 s, rapport affiché en carte (texte brut, pas de rendu markdown), saisie bloquée pendant la research (409 côté API).
  - Testé : `SessionTest.researchEnTacheDeFondAvecPauseTurn` + capture headless.
  - Limite : rapport = texte du **dernier** message ; si Claude l'étale sur plusieurs messages, seul le dernier est gardé.
