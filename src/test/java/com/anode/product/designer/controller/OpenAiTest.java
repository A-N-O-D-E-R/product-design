package com.anode.product.designer.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;

import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Fournisseur openai (Spring AI) contre un faux serveur Chat Completions, avec la recherche web client (faux Tavily). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OpenAiTest {

    static HttpServer faux;
    static final List<String> requetes = new CopyOnWriteArrayList<>();
    static final List<String> recherches = new CopyOnWriteArrayList<>();
    static final Queue<String> reponses = new ConcurrentLinkedQueue<>();

    static String json(String texte) {
        return texte.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }

    static String chunk(String choix) {
        return "data: {\"id\":\"c1\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"gpt-test\",\"choices\":[" + choix + "]}\n\n";
    }

    /** Stream Chat Completions : texte en deux morceaux, puis éventuellement un appel d'outil, puis l'usage. */
    static String stream(String texte, String outil, String arguments) {
        StringBuilder sb = new StringBuilder();
        int moitie = texte.length() / 2;
        for (String morceau : List.of(texte.substring(0, moitie), texte.substring(moitie))) {
            sb.append(chunk("{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"" + json(morceau) + "\"},\"finish_reason\":null}"));
        }
        if (outil != null) {
            sb.append(chunk("{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_1\",\"type\":\"function\",\"function\":{\"name\":\""
                    + outil + "\",\"arguments\":\"\"}}]},\"finish_reason\":null}"));
            int coupe = arguments.length() / 2;
            for (String morceau : List.of(arguments.substring(0, coupe), arguments.substring(coupe))) {
                sb.append(chunk("{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"" + json(morceau)
                        + "\"}}]},\"finish_reason\":null}"));
            }
        }
        sb.append(chunk("{\"index\":0,\"delta\":{},\"finish_reason\":\"" + (outil == null ? "stop" : "tool_calls") + "\"}"));
        sb.append("data: {\"id\":\"c1\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"gpt-test\",\"choices\":[],"
                + "\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5,\"total_tokens\":15}}\n\n");
        return sb.append("data: [DONE]\n\n").toString();
    }

    static final String ROUND = """
            {"round":{"questions":[{"id":"Q1","titre":"Ingrédients structurés ?","corps":"Pour qui ?","recommandation":"Négociable, à voir."}]},"decisions":[],"questions":[]}""";

    @DynamicPropertySource
    static void config(DynamicPropertyRegistry registry) throws Exception {
        faux = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        faux.createContext("/", exchange -> {
            String corps = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String reponse;
            String type;
            if (exchange.getRequestURI().getPath().equals("/tavily/search")) {
                recherches.add(exchange.getRequestHeaders().getFirst("Authorization") + " " + corps);
                reponse = """
                        {"results":[{"title":"Jira Cloud REST","url":"https://developer.atlassian.com/jira","content":"Lier une Story à son Epic par le champ parent."}]}""";
                type = "application/json";
            } else {
                requetes.add(exchange.getRequestURI().getPath() + " " + corps);
                reponse = reponses.poll();
                reponse = reponse == null ? stream("Rien.", null, null) : reponse;
                type = "text/event-stream";
            }
            byte[] body = reponse.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", type);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        faux.start();
        String base = "http://localhost:" + faux.getAddress().getPort();
        String dir = Files.createTempDirectory("productdesigner").toString();
        registry.add("productdesigner.data-dir", () -> dir);
        registry.add("spring.ai.model.chat", () -> "openai");
        registry.add("spring.ai.openai.api-key", () -> "test");
        registry.add("spring.ai.openai.base-url", () -> base + "/v1");
        registry.add("spring.ai.openai.chat.options.model", () -> "gpt-test");
        registry.add("productdesigner.recherche.tavily-api-key", () -> "tvly-test");
        registry.add("productdesigner.recherche.tavily-url", () -> base + "/tavily");
    }

    @AfterAll
    static void stop() {
        faux.stop(0);
    }

    @BeforeEach
    void reset() {
        requetes.clear();
        recherches.clear();
        reponses.clear();
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
    void sessionSurOpenAiAvecRecordPuisToolResult() throws Exception {
        assertThat(send("GET", "/api/status", null)).contains("\"fournisseur\":\"openai\"", "\"model\":\"gpt-test\"", "\"rechercheWeb\":true");
        String projet = send("POST", "/api/projets", "{\"nom\":\"P\",\"description\":\"Une idée\"}").replaceAll(".*\"id\":(\\d+).*", "$1");
        String session = send("POST", "/api/projets/" + projet + "/sessions", "{\"methode\":\"grilling\"}").replaceAll(".*\"id\":(\\d+).*", "$1");

        reponses.add(stream("Bonjour depuis OpenAI.", "record", ROUND));
        String sse = send("POST", "/api/sessions/" + session + "/messages", "{\"texte\":\"Salut\"}");
        assertThat(sse).contains("Bonjour dep", "uis OpenAI.", "event:record", "Ingrédients structurés ?", "Négociable, à voir.");
        assertThat(sse).doesNotContain("avertissement");
        assertThat(requetes.getLast()).contains("\"name\":\"record\"", "\"model\":\"gpt-test\"", "Une idée");
        assertThat(send("GET", "/api/sessions/" + session + "/consommation", null)).contains("\"inputTokens\":10", "\"outputTokens\":5");

        reponses.add(stream("Noté.", "record", ROUND));
        send("POST", "/api/sessions/" + session + "/messages", "{\"texte\":\"Q1: oui\"}");
        // L'appel record précédent reçoit sa réponse d'outil, avant le nouveau message.
        String second = requetes.getLast();
        assertThat(second).contains("\"tool_call_id\":\"call_1\"", "Enregistré.");
        assertThat(second.indexOf("\"tool_call_id\":\"call_1\"")).isLessThan(second.indexOf("Q1: oui"));
    }

    @Test
    void researchAvecLaRechercheWebClient() throws Exception {
        String projetId = send("POST", "/api/projets", "{\"nom\":\"R\"}").replaceAll(".*\"id\":(\\d+),.*", "$1");
        String p = "/api/projets/" + projetId;
        String carte = send("POST", p + "/questions", "{\"titre\":\"API Jira\",\"corps\":\"Comment créer un Epic ?\",\"type\":\"research\",\"blockedBy\":[]}");
        String q = carte.replaceAll(".*\"questions\":\\[\\{\"id\":(\\d+),.*", "$1");
        reponses.add(stream("Je cherche.", "web_search", "{\"query\":\"jira epic parent\"}"));
        reponses.add(stream("# Rapport Jira\n\nUtiliser le champ parent.", "record", """
                {"round":{"questions":[]},"decisions":[{"texte":"Lier les Stories par parent"}],"questions":[]}"""));

        String id = send("POST", p + "/questions/" + q + "/sessions", null).replaceAll(".*?\"id\":(\\d+),.*", "$1");
        String detail = "";
        for (int i = 0; i < 100 && !detail.contains("\"tache\":\"terminee\"") && !detail.contains("echec"); i++) {
            Thread.sleep(100);
            detail = send("GET", "/api/sessions/" + id, null);
        }
        assertThat(detail).contains("\"tache\":\"terminee\"", "1 recherche(s) web");
        assertThat(recherches).singleElement().asString().contains("Bearer tvly-test", "jira epic parent");
        assertThat(requetes).hasSize(2);
        assertThat(requetes.getFirst()).contains("\"name\":\"web_search\"", "Comment créer un Epic ?");
        // Les résultats de la recherche reviennent au modèle en réponse d'outil.
        assertThat(requetes.getLast()).contains("\"tool_call_id\":\"call_1\"", "Lier une Story à son Epic par le champ parent.");
        assertThat(send("GET", "/api/sessions/" + id + "/artefacts", null)).contains("\"type\":\"research_md\"", "# Rapport Jira");
        assertThat(send("GET", "/api/sessions/" + id + "/decisions", null)).contains("Lier les Stories par parent");
    }
}
