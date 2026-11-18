# 5. Adopter la base A-N-O-D-E-R : starters de la collection

Date : 2026-11-18 — Statut : accepté (première étape ; Postgres, sécurité, frontend et Docker restent à faire)

## Contexte

productDesigner gérait Jira et Confluence avec un starter à part (`atlassian-springboot-starter`, jamais publié, version
`0.1.0-SNAPSHOT` : le projet ne se construisait que sur la machine où il avait été installé à la main), et ses propres
reprises sur 429 avec Resilience4j. Les trois autres applications utilisent `springboot-starter-collection`.

## Décision

- Coordonnées `io.github.a-n-o-d-e-r`, package de base `com.anode.productdesigner` (était `com.anode.product.designer`),
  version 0.2.0, BOM `springboot-starter-collection-dependencies` (qui apporte aussi le BOM Spring AI).
- Jira et Confluence passent par `spring-boot-starter-jira` et `spring-boot-starter-confluence`. Config : `anode.jira.*` et
  `anode.confluence.*` (remplacent `anode.atlassian.*`), avec `cloud-id` / `gateway-url` pour les tokens scopés.
- Resilience4j est supprimé : les reprises sont dans les starters (`TransientResponseRetry` : 429 avec `Retry-After`, 502/503/504
  seulement pour les requêtes idempotentes). Pas de reprise à l'aveugle d'un `POST /issue` dont le résultat est inconnu : c'est
  le rôle de la recherche de l'export (`creationIncertaineRetrouvee...`).
- Le convertisseur markdown → ADF (`Adf`) est dans les starters : `JiraDocuments.markdown`.
- `JiraService` utilise le client généré (création, création en masse, édition, liens, types d'issue ont été ajoutés à la spec
  OpenAPI filtrée de la collection). La réponse des types d'issue est lue en `Map` : Jira la nomme `issueTypes` ou `values`.

## Conséquences

- Il faut la collection 0.2.0 publiée pour construire hors de la machine de développement.
- Migration de config : `~/.productdesigner/application.yml` doit passer de `anode.atlassian.*` à `anode.jira.*` et
  `anode.confluence.*`.
- Pas encore fait (au moment de cet ADR) : Postgres (ADR 0007), sécurité (ADR 0006), pnpm / React Query / vitest, OpenAPI,
  Docker et compose, CI via le workflow réutilisable.
