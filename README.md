# productDesigner

Logiciel d'interview guidée par LLM : il aide à préciser une idée de projet, une décision à la fois, puis en tire une Spec, une Roadmap et des Tickets exportables vers Jira Cloud.

Application locale, mono-utilisateur, sans authentification. Vocabulaire du domaine : [`GLOSSARY.md`](GLOSSARY.md).

## Parcours

1. **Créer un Projet** : décrire l'idée. Une première Session (Méthode *wayfinder*) trace la **Map** : Destination, Questions typées et leurs dépendances, Brouillard, Hors périmètre.
2. **Résoudre les Questions** une par une depuis la **frontière** (Questions ouvertes dont les prérequis sont résolus). Chaque Question ouvre une Session avec la Méthode de son type :
   - *grilling* : rounds de questions avec recommandations ; on répond question par question ;
   - *research* : recherche web en tâche de fond, rapport markdown ;
   - *prototype* : HTML jetable affiché dans une iframe isolée.
3. **Confirmer les Décisions** proposées dans le panneau latéral (`D-1`, `D-2`…), puis clore la Session : la Question passe à résolue s'il y a au moins une Décision.
4. **Générer la Spec** (gabarit fixe à 7 sections, chaque exigence `E-n` cite ses Décisions), versionnée.
5. **Générer la Roadmap** depuis une version de Spec : Jalons et Tickets (vertical slices), éditables.
6. **Exporter vers Jira** : un Epic par Jalon, une Story par Ticket, liens « Blocks ». Le ré-export met à jour l'existant et ne supprime jamais rien dans Jira.
7. **Publier la Spec sur Confluence** : une page par Projet ; chaque version de Spec publiée en devient une version Confluence.

## Prérequis

- **Java 25** (ex. `sdk install java 25.0.2-oracle`) et Maven 3.9+.
- Node n'est pas requis : le build Maven installe sa propre copie (v20.20) pour construire le front.
- Le **CLI Claude Code** installé (`npm install -g @anthropic-ai/claude-code`, vérifier `claude --version`). L'app l'appelle via [spring-ai-claude-code-starter](https://github.com/A-N-O-D-E-R/spring-ai-claude-code-starter), à installer d'abord dans le dépôt Maven local : `mvn install` dans ce dépôt.
- Un accès à Claude pour ce CLI : un token OAuth d'abonnement (`claude setup-token`) ou une clé API Console, saisi dans l'en-tête de l'app ; à défaut, la connexion locale (`claude login`).
- Pour l'export, un site Atlassian Cloud, un email et un [token API](https://id.atlassian.com/manage-profile/security/api-tokens) (non scopé). La même config sert à Jira et à Confluence.

## Configuration

Les secrets vivent hors du dépôt, dans `~/.productdesigner/application.yml` (importé au démarrage) :

```yaml
productdesigner:
  jira:
    site: https://<site>.atlassian.net
    email: moi@exemple.fr
    token: <token API Atlassian>
```

### Jira et Confluence

Même compte Atlassian pour les deux. Le token API conseillé est **scopé** (« Create API token with scopes ») ; il passe par la passerelle `api.atlassian.com`, qui demande le `cloudId` du site (`https://<site>.atlassian.net/_edge/tenant_info`) :

```yaml
productdesigner:
  jira:
    site: https://<site>.atlassian.net
    email: moi@exemple.fr
    token: <token API scopé>
    cloud-id: <cloudId>          # absent : token sans scope, appels directs au site
```

Scopes minimaux :

| Produit | Scopes |
|---|---|
| Jira | `read:jira-work`, `write:jira-work` |
| Confluence | `read:space:confluence`, `read:page:confluence`, `write:page:confluence` |

Un token expire au bout d'un an au plus ; l'app affiche alors « Token Jira expiré ou invalide ».

### Claude Code

Le token Claude se saisit dans l'interface (« Saisir un token » dans l'en-tête), pas dans ce fichier : il est écrit dans `~/.productdesigner/claude-token` (lecture seule pour le propriétaire), jamais renvoyé au navigateur, et passé à chaque processus du CLI (`CLAUDE_CODE_OAUTH_TOKEN`, ou `ANTHROPIC_API_KEY` pour une clé `sk-ant-api…`). API équivalente : `PUT /api/config/token` avec `{"token":"…"}`, `GET` pour l'état, `DELETE` pour le retirer.

Chaque appel est un `claude -p` sans état (l'historique est rejoué depuis la base), isolé de la config de la machine : aucun réglage utilisateur ni plugin, aucun serveur MCP, aucun outil, sauf `WebSearch` pour les Sessions *research*. Il tourne dans `~/.productdesigner/claude-workspace`. Réglages (`spring.ai.claude-code.*`) : `model` (défaut `opus`), `timeout` (`30m`), `max-turns` (`40`), `executable` (`claude`).

Limites : pas de prompt caching piloté par l'app (le CLI gère le sien), et le remplissage de contexte affiché cumule les tours internes du CLI (recherches web comprises).

Sans token saisi, l'app utilise la connexion locale du CLI et affiche un bandeau ; sans config Jira, l'export l'indique. Autres réglages (valeurs par défaut dans [`src/main/resources/application.yml`](src/main/resources/application.yml), surchargeables au même endroit) :

| Propriété | Défaut | Rôle |
|---|---|---|
| `productdesigner.data-dir` | `~/.productdesigner` | Dossier de la base `data.db` |
| `productdesigner.tarifs.<modèle>` | Opus 5.5, Sonnet 5.5 | $ par million de tokens, pour le coût estimé |
| `productdesigner.contexte.fenetre` / `.seuil` | `1000000` / `0.8` | Bandeau « Continuer dans une nouvelle Session » |
| `productdesigner.jira.backoff-ms` | `2000` | Base du backoff sur un 429 Jira |

## Lancer

```sh
JAVA_HOME=~/.sdkman/candidates/java/25.0.2-oracle mvn package
java -jar target/productdesigner-0.1.0-SNAPSHOT.jar
```

Puis http://localhost:8080. Le jar contient le front ; la base et ses migrations sont créées au premier lancement.

**Développement** : `mvn spring-boot:run` pour le backend (port 8080) et `npm run dev` dans `frontend/` (le serveur Vite relaie `/api`).

**Tests** : `mvn test` (ajouter `-Dskip.npm -Dskip.installnodenpm` pour ne pas rebuilder le front). Ils remplacent le CLI `claude` par un script qui rejoue des sorties stream-json, et Jira par un faux serveur : aucun appel réseau.

## Architecture

```
navigateur (React, TanStack Router, shadcn)
   │  REST /api/*    SSE pour les réponses streamées
   ▼
Spring Boot 4.1, un seul jar, virtual threads
   ├─ methodes/*.md        prompts des Méthodes (frontmatter : champs de record utilisés)
   ├─ prompts/*.md         prompts de la Spec et de la Roadmap
   ├─ Claude Code CLI      via spring-ai-claude-code-starter : claude -p en stream-json,
   │                       record en --json-schema, WebSearch pour la research
   ├─ AtlassianClient      Jira v3 (Basic, bulk, ADF, Retry-After) et Confluence v2 (pages en ADF, versions)
   └─ JdbcClient + Flyway ─▶ SQLite (une connexion)
```

- **`record`** : une sortie structurée (`--json-schema`), au schéma commun à toutes les Méthodes, porte les sorties d'un tour (round, Décisions proposées, Questions, modifications de Map, prototype). Seules les Décisions demandent une confirmation.
- Les messages sont stockés en blocs (texte, puis `record` en bloc `tool_use`) et rejoués en texte à chaque appel, `record` compris.
- Le contenu HTML d'un prototype est non fiable : iframe `sandbox="allow-scripts"` sans `allow-same-origin`, CSP sans réseau, et refus serveur des requêtes `Origin: null`.

## Organisation du dépôt

| Chemin | Contenu |
|---|---|
| `src/main/java/productdesigner/` | Backend : un contrôleur par zone (Projet, Session, Map, Décision, Spec, Roadmap, Jira, Confluence) |
| `src/main/resources/db/migration/` | Schéma SQLite (Flyway) |
| `frontend/src/` | Pages et composants du front |
| `.scratch/productdesigner-mvp/` | Map de conception, Questions résolues, Spec du MVP, prototype de l'écran de Session |
| `.scratch/productdesigner-build/` | Roadmap et Tickets d'implémentation, avec notes de réalisation |
| `docs/adr/` | Décisions d'architecture prises après la Spec du MVP |
| `docs/agents/` | Conventions du tracker local et du domaine |

## État de validation

MVP terminé : 22 Tickets de la Roadmap, plus les suites de revue et du test réel. Tests automatisés sans appel réseau (faux CLI Claude Code, faux Jira et Confluence).

Depuis le 2026-11-22, Claude passe par le CLI Claude Code ([ADR 0004](docs/adr/0004-claude-code-cli.md)) ; Amazon Bedrock est retiré. Les parcours ci-dessous restent à revérifier en réel par ce chemin.

Vérifié en réel le 2026-11-03, alors sur Bedrock (détail : [`.scratch/productdesigner-build/issues/24-test-reel.md`](.scratch/productdesigner-build/issues/24-test-reel.md)) :

| Parcours | Réel | Avec |
|---|---|---|
| Tracé de la Map, Session grilling, Décisions, clôture | oui | Amazon Bedrock, Opus 5 |
| Spec (2 versions) et Roadmap | oui | Amazon Bedrock, Opus 5 |
| Export et ré-export Jira | oui | Jira Cloud, token scopé |
| Publication Confluence (2 versions) | oui | Confluence Cloud, token scopé |
| Research (recherche web) | **non** | indisponible sur Bedrock ; désormais `WebSearch` du CLI |
| Prototype | non | testé contre un faux serveur ; isolation de l'iframe vérifiée dans Chromium |

Décisions prises après la Spec du MVP : [`docs/adr/`](docs/adr/).

## Licences

Les fichiers de Méthode et le prompt de Roadmap sont adaptés de [mattpocock/skills](https://github.com/mattpocock/skills), sous licence MIT ; voir [`LICENSES/mattpocock-skills.txt`](LICENSES/mattpocock-skills.txt). Ce projet n'est ni affilié à son auteur ni approuvé par lui.
