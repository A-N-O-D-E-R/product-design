# 0009 — Claude par le CLI Claude Code, fournisseur par défaut

Date : 2026-11-22 · Statut : acceptée · Complète : [0004](0004-fournisseur-llm-spring-ai.md)

## Contexte

L'utilisateur veut utiliser son abonnement Claude, via un token OAuth (`claude setup-token`) saisi dans l'interface, avec le starter maison [spring-ai-claude-code-starter](https://github.com/A-N-O-D-E-R/spring-ai-claude-code-starter), qui lance le CLI Claude Code. Sur `main`, ce changement remplaçait le SDK Anthropic et Bedrock ; ici, les fournisseurs Spring AI de l'ADR 0004 existent déjà et restent.

## Décision

- Nouveau fournisseur `spring.ai.model.chat: claude-code`, par défaut. Hors Spring AI : `Llm` délègue à `ClaudeCode`, qui lance un `claude -p` par appel (`ClaudeCodeAgent.stream`) ; les autres fournisseurs ne changent pas.
- Appels sans état : l'historique est rejoué en texte depuis la base, `record` compris. Pas de `--resume` : la base reste la seule source de vérité (relance, coupure, nouvelle Session).
- `record` devient la sortie structurée (`--json-schema`, son schéma de tool) ; elle revient à l'app comme un appel d'outil `record`, si bien que Session, stockage (bloc `tool_use`) et relances ne changent pas. Sa description passe dans le prompt système. Roadmap : même `--json-schema`, la sortie tient lieu de texte.
- Isolation : `--setting-sources ""`, `--strict-mcp-config`, aucun outil sauf `WebSearch` pour la research, `--permission-mode dontAsk`, `--system-prompt` en remplacement, dossier de travail dédié (`~/.productdesigner/claude-workspace`).
- Token saisi dans l'interface (`/api/config/token`), écrit dans `~/.productdesigner/claude-token` (0600), jamais renvoyé ; sans token, la connexion locale du CLI.

## Conséquences

- La research passe par le WebSearch du CLI : les outils exécutés par l'app (`web_search` Tavily, `ai_scientist`) ne sont pas proposés avec `claude-code` (le CLI mène sa propre boucle et ne peut pas les appeler).
- Effort, prompt caching piloté par l'app et pauses serveur ne s'appliquent pas ; l'usage remonté est cumulé sur tous les tours internes du CLI.
- Dépendance au CLI installé sur la machine, et au starter installé dans le dépôt Maven local (0.1.0-SNAPSHOT, absent des dépôts distants : le CI ne le résout pas tant qu'il n'est pas publié). L'image Docker n'embarque pas le CLI : `deploy/compose.yaml` garde `anthropic`.
- Testé contre un faux agent (`ClaudeCodeTest`) ; pas encore revérifié contre le vrai CLI sur cette branche.
