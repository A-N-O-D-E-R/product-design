package productdesigner;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

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

/** Publication de la Spec vers un faux Confluence Cloud (API v2). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ConfluenceTest {

    static HttpServer faux;
    static final List<String> requetes = new CopyOnWriteArrayList<>();
    /** Version courante de la page 123 ; 0 = page absente (supprimée). */
    static final AtomicInteger version = new AtomicInteger();
    static volatile boolean titrePris;

    static void repondre(HttpExchange ex, int code, String json) throws java.io.IOException {
        byte[] b = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(code, b.length);
        ex.getResponseBody().write(b);
        ex.close();
    }

    static String page() {
        return "{\"id\":\"123\",\"version\":{\"number\":" + version.get() + "},\"_links\":{\"base\":\"https://site/wiki\",\"webui\":\"/spaces/PD/pages/123\"}}";
    }

    @DynamicPropertySource
    static void config(DynamicPropertyRegistry registry) throws Exception {
        faux = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        faux.createContext("/", ex -> {
            String corps = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String m = ex.getRequestMethod(), path = ex.getRequestURI().getPath();
            requetes.add(m + " " + ex.getRequestURI() + " " + corps);
            if (path.equals("/wiki/api/v2/spaces")) {
                repondre(ex, 200, ex.getRequestURI().getQuery().contains("keys=PD") ? "{\"results\":[{\"id\":\"98\",\"key\":\"PD\"}]}" : "{\"results\":[]}");
            } else if (path.equals("/wiki/api/v2/pages") && m.equals("POST")) {
                if (titrePris) {
                    repondre(ex, 400, "{\"errors\":[{\"title\":\"A page with this title already exists\"}]}");
                } else {
                    version.set(1);
                    repondre(ex, 200, page());
                }
            } else if (path.equals("/wiki/api/v2/pages/123") && m.equals("GET")) {
                repondre(ex, version.get() == 0 ? 404 : 200, version.get() == 0 ? "{}" : page());
            } else if (path.equals("/wiki/api/v2/pages/123") && m.equals("PUT")) {
                version.incrementAndGet();
                repondre(ex, 200, page());
            } else {
                repondre(ex, 404, "{}");
            }
        });
        faux.start();
        String dir = Files.createTempDirectory("productdesigner").toString();
        registry.add("productdesigner.data-dir", () -> dir);
        registry.add("productdesigner.jira.site", () -> "http://localhost:" + faux.getAddress().getPort());
        registry.add("productdesigner.jira.email", () -> "moi@exemple.fr");
        registry.add("productdesigner.jira.token", () -> "secret");
    }

    @AfterAll
    static void stop() {
        faux.stop(0);
    }

    @BeforeEach
    void reset() {
        requetes.clear();
        version.set(0);
        titrePris = false;
    }

    @LocalServerPort
    int port;

    @Autowired
    JdbcClient jdbc;

    String post(String path, String json) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(json)).build(),
                HttpResponse.BodyHandlers.ofString()).body();
    }

    long spec(long projet, int numero) {
        return jdbc.sql("INSERT INTO spec (projet_id, version, contenu) VALUES (?, ?, ?) RETURNING id")
                .params(projet, numero, "# Spec : Notes\n\n## 1. Problème et Destination\n\nPrendre des **notes** vite.\n\n## 5. Exigences fonctionnelles\n\n- **E-1** Créer [D-1]\n")
                .query(Long.class).single();
    }

    long projet(String nom) throws Exception {
        return Long.parseLong(post("/api/projets", "{\"nom\":\"" + nom + "\"}").replaceAll(".*\"id\":(\\d+),.*", "$1"));
    }

    @Test
    void premiereVersionCreeLaPagePuisLesSuivantesLaVersionnent() throws Exception {
        long p = projet("Notes");
        long v1 = spec(p, 1);
        String r = post("/api/specs/" + v1 + "/export-confluence", "{\"espace\":\"pd\"}");
        assertThat(r).contains("\"espace\":\"PD\"", "\"url\":\"https://site/wiki/spaces/PD/pages/123\"", "\"versionConfluence\":1", "\"creee\":true");
        String creation = requetes.stream().filter(q -> q.startsWith("POST /wiki/api/v2/pages")).findFirst().orElseThrow();
        assertThat(creation).contains("\"spaceId\":\"98\"", "\"title\":\"Spec — Notes\"", "\"representation\":\"atlas_doc_format\"",
                "\\\"type\\\":\\\"heading\\\"", "\\\"type\\\":\\\"strong\\\"").doesNotContain("Spec : Notes");

        long v2 = spec(p, 2);
        requetes.clear();
        r = post("/api/specs/" + v2 + "/export-confluence", "{\"espace\":\"AUTRE\"}");
        assertThat(r).contains("\"espace\":\"PD\"", "\"versionConfluence\":2", "\"creee\":false");
        assertThat(requetes).anyMatch(q -> q.startsWith("PUT /wiki/api/v2/pages/123") && q.contains("\"number\":2")
                && q.contains("Spec version 2 (productDesigner)"));
        assertThat(requetes).noneMatch(q -> q.startsWith("POST"));
        assertThat(jdbc.sql("SELECT confluence_version FROM spec WHERE id = ?").param(v2).query(Integer.class).single()).isEqualTo(2);
    }

    @Test
    void pageSupprimeeDansConfluenceRecree() throws Exception {
        long p = projet("Supprimee");
        post("/api/specs/" + spec(p, 1) + "/export-confluence", "{\"espace\":\"PD\"}");
        version.set(0);
        assertThat(post("/api/specs/" + spec(p, 2) + "/export-confluence", "{\"espace\":\"PD\"}")).contains("\"creee\":true", "\"versionConfluence\":1");
    }

    @Test
    void titreDejaPrisOuEspaceInconnuMessageClair() throws Exception {
        long p = projet("Conflit");
        titrePris = true;
        assertThat(post("/api/specs/" + spec(p, 1) + "/export-confluence", "{\"espace\":\"PD\"}"))
                .contains("une page « Spec — Conflit » existe peut-être déjà");
        assertThat(post("/api/specs/" + spec(p, 2) + "/export-confluence", "{\"espace\":\"NOPE\"}")).contains("Espace Confluence NOPE introuvable");
        assertThat(jdbc.sql("SELECT confluence_espace FROM projet WHERE id = ?").param(p).query(String.class).optional()).isEmpty();
    }

    @Test
    void cleDEspacePersonnelGardeSaCasse() {
        assertThat(ConfluenceController.cle(" pd ")).isEqualTo("PD");
        assertThat(ConfluenceController.cle("~712020bcba375b35da47e2bfeb2d0063230003")).isEqualTo("~712020bcba375b35da47e2bfeb2d0063230003");
    }
}
