# 0004 — Fournisseur LLM indépendant, via Spring AI

Date : 2026-11-13 · Statut : acceptée · Remplace : [0002](0002-fournisseur-bedrock.md) · Révise : D-2, D-24

## Contexte

L'app appelait le SDK Java Anthropic (API Anthropic, ou Bedrock via l'endpoint Mantle). L'utilisateur veut pouvoir changer de fournisseur.

## Décision

- Spring AI 2.0 (`ChatModel`), un starter par fournisseur : `anthropic`, `openai` (et compatibles), `bedrock-converse`, `ollama` ; `spring.ai.model.chat` choisit. Config sous `spring.ai.*` (fin de `productdesigner.anthropic.*` et `productdesigner.bedrock.*`).
- Spring AI n'exécute aucun outil : l'app lit l'appel `record` comme avant, et exécute elle-même l'outil de recherche.
- Recherche web : outil client `web_search` (API Tavily, `productdesigner.recherche.tavily-api-key`) sur tout fournisseur ; sans clé, recherche serveur d'Anthropic ; sinon research refusée.
- Format des messages persistés gardé (`text`, `tool_use`, `tool_result`), converti en messages Spring AI à chaque tour : pas de migration ; les blocs thinking et de recherche serveur des messages existants sont ignorés à la relecture.
- Roadmap : décodage contraint natif du fournisseur (`outputSchema`), schéma tiré de `RoadmapGeneree`.
- Effort, prompt caching et recherche serveur restent réglés pour Anthropic seulement.

## Conséquences

- Après un `pause_turn` (recherche serveur Anthropic), Spring AI ne garde pas les blocs de recherche chiffrés : le tour reprend par un message masqué « Continue. », sans eux. Avec Tavily, rien n'est perdu.
- Un flux coupé sans raison d'arrêt est traité comme une coupure (Spring AI le termine sans erreur).
- Testés contre de faux serveurs : Anthropic et OpenAI (avec Tavily). Bedrock Converse et Ollama ne le sont pas ; aucun fournisseur n'a été revérifié en réel depuis.
- Le modèle doit savoir appeler des outils (petits modèles Ollama : `record` peut manquer, la relance masquée puis l'avertissement s'appliquent).
