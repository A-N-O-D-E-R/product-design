# 0004 — Claude par le CLI Claude Code

Date : 2026-11-22 · Statut : acceptée · Remplace : [0002](0002-fournisseur-bedrock.md)

## Contexte

L'accès à Claude passait par le SDK Java Anthropic (clé API) ou Amazon Bedrock (AWS SSO). L'utilisateur veut utiliser son abonnement Claude, via un token OAuth (`claude setup-token`) saisi dans l'interface, avec le starter maison [spring-ai-claude-code-starter](https://github.com/A-N-O-D-E-R/spring-ai-claude-code-starter), qui lance le CLI Claude Code.

## Décision

- Retrait de Bedrock et du SDK Anthropic. Chaque appel (Session, research, Spec, Roadmap) est un `claude -p` lancé par `ClaudeCodeAgent`.
- Le starter a été étendu pour cela : sortie `stream-json` relayée delta par delta (`stream`), `--json-schema` (sortie lue dans `structuredOutput`), `--system-prompt` en remplacement.
- Appels sans état : l'historique est rejoué en texte depuis la base, `record` compris. Pas de `--resume` : la base reste la seule source de vérité (relance, coupure, nouvelle Session).
- `record` devient une sortie structurée (`--json-schema`) au lieu d'un tool. Le CLI valide le schéma : plus de relance masquée côté app (D-18 caduque pour les nouveaux tours). Le stockage garde le format de bloc `tool_use` nommé `record`, les Sessions existantes restent lisibles.
- Isolation : `--setting-sources ""`, `--strict-mcp-config`, `--tools ""` (sauf `WebSearch` pour la research), `--permission-mode dontAsk`, dossier de travail dédié.
- Token saisi dans l'interface, écrit dans `~/.productdesigner/claude-token` (0600), jamais renvoyé ; sans token, la connexion locale du CLI.

## Conséquences

- La research redevient possible (WebSearch du CLI).
- Perte du prompt caching piloté par l'app et des pauses serveur (le CLI mène sa boucle) ; l'usage remonté est cumulé sur tous les tours internes du CLI.
- Dépendance au CLI installé sur la machine, et au starter installé dans le dépôt Maven local.
