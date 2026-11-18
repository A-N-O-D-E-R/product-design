package com.anode.productdesigner.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.anode.productdesigner.TestDb;

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
class ResearchAiScientistTest {

    static HttpServer faux;
    static final List<String> requetes = new CopyOnWriteArrayList<>();
    static final List<String> scientist = new CopyOnWriteArrayList<>();
    static final Queue<String> reponses = new ConcurrentLinkedQueue<>();

    static String json(String texte) {
        return texte.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }

    static String chunk(String choix) {
        return "data: {\"id\":\"c1\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"gpt-test\",\"choices\":[" + choix + "]}\n\n";
    }

    /** Stream Chat Completions : un texte, puis éventuellement un appel d'outil. */
    static String stream(String texte, String outil, String arguments) {
        StringBuilder sb = new StringBuilder();
        sb.append(chunk("{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"" + json(texte) + "\"},\"finish_reason\":null}"));
        if (outil != null) {
            sb.append(chunk("{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_1\",\"type\":\"function\",\"function\":{\"name\":\""
                    + outil + "\",\"arguments\":\"" + json(arguments) + "\"}}]},\"finish_reason\":null}"));
        }
        sb.append(chunk("{\"index\":0,\"delta\":{},\"finish_reason\":\"" + (outil == null ? "stop" : "tool_calls") + "\"}"));
        sb.append("data: {\"id\":\"c1\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"gpt-test\",\"choices\":[],"
                + "\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5,\"total_tokens\":15}}\n\n");
        return sb.append("data: [DONE]\n\n").toString();
    }

    static final String IDEES = """
            [{"Name":"a","Title":"Quantification par distillation","Short Hypothesis":"Distiller en 4 bits garde 98 % de la précision.",
              "Related Work":"GPTQ et AWQ quantifient après entraînement.","Abstract":"Résumé.","Experiments":["Comparer sur trois benchmarks"],
              "Risk Factors and Limitations":"Coût de la distillation."}]""";

    @DynamicPropertySource
    static void config(DynamicPropertyRegistry registry) throws Exception {
        faux = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        faux.createContext("/", exchange -> {
            String corps = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String chemin = exchange.getRequestURI().getPath();
            String reponse;
            String type = "application/json";
            if (chemin.equals("/ais/v1/ideations")) {
                scientist.add("POST " + chemin + " " + exchange.getRequestHeaders().getFirst("Authorization") + " " + corps);
                reponse = "{\"id\":\"job-9\",\"status\":\"QUEUED\"}";
            } else if (chemin.equals("/ais/v1/jobs/job-9")) {
                scientist.add("GET " + chemin);
                reponse = "{\"id\":\"job-9\",\"status\":\"DONE\",\"result\":" + IDEES + "}";
            } else {
                requetes.add(chemin + " " + corps);
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
        TestDb.enregistrer(registry);
        registry.add("spring.ai.model.chat", () -> "openai");
        registry.add("spring.ai.openai.api-key", () -> "test");
        registry.add("spring.ai.openai.base-url", () -> base + "/v1");
        registry.add("spring.ai.openai.chat.options.model", () -> "gpt-test");
        registry.add("productdesigner.recherche.tavily-api-key", () -> "tvly-test");
        registry.add("productdesigner.recherche.tavily-url", () -> base + "/tavily");
        registry.add("productdesigner.ai-scientist.url", () -> base + "/ais");
        registry.add("productdesigner.ai-scientist.api-key", () -> "ais_test_key");
        registry.add("productdesigner.ai-scientist.poll-interval", () -> "20ms");
    }

    @AfterAll
    static void stop() {
        faux.stop(0);
    }

    @BeforeEach
    void reset() {
        requetes.clear();
        scientist.clear();
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
    void uneQuestionTechniqueDonneUneEtudeAiScientistEnArtefactEtAuModele() throws Exception {
        String projetId = send("POST", "/api/projets", "{\"nom\":\"ML\"}").replaceAll(".*\"id\":(\\d+),.*", "$1");
        String p = "/api/projets/" + projetId;
        String carte = send("POST", p + "/questions", "{\"titre\":\"Quantification\",\"corps\":\"Quelle quantification pour un modèle de 7B ?\",\"type\":\"research\",\"blockedBy\":[]}");
        String q = carte.replaceAll(".*\"questions\":\\[\\{\"id\":(\\d+),.*", "$1");
        reponses.add(stream("Question technique : j'interroge l'AI Scientist.", "ai_scientist",
                "{\"topic\":\"# Title: Quantification 4 bits\\n\\n## TL;DR\\nQuantifier un 7B sans perdre en précision.\"}"));
        reponses.add(stream("# Rapport quantification\n\nDistiller en 4 bits.", "record", """
                {"round":{"questions":[]},"decisions":[{"texte":"Quantifier par distillation"}],"questions":[]}"""));

        String id = send("POST", p + "/questions/" + q + "/sessions", null).replaceAll(".*?\"id\":(\\d+),.*", "$1");
        String detail = "";
        for (int i = 0; i < 150 && !detail.contains("\"tache\":\"terminee\"") && !detail.contains("echec"); i++) {
            Thread.sleep(100);
            detail = send("GET", "/api/sessions/" + id, null);
        }
        assertThat(detail).contains("\"tache\":\"terminee\"");

        // le modèle a eu l'outil, à côté de web_search
        assertThat(requetes.getFirst()).contains("\"name\":\"ai_scientist\"", "\"name\":\"web_search\"", "Quelle quantification pour un modèle de 7B ?");
        // le job a été créé avec la clé ais_, puis relevé
        assertThat(scientist.getFirst()).startsWith("POST /ais/v1/ideations Bearer ais_test_key").contains("Quantification 4 bits", "\"budgetUsd\":2.0");
        assertThat(scientist).contains("GET /ais/v1/jobs/job-9");
        // son résultat revient au modèle en réponse d'outil
        assertThat(requetes.getLast()).contains("\"tool_call_id\":\"call_1\"", "Distiller en 4 bits garde 98 % de la précision.", "GPTQ et AWQ");
        // et il est enregistré avec le rapport final : deux Artefacts research_md
        String artefacts = send("GET", "/api/sessions/" + id + "/artefacts", null);
        assertThat(artefacts).contains("# AI Scientist : idées de recherche", "Quantification par distillation", "# Rapport quantification");
        assertThat(artefacts.split("\"type\":\"research_md\"", -1).length - 1).isEqualTo(2);
        assertThat(send("GET", "/api/sessions/" + id + "/decisions", null)).contains("Quantifier par distillation");
    }
}
