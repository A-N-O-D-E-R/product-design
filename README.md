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
- Un fournisseur LLM : API Anthropic (défaut), OpenAI ou compatible, Amazon Bedrock, ou Ollama en local (voir [Fournisseur LLM](#fournisseur-llm)).
- Pour l'export, un site Atlassian Cloud, un email et un [token API](https://id.atlassian.com/manage-profile/security/api-tokens) (non scopé). La même config sert à Jira et à Confluence.

## Configuration

Les secrets vivent hors du dépôt, dans `~/.productdesigner/application.yml` (importé au démarrage) :

```yaml
spring:
  ai:
    anthropic:
      api-key: sk-ant-...
      # chat.options.model: claude-opus-5-5   # défaut
```

### Jira et Confluence

Clients fournis par [atlassian-springboot-starter](../atlassian-springboot-starter) (`anode.atlassian.*`). Même compte Atlassian pour les deux. Le token API conseillé est **scopé** (« Create API token with scopes ») ; il passe par la passerelle `api.atlassian.com`, qui demande le `cloudId` du site (`https://<site>.atlassian.net/_edge/tenant_info`) :

```yaml
anode:
  atlassian:
    base-url: https://<site>.atlassian.net
    email: moi@exemple.fr
    api-token: <token API scopé>
    cloud-id: <cloudId>          # absent : token sans scope, appels directs au site
```

Scopes minimaux :

| Produit | Scopes |
|---|---|
| Jira | `read:jira-work`, `write:jira-work` |
| Confluence | `read:space:confluence`, `read:page:confluence`, `write:page:confluence` |

Un token expire au bout d'un an au plus ; l'app affiche alors « Token Jira expiré ou invalide ».

### Fournisseur LLM

Via [Spring AI](https://docs.spring.io/spring-ai/reference/) : `spring.ai.model.chat` choisit le fournisseur, ses réglages suivent les propriétés Spring AI (`spring.ai.<fournisseur>.*`).

```yaml
spring:
  ai:
    model:
      chat: anthropic          # anthropic (défaut) | openai | bedrock-converse | ollama
    openai:                    # OpenAI, ou toute API compatible via base-url
      api-key: sk-...
      chat.options.model: gpt-5
    bedrock:
      aws:
        region: eu-west-1
        profile.name: mon-profil-aws   # profil SSO compris (aws sso login --profile mon-profil-aws)
      converse.chat.options.model: eu.anthropic.claude-opus-5-5-v1:0
    ollama:
      base-url: http://localhost:11434
      chat.options.model: qwen3
```

Le modèle doit savoir appeler des outils : le tool `record` porte les sorties structurées de chaque tour. Sur Bedrock, la chaîne AWS standard s'applique sans profil (variables `AWS_*`, `~/.aws/config`, rôle d'instance).

Ce qui dépend du fournisseur :
- **Recherche web** (Méthode research) : avec `productdesigner.recherche.tavily-api-key` (clé [Tavily](https://tavily.com), payante au-delà du quota gratuit), l'app exécute elle-même l'outil `web_search`, sur tout fournisseur. Sans clé, seule l'API Anthropic en a une (recherche serveur) ; ailleurs une Question *research* ne peut pas être ouverte.
- **Effort et prompt caching** : réglés pour Anthropic seulement ; les autres fournisseurs gardent leurs défauts (`spring.ai.<fournisseur>.chat.options.*`, dont `max-tokens`).
- **Coût estimé** : `productdesigner.tarifs.<modèle>` ne connaît que les prix de l'API Anthropic ; un autre modèle s'affiche « inconnu » sans entrée de tarif.

Sans fournisseur LLM configuré, l'app démarre et affiche un bandeau ; sans config Jira, l'export l'indique. Autres réglages (valeurs par défaut dans [`src/main/resources/application.yml`](src/main/resources/application.yml), surchargeables au même endroit) :

| Propriété | Défaut | Rôle |
|---|---|---|
| `productdesigner.data-dir` | `~/.productdesigner` | Dossier de la base `data.db` |
| `productdesigner.tarifs.<modèle>` | Opus 5.5, Sonnet 5.5 | $ par million de tokens, pour le coût estimé |
| `productdesigner.contexte.fenetre` / `.seuil` | `1000000` / `0.8` | Bandeau « Continuer dans une nouvelle Session » |
| `productdesigner.jira.backoff-ms` | `2000` | Base du backoff sur un 429 Jira |
| `productdesigner.recherche.tavily-api-key` | vide | Recherche web client de la research, tout fournisseur |

## Lancer

```sh
JAVA_HOME=~/.sdkman/candidates/java/25.0.2-oracle mvn package
java -jar target/productdesigner-0.1.0-SNAPSHOT.jar
```

Puis http://localhost:8080. Le jar contient le front ; la base et ses migrations sont créées au premier lancement.

**Développement** : `mvn spring-boot:run` pour le backend (port 8080) et `npm run dev` dans `frontend/` (le serveur Vite relaie `/api`).

**Tests** : `mvn test` (ajouter `-Dskip.npm -Dskip.installnodenpm` pour ne pas rebuilder le front). Ils tournent contre de faux serveurs (Anthropic, OpenAI, Tavily, Jira, Confluence), sans appel réseau.

## Architecture

```
navigateur (React, TanStack Router, shadcn)
   │  REST /api/*    SSE pour les réponses streamées
   ▼
Spring Boot 4.1, un seul jar, virtual threads
   ├─ methodes/*.md        prompts des Méthodes (frontmatter : champs de record utilisés)
   ├─ prompts/*.md         prompts de la Spec et de la Roadmap
   ├─ Llm (Spring AI)      streaming, tool `record`, sortie structurée ; Anthropic, OpenAI, Bedrock, Ollama
   │                       (effort, prompt caching et recherche serveur sur Anthropic)
   ├─ Recherche            outil web_search exécuté par l'app (Tavily), tout fournisseur
   ├─ JiraService          export Roadmap vers Jira v3 (bulk, ADF), via atlassian-springboot-starter
   ├─ ConfluenceService    publication de la Spec dans Confluence v2 (pages en ADF, versions)
   ├─ Atlassian            config absente, 401, reprises sur 429 (Retry-After) pour les deux
   ├─ controller ─▶ service ─▶ repository   (une zone = un trio ; seuls les repositories écrivent du SQL)
   └─ JdbcClient + Flyway ─▶ SQLite (une connexion)
```

- **`record`** : un seul tool, au schéma strict commun à toutes les Méthodes, porte les sorties structurées d'un tour (round, Décisions proposées, Questions, modifications de Map, prototype). Seules les Décisions demandent une confirmation.
- Les messages sont stockés dans un format neutre (blocs `text`, `tool_use`, `tool_result`) et convertis en messages Spring AI à chaque tour (`Conversation`). Les blocs propres à un fournisseur (thinking, résultats de recherche serveur chiffrés) ne sont pas rejoués.
- Le contenu HTML d'un prototype est non fiable : iframe `sandbox="allow-scripts"` sans `allow-same-origin`, CSP sans réseau, et refus serveur des requêtes `Origin: null`.

## Organisation du dépôt

| Chemin | Contenu |
|---|---|
| `src/main/java/com/anode/product/designer/` | Backend en 3 couches : `controller` (HTTP et SSE seulement), `service` (règles métier, LLM, Jira, Confluence, transactions), `repository` (tout le SQL, via `JdbcClient`). Une zone = un contrôleur, un service, un repository (Projet, Session, Map, Décision, Spec, Roadmap, Consommation) |
| `src/main/resources/db/migration/` | Schéma SQLite (Flyway) |
| `frontend/src/` | Front en 3 couches comme le backend : `api/` (un module par zone : types + appels REST/SSE, via `client.ts`), `hooks/` (état et actions : `useSession`, `useProjet`, `useDecisions`), `pages/` et `components/` (affichage seulement) |
| `.scratch/productdesigner-mvp/` | Map de conception, Questions résolues, Spec du MVP, prototype de l'écran de Session |
| `.scratch/productdesigner-build/` | Roadmap et Tickets d'implémentation, avec notes de réalisation |
| `docs/adr/` | Décisions d'architecture prises après la Spec du MVP |
| `docs/agents/` | Conventions du tracker local et du domaine |

## État de validation

MVP terminé : 22 Tickets de la Roadmap, plus les suites de revue et du test réel. Tests automatisés sans appel réseau (faux serveurs Anthropic, OpenAI, Tavily, Jira et Confluence).

Vérifié en réel le 2026-11-03 (détail : [`.scratch/productdesigner-build/issues/24-test-reel.md`](.scratch/productdesigner-build/issues/24-test-reel.md)) :

| Parcours | Réel | Avec |
|---|---|---|
| Tracé de la Map, Session grilling, Décisions, clôture | oui | Amazon Bedrock, Opus 5 |
| Spec (2 versions) et Roadmap | oui | Amazon Bedrock, Opus 5 |
| Export et ré-export Jira | oui | Jira Cloud, token scopé |
| Publication Confluence (2 versions) | oui | Confluence Cloud, token scopé |
| Research (recherche web) | **non** | demande une clé API Anthropic (indisponible sur Bedrock) |
| Prototype | non | testé contre un faux serveur ; isolation de l'iframe vérifiée dans Chromium |
| API Anthropic directe | non | pas de clé disponible |

Ce test réel date d'avant Spring AI (Bedrock via l'endpoint Mantle) : Bedrock Converse, OpenAI réel, Ollama et Tavily réel restent à vérifier ([ADR 0004](docs/adr/0004-fournisseur-llm-spring-ai.md)).

Décisions prises après la Spec du MVP : [`docs/adr/`](docs/adr/).

## Licences

Les fichiers de Méthode et le prompt de Roadmap sont adaptés de [mattpocock/skills](https://github.com/mattpocock/skills), sous licence MIT ; voir [`LICENSES/mattpocock-skills.txt`](LICENSES/mattpocock-skills.txt). Ce projet n'est ni affilié à son auteur ni approuvé par lui.
