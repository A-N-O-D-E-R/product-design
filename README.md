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
- **Docker** : Postgres 17 en développement (`compose.yaml`) et dans les tests (Testcontainers).
- Node n'est pas requis : le build Maven installe sa propre copie (Node 22 et pnpm 10) pour construire le front.
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

### Research avec ai-scientist

Pour une Question **technique ou de machine learning**, la research peut confier une étude à [ai-scientist](https://github.com/A-N-O-D-E-R/ai-scientist)
en plus de la recherche web : un second outil, `ai_scientist`, proposé au modèle à côté de `web_search`. Il crée un job d'ideation
(`POST /v1/ideations`), l'attend (`GET /v1/jobs/{id}`) et rend des idées de recherche avec hypothèse, travaux connexes (recherche
bibliographique Semantic Scholar) et risques. Le résultat complet devient un **Artefact** de la Session (affiché comme un rapport),
en plus du rapport final. Lent (minutes) et payant : une étude par recherche, coût plafonné.

```yaml
productdesigner:
  ai-scientist:
    url: https://ai-scientist.exemple.org
    api-key: ais_...             # une clé d'API d'ai-scientist (Réglages > clés d'API)
    # budget-usd: 2   max-generations: 3   timeout: 20m   poll-interval: 10s
```
Sans `url` et `api-key`, l'outil n'est pas proposé. Un échec, une pause ou un délai revient au modèle en résultat d'outil : la research continue
sans lui. Le contrat est l'OpenAPI d'ai-scientist : `src/test/resources/contracts/ai-scientist-openapi.json` (copie de son instantané,
à resynchroniser) valide la requête envoyée et les champs lus (`AiScientistTest`). Pas encore : les *experiment runs*.

### Base de données

Postgres 17, schéma géré par Flyway (`V1` à `V20`), accès par `JdbcClient`.

- **Développement** : `mvn spring-boot:run` démarre le Postgres de `compose.yaml` (spring-boot-docker-compose) et en branche la
  datasource ; les données restent dans le volume Docker entre deux lancements.
- **Ailleurs** : `SPRING_DATASOURCE_URL` (`jdbc:postgresql://hôte:5432/productdesigner`), `SPRING_DATASOURCE_USERNAME`,
  `SPRING_DATASOURCE_PASSWORD`, et `SPRING_DOCKER_COMPOSE_ENABLED=false`.
- **Tests** : Testcontainers, une base neuve par classe de test.

> Les données d'une ancienne installation SQLite (`~/.productdesigner/data.db`) ne sont pas migrées : les migrations Flyway ont été
> réécrites pour Postgres (mêmes versions, dates en `timestamptz`, drapeaux en `boolean`). Une installation existante repart d'une base vide.

### Sécurité

Starter de sécurité de la collection (`anode.security.*`). Seul `/api/**` demande une connexion : le front statique et la page
de login sont publics, l'API répond `401` en `application/problem+json` (RFC 9457) sans connexion, et les écritures demandent le
jeton CSRF (cookie `XSRF-TOKEN` renvoyé en `X-XSRF-TOKEN`, ce que fait le front).

| Usage | Configuration |
|---|---|
| **Poste local** (défaut) | login *mock* : n'importe quel nom et mot de passe. `anode.security.mock: true` ; le serveur doit écouter en loopback (`server.address` = 127.0.0.1) et `Host` doit être local (DNS rebinding) : sinon le démarrage échoue |
| **Hébergé** | `PRODUCTDESIGNER_MOCK=false`, `PRODUCTDESIGNER_OIDC=true`, `HOST=0.0.0.0`, et le fournisseur : `spring.security.oauth2.client.registration.oidc.{client-id,client-secret,scope}` + `provider.oidc.issuer-uri` (plusieurs fournisseurs : une `registration` chacun) |
| Qui entre | `productdesigner.allowed-users` (nom, ou e-mail vérifié d'OIDC, séparés par des virgules). Vide : tout compte connecté. Avec OIDC seulement : le login mock donne à tous la même identité |

> L'application n'a **pas de données par utilisateur** : tout compte autorisé voit tous les Projets. Hébergée avec un
> fournisseur public (GitHub, Google), renseigner `allowed-users`.

**Clés d'API** pour les autres applications (idea-generator → `POST /api/projets/import`) : sans session ni CSRF, en
`Authorization: Bearer pd_...`.

```sh
# connecté dans le navigateur : créer une clé (affichée une seule fois)
curl -X POST $BASE/api/cles-api -H 'Content-Type: application/json' -d '{"nom":"idea-generator","jours":90}'
curl -X POST $BASE/api/projets/import -H "Authorization: Bearer pd_..." -H 'Content-Type: application/json' --data @idea.json
```
10 clés actives au plus, 1 à 365 jours, seul le SHA-256 est stocké, `DELETE /api/cles-api/{id}` révoque. Une clé ne gère pas les
clés (session de navigateur seulement).

### Jira et Confluence

Clients fournis par les starters `spring-boot-starter-jira` et `spring-boot-starter-confluence` de
[springboot-starter-collection](https://github.com/A-N-O-D-E-R/springboot-starter-collection) (`anode.jira.*` et
`anode.confluence.*`, même compte Atlassian pour les deux). Le token API conseillé est **scopé** (« Create API token with
scopes ») ; il passe par la passerelle `api.atlassian.com`, qui demande le `cloudId` du site
(`https://<site>.atlassian.net/_edge/tenant_info`) :

```yaml
anode:
  jira: &atlassian
    base-url: https://<site>.atlassian.net
    email: moi@exemple.fr
    api-token: <token API scopé>
    cloud-id: <cloudId>          # absent : token sans scope, appels directs au site
  confluence: *atlassian
```
Reprises : les starters réessaient les 429 (en attendant `Retry-After`) pour toute requête, et les 502/503/504 pour les seules
requêtes idempotentes (une création dont le résultat est inconnu n'est jamais rejouée à l'aveugle : l'export la retrouve
par recherche). Réglage : `anode.jira.retry.max-retries` (3), `.delay` (1 s), `.max-delay` (10 s), idem pour `confluence`.

**Vers digital-factory** : les Stories exportées portent l'étiquette `factory` (`productdesigner.jira.label-factory`, vide : aucune ; une
Story déjà exportée la reçoit au ré-export sans perdre ses autres étiquettes). Leur description reprend le texte et les critères du
Ticket, puis les **exigences couvertes avec leur texte et leurs Décisions sources** (`E-3 … [D-2, D-5]`, tel que la Spec les écrit) et
une ligne `Source : productDesigner, Projet …, Spec v…` (avec l'idée d'origine si le Projet vient d'un import). Les liens « Blocks »
entre Stories donnent l'ordre : la règle d'intake de digital-factory (`labels = factory AND status = "To Do"`) ne prend pas une Story
tant qu'une bloquante n'est pas terminée.

> Migration : `anode.atlassian.*` (atlassian-springboot-starter) est remplacé par `anode.jira.*` et `anode.confluence.*`.

| Propriété | Défaut | Rôle |
|---|---|---|
| `spring.datasource.url` / `.username` / `.password` | Postgres du `compose.yaml` en dev | Base Postgres 17 (voir [Base de données](#base-de-données)) |
| `productdesigner.tarifs.<modèle>` | Opus 5.5, Sonnet 5.5 | $ par million de tokens, pour le coût estimé |
| `productdesigner.contexte.fenetre` / `.seuil` | `1000000` / `0.8` | Bandeau « Continuer dans une nouvelle Session » |
| `productdesigner.allowed-users` | vide | Qui peut entrer (voir Sécurité) |
| `productdesigner.jira.label-factory` | `factory` | Étiquette des Stories exportées, prise par l'intake de digital-factory |
| `productdesigner.recherche.tavily-api-key` | vide | Recherche web client de la research, tout fournisseur |
| `productdesigner.ai-scientist.url` / `.api-key` | vide | Outil `ai_scientist` de la research (voir ci-dessus) |

## Docker

```sh
docker compose -f deploy/compose.yaml up -d --build      # app + Postgres, login mock, http://127.0.0.1:8080
```
`deploy/compose.yaml` publie le port sur `127.0.0.1` seul : le login mock n'a pas de mot de passe (`productdesigner.mock-publie-en-local`,
sans quoi le démarrage refuse d'écouter sur `0.0.0.0` avec le login mock). Hébergé : OIDC (voir [Sécurité](#sécurité)). L'image est
construite par le CI (`ghcr.io/a-n-o-d-e-r/productdesigner`).

## Contrat de l'API

`/api/openapi.json` (OpenAPI 3.1, derrière la connexion). `OpenApiTest` le compare à `src/test/resources/openapi.json` : un
changement d'API fait échouer le test ; relire le diff puis régénérer avec `UPDATE_OPENAPI=1 mvn test -Dtest=OpenApiTest`.

## Lancer

```sh
JAVA_HOME=~/.sdkman/candidates/java/25.0.2-oracle mvn package
java -jar target/productdesigner-0.3.0.jar   # avec SPRING_DATASOURCE_URL/USERNAME/PASSWORD et SPRING_DOCKER_COMPOSE_ENABLED=false
```

Puis http://localhost:8080. Le jar contient le front ; les migrations Flyway s'appliquent au premier lancement sur la base Postgres indiquée.

**Développement** : `mvn spring-boot:run` pour le backend (port 8080) et `pnpm dev` dans `frontend/` (le serveur Vite relaie `/api`). Dans `frontend/` : `pnpm lint`, `pnpm test` (vitest), `pnpm build`. Après tout changement de contrat REST : `UPDATE_OPENAPI=1 mvn test -Dtest=OpenApiTest` puis `pnpm api:types` (régénère `src/api/schema.d.ts`) ; un test échoue si les deux divergent.

**Tests** : `mvn test` (ajouter `-Dskip.pnpm -Dskip.installnodepnpm` pour ne pas rebuilder le front). Ils tournent contre de faux serveurs (Anthropic, OpenAI, Tavily, Jira, Confluence), sans appel réseau, et contre un vrai Postgres (Testcontainers, Docker requis).

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
   ├─ JiraService          export Roadmap vers Jira v3 (bulk, ADF), via spring-boot-starter-jira
   ├─ ConfluenceService    publication de la Spec dans Confluence v2 (pages en ADF, versions)
   ├─ Atlassian            config absente et messages dédiés pour 401 et 429 persistant (les reprises sont dans les starters)
   ├─ controller ─▶ service ─▶ repository   (une zone = un trio ; seuls les repositories écrivent du SQL)
   └─ JdbcClient + Flyway ─▶ Postgres 17
```

- **`record`** : un seul tool, au schéma strict commun à toutes les Méthodes, porte les sorties structurées d'un tour (round, Décisions proposées, Questions, modifications de Map, prototype). Seules les Décisions demandent une confirmation.
- Les messages sont stockés dans un format neutre (blocs `text`, `tool_use`, `tool_result`) et convertis en messages Spring AI à chaque tour (`Conversation`). Les blocs propres à un fournisseur (thinking, résultats de recherche serveur chiffrés) ne sont pas rejoués.
- Le contenu HTML d'un prototype est non fiable : iframe `sandbox="allow-scripts"` sans `allow-same-origin`, CSP sans réseau, et refus serveur des requêtes `Origin: null`.

## Organisation du dépôt

| Chemin | Contenu |
|---|---|
| `src/main/java/com/anode/product/designer/` | Backend en 3 couches : `controller` (HTTP et SSE seulement), `service` (règles métier, LLM, Jira, Confluence, transactions), `repository` (tout le SQL, via `JdbcClient`). Une zone = un contrôleur, un service, un repository (Projet, Session, Map, Décision, Spec, Roadmap, Consommation) |
| `src/main/resources/db/migration/` | Schéma Postgres (Flyway, V1 à V20) |
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

## Importer une idée d'idea-generator

`POST /api/projets/import` prend l'`idea.json` d'idea-generator (contrat `idea-brief.schema.json`, copie de
`digital-factory/factory-contracts` : le garder synchronisé) et amorce un Projet et sa Map :

| idea.json | productDesigner |
|---|---|
| `brief.name`, `brief.oneLiner` | nom et description du Projet |
| `brief.problem` | Destination de la Map |
| `brief.audience`, `mvp`, `stack`, `firstSteps` | notes de la Map |
| `brief.openQuestions` | Questions (grilling, ouvertes) |
| `brief.outOfScope` | Hors périmètre |
| `brief.risks`, `research.riskFactors`, `research.hypotheses` | Brouillard |
| `research` (ai-scientist) | artefact `research_md` d'une Session research close |
| `lineage[].sourceUrl` (ou `?source=`) | `projet.source_idea_url`, provenance en JSON dans `projet.source_idea` |

```sh
curl -X POST 'http://localhost:8080/api/projets/import?source=https://ideas.example.org/boards/7/ideas/42' \
  -H 'Content-Type: application/json' --data @idea.json
curl -X POST http://localhost:8080/api/projets/import -F file=@idea.json      # ou en fichier
```

201 avec les compteurs ; 422 si l'idea.json ne respecte pas le schéma (les violations sont dans le message) ; 409 si
la même source est déjà importée.
