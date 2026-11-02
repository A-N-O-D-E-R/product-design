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

## Prérequis

- **Java 25** (ex. `sdk install java 25.0.2-oracle`) et Maven 3.9+.
- Node n'est pas requis : le build Maven installe sa propre copie (v20.20) pour construire le front.
- Une clé API Anthropic ; pour l'export, un site Jira Cloud, un email et un [token API](https://id.atlassian.com/manage-profile/security/api-tokens) (non scopé).

## Configuration

Les secrets vivent hors du dépôt, dans `~/.productdesigner/application.yml` (importé au démarrage) :

```yaml
productdesigner:
  anthropic:
    api-key: sk-ant-...
    # model: claude-opus-5-5          # défaut
  jira:
    site: https://<site>.atlassian.net
    email: moi@exemple.fr
    token: <token API Atlassian>
```

Sans clé Anthropic, l'app démarre et affiche un bandeau ; sans config Jira, l'export l'indique. Autres réglages (valeurs par défaut dans [`src/main/resources/application.yml`](src/main/resources/application.yml), surchargeables au même endroit) :

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

**Tests** : `mvn test` (ajouter `-Dskip.npm -Dskip.installnodenpm` pour ne pas rebuilder le front). Ils tournent contre un faux serveur Anthropic et un faux Jira, sans appel réseau.

## Architecture

```
navigateur (React, TanStack Router, shadcn)
   │  REST /api/*    SSE pour les réponses streamées
   ▼
Spring Boot 4.1, un seul jar, virtual threads
   ├─ methodes/*.md        prompts des Méthodes (frontmatter : champs de record utilisés)
   ├─ prompts/*.md         prompts de la Spec et de la Roadmap
   ├─ SDK Java Anthropic   streaming, tool `record` strict, web search, prompt caching
   ├─ JiraClient           API REST v3 (Basic, bulk, ADF, Retry-After)
   └─ JdbcClient + Flyway ─▶ SQLite (une connexion)
```

- **`record`** : un seul tool, au schéma strict commun à toutes les Méthodes, porte les sorties structurées d'un tour (round, Décisions proposées, Questions, modifications de Map, prototype). Seules les Décisions demandent une confirmation.
- Les messages sont stockés en blocs bruts du SDK et rejoués tels quels (blocs thinking et résultats de recherche chiffrés compris).
- Le contenu HTML d'un prototype est non fiable : iframe `sandbox="allow-scripts"` sans `allow-same-origin`, CSP sans réseau, et refus serveur des requêtes `Origin: null`.

## Organisation du dépôt

| Chemin | Contenu |
|---|---|
| `src/main/java/productdesigner/` | Backend : un contrôleur par zone (Projet, Session, Map, Décision, Spec, Roadmap, Jira) |
| `src/main/resources/db/migration/` | Schéma SQLite (Flyway) |
| `frontend/src/` | Pages et composants du front |
| `.scratch/productdesigner-mvp/` | Map de conception, Questions résolues, Spec du MVP, prototype de l'écran de Session |
| `.scratch/productdesigner-build/` | Roadmap et Tickets d'implémentation, avec notes de réalisation |
| `docs/agents/` | Conventions du tracker local et du domaine |

## Licences

Les fichiers de Méthode et le prompt de Roadmap sont adaptés de [mattpocock/skills](https://github.com/mattpocock/skills), sous licence MIT ; voir [`LICENSES/mattpocock-skills.txt`](LICENSES/mattpocock-skills.txt). Ce projet n'est ni affilié à son auteur ni approuvé par lui.
