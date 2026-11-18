# 8. Front : pnpm, types générés, React Query

Date: 2026-11-18 — Status: accepted. Aligne le front sur celui de digital-factory (ADR 0005).

## Décision

- **pnpm 10** (`packageManager`, `pnpm-lock.yaml`), versions exactes (`.npmrc save-exact`), **TypeScript 5.9.3** (au lieu de 7.0.2, avec `strict` et
  `DOM.Iterable` explicites), Node ≥ 22.22. Le build Maven installe Node et pnpm (frontend-maven-plugin) et utilise `--frozen-lockfile`.
- **Types de l'API générés** : `pnpm api:types` produit `src/api/schema.d.ts` de l'instantané `src/test/resources/openapi.json` (celui que `OpenApiTest`
  garde à jour). springdoc n'exprime ni nullabilité ni énumérations : `src/api/types.ts` les ajoute par-dessus (`Nullable<T,K>`, unions littérales) sans
  redéfinir les champs. Les modules `api/*` gardent leurs noms d'export. `schema.test.ts` échoue si `schema.d.ts` n'est plus celui de l'instantané.
- **ky** pour le HTTP (`api/client.ts`) : cookies de session, `X-XSRF-TOKEN` sur les écritures, ni délai ni nouvel essai (une génération LLM dure des
  minutes ; rejouer une écriture la ferait deux fois), erreurs lues dans `detail` (RFC 9457), 401 → `/login`.
- **TanStack Query** pour les lectures (`api/query.ts` : clés et lectures au même endroit) : `useProjet`, `useDecisions`, projets, statut, consommation.
  Une écriture met le cache à jour ou invalide la clé touchée.
- **vitest** + Testing Library (jsdom) ; oxlint.

## Reste hors React Query

Les flux SSE (`postSse`, `useSession`, génération de spec et de roadmap) gardent leur état local : ils sont pilotés par le flux, pas par une lecture.
Les lectures de `SpecSection` et `RoadmapSection` passent par React Query ; leurs écritures renvoient la Roadmap à jour, qui remplace le cache.
