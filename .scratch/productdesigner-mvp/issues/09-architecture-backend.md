# Architecture backend

Type: grilling
Status: resolved
Blocked by: 02

## Question

Persistance SQLite (JPA, JDBC, jOOQ ?), streaming SSE vers le front, exécution des research en tâche de fond, chargement des fichiers de Méthode, packaging un jar servant le front Vite ?

## Answer

- **Persistance :** Spring `JdbcClient`, Flyway, driver xerial `sqlite-jdbc`. Pas de JPA : le dialecte Hibernate pour SQLite pose problème et le modèle est petit (~12 tables). Pas de jOOQ : un générateur de code est superflu à cette taille.
- **Streaming :** `SseEmitter` sur virtual threads (`spring.threads.virtual.enabled=true`). Le flux `createStreaming()` du SDK est relayé événement par événement ; `MessageAccumulator` reconstitue le message, persisté en blocs bruts. Pas de WebFlux.
- **Research en tâche de fond :** `@Async` sur un exécuteur à virtual threads. La progression est écrite en base dans la Session, et le front la récupère par polling. Au démarrage, toute research restée `active` passe en échec ; l'utilisateur peut la relancer. Pas de file de messages.
- **Méthodes :** fichiers `classpath:methodes/*.md`, frontmatter YAML lu avec SnakeYAML (déjà fourni par Spring Boot), chargés une fois au démarrage. Les fichiers dérivés des skills de Matt Pocock portent l'en-tête MIT. Pas de rechargement à chaud.
- **Packaging :** Maven avec `frontend-maven-plugin`. Le build Vite est copié dans `static/` ; un contrôleur renvoie `index.html` pour toute route hors `/api`. Livraison en un seul jar.
- **Config locale :** `~/.productdesigner/application.yml`, importé via `spring.config.import`, contient la clé Anthropic et l'email + token Jira. Base de données dans `~/.productdesigner/data.db`. Aucun secret dans le dépôt.
