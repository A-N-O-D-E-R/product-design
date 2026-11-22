package com.anode.productdesigner.controller;

import com.anode.productdesigner.service.RoadmapService;
import static org.assertj.core.api.Assertions.assertThat;

import com.anode.productdesigner.TestDb;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.anode.productdesigner.service.RoadmapService.JalonGenere;
import com.anode.productdesigner.service.RoadmapService.RoadmapGeneree;
import com.anode.productdesigner.service.RoadmapService.TicketGenere;

/** Édition de la Roadmap avant export (E-25), sur une Roadmap posée sans LLM. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RoadmapTest {

    @DynamicPropertySource
    static void dataDir(DynamicPropertyRegistry registry) throws Exception {
        TestDb.enregistrer(registry);
    }

    @LocalServerPort
    int port;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    RoadmapService roadmaps;

    HttpResponse<String> send(String method, String path, String json) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .method(method, json == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json))
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    long id(String sql) {
        return jdbc.sql(sql).query(Long.class).single();
    }

    @Test
    void editerAvantExport() throws Exception {
        send("POST", "/api/projets", "{\"nom\":\"E\"}");
        long spec = jdbc.sql("INSERT INTO spec (projet_id, version, contenu) VALUES (1, 1, 'x') RETURNING id").query(Long.class).single();
        roadmaps.enregistrer(spec, new RoadmapGeneree(List.of(
                new JalonGenere("J1", List.of(new TicketGenere("A", "a", List.of(), List.of(), List.of()))),
                new JalonGenere("J2", List.of(new TicketGenere("B", "b", List.of(), List.of(), List.of()))))), Set.of());
        long j1 = id("SELECT id FROM jalon WHERE titre = 'J1'"), j2 = id("SELECT id FROM jalon WHERE titre = 'J2'");
        long a = id("SELECT id FROM ticket WHERE titre = 'A'"), b = id("SELECT id FROM ticket WHERE titre = 'B'");

        send("PUT", "/api/jalons/" + j1, "{\"titre\":\"Socle\"}");
        send("POST", "/api/jalons/" + j1 + "/tickets", "{\"jalonId\":" + j1 + ",\"titre\":\"C\",\"corps\":\"c\",\"exigences\":[\"E-1\"],\"blockedBy\":[" + a + "]}");
        // Déplacer B dans J1 : il y passe en dernier, après C.
        send("PUT", "/api/tickets/" + b, "{\"jalonId\":" + j1 + ",\"titre\":\"B renommé\",\"corps\":\"bb\",\"exigences\":[],\"blockedBy\":[]}");
        send("DELETE", "/api/tickets/" + a, null);

        String r = send("GET", "/api/specs/" + spec + "/roadmap", null).body();
        assertThat(r).contains("\"titre\":\"Socle\"", "\"titre\":\"C\"", "\"exigences\":[\"E-1\"]", "\"blockedBy\":[]");
        assertThat(r.indexOf("\"titre\":\"C\"")).isLessThan(r.indexOf("\"titre\":\"B renommé\""));
        assertThat(r).doesNotContain("\"titre\":\"A\"");
        assertThat(r.indexOf("\"titre\":\"B renommé\"")).isLessThan(r.indexOf("\"titre\":\"J2\""));
        assertThat(send("PUT", "/api/tickets/" + b, "{\"jalonId\":" + j2 + ",\"titre\":\" \",\"corps\":\"\",\"exigences\":[],\"blockedBy\":[]}")
                .statusCode()).isEqualTo(400);
    }
}
