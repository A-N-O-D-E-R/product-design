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

/** API REST Jira Cloud v3 (D-10, D-11) : auth Basic email + token API non scopé. */
@Component
class JiraClient {

    static final String CONFIG_ABSENTE = "Config Jira absente : ajouter productdesigner.jira.site, .email et .token"
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

    JiraClient(@Value("${productdesigner.jira.site:}") String site,
               @Value("${productdesigner.jira.email:}") String email,
               @Value("${productdesigner.jira.token:}") String token,
               @Value("${productdesigner.jira.backoff-ms:2000}") long baseMs) {
        this.site = site.replaceAll("/+$", "");
        this.email = email;
        this.token = token;
        this.baseMs = baseMs;
    }

    boolean configure() {
        return !site.isBlank() && !email.isBlank() && !token.isBlank();
    }

    private RestClient client() {
        if (!configure()) {
            throw new ConfigManquanteException(CONFIG_ABSENTE);
        }
        String basic = Base64.getEncoder().encodeToString((email + ":" + token).getBytes(StandardCharsets.UTF_8));
        return RestClient.builder().baseUrl(site)
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
            Map<String, Object> page = appel(() -> client().get()
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
        Map<String, Object> r = appel(() -> client().post().uri("/rest/api/3/issue")
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("fields", champs)).retrieve().body(Map.class));
        return String.valueOf(r.get("key"));
    }

    /** Bulk create (50 au plus) : un 201 peut cacher des échecs partiels, on lit toujours `errors` (D-10). */
    @SuppressWarnings("unchecked")
    Bulk creerEnMasse(List<Map<String, Object>> champs) {
        List<Map<String, Object>> elements = champs.stream().map(c -> Map.<String, Object>of("fields", c)).toList();
        Map<String, Object> r;
        try {
            r = appel(() -> client().post().uri("/rest/api/3/issue/bulk").contentType(MediaType.APPLICATION_JSON)
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

    void modifier(String cle, Map<String, Object> champs) {
        appel(() -> client().put().uri("/rest/api/3/issue/{cle}", cle).contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("fields", champs)).retrieve().toBodilessEntity());
    }

    /** Lien « Blocks » : `bloquant` blocks `bloque` (outwardIssue = celui qui bloque). */
    void lierBlocage(String bloquant, String bloque) {
        appel(() -> client().post().uri("/rest/api/3/issueLink").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("type", Map.of("name", "Blocks"), "outwardIssue", Map.of("key", bloquant), "inwardIssue", Map.of("key", bloque)))
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
