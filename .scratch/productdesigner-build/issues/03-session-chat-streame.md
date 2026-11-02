# Session grilling avec chat streamé

Type: task
Status: resolved
Jalon: J1 · Socle et Session grilling de bout en bout
Blocked by: 02
Exigences: E-7, E-9, E-14
Décisions: D-4, D-5, D-19, D-24, D-25, D-27

## Quoi

L'utilisateur ouvre une Session grilling sur un Projet et échange en messages libres. La réponse de Claude est streamée en SSE (`SseEmitter`, `createStreaming`) puis persistée en blocs SDK bruts via `MessageAccumulator`.

La Méthode grilling est chargée depuis `classpath:methodes/grilling.md`, avec un frontmatter lu par SnakeYAML et un en-tête MIT. Le prompt caching porte sur la Méthode et l'historique.

## Critères d'acceptation

- [x] Le texte s'affiche au fil de l'eau.
- [x] Une Session rouverte réaffiche son historique à l'identique et la conversation continue.
- [x] Le fichier de Méthode porte l'en-tête « Adapté de mattpocock/skills, MIT ».

## Comments

- 2026-11-01 : fait.
  - Tables `session` et `message` (`contenu` = `MessageParam` du SDK en JSON, rejoué tel quel, blocs thinking compris).
  - `POST /api/sessions/{id}/messages` streame en SSE (`delta`, `fin`, `erreur`) depuis un virtual thread ; `MessageAccumulator.message().toParam()` est persisté.
  - System = Méthode (cache_control) + contexte Projet ; cache automatique de l'historique. Modèle `claude-opus-5-5` (configurable via `productdesigner.anthropic.model`), effort `medium`.
  - Méthode `methodes/grilling.md` adaptée du skill, notice MIT en commentaire HTML (retiré avant envoi au LLM).
  - `SessionTest` vérifie le flux complet contre un faux serveur Anthropic (`productdesigner.anthropic.base-url`). **Pas encore testé contre l'API réelle** (aucune clé configurée).
  - Non fait : fallbacks serveur en cas de refus (beta `server-side-fallback`) ; un refus affiche seulement un message. Skill `domain-modeling` (D-5) pas encore injecté dans la Session.
