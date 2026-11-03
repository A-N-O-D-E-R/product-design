package productdesigner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import productdesigner.RoadmapController.JalonGenere;
import productdesigner.RoadmapController.RoadmapGeneree;
import productdesigner.RoadmapController.TicketGenere;

/** Export vers un faux Jira Cloud qui enregistre les requêtes. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class JiraTest {

    static HttpServer fauxJira;
    static final List<String> requetes = new CopyOnWriteArrayList<>();
    static final AtomicInteger numero = new AtomicInteger();
    /** Issues « créées » par le faux Jira, pour la recherche JQL : clé, titre, Epic parent. */
    static final List<String[]> creees = new CopyOnWriteArrayList<>();
    static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper();

    /** Enregistre les issues d'un corps de création (simple ou bulk) et renvoie leurs clés. */
    static List<String> creer(String corps) throws java.io.IOException {
        var racine = JSON.readTree(corps);
        var elements = racine.has("issueUpdates") ? racine.get("issueUpdates") : JSON.createArrayNode().add(racine);
        List<String> cles = new java.util.ArrayList<>();
        for (var e : elements) {
            String cle = "PD-" + numero.incrementAndGet();
            creees.add(new String[] {cle, e.path("fields").path("summary").asText(), e.path("fields").path("parent").path("key").asText(null)});
            cles.add(cle);
        }
        return cles;
    }

    /** Réponse du bulk, selon le corps reçu ; remplaçable par test. */
    static volatile Function<String, String[]> bulk;

    static void repondre(HttpExchange ex, int code, String json) throws java.io.IOException {
        byte[] b = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(code, b.length);
        ex.getResponseBody().write(b);
        ex.close();
    }

    static String bulkOk(String corps) {
        try {
            StringBuilder sb = new StringBuilder("{\"issues\":[");
            for (String cle : creer(corps)) {
                sb.append(sb.length() > 11 ? "," : "").append("{\"key\":\"").append(cle).append("\"}");
            }
            return sb.append("],\"errors\":[]}").toString();
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void config(DynamicPropertyRegistry registry) throws Exception {
        fauxJira = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        fauxJira.createContext("/", ex -> {
            String corps = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String path = ex.getRequestURI().getPath();
            requetes.add(ex.getRequestMethod() + " " + path + " " + ex.getRequestHeaders().getFirst("Authorization") + " " + corps);
            if (path.endsWith("/issuetypes")) {
                repondre(ex, 200, """
                        {"issueTypes":[{"id":"10001","name":"Tâche","hierarchyLevel":0},{"id":"10000","name":"Épopée","hierarchyLevel":1},
                         {"id":"10002","name":"Story","hierarchyLevel":0},{"id":"10003","name":"Sous-tâche","hierarchyLevel":-1}],
                         "total":4,"isLast":true}""");
            } else if (path.equals("/rest/api/3/search/jql")) {
                StringBuilder sb = new StringBuilder("{\"issues\":[");
                for (String[] c : creees) {
                    sb.append(sb.length() > 11 ? "," : "").append("{\"key\":\"").append(c[0]).append("\",\"fields\":{\"summary\":")
                            .append(JSON.writeValueAsString(c[1])).append(c[2] == null ? "" : ",\"parent\":{\"key\":\"" + c[2] + "\"}").append("}}");
                }
                repondre(ex, 200, sb.append("]}").toString());
            } else if (path.equals("/rest/api/3/issue")) {
                if (corps.contains("\"key\":\"BAD\"")) {
                    repondre(ex, 400, "{\"errors\":{\"project\":\"Création interdite dans ce projet\"}}");
                } else {
                    repondre(ex, 201, "{\"key\":\"" + creer(corps).getFirst() + "\"}");
                }
            } else if (path.startsWith("/rest/api/3/issue/") && ex.getRequestMethod().equals("PUT")) {
                ex.sendResponseHeaders(204, -1);
                ex.close();
            } else if (path.equals("/rest/api/3/issueLink")) {
                repondre(ex, 201, "");
            } else if (path.equals("/rest/api/3/issue/bulk")) {
                String[] r = bulk.apply(corps);
                repondre(ex, Integer.parseInt(r[0]), r[1]);
            } else {
                repondre(ex, 404, "{}");
            }
        });
        fauxJira.start();
        String dir = Files.createTempDirectory("productdesigner").toString();
        registry.add("productdesigner.data-dir", () -> dir);
        registry.add("productdesigner.jira.site", () -> "http://localhost:" + fauxJira.getAddress().getPort() + "/");
        registry.add("productdesigner.jira.email", () -> "moi@exemple.fr");
        registry.add("productdesigner.jira.token", () -> "secret");
        registry.add("productdesigner.jira.backoff-ms", () -> "10");
    }

    @AfterAll
    static void stop() {
        fauxJira.stop(0);
    }

    @BeforeEach
    void reset() {
        requetes.clear();
        bulk = c -> new String[] {"201", bulkOk(c)};
    }

    @LocalServerPort
    int port;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    RoadmapController roadmaps;

    String post(String path, String json) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(json)).build(),
                HttpResponse.BodyHandlers.ofString()).body();
    }

    long roadmap(String nom) throws Exception {
        String projet = post("/api/projets", "{\"nom\":\"" + nom + "\"}").replaceAll(".*\"id\":(\\d+),.*", "$1");
        long spec = jdbc.sql("INSERT INTO spec (projet_id, version, contenu) VALUES (?, 1, 'x') RETURNING id").param(projet)
                .query(Long.class).single();
        roadmaps.enregistrer(spec, new RoadmapGeneree(List.of(
                new JalonGenere("Socle", List.of(
                        new TicketGenere("Créer une note", "L'utilisateur **crée** une note.", List.of("La note apparaît"), List.of("E-1"), List.of()),
                        new TicketGenere("Lister", "Liste.", List.of(), List.of(), List.of("Créer une note")))),
                new JalonGenere("Recherche", List.of(new TicketGenere("Chercher", "Plein texte.", List.of(), List.of(), List.of()))))), Set.of());
        return spec;
    }

    @Test
    void unEpicParJalonUneStoryParTicketSousLeBonEpic() throws Exception {
        long spec = roadmap("Export");
        String r = post("/api/specs/" + spec + "/export-jira", "{\"projet\":\"pd\"}");
        assertThat(r).contains("\"projet\":\"PD\"", "\"epicsCrees\":2", "\"storiesCreees\":3", "\"liensCrees\":1", "\"echecs\":[]");
        // « Créer une note » bloque « Lister ».
        String creer = jdbc.sql("SELECT t.jira_key FROM ticket t JOIN jalon j ON j.id = t.jalon_id WHERE j.spec_id = ? AND t.titre = 'Créer une note'")
                .param(spec).query(String.class).single();
        String lister = jdbc.sql("SELECT t.jira_key FROM ticket t JOIN jalon j ON j.id = t.jalon_id WHERE j.spec_id = ? AND t.titre = 'Lister'")
                .param(spec).query(String.class).single();
        assertThat(requetes).anyMatch(q -> q.startsWith("POST /rest/api/3/issueLink") && q.contains("\"name\":\"Blocks\"")
                && q.contains("\"outwardIssue\":{\"key\":\"" + creer + "\"}") && q.contains("\"inwardIssue\":{\"key\":\"" + lister + "\"}"));

        assertThat(requetes.getFirst()).startsWith("GET /rest/api/3/issue/createmeta/PD/issuetypes Basic bW9pQGV4ZW1wbGUuZnI6c2VjcmV0");
        List<String> epics = requetes.stream().filter(q -> q.startsWith("POST /rest/api/3/issue ")).toList();
        assertThat(epics).hasSize(2);
        assertThat(epics.getFirst()).contains("\"summary\":\"Socle\"", "\"issuetype\":{\"id\":\"10000\"}");
        String bulkReq = requetes.stream().filter(q -> q.startsWith("POST /rest/api/3/issue/bulk")).findFirst().orElseThrow();
        // Story = type « Story » (pas le premier de niveau 0), rattachée par parent, description en ADF.
        String socle = jdbc.sql("SELECT jira_key FROM jalon WHERE spec_id = ? AND titre = 'Socle'").param(spec).query(String.class).single();
        String recherche = jdbc.sql("SELECT jira_key FROM jalon WHERE spec_id = ? AND titre = 'Recherche'").param(spec).query(String.class).single();
        assertThat(bulkReq).contains("\"issuetype\":{\"id\":\"10002\"}", "\"parent\":{\"key\":\"" + socle + "\"}", "\"parent\":{\"key\":\"" + recherche + "\"}",
                "\"type\":\"doc\"", "\"type\":\"heading\"", "Critères d'acceptation", "\"type\":\"strong\"", "Exigences :");

        assertThat(jdbc.sql("SELECT count(*) FROM ticket WHERE jira_key IS NOT NULL").query(Integer.class).single()).isGreaterThanOrEqualTo(3);

        // Ré-exporter sans changement ne recrée rien.
        requetes.clear();
        assertThat(post("/api/specs/" + spec + "/export-jira", "{\"projet\":\"PD\"}")).contains("\"epicsCrees\":0", "\"storiesCreees\":0", "\"liensCrees\":0");
        assertThat(requetes).noneMatch(q -> q.startsWith("POST"));
    }

    @Test
    void reexportMetAJourSansDoublonEtListeLesOrphelines() throws Exception {
        long spec = roadmap("Reexport");
        post("/api/specs/" + spec + "/export-jira", "{\"projet\":\"PD\"}");
        String lister = jdbc.sql("SELECT t.jira_key FROM ticket t JOIN jalon j ON j.id = t.jalon_id WHERE j.spec_id = ? AND t.titre = 'Lister'")
                .param(spec).query(String.class).single();
        String chercher = jdbc.sql("SELECT t.jira_key FROM ticket t JOIN jalon j ON j.id = t.jalon_id WHERE j.spec_id = ? AND t.titre = 'Chercher'")
                .param(spec).query(String.class).single();
        long listerId = jdbc.sql("SELECT id FROM ticket WHERE jira_key = ?").param(lister).query(Long.class).single();
        long chercherId = jdbc.sql("SELECT id FROM ticket WHERE jira_key = ?").param(chercher).query(Long.class).single();
        long jalon = jdbc.sql("SELECT jalon_id FROM ticket WHERE id = ?").param(listerId).query(Long.class).single();

        HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/tickets/" + listerId))
                .header("Content-Type", "application/json").PUT(HttpRequest.BodyPublishers.ofString(
                        "{\"jalonId\":" + jalon + ",\"titre\":\"Lister les notes\",\"corps\":\"Liste triée.\",\"exigences\":[],\"blockedBy\":[]}")).build(),
                HttpResponse.BodyHandlers.ofString());
        HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/tickets/" + chercherId)).DELETE().build(),
                HttpResponse.BodyHandlers.ofString());

        requetes.clear();
        String r = post("/api/specs/" + spec + "/export-jira", "{\"projet\":\"PD\"}");
        assertThat(r).contains("\"storiesCreees\":0", "\"orphelines\":[\"" + chercher + " (Chercher)\"]");
        assertThat(requetes).anyMatch(q -> q.startsWith("PUT /rest/api/3/issue/" + lister) && q.contains("\"summary\":\"Lister les notes\"")
                && q.contains("Liste triée."));
        assertThat(requetes).noneMatch(q -> q.startsWith("POST /rest/api/3/issue") || q.contains("DELETE"));
    }

    @Test
    void nouvelleVersionDeSpecCreeDeNouveauxEpics() throws Exception {
        long v1 = roadmap("Versions");
        post("/api/specs/" + v1 + "/export-jira", "{\"projet\":\"PD\"}");
        String ancien = jdbc.sql("SELECT jira_key FROM jalon WHERE spec_id = ? AND titre = 'Socle'").param(v1).query(String.class).single();
        long projet = jdbc.sql("SELECT projet_id FROM spec WHERE id = ?").param(v1).query(Long.class).single();
        long v2 = jdbc.sql("INSERT INTO spec (projet_id, version, contenu) VALUES (?, 2, 'y') RETURNING id").param(projet).query(Long.class).single();
        roadmaps.enregistrer(v2, new RoadmapGeneree(List.of(new JalonGenere("Socle",
                List.of(new TicketGenere("Créer une note", "v2", List.of(), List.of(), List.of()))))), Set.of());

        requetes.clear();
        assertThat(post("/api/specs/" + v2 + "/export-jira", "{\"projet\":\"PD\"}")).contains("\"epicsCrees\":1", "\"storiesCreees\":1");
        assertThat(jdbc.sql("SELECT jira_key FROM jalon WHERE spec_id = ?").param(v2).query(String.class).single()).isNotEqualTo(ancien);
        assertThat(requetes).noneMatch(q -> q.contains(ancien));
    }

    @Test
    void echecPartielSignaleEtSeulementRejoue() throws Exception {
        long spec = roadmap("Partiel");
        // 1er bulk : le 2e élément échoue (201 quand même) ; le suivant réussit.
        bulk = c -> c.contains("Socle") || c.contains("\"Lister\"") && c.contains("Créer une note")
                ? new String[] {"201", "{\"issues\":[{\"key\":\"X-1\"},{\"key\":\"X-3\"}],"
                        + "\"errors\":[{\"failedElementNumber\":1,\"status\":400,\"elementErrors\":{\"errors\":{\"summary\":\"trop long\"}}}]}"}
                : new String[] {"201", bulkOk(c)};
        String r = post("/api/specs/" + spec + "/export-jira", "{\"projet\":\"PD\"}");
        assertThat(r).contains("\"storiesCreees\":2", "\"titre\":\"Lister\"", "trop long");
        assertThat(jdbc.sql("SELECT jira_key FROM ticket WHERE titre = 'Chercher' AND jalon_id IN (SELECT id FROM jalon WHERE spec_id = ?)")
                .param(spec).query(String.class).single()).isEqualTo("X-3");

        requetes.clear();
        bulk = c -> new String[] {"201", bulkOk(c)};
        r = post("/api/specs/" + spec + "/export-jira", "{\"projet\":\"PD\"}");
        assertThat(r).contains("\"storiesCreees\":1", "\"echecs\":[]");
        String rejeu = requetes.stream().filter(q -> q.startsWith("POST /rest/api/3/issue/bulk")).findFirst().orElseThrow();
        assertThat(rejeu).contains("\"summary\":\"Lister\"").doesNotContain("Créer une note", "Chercher");
    }

    @Test
    void tokenInvalideEtLimiteDeDebit() throws Exception {
        long spec = roadmap("Erreurs");
        bulk = c -> new String[] {"401", "{}"};
        assertThat(post("/api/specs/" + spec + "/export-jira", "{\"projet\":\"PD\"}")).contains("Token Jira expiré ou invalide.");

        AtomicInteger appels = new AtomicInteger();
        bulk = c -> appels.incrementAndGet() < 3 ? new String[] {"429", "{}"} : new String[] {"201", bulkOk(c)};
        assertThat(post("/api/specs/" + spec + "/export-jira", "{\"projet\":\"PD\"}")).contains("\"storiesCreees\":3");
        assertThat(appels.get()).isEqualTo(3);
    }

    @Test
    void delaiRetryAfterPuisBackoff() {
        AtlassianClient c = new AtlassianClient("http://x", "e", "t", 2000, "", "");
        assertThat(c.delai("7", 1)).isEqualTo(7000);
        assertThat(c.delai(null, 1)).isBetween(1400L, 2600L);
        assertThat(c.delai(null, 3)).isBetween(5600L, 10400L);
        assertThat(c.delai(null, 10)).isEqualTo(30_000L);
    }

    @Test
    void creationIncertaineRetrouveeAuReexportSansDoublon() throws Exception {
        long spec = roadmap("Incertain");
        // Jira crée les Stories mais répond 500 (ou la connexion coupe) : l'app ne connaît pas les clés.
        bulk = c -> {
            bulkOk(c);
            return new String[] {"500", "{}"};
        };
        String r = post("/api/specs/" + spec + "/export-jira", "{\"projet\":\"PD\"}");
        assertThat(r).contains("\"storiesCreees\":0", "\"titre\":\"Créer une note\"");
        int creeesAvant = creees.size();

        requetes.clear();
        bulk = c -> new String[] {"201", bulkOk(c)};
        r = post("/api/specs/" + spec + "/export-jira", "{\"projet\":\"PD\"}");
        assertThat(r).contains("\"storiesCreees\":0", "\"recuperes\":3", "\"echecs\":[]");
        assertThat(creees).hasSize(creeesAvant);
        assertThat(requetes).noneMatch(q -> q.startsWith("POST /rest/api/3/issue/bulk"));
        assertThat(jdbc.sql("SELECT count(*) FROM ticket t JOIN jalon j ON j.id = t.jalon_id WHERE j.spec_id = ? AND t.jira_key IS NULL")
                .param(spec).query(Integer.class).single()).isZero();
    }

    @Test
    void mauvaiseCleDeProjetNeFigePasLaCible() throws Exception {
        long spec = roadmap("Cible");
        assertThat(post("/api/specs/" + spec + "/export-jira", "{\"projet\":\"BAD\"}")).contains("Création interdite", "\"epicsCrees\":0");
        assertThat(jdbc.sql("SELECT jira_projet FROM spec WHERE id = ?").param(spec).query(String.class).optional()).isEmpty();
        assertThat(post("/api/specs/" + spec + "/export-jira", "{\"projet\":\"PD\"}")).contains("\"projet\":\"PD\"", "\"epicsCrees\":2");
    }

    @Test
    void configAbsenteMessageExplicite() {
        assertThatThrownBy(() -> new AtlassianClient("", "", "", 1, "", "").types("PD"))
                .isInstanceOf(ConfigManquanteException.class).hasMessageContaining("Config Atlassian absente");
    }
}
