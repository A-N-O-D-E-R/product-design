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

import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Fournisseur Amazon Bedrock (Mantle), contre un faux endpoint `/anthropic/v1/messages` sans signature AWS. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class BedrockTest {

    static HttpServer fauxBedrock;
    static final List<String> requetes = new CopyOnWriteArrayList<>();

    @DynamicPropertySource
    static void config(DynamicPropertyRegistry registry) throws Exception {
        fauxBedrock = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        fauxBedrock.createContext("/", ex -> {
            requetes.add(ex.getRequestURI().getPath() + " " + new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] b = SessionTest.stream("Bonjour depuis Bedrock.", SessionTest.ROUND).getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "text/event-stream");
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        fauxBedrock.start();
        String dir = Files.createTempDirectory("productdesigner").toString();
        registry.add("productdesigner.data-dir", () -> dir);
        registry.add("productdesigner.anthropic.fournisseur", () -> "bedrock");
        registry.add("productdesigner.anthropic.api-key", () -> "");
        registry.add("productdesigner.bedrock.region", () -> "eu-west-3");
        registry.add("productdesigner.anthropic.base-url", () -> "http://localhost:" + fauxBedrock.getAddress().getPort() + "/anthropic");
    }

    @AfterAll
    static void stop() {
        fauxBedrock.stop(0);
    }

    @LocalServerPort
    int port;

    String send(String method, String path, String json) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .method(method, json == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json))
                .build(), HttpResponse.BodyHandlers.ofString()).body();
    }

    @Test
    void sessionSurBedrockSansCleAnthropic() throws Exception {
        assertThat(send("GET", "/api/status", null)).contains("\"fournisseur\":\"bedrock\"", "\"model\":\"anthropic.claude-opus-5-5\"",
                "\"rechercheWeb\":false");
        String projet = send("POST", "/api/projets", "{\"nom\":\"B\"}").replaceAll(".*\"id\":(\\d+),.*", "$1");
        String session = send("POST", "/api/projets/" + projet + "/sessions", "{\"methode\":\"grilling\"}").replaceAll(".*?\"id\":(\\d+),.*", "$1");

        assertThat(send("POST", "/api/sessions/" + session + "/messages", "{\"texte\":\"Salut\"}")).contains("uis Bedrock.", "event:record");
        assertThat(requetes.getLast()).startsWith("/anthropic/v1/messages ").contains("\"model\":\"anthropic.claude-opus-5-5\"", "\"name\":\"record\"",
                "ne crée pas de Question de type research");
    }

    @Test
    void researchRefuseeFauteDeRechercheWeb() throws Exception {
        String projet = send("POST", "/api/projets", "{\"nom\":\"R\"}").replaceAll(".*\"id\":(\\d+),.*", "$1");
        String carte = send("POST", "/api/projets/" + projet + "/questions",
                "{\"titre\":\"Fait\",\"corps\":\"\",\"type\":\"research\",\"blockedBy\":[]}");
        String q = carte.replaceAll(".*\"questions\":\\[\\{\"id\":(\\d+),.*", "$1");
        assertThat(send("POST", "/api/projets/" + projet + "/questions/" + q + "/sessions", null))
                .contains("recherche web, indisponible sur Amazon Bedrock");
        assertThat(send("GET", "/api/projets/" + projet + "/map", null)).contains("\"statut\":\"open\"");

        // Session research reprise sur Bedrock (ex. après changement de fournisseur) : pas d'outil web_search envoyé.
        String session = send("POST", "/api/projets/" + projet + "/sessions", "{\"methode\":\"research\"}").replaceAll(".*?\"id\":(\\d+),.*", "$1");
        send("POST", "/api/sessions/" + session + "/messages", "{\"texte\":\"Résume\"}");
        assertThat(requetes.getLast()).doesNotContain("web_search");
    }
}
