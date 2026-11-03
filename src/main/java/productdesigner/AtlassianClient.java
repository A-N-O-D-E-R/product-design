package productdesigner;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

/** API REST Atlassian Cloud, Jira v3 et Confluence v2 (D-10, D-11) : même site, auth Basic email + token API non scopé. */
@Component
class AtlassianClient {

    static final String CONFIG_ABSENTE = "Config Atlassian absente (Jira et Confluence) : ajouter productdesigner.jira.site, .email et .token"
            + " dans ~/.productdesigner/application.yml, puis redémarrer.";

    record Types(String epic, String story) {}

    /** Résultat d'un bulk create : clé par élément envoyé (null si échec) et erreurs par index. */
    record Bulk(List<String> cles, Map<Integer, String> erreurs) {}

    /** Tentatives au plus sur un 429 (D-11). */
    static final int TENTATIVES = 4;

    private final String site;
    private final String email;
    private final String token;
    private final long baseMs;
    private final String cloudId;
    private final String passerelle;

    AtlassianClient(@Value("${productdesigner.jira.site:}") String site,
               @Value("${productdesigner.jira.email:}") String email,
               @Value("${productdesigner.jira.token:}") String token,
               @Value("${productdesigner.jira.backoff-ms:2000}") long baseMs,
               @Value("${productdesigner.jira.cloud-id:}") String cloudId,
               @Value("${productdesigner.jira.passerelle:https://api.atlassian.com}") String passerelle) {
        this.site = site.replaceAll("/+$", "");
        this.email = email;
        this.token = token;
        this.baseMs = baseMs;
        this.cloudId = cloudId.strip();
        this.passerelle = passerelle.replaceAll("/+$", "");
    }

    boolean configure() {
        return !site.isBlank() && !email.isBlank() && !token.isBlank();
    }

    /**
     * Token sans scope : appels directs au site. Token scopé (cloud-id renseigné) : passerelle Atlassian
     * `api.atlassian.com/ex/{jira|confluence}/{cloudId}`, seule voie qui l'accepte.
     */
    private RestClient client(String produit) {
        if (!configure()) {
            throw new ConfigManquanteException(CONFIG_ABSENTE);
        }
        String basic = Base64.getEncoder().encodeToString((email + ":" + token).getBytes(StandardCharsets.UTF_8));
        String base = cloudId.isBlank() ? site : passerelle + "/ex/" + produit + "/" + cloudId;
        return RestClient.builder().baseUrl(base)
                .defaultHeader("Authorization", "Basic " + basic)
                .defaultHeader("Accept", MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    /** Epic = hierarchyLevel 1 ; Story = « Story » de niveau 0, sinon le premier type de niveau 0 (D-10). */
    @SuppressWarnings("unchecked")
    Types types(String projet) {
        String epic = null, story = null, premierNiveau0 = null;
        int debut = 0;
        while (true) {
            int startAt = debut;
            Map<String, Object> page = appel(() -> client("jira").get()
                    .uri("/rest/api/3/issue/createmeta/{projet}/issuetypes?startAt={debut}&maxResults=50", projet, startAt)
                    .retrieve().body(Map.class));
            List<Map<String, Object>> types = (List<Map<String, Object>>) page.getOrDefault("issueTypes", page.getOrDefault("values", List.of()));
            for (Map<String, Object> t : types) {
                int niveau = ((Number) t.getOrDefault("hierarchyLevel", 0)).intValue();
                String id = String.valueOf(t.get("id"));
                if (niveau == 1 && epic == null) {
                    epic = id;
                } else if (niveau == 0) {
                    premierNiveau0 = premierNiveau0 == null ? id : premierNiveau0;
                    if ("Story".equalsIgnoreCase(String.valueOf(t.get("name")))) {
                        story = id;
                    }
                }
            }
            debut += types.size();
            if (types.isEmpty() || Boolean.TRUE.equals(page.get("isLast")) || debut >= ((Number) page.getOrDefault("total", 0)).intValue()) {
                break;
            }
        }
        if (epic == null || premierNiveau0 == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Le projet Jira " + projet + " n'a pas de type Epic (niveau 1) ou de type de niveau 0.");
        }
        return new Types(epic, story != null ? story : premierNiveau0);
    }

    @SuppressWarnings("unchecked")
    String creer(Map<String, Object> champs) {
        Map<String, Object> r = appel(() -> client("jira").post().uri("/rest/api/3/issue")
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("fields", champs)).retrieve().body(Map.class));
        return String.valueOf(r.get("key"));
    }

    /** Bulk create (50 au plus) : un 201 peut cacher des échecs partiels, on lit toujours `errors` (D-10). */
    @SuppressWarnings("unchecked")
    Bulk creerEnMasse(List<Map<String, Object>> champs) {
        List<Map<String, Object>> elements = champs.stream().map(c -> Map.<String, Object>of("fields", c)).toList();
        Map<String, Object> r;
        try {
            r = appel(() -> client("jira").post().uri("/rest/api/3/issue/bulk").contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("issueUpdates", elements)).retrieve().body(Map.class));
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode().value() != 400) {
                throw e;
            }
            // 400 : tous les éléments ont échoué ; même corps (issues vides + errors).
            r = e.getResponseBodyAs(Map.class);
        }
        Map<Integer, String> erreurs = new HashMap<>();
        for (Map<String, Object> err : (List<Map<String, Object>>) r.getOrDefault("errors", List.of())) {
            int index = ((Number) err.get("failedElementNumber")).intValue();
            erreurs.put(index, String.valueOf(((Map<String, Object>) err.getOrDefault("elementErrors", Map.of())).getOrDefault("errors", err)));
        }
        List<Map<String, Object>> creees = (List<Map<String, Object>>) r.getOrDefault("issues", List.of());
        List<String> cles = new ArrayList<>();
        int suivante = 0;
        for (int i = 0; i < champs.size(); i++) {
            cles.add(erreurs.containsKey(i) || suivante >= creees.size() ? null : String.valueOf(creees.get(suivante++).get("key")));
        }
        return new Bulk(cles, erreurs);
    }

    /**
     * Issue déjà créée par une tentative à l'issue inconnue : même projet, type, titre exact et, pour une Story,
     * même Epic parent. JQL `summary ~` est une recherche plein texte : le filtre exact se fait ici.
     */
    @SuppressWarnings("unchecked")
    java.util.Optional<String> chercher(String projet, String type, String titre, String parent) {
        // Guillemets et antislashs retirés de la phrase recherchée ; l'égalité exacte est vérifiée ci-dessous.
        String phrase = titre.replaceAll("[\"\\\\]", " ");
        String jql = "project = \"" + projet + "\" AND issuetype = " + type + " AND summary ~ \"\\\"" + phrase + "\\\"\" ORDER BY created DESC";
        Map<String, Object> r = appel(() -> client("jira").get()
                .uri(u -> u.path("/rest/api/3/search/jql").queryParam("jql", "{jql}").queryParam("fields", "summary,parent")
                        .queryParam("maxResults", 50).build(jql))
                .retrieve().body(Map.class));
        for (Map<String, Object> issue : (List<Map<String, Object>>) r.getOrDefault("issues", List.of())) {
            Map<String, Object> champs = (Map<String, Object>) issue.getOrDefault("fields", Map.of());
            Map<String, Object> parentTrouve = (Map<String, Object>) champs.get("parent");
            if (titre.equals(champs.get("summary"))
                    && (parent == null || parentTrouve != null && parent.equals(parentTrouve.get("key")))) {
                return java.util.Optional.of(String.valueOf(issue.get("key")));
            }
        }
        return java.util.Optional.empty();
    }

    /** Un 4xx dit clairement que rien n'a été créé ; un 5xx, un timeout ou une erreur réseau laissent le doute. */
    static boolean incertain(RuntimeException e) {
        return !(e instanceof HttpClientErrorException) && !(e instanceof ResponseStatusException);
    }

    void modifier(String cle, Map<String, Object> champs) {
        appel(() -> client("jira").put().uri("/rest/api/3/issue/{cle}", cle).contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("fields", champs)).retrieve().toBodilessEntity());
    }

    // --- Confluence Cloud, API v2 ---

    record Page(String id, int version, String url) {}

    /** Id de l'espace d'après sa clé (`GET /wiki/api/v2/spaces?keys=`). */
    @SuppressWarnings("unchecked")
    String espace(String cle) {
        Map<String, Object> r = appel(() -> client("confluence").get()
                .uri(u -> u.path("/wiki/api/v2/spaces").queryParam("keys", "{cle}").build(cle)).retrieve().body(Map.class));
        List<Map<String, Object>> espaces = (List<Map<String, Object>>) r.getOrDefault("results", List.of());
        if (espaces.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Espace Confluence " + cle + " introuvable (ou pas d'accès).");
        }
        return String.valueOf(espaces.getFirst().get("id"));
    }

    /** Page courante, ou vide si elle a été supprimée de Confluence. */
    @SuppressWarnings("unchecked")
    java.util.Optional<Page> page(String id) {
        try {
            Map<String, Object> r = appel(() -> client("confluence").get().uri("/wiki/api/v2/pages/{id}", id).retrieve().body(Map.class));
            return java.util.Optional.of(versPage(r));
        } catch (HttpClientErrorException.NotFound e) {
            return java.util.Optional.empty();
        }
    }

    /** Corps en ADF (`atlas_doc_format`), comme les descriptions Jira. */
    Page creerPage(String espaceId, String titre, Map<String, Object> adf) {
        return versPage(appel(() -> client("confluence").post().uri("/wiki/api/v2/pages").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("spaceId", espaceId, "status", "current", "title", titre, "body", corps(adf)))
                .retrieve().body(Map.class)));
    }

    /** Nouvelle version de la page : numéro courant + 1, avec un message de version. */
    Page modifierPage(String id, int version, String titre, Map<String, Object> adf, String message) {
        return versPage(appel(() -> client("confluence").put().uri("/wiki/api/v2/pages/{id}", id).contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("id", id, "status", "current", "title", titre, "body", corps(adf),
                        "version", Map.of("number", version + 1, "message", message)))
                .retrieve().body(Map.class)));
    }

    private static Map<String, Object> corps(Map<String, Object> adf) {
        try {
            return Map.of("representation", "atlas_doc_format", "value", new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(adf));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private Page versPage(Map<String, Object> r) {
        Map<String, Object> version = (Map<String, Object>) r.getOrDefault("version", Map.of());
        Map<String, Object> liens = (Map<String, Object>) r.getOrDefault("_links", Map.of());
        Object base = liens.getOrDefault("base", site + "/wiki");
        return new Page(String.valueOf(r.get("id")), ((Number) version.getOrDefault("number", 1)).intValue(),
                base + String.valueOf(liens.getOrDefault("webui", "")));
    }

    /**
     * Lien « Blocks » : `bloquant` blocks `bloque`. Vérifié sur Jira Cloud : c'est `inwardIssue` qui « blocks »
     * `outwardIssue` (l'inverse de l'intuition, et la doc ne le précise pas).
     */
    void lierBlocage(String bloquant, String bloque) {
        appel(() -> client("jira").post().uri("/rest/api/3/issueLink").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("type", Map.of("name", "Blocks"), "inwardIssue", Map.of("key", bloquant), "outwardIssue", Map.of("key", bloque)))
                .retrieve().toBodilessEntity());
    }

    /**
     * 401 : message dédié. 429 : attendre `Retry-After`, sinon backoff exponentiel avec jitter
     * (base 2 s, ×0,7 à 1,3, plafond 30 s), {@value #TENTATIVES} tentatives au plus (D-11).
     */
    private <T> T appel(java.util.function.Supplier<T> requete) {
        for (int tentative = 1; ; tentative++) {
            try {
                return requete.get();
            } catch (HttpClientErrorException.Unauthorized e) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Token Jira expiré ou invalide.");
            } catch (HttpClientErrorException.TooManyRequests e) {
                if (tentative == TENTATIVES) {
                    throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Jira limite le débit (429) : réessayer plus tard.");
                }
                attendre(delai(e.getResponseHeaders() == null ? null : e.getResponseHeaders().getFirst("Retry-After"), tentative));
            }
        }
    }

    long delai(String retryAfter, int tentative) {
        if (retryAfter != null) {
            try {
                return Long.parseLong(retryAfter.strip()) * 1000;
            } catch (NumberFormatException ignored) {
                // Date HTTP : on retombe sur le backoff.
            }
        }
        double jitter = 0.7 + java.util.concurrent.ThreadLocalRandom.current().nextDouble() * 0.6;
        return Math.min(30_000, (long) (baseMs * Math.pow(2, tentative - 1) * jitter));
    }

    private static void attendre(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
