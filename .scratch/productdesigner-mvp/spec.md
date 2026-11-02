# Spec : productDesigner MVP

Version : 2 · 2026-11-01 · source : `map.md` + `issues/01..11`

## 1. Problème et Destination

Quand on a une idée de projet, elle reste floue : on mélange les décisions prises, les questions ouvertes et les intuitions, et on passe au code (ou au Jira) trop tôt. productDesigner est un logiciel d'Interview guidée par LLM. Il fait préciser sa pensée à l'utilisateur, une Question à la fois, puis produit une Spec, une Roadmap et des Tickets qu'on peut exporter vers Jira.

Destination du MVP : un outil local, mono-utilisateur, qui couvre le parcours complet. On décrit un Projet, on trace sa Map, on résout ses Questions en Sessions, puis on génère la Spec, la Roadmap et les Tickets, qu'on exporte vers Jira Cloud.

## 2. Utilisateurs et parcours clés

**Utilisateur** : une seule personne (le développeur ou designer produit) qui fait tourner l'app sur sa machine [D-3].

**P1. Créer un Projet et tracer sa Map**
L'utilisateur crée un Projet et décrit son idée. La 1re Session applique la Méthode wayfinder et produit la Map : Destination, Questions typées avec leurs dépendances, Brouillard et Hors périmètre [D-6].

**P2. Résoudre une Question**
L'utilisateur ouvre une Question de la frontière. La Session applique la Méthode qui correspond au type de la Question. Le LLM pose des rounds de questions avec ses recommandations. L'utilisateur répond question par question, puis confirme les Décisions proposées. À la clôture de la Session, la Question passe à `resolved` [D-15, D-16, D-20].

**P3. Research en tâche de fond**
Une Question de type research tourne sans l'utilisateur. Le LLM fait des recherches web et écrit un Artefact markdown. L'utilisateur consulte le résultat et confirme les Décisions [D-8, D-26].

**P4. Prototype**
Une Question de type prototype produit un Artefact HTML, rendu dans une iframe `sandbox`. L'utilisateur réagit au prototype pour trancher [D-8].

**P5. Générer la Spec**
Quand la Map n'a plus de Question ouverte, ou si l'utilisateur force la génération, l'app produit une nouvelle version de la Spec à partir des Décisions [D-7, D-13].

**P6. Générer la Roadmap et les Tickets, puis exporter**
À partir d'une version de la Spec, l'app génère les Jalons et les Tickets. L'utilisateur les édite, puis les exporte vers Jira Cloud : chaque Jalon devient un Epic, chaque Ticket une Story. Il peut ré-exporter après modification [D-21, D-22, D-23].

## 3. Périmètre / Hors périmètre

**Dans le périmètre** : les parcours P1 à P6 ; les Méthodes grilling, wayfinder, research et prototype ; l'export Jira Cloud ; une UI en français.

**Hors périmètre** :
- auth, multi-utilisateur, BYOK ;
- connecteurs Linear et GitHub ;
- autres Méthodes (TDD, code-review…) ;
- fusion automatique des versions de Spec ;
- OAuth Jira ;
- Docker ;
- UI multilingue ;
- évaluation de la qualité des Interviews ;
- graphe de la Map (le panneau latéral simple suffit) [D-9].

## 4. Modèle de domaine

Le vocabulaire est défini dans `GLOSSARY.md`. Une Question n'est pas un Ticket : la Question est une unité de décision, le Ticket une unité d'implémentation.

| Entité | Attributs clés | Relations |
|---|---|---|
| Projet | nom | 1–1 Map, 1–n Session, 1–n Spec |
| Map | destination, notes | 1–n Brouillard, 1–n HorsPérimètre, 1–n Question |
| Question | numéro, titre, corps, type (`grilling`/`wayfinder`/`research`/`prototype`/`tâche`), statut | n–n `blocked_by` Question, 1–n Décision |
| Session | méthode, statut `active`/`close`, `continued_from` optionnel | Projet ; Question optionnelle (absente = tracé de la Map) ; 1–n Message ; 1–n Artefact |
| Message | rôle, blocs JSON bruts du SDK, `usage`, `incomplet` | Session |
| Décision | numéro `D-n` par Projet, texte, `confirmed_at` | Question, Session |
| Artefact | type `prototype_html`/`research_md`, contenu | Session |
| Spec | version, markdown, date | Projet ; 1–n Jalon |
| Jalon | ordre, titre, `jira_key` | Spec ; 1–n Ticket |
| Ticket | ordre, titre, corps (inclut les critères), `jira_key` | Jalon ; n–n `blocked_by` Ticket |

La Roadmap n'est pas une entité. C'est la liste ordonnée des Jalons d'une version de Spec [D-19].

**Cycle de vie d'une Question** [D-20] :
- `open` passe à `claimed` à l'ouverture d'une Session sur la Question ;
- `claimed` passe à `resolved` à la clôture de la Session, s'il y a au moins une Décision, et revient à `open` s'il n'y en a aucune ;
- `hors_périmètre` est atteignable depuis n'importe quel statut.

## 5. Exigences fonctionnelles

### Projet et Map
- **E-1** Créer un Projet crée sa Map vide et ouvre la Session de tracé, qui applique la Méthode wayfinder [D-6].
- **E-2** La Session de tracé remplit la Map : Destination, Questions typées avec `blocked_by`, Brouillard et Hors périmètre [D-6, D-16].
- **E-3** L'utilisateur peut éditer la Map à la main. Promouvoir un Brouillard le supprime et crée une Question à la place [D-20].
- **E-4** La frontière liste les Questions `open` dont toutes les dépendances `blocked_by` sont `resolved`, triées par numéro.
- **E-5** L'utilisateur peut passer n'importe quelle Question en `hors_périmètre` [D-20].

### Session
- **E-6** Ouvrir une Question crée une Session avec la Méthode qui correspond à son type, et passe la Question à `claimed` [D-20].
- **E-7** La réponse du LLM est streamée dans le fil de la Session au fur et à mesure qu'elle arrive [D-25].
- **E-8** Un round s'affiche comme une carte. Chaque question y porte un id, la recommandation du LLM, une case « Accepter la reco » cochée par défaut et un champ de réponse libre. Un bouton unique envoie toutes les réponses au format `Q1: …` [D-15, D-17].
- **E-9** Un champ de message libre, hors round, est toujours disponible [D-15].
- **E-10** Les Décisions proposées par le LLM apparaissent dans le panneau « Décisions proposées ». Chacune se confirme ou se modifie. Une Décision n'est persistée, avec son numéro `D-n`, qu'une fois confirmée [D-17].
- **E-11** Le panneau latéral affiche trois listes : Décisions proposées, Décisions et Questions ouvertes [D-15].
- **E-12** Si le LLM ne produit pas de sortie structurée, l'app le relance automatiquement une fois. En cas de nouvel échec, elle affiche le texte seul avec un avertissement [D-18].
- **E-13** Clore une Session applique le cycle de vie de la Question : `resolved` si au moins une Décision a été confirmée, `open` sinon [D-20].
- **E-14** Une Session rouverte reprend son historique de Messages à l'identique [D-19].

### Research et prototype
- **E-15** Une Session research s'exécute en tâche de fond. Le LLM peut faire des recherches web, et l'app affiche sa progression [D-8, D-26].
- **E-16** Si l'app redémarre pendant une research, celle-ci passe en échec et l'utilisateur peut la relancer [D-26].
- **E-17** Une research produit un Artefact markdown consultable. Ses Décisions se confirment comme dans E-10.
- **E-18** Une Session prototype produit un Artefact HTML, rendu dans une iframe `sandbox`, sans accès au parent [D-8].

### Spec
- **E-19** L'app propose de générer la Spec quand la Map n'a plus de Question `open` ou `claimed`. L'utilisateur peut aussi forcer la génération à tout moment [D-7].
- **E-20** La Spec suit le gabarit : les sections 1 à 7 sont toujours présentes (« non décidé » quand rien n'est tranché). Les sections optionnelles n'apparaissent que si des Décisions les couvrent [D-13].
- **E-21** Chaque exigence de la Spec est numérotée `E-n` et cite ses Décisions sources `D-n` [D-14].
- **E-22** Chaque génération crée une nouvelle version, sans fusion. Les versions précédentes restent consultables [D-7].

### Roadmap et Tickets
- **E-23** À partir d'une version de Spec, l'app génère des Jalons ordonnés par valeur et par risque, chacun regroupant 3 à 10 Tickets [D-21, D-22].
- **E-24** Chaque Ticket est un vertical slice d'au plus un jour. Il contient ses critères d'acceptation, les exigences `E-n` qu'il couvre et ses dépendances `blocked_by` [D-21].
- **E-25** Avant l'export, l'utilisateur peut renommer, éditer, ajouter, supprimer et déplacer des Tickets entre Jalons. Régénérer remplace l'ensemble [D-22].

### Export Jira
- **E-26** L'export crée un Epic par Jalon, puis les Stories de ses Tickets, rattachées par `parent`. Les dépendances `blocked_by` deviennent des liens « Blocks » [D-10, D-23].
- **E-27** La description des Tickets est convertie en ADF [D-10].
- **E-28** Un ré-export met à jour les issues qui ont déjà un `jira_key` et crée celles qui n'en ont pas. Il ne supprime jamais rien dans Jira. Il liste plutôt les clés des Tickets supprimés localement [D-23].
- **E-29** Une nouvelle version de Spec donne une Roadmap sans `jira_key`. Son export crée de nouveaux Epics, sans toucher aux anciens [D-23].
- **E-30** Un échec partiel de l'export est affiché Ticket par Ticket, et seuls les éléments en erreur sont rejoués. Un 401 affiche « token Jira expiré ou invalide » [D-10, D-11].

### Configuration
- **E-31** La clé Anthropic, l'URL du site Jira, l'email et le token sont lus depuis la config locale de l'utilisateur. Si l'un d'eux manque, l'app affiche un message explicite au moment de l'action concernée [D-27].

### Contexte, coûts et erreurs LLM
- **E-32** Au-delà de 80 % de la fenêtre de contexte (calculé depuis `usage`, seuil configurable), un bandeau propose « Continuer dans une nouvelle Session ». La nouvelle Session est liée par `continued_from` et amorcée avec la Map et les Décisions [D-28].
- **E-33** Les tokens et le coût estimé s'affichent par Session et par Projet, calculés à partir du `usage` persisté sur chaque Message et d'une table de tarifs en config [D-29].
- **E-34** Un stream coupé conserve le texte partiel, marque le Message `incomplet` et propose « Réessayer ». Le Message partiel est exclu de l'historique envoyé à l'API [D-30].
- **E-35** Les erreurs 401 et 400 de l'API Anthropic s'affichent dans le fil avec un message clair. Les 429, 529 et 5xx passent par les retries du SDK [D-30].

## 6. Décisions structurantes

### Cadrage (notes de la Map)
- **D-1** Stack : Java 25 et Spring Boot 4.1 côté backend ; TypeScript, Vite, TanStack et shadcn côté front.
- **D-2** Le LLM est Claude, avec la clé côté serveur. L'UI est en français, les livrables dans la langue de l'utilisateur.
- **D-3** L'app est mono-utilisateur, tourne en localhost avec SQLite et n'a pas d'auth.
- **D-4** Les Méthodes du MVP sont grilling, wayfinder, research et prototype, définies dans des fichiers markdown versionnés.
- **D-5** Chaque Session consulte les skills `grilling` et `domain-modeling`.
- **D-6** Tout Projet a une Map. La 1re Session trace la Map, chaque Session suivante résout une seule Question.
- **D-7** La Spec est générée quand la Map est vide, ou si l'utilisateur la force. Les versions sont simples, sans fusion.
- **D-8** Research est le seul type AFK et tourne en tâche de fond. Le prototype HTML est rendu dans une iframe `sandbox`.
- **D-9** Le panneau latéral est une simple liste, pas un graphe.

### Issues résolues
- **D-10** [03](issues/03-api-jira-cloud.md) Pour Jira :
  - le lien entre Epic et Story passe par `parent: {key}` (Epic Link est déprécié) ;
  - on crée l'Epic via `POST /issue`, puis les Stories via `POST /issue/bulk`, par paquets de 50 au plus, en lisant `errors[]` même sur un 201 ;
  - la description est en ADF ;
  - on identifie l'Epic par `hierarchyLevel == 1` via `createmeta/{projet}/issuetypes`.
- **D-11** [03](issues/03-api-jira-cloud.md) Auth Jira en Basic avec `email:token` non scopé (le token expire au bout d'un an au plus). Sur un 429, on attend `Retry-After`, sinon backoff exponentiel avec jitter.
- **D-12** [01](issues/01-licence-skills-mattpocock.md) Les skills mattpocock sont sous licence MIT. On les reprend, verbatim ou adaptés, en gardant la notice : `LICENSES/mattpocock-skills.txt` et un en-tête dans chaque fichier de Méthode dérivé.
- **D-13** [04](issues/04-gabarit-spec.md) La Spec a un socle fixe de 7 sections, plus des sections optionnelles. Elle décrit le quoi, pas le comment.
- **D-14** [04](issues/04-gabarit-spec.md) Chaque exigence cite ses Décisions sources.
- **D-15** [05](issues/05-ecran-session.md) L'écran de Session suit le prototype `prototypes/05-ecran-session.html`.
- **D-16** [06](issues/06-sorties-structurees-llm.md) Le texte est streamé, et un tool `record(decisions[], questions[], round{questions[], recommandations[]})` porte les sorties structurées. Son schéma est unique et fixé dans le code. Chaque Méthode déclare en frontmatter les champs qu'elle utilise.
- **D-17** [06](issues/06-sorties-structurees-llm.md) L'utilisateur répond question par question, par id. Une Décision n'est persistée qu'après sa confirmation.
- **D-18** [06](issues/06-sorties-structurees-llm.md) Si `record` est absent, une relance automatique, puis un avertissement.
- **D-19** [07](issues/07-modele-donnees.md) Le modèle de données est celui de la section 4. Les Messages sont stockés en blocs SDK bruts. Il n'y a pas d'entité Roadmap.
- **D-20** [07](issues/07-modele-donnees.md) Le cycle de vie d'une Question est celui de la section 4. Promouvoir un Brouillard le supprime et crée une Question.
- **D-21** [08](issues/08-generation-roadmap-tickets.md) Un Ticket est un vertical slice d'au plus un jour, avec ses critères et ses `E-n`. Un Jalon est un incrément démontrable de 3 à 10 Tickets. Les dépendances `blocked_by` sont minimales.
- **D-22** [08](issues/08-generation-roadmap-tickets.md) La Roadmap est générée en structured output par version de Spec. L'édition est libre, et régénérer remplace tout.
- **D-23** [08](issues/08-generation-roadmap-tickets.md) Le ré-export s'appuie sur `jira_key` : création ou `PUT`, jamais de suppression dans Jira. Une nouvelle version de Spec donne de nouveaux Epics.
- **D-24** [02](issues/02-sdk-anthropic-vs-spring-ai.md) On utilise le SDK Java officiel Anthropic 3.x en direct, sous forme de bean `AnthropicClient`, sans Spring AI.
- **D-25** [09](issues/09-architecture-backend.md) Persistance avec `JdbcClient`, Flyway et `sqlite-jdbc`, sans JPA. Le streaming passe par `SseEmitter` sur virtual threads, et `MessageAccumulator` reconstitue la réponse avant persistance.
- **D-26** [09](issues/09-architecture-backend.md) La research tourne en `@Async` sur virtual threads, avec polling côté front. Après un redémarrage, elle passe en échec. Il faut gérer `pause_turn` et renvoyer les blocs `encrypted_content` à l'identique.
- **D-27** [09](issues/09-architecture-backend.md) Les Méthodes sont dans `classpath:methodes/*.md`, avec un frontmatter lu par SnakeYAML. Le build Maven, avec `frontend-maven-plugin`, produit un seul jar, et toute route hors `/api` renvoie vers `index.html`. Les secrets sont dans `~/.productdesigner/application.yml` et la base dans `~/.productdesigner/data.db`.

- **D-28** [10](issues/10-sessions-longues.md) L'état vit en base. À la saturation du contexte, une nouvelle Session est amorcée avec la Map et les Décisions, sans résumé maison ni compaction côté API.
- **D-29** [11](issues/11-couts-erreurs-llm.md) Le `usage` est persisté sur chaque Message. Le coût est estimé via une table de tarifs en config, sans plafond de budget.
- **D-30** [11](issues/11-couts-erreurs-llm.md) Les retries sont ceux du SDK. Un stream coupé est marqué `incomplet` avec « Réessayer ». Les erreurs 401 et 400 ont un message clair.

## 7. Questions ouvertes / Brouillard restant

- **Contenu détaillé des prompts de chaque Méthode** : relève de l'implémentation et se traite en Tickets, en adaptant les skills (D-12).

## Architecture (optionnelle, couverte par D-24 à D-27)

```
navigateur (Vite build servi en statique)
   │  REST /api/*      SSE /api/sessions/{id}/stream
   ▼
Spring Boot 4.1 (un seul jar, virtual threads)
   ├─ Méthodes : classpath:methodes/*.md (frontmatter outputs)
   ├─ LLM : AnthropicClient (createStreaming, tool record, web search, prompt caching)
   ├─ Research : @Async + statut en base
   ├─ Export : client HTTP Jira Cloud v3 (Basic, bulk, ADF, Retry-After)
   └─ JdbcClient + Flyway ─▶ ~/.productdesigner/data.db (SQLite)
```

## Contraintes non fonctionnelles (optionnelle)

- Aucun secret dans le dépôt [D-27].
- Le contenu HTML d'un prototype est non fiable : rendu uniquement dans une iframe `sandbox`, sans `allow-same-origin` [D-8].
- Prompt caching sur le fichier de Méthode et sur l'historique de la Session, pour limiter les coûts [D-24].
- Fichier `LICENSES/mattpocock-skills.txt` présent dans le jar [D-12].
