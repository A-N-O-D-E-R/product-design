# Socle : jar, front servi, SQLite, config locale

Type: task
Status: resolved
Jalon: J1 · Socle et Session grilling de bout en bout
Blocked by: 
Exigences: E-31
Décisions: D-1, D-3, D-12, D-25, D-27

## Quoi

Ce ticket pose le socle technique. C'est le seul ticket purement technique de la Roadmap.

Il met en place un projet Maven Spring Boot 4.1 sous Java 25, avec le front Vite (TS/TanStack/shadcn). Le front est buildé par `frontend-maven-plugin` puis servi en statique. Il comprend aussi :

- un fallback SPA : toute route hors `/api` renvoie `index.html` ;
- Flyway sur SQLite, avec la base dans `~/.productdesigner/data.db` ;
- la config locale `~/.productdesigner/application.yml`, chargée via `spring.config.import` ;
- les virtual threads activés ;
- un bean `AnthropicClient` ;
- le fichier `LICENSES/mattpocock-skills.txt`.

## Critères d'acceptation

- [x] `mvn package` produit un jar unique ; `java -jar` sert l'UI sur localhost.
- [x] Ouvrir une URL profonde (ex. `/projets/1`) renvoie l'UI, pas une 404.
- [x] La base et ses migrations sont créées au premier lancement.
- [x] Si la clé Anthropic est absente, l'action qui l'utilise affiche un message explicite.

## Comments

- 2026-11-01 : fait. `JAVA_HOME=~/.sdkman/candidates/java/25.0.2-oracle mvn package`, puis `java -jar target/productdesigner-0.1.0-SNAPSHOT.jar`. `SocleTest` couvre les 4 critères. La clé Anthropic se met dans `productdesigner.anthropic.api-key` ; tant qu'elle manque, `/api/status` renvoie le message et l'UI l'affiche en bandeau. Le dossier de la base peut être déplacé via `productdesigner.data-dir`.
