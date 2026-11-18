# 6. Sécurité par le starter de la collection

Date : 2026-11-18 — Statut : accepté. Remplace la décision D-3 (« mono-utilisateur sans authentification, jamais exposé au réseau »).

## Contexte

L'API n'avait pas d'authentification : un `OrigineFilter` n'acceptait que `Host` et `Origin` locaux (anti DNS rebinding et
anti CSRF). Impossible d'héberger l'application, ni de la faire appeler par une autre (idea-generator envoie ses idées).

## Décision

- `spring-boot-starter-security` (`anode.security.*`) : OIDC par `env-oidc` (plusieurs fournisseurs = plusieurs `registration`),
  login *mock* pour le poste local par `mock`, CSRF en mode SPA. `OrigineFilter` est supprimé.
- Seul `/api/**` demande une connexion ; le front statique reste public, il se charge puis l'API répond `401` (le front renvoie
  alors au login). Les prototypes générés par le LLM tournent dans une iframe `sandbox` injectée en `srcDoc` : sans origine ni
  cookie, ils n'atteignent pas l'API (`401`).
- Erreurs en RFC 9457 (`spring.mvc.problemdetails.enabled`, remplace `include-message: always`) : le message est dans `detail` ;
  le front lit `detail`.
- Le login mock n'a pas de mot de passe : le démarrage échoue si `server.address` n'est pas locale, et `Host` doit être local
  (sans cela un nom de domaine résolu vers 127.0.0.1 pourrait ouvrir une session mock). Le démarrage échoue aussi sans aucun login.
- Clés d'API `pd_...` (`V20__cle_api.sql`, `CleApi`, `/api/cles-api`) en jeton Bearer, sans session ni CSRF : même forme que les
  `ais_` d'ai-scientist et `idg_` d'idea-generator, mais sans propriétaire (pas de tenants ici), gérées depuis une session seulement.
- `productdesigner.allowed-users` : liste des comptes admis (nom ou e-mail vérifié d'OIDC). Vide, tout compte connecté entre.
  Le login mock du starter donne à tous les utilisateurs la même identité (sujet `client-id`) : la liste n'a de sens qu'avec OIDC.

## Conséquences

- Pas de données par utilisateur : tout compte admis voit tous les Projets. Un modèle multi-utilisateur serait une autre décision.
- L'Okta du starter est exclu du POM (sa configuration d'environnement casse la liaison de la config Spring AI).
- Les tests de l'API tournent sans le starter (`anode.security.enabled=false` + chaîne permissive de test) ; `SecuriteTest` et
  `SecuriteAutoriseTest` testent le vrai.
- Pas encore fait : OpenAPI, Postgres, pnpm / React Query / vitest, Docker.
