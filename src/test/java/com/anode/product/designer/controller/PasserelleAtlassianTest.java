package com.anode.product.designer.controller;

import com.anode.product.designer.service.RoadmapService;
import static org.assertj.core.api.Assertions.assertThat;

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

import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.anode.product.designer.service.RoadmapService.JalonGenere;
import com.anode.product.designer.service.RoadmapService.RoadmapGeneree;
import com.anode.product.designer.service.RoadmapService.TicketGenere;

/** Token scopé : avec un cloud-id, Jira et Confluence passent par la passerelle api.atlassian.com/ex/{produit}/{cloudId}. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PasserelleAtlassianTest {

    static final String CLOUD = "692b908b-e782-4250-b949-3a657d411d07";
    static HttpServer passerelle;
    static final List<String> chemins = new CopyOnWriteArrayList<>();
    static final AtomicInteger cles = new AtomicInteger();

    @DynamicPropertySource
    static void config(DynamicPropertyRegistry registry) throws Exception {
        passerelle = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        passerelle.createContext("/", ex -> {
            String corps = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String path = ex.getRequestURI().getPath();
            chemins.add(ex.getRequestMethod() + " " + path);
            String r;
            if (path.endsWith("/issuetypes")) {
                r = "{\"issueTypes\":[{\"id\":\"1\",\"name\":\"Epic\",\"hierarchyLevel\":1},{\"id\":\"2\",\"name\":\"Story\",\"hierarchyLevel\":0}],\"isLast\":true}";
            } else if (path.endsWith("/issue/bulk")) {
                r = "{\"issues\":[{\"key\":\"PD-" + cles.incrementAndGet() + "\"}],\"errors\":[]}";
            } else if (path.endsWith("/issue")) {
                r = "{\"key\":\"PD-" + cles.incrementAndGet() + "\"}";
            } else if (path.endsWith("/spaces")) {
                r = "{\"results\":[{\"id\":\"98\"}]}";
            } else if (path.endsWith("/pages")) {
                r = "{\"id\":\"123\",\"version\":{\"number\":1},\"_links\":{\"base\":\"https://site/wiki\",\"webui\":\"/x\"}}";
            } else {
                r = "{}";
            }
            byte[] b = r.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        passerelle.start();
        String dir = Files.createTempDirectory("productdesigner").toString();
        registry.add("productdesigner.data-dir", () -> dir);
        registry.add("anode.atlassian.base-url", () -> "https://ne-pas-appeler.atlassian.net");
        registry.add("anode.atlassian.email", () -> "moi@exemple.fr");
        registry.add("anode.atlassian.api-token", () -> "scope");
        registry.add("anode.atlassian.cloud-id", () -> CLOUD);
        registry.add("anode.atlassian.gateway-url", () -> "http://localhost:" + passerelle.getAddress().getPort());
    }

    @AfterAll
    static void stop() {
        passerelle.stop(0);
    }

    @LocalServerPort
    int port;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    RoadmapService roadmaps;

    String post(String path, String json) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(json)).build(),
                HttpResponse.BodyHandlers.ofString()).body();
    }

    @Test
    void jiraEtConfluenceViaLaPasserelle() throws Exception {
        long projet = Long.parseLong(post("/api/projets", "{\"nom\":\"P\"}").replaceAll(".*\"id\":(\\d+),.*", "$1"));
        long spec = jdbc.sql("INSERT INTO spec (projet_id, version, contenu) VALUES (?, 1, '# S\n\n## 1. Problème\n\nX') RETURNING id")
                .param(projet).query(Long.class).single();
        roadmaps.enregistrer(spec, new RoadmapGeneree(List.of(new JalonGenere("J", List.of(
                new TicketGenere("T", "c", List.of(), List.of(), List.of()))))), Set.of());

        assertThat(post("/api/specs/" + spec + "/export-jira", "{\"projet\":\"PD\"}")).contains("\"storiesCreees\":1");
        assertThat(post("/api/specs/" + spec + "/export-confluence", "{\"espace\":\"PD\"}")).contains("\"creee\":true");

        assertThat(chemins).contains(
                "GET /ex/jira/" + CLOUD + "/rest/api/3/issue/createmeta/PD/issuetypes",
                "POST /ex/jira/" + CLOUD + "/rest/api/3/issue",
                "POST /ex/jira/" + CLOUD + "/rest/api/3/issue/bulk",
                "GET /ex/confluence/" + CLOUD + "/wiki/api/v2/spaces",
                "POST /ex/confluence/" + CLOUD + "/wiki/api/v2/pages");
        assertThat(chemins).allMatch(c -> c.contains("/ex/jira/" + CLOUD + "/rest/") || c.contains("/ex/confluence/" + CLOUD + "/wiki/"));
    }
}
