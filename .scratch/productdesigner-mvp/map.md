# Map : productDesigner MVP

Label: wayfinder:map

## Destination

Spec MVP de productDesigner (périmètre, parcours, modèle de données, architecture, roadmap de construction), prête à découper en Tickets.

## Notes

- **Spec v2 (2026-11-01) : [`spec.md`](spec.md). Tickets : [`../productdesigner-build/roadmap.md`](../productdesigner-build/roadmap.md).**
- Domaine : logiciel d'interview guidée par LLM ; vocabulaire dans `GLOSSARY.md` (Question ≠ Ticket).
- Stack imposée : Java 25 + Spring Boot 4.1 ; front TS/Vite/TanStack/shadcn.
- Mono-utilisateur, localhost, SQLite, pas d'auth ; un jar Spring Boot sert le front buildé.
- LLM : Claude, clé côté serveur. UI en français, livrables dans la langue de l'utilisateur.
- Méthodes MVP : grilling, wayfinder, research, prototype ; définies en fichiers markdown versionnés.
- Tout Projet a une Map ; 1re Session trace la Map, chaque Session suivante résout une Question.
- Spec générée quand Map vide (ou forcée) ; versions simples sans fusion.
- Export Jira Cloud : Jalon = Epic, Ticket = Story ; email + API token en config locale.
- Research = seul type AFK, tourne en tâche de fond. Prototype HTML rendu en iframe `sandbox`.
- Panneau latéral simple (Décisions + Questions ouvertes), pas de graphe.
- Chaque Session : consulter skills `grilling` et `domain-modeling`.

## Decisions so far

- [Licence et réutilisation des skills de Matt Pocock](issues/01-licence-skills-mattpocock.md): MIT, réutilisation verbatim ou adaptée OK même en SaaS commercial, à condition de conserver la notice de copyright et le texte MIT.
- [SDK Anthropic Java ou Spring AI](issues/02-sdk-anthropic-vs-spring-ai.md): SDK Java officiel Anthropic 3.x en direct (bean `AnthropicClient`), car Spring AI n'est qu'une couche sur ce même SDK, figée en 2.72 et en retard sur l'API, et sa portabilité multi-fournisseurs est inutile pour un produit 100 % Claude.
- [API Jira Cloud pour Epic et Story](issues/03-api-jira-cloud.md): créer l'Epic (type `hierarchyLevel==1` découvert via `createmeta/{projet}/issuetypes`) puis les Stories en `POST /issue/bulk` (≤50) avec `parent: {key}` dans tous les types de projet (Epic Link déprécié), description en ADF, Basic email:token (expire ≤1 an), 429 géré via `Retry-After` + backoff.
- [Gabarit de la spec](issues/04-gabarit-spec.md): socle fixe de 7 sections (problème/destination, utilisateurs/parcours, périmètre, modèle de domaine, exigences numérotées, décisions, questions ouvertes) + sections optionnelles si des Décisions les couvrent ; le quoi, pas le comment ; chaque exigence cite ses Décisions sources.
- [Extraction des sorties structurées du LLM](issues/06-sorties-structurees-llm.md): texte streamé + tool `record` (decisions, questions, round) au schéma unique fixé dans le code ; la Méthode déclare en frontmatter les champs utilisés ; réponses par question (id) ; Décision persistée seulement après confirmation de l'utilisateur ; 1 relance auto si `record` absent.
- [Écran de Session](issues/05-ecran-session.md): fil streamé + rounds en carte (id, reco pré-acceptée, réponse libre, envoi groupé `Q1: …`) ; panneau droit Décisions proposées (confirmer/modifier une par une) / Décisions / Questions ouvertes ; prototype `prototypes/05-ecran-session.html`.
- [Modèle de données](issues/07-modele-donnees.md): Projet–Map (Brouillard, HorsPérimètre, Questions n–n blocked_by), Session (question optionnelle) → Messages en blocs SDK bruts, Décision `D-n`, Spec versionnée → Jalons → Tickets (`jira_key`), Artefact ; pas d'entité Roadmap ; Question open→claimed→resolved (retour open si 0 Décision), hors_périmètre depuis tout état.
- [Règles de génération roadmap et Tickets](issues/08-generation-roadmap-tickets.md): Ticket = vertical slice ≤1 j avec critères et exigences `E-n` ; Jalon = incrément démontrable de 3–10 Tickets ordonné valeur/risque ; `blocked_by` minimal exporté en lien « Blocks » ; génération en structured output par version de Spec ; édition libre avant export ; ré-export par `jira_key` (create/PUT, jamais de suppression Jira).
- [Architecture backend](issues/09-architecture-backend.md): `JdbcClient` + Flyway + sqlite-jdbc (pas de JPA) ; `SseEmitter` sur virtual threads ; research en `@Async` + polling, échec au redémarrage ; Méthodes `classpath:methodes/*.md` + SnakeYAML ; Maven + frontend-maven-plugin en un seul jar ; secrets dans `~/.productdesigner/application.yml`.
- [Sessions longues dépassant le contexte](issues/10-sessions-longues.md): l'état vit en base ; bandeau à 80 % du contexte (`usage`) proposant une nouvelle Session amorcée avec la Map et les Décisions ; `Session.continued_from` ; pas de compaction dans le MVP.
- [Suivi des coûts LLM et gestion des erreurs](issues/11-couts-erreurs-llm.md): `usage` persisté par Message, tokens et coût estimé par Session et par Projet ; retries du SDK ; stream coupé marqué `incomplet` + Réessayer ; messages clairs pour 401 et 400.

## Not yet specified

- Contenu détaillé des prompts de chaque Méthode : traité dans les Tickets qui introduisent chaque Méthode.

## Out of scope

- Évaluation de la qualité des interviews.
- Auth et multi-utilisateur ; BYOK.
- Connecteurs Linear et GitHub.
- Autres méthodes (TDD, code-review…).
- Fusion automatique des versions de spec.
- OAuth Jira ; Docker ; UI multilingue.
