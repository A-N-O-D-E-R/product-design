# SDK Anthropic Java ou Spring AI

Type: research
Status: resolved
Blocked by: 

## Question

Pour appeler Claude depuis Java 25 + Spring Boot 4.1 : SDK Java officiel Anthropic ou Spring AI ? Comparer compatibilité de version, streaming, tool use, outil serveur web search, prompt caching, maturité. Recommandation argumentée.

## Answer

**Recommandation : SDK Java officiel Anthropic (`com.anthropic:anthropic-java` 3.x) en direct**, sans Spring AI. Un simple bean `AnthropicClient` dans la config Spring suffit.

- **Compatibilité** : les deux fonctionnent. SDK : Java 8+, sans dépendance Spring ; la doc officielle écrit même ses exemples en JDK 25. Spring AI 2.0.x (stable, 2.0.2 du 23/10/2026) est compilé contre Spring Boot 4.1.2, donc compatible Boot 4.1. Spring AI 2.1 (RC1) vise déjà Boot 4.2.0-RC1.
- **Spring AI est une couche sur le même SDK** : depuis la 2.0, son module Anthropic s'appuie sur le SDK officiel, mais il le fige en version 2.72.0 (API `2023-06-01`). Le SDK est en 3.3.0, et la 3.0 est la première à envoyer l'API `2026-10-21`. Avec Spring AI on a donc toujours du retard, et on ne peut pas monter le SDK soi-même sans risquer un conflit.
- **Streaming → SSE** : SDK : `createStreaming()` synchrone (`StreamResponse`, très simple avec les virtual threads Java 25 + `SseEmitter`) ou asynchrone (`subscribe`), plus `MessageAccumulator` pour reconstituer le message final et le persister. Spring AI : `Flux<ChatResponse>`, ce qui impose Reactor. Égalité fonctionnelle, mais le SDK expose les événements bruts (blocs `server_tool_use`, deltas) dont on a besoin pour l'UI de la Session.
- **Tool use** : les deux le gèrent. SDK : `addTool(Class)` dérive le schéma d'une classe Java, avec un mode strict. Spring AI : `toolCallbacks`, `ToolChoice*`, exécution automatique de la boucle. Pour extraire Décisions, Questions et rounds, on n'a pas besoin d'exécuter d'outil : un schéma suffit (voir le point suivant).
- **Structured outputs** (GA) : SDK : `outputConfig(MaClasse.class)` → `StructuredMessage<T>`, compatible avec le streaming via `accumulator.message(Class)`. Spring AI : `outputSchema(json)` / `StructuredOutputChatOptions`. C'est le mécanisme à retenir pour sortir les Décisions et Questions d'un tour d'Interview.
- **Web search serveur** (stable, 10 $ / 1 000 recherches) : SDK : classes typées `WebSearchTool20250305/20260209/20260318` (filtrage dynamique, `response_inclusion`). Spring AI : `AnthropicWebSearchTool` (maxUses, domaines, userLocation). Pour la Méthode research en AFK, il faut gérer `pause_turn` et renvoyer les blocs `encrypted_content` à l'identique. C'est plus sûr avec les types bruts du SDK qu'à travers l'abstraction `ChatResponse`.
- **Prompt caching** (GA) : SDK : `cacheControl(CacheControlEphemeral)` en haut de requête (cache automatique) ou par bloc. Spring AI : stratégies `AnthropicCacheStrategy` (SYSTEM_ONLY, CONVERSATION_HISTORY…), pratiques mais moins fines. Les deux couvrent le besoin : fichier de Méthode en system prompt + historique de la Session.
- **Maturité / cadence** : SDK officiel généré par Anthropic, environ 12 releases entre le 30/09 et le 23/10/2026, et les nouveautés de l'API arrivent le jour même. Spring AI : un patch tous les 2 mois environ, une mineure tous les 4 à 6 mois (2.0.0 en juin, 2.1 en RC en octobre 2026), bon projet Spring mais toujours en retard sur l'API.
- **Pourquoi pas Spring AI** : son atout, c'est la portabilité multi-fournisseurs, inutile ici (Claude seul, BYOK hors périmètre). Il ajoute une couche, du retard sur l'API et Reactor, alors que nos besoins (web search, pause_turn, caching, structured outputs) sont tous spécifiques à Anthropic.
- **Point de vigilance** : le SDK dépend de Jackson 2 (`com.fasterxml`, ≥ 2.13.4, 2.19.4 par défaut) alors que Boot 4 utilise Jackson 3 (`tools.jackson`). Les deux coexistent, ce sont des packages différents. Le SDK vérifie la version au démarrage. Spring AI a exactement la même dépendance.

Sources :
- https://github.com/anthropics/anthropic-sdk-java (README, releases v2.68.0 → v3.3.0)
- https://platform.claude.com/docs/en/api/sdks/java (streaming, MessageAccumulator, tool use, Java 8+, semver / version d'API)
- https://platform.claude.com/docs/en/api/migration-2026-10-21 (SDK Java 3.0.0 = API 2026-10-21)
- https://platform.claude.com/docs/en/build-with-claude/structured-outputs
- https://platform.claude.com/docs/en/agents-and-tools/tool-use/web-search-tool
- https://platform.claude.com/docs/en/build-with-claude/prompt-caching
- https://repo1.maven.org/maven2/com/anthropic/anthropic-java/maven-metadata.xml (latest 3.3.0)
- https://docs.spring.io/spring-ai/reference/api/chat/anthropic-chat.html (Spring AI 2.0.2 : SDK officiel, streaming, tools, web search, caching, structured output)
- https://github.com/spring-projects/spring-ai/releases et `pom.xml` des tags v2.0.2 (`spring-boot.version` 4.1.2, `anthropic-sdk.version` 2.72.0) et v2.1.0-RC1 (Boot 4.2.0-RC1)
- https://repo1.maven.org/maven2/org/springframework/ai/spring-ai-anthropic/maven-metadata.xml
