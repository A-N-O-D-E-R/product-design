# Tool record et rounds en carte

Type: task
Status: resolved
Jalon: J1 · Socle et Session grilling de bout en bout
Blocked by: 03
Exigences: E-8, E-12
Décisions: D-15, D-16, D-17, D-18

## Quoi

Ajouter le tool `record(decisions[], questions[], round{questions[], recommandations[]})`, avec un schéma unique fixé dans le code. Chaque Méthode déclare en frontmatter les champs qu'elle utilise (`outputs`).

Un round s'affiche en carte. Chaque question y porte son id et sa reco, une case « Accepter la reco » cochée par défaut et une réponse libre. Un envoi groupé produit `Q1: …`. Si `record` est absent, l'app relance une fois, puis affiche un avertissement.

## Critères d'acceptation

- [x] Un round renvoyé par `record` s'affiche en carte, conforme au prototype `../productdesigner-mvp/prototypes/05-ecran-session.html`.
- [x] L'envoi produit un message utilisateur `Qn: <réponse ou reco>` par question.
- [x] Si `record` manque : une relance automatique, puis le texte s'affiche seul avec un avertissement.

## Comments

- 2026-11-01 : fait.
  - `Record.TOOL` : schéma unique strict (`round.questions[id,titre,corps,recommandation]`, `decisions[texte]`, `questions[titre,corps,type]`), `tool_choice` auto (le forcé est refusé par Opus 5.5). La consigne ajoutée au prompt liste les `outputs` du frontmatter.
  - Le message suivant un appel `record` commence par son `tool_result`. Sans `record` : une relance masquée (`message.visible = 0`), puis événement SSE `avertissement`.
  - Choix : le round n'est plus recopié dans le texte du LLM (sinon doublon avec la carte) ; `grilling.md` ajusté.
  - `SessionTest` couvre round, tool_result, relance réussie et relance ratée. Rendu de la carte vérifié par capture Chromium headless contre un faux serveur. L'envoi groupé `Qn: …` (logique front) n'a pas de test automatisé.
