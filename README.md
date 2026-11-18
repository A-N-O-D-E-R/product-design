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

> Migration : `anode.atlassian.*` (atlassian-springboot-starter) est remplacé par `anode.jira.*` et `anode.confluence.*`.

| Propriété | Défaut | Rôle |
|---|---|---|
| `spring.datasource.url` / `.username` / `.password` | Postgres du `compose.yaml` en dev | Base Postgres 17 (voir [Base de données](#base-de-données)) |
| `productdesigner.tarifs.<modèle>` | Opus 5.5, Sonnet 5.5 | $ par million de tokens, pour le coût estimé |
| `productdesigner.contexte.fenetre` / `.seuil` | `1000000` / `0.8` | Bandeau « Continuer dans une nouvelle Session » |
| `productdesigner.allowed-users` | vide | Qui peut entrer (voir Sécurité) |
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
