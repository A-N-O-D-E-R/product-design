package com.anode.productdesigner.controller;

import com.anode.productdesigner.service.MapService;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MapTest {

    @DynamicPropertySource
    static void dataDir(DynamicPropertyRegistry registry) throws Exception {
        String dir = Files.createTempDirectory("productdesigner").toString();
        registry.add("productdesigner.data-dir", () -> dir);
    }

    @LocalServerPort
    int port;

    @Autowired
    JdbcClient jdbc;

    HttpResponse<String> send(String method, String path, String json) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .method(method, json == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json))
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    String id(String json) {
        return json.replaceAll(".*\"id\":(\\d+),.*", "$1");
    }

    @Test
    void editionsPersistees() throws Exception {
        String p = "/api/projets/" + id(send("POST", "/api/projets", "{\"nom\":\"M\"}").body());
        send("PUT", p + "/map", "{\"destination\":\" Spec MVP \",\"notes\":\"Stack Java\"}");
        send("POST", p + "/questions", "{\"titre\":\"SDK\",\"corps\":\"Lequel ?\",\"type\":\"research\",\"blockedBy\":[]}");
        send("POST", p + "/questions", "{\"titre\":\"Modèle\",\"corps\":\"\",\"type\":\"grilling\",\"blockedBy\":[1]}");
        assertThat(send("POST", p + "/questions", "{\"titre\":\"SDK\",\"corps\":\"\",\"type\":\"grilling\",\"blockedBy\":[]}").statusCode()).isEqualTo(409);

        String q2 = jdbc.sql("SELECT id FROM question WHERE titre = 'Modèle'").query(String.class).single();
        send("PUT", p + "/questions/" + q2, "{\"titre\":\"Modèle de données\",\"corps\":\"Entités ?\",\"type\":\"grilling\",\"blockedBy\":[]}");
        send("POST", p + "/hors-perimetre", "{\"texte\":\"Mobile\"}");
        String b = id(send("POST", p + "/brouillard", "{\"texte\":\"Coûts\"}").body().replaceAll(".*\"brouillard\":\\[", ""));
        send("PUT", p + "/brouillard/" + b, "{\"texte\":\"Coûts LLM\"}");

        String carte = send("GET", p + "/map", null).body();
        assertThat(carte).contains("\"destination\":\"Spec MVP\"", "\"notes\":\"Stack Java\"",
                "\"numero\":2,\"titre\":\"Modèle de données\",\"corps\":\"Entités ?\"", "\"blockedBy\":[]",
                "\"texte\":\"Coûts LLM\"", "\"texte\":\"Mobile\"");

        send("DELETE", p + "/hors-perimetre/" + jdbc.sql("SELECT id FROM hors_perimetre").query(String.class).single(), null);
        assertThat(send("GET", p + "/map", null).body()).contains("\"horsPerimetre\":[]");
    }

    @Test
    void promouvoirUnBrouillard() throws Exception {
        String p = "/api/projets/" + id(send("POST", "/api/projets", "{\"nom\":\"B\"}").body());
        send("POST", p + "/brouillard", "{\"texte\":\"Partage entre appareils\"}");
        String b = jdbc.sql("SELECT id FROM brouillard WHERE texte = 'Partage entre appareils'").query(String.class).single();

        String carte = send("POST", p + "/brouillard/" + b + "/promouvoir", "{\"type\":\"grilling\"}").body();
        assertThat(carte).contains("\"brouillard\":[]", "\"titre\":\"Partage entre appareils\"", "\"statut\":\"open\"");
    }

    @Test
    void horsPerimetreDepuisToutStatut() throws Exception {
        String p = "/api/projets/" + id(send("POST", "/api/projets", "{\"nom\":\"H\"}").body());
        for (String statut : new String[] {"open", "claimed", "resolved"}) {
            send("POST", p + "/questions", "{\"titre\":\"" + statut + "\",\"corps\":\"\",\"type\":\"grilling\",\"blockedBy\":[]}");
            String q = jdbc.sql("SELECT id FROM question WHERE titre = ?").param(statut).query(String.class).single();
            jdbc.sql("UPDATE question SET statut = ? WHERE id = ?").params(statut, q).update();
            assertThat(send("POST", p + "/questions/" + q + "/hors-perimetre", null).statusCode()).isEqualTo(200);
            assertThat(jdbc.sql("SELECT statut FROM question WHERE id = ?").param(q).query(String.class).single())
                    .isEqualTo("hors_perimetre");
        }
    }

    @Test
    void frontiereEtOuvertureDUneQuestion() throws Exception {
        String p = "/api/projets/" + id(send("POST", "/api/projets", "{\"nom\":\"F\"}").body());
        send("POST", p + "/questions", "{\"titre\":\"Socle\",\"corps\":\"Quelle stack ?\",\"type\":\"grilling\",\"blockedBy\":[]}");
        send("POST", p + "/questions", "{\"titre\":\"Suite\",\"corps\":\"\",\"type\":\"grilling\",\"blockedBy\":[1]}");
        String carte = send("GET", p + "/map", null).body();
        assertThat(carte).contains("\"titre\":\"Socle\"", "\"blockedBy\":[1],\"ouvrable\":false");
        assertThat(carte.indexOf("\"ouvrable\":true")).isLessThan(carte.indexOf("\"titre\":\"Suite\""));

        String q1 = jdbc.sql("SELECT id FROM question WHERE titre = 'Socle'").query(String.class).single();
        String q2 = jdbc.sql("SELECT id FROM question WHERE titre = 'Suite'").query(String.class).single();
        assertThat(send("POST", p + "/questions/" + q2 + "/sessions", null).statusCode()).isEqualTo(409);

        HttpResponse<String> session = send("POST", p + "/questions/" + q1 + "/sessions", null);
        assertThat(session.statusCode()).isEqualTo(201);
        assertThat(session.body()).contains("\"methode\":\"grilling\"", "\"questionId\":" + q1, "\"question\":\"Socle\"");
        assertThat(jdbc.sql("SELECT statut FROM question WHERE id = ?").param(q1).query(String.class).single()).isEqualTo("claimed");
        assertThat(send("POST", p + "/questions/" + q1 + "/sessions", null).statusCode()).isEqualTo(409);

        // Bloqueuse résolue : la suivante entre dans la frontière.
        jdbc.sql("UPDATE question SET statut = 'resolved' WHERE id = ?").param(q1).update();
        assertThat(send("GET", p + "/map", null).body()).contains("\"blockedBy\":[1],\"ouvrable\":true");
    }

    @Autowired
    MapService maps;

    @Test
    void bloquanteHorsPerimetreLeveLeBlocageEtCyclesRefuses() throws Exception {
        String p = "/api/projets/" + id(send("POST", "/api/projets", "{\"nom\":\"Cycle\"}").body());
        send("POST", p + "/questions", "{\"titre\":\"A\",\"corps\":\"\",\"type\":\"grilling\",\"blockedBy\":[]}");
        send("POST", p + "/questions", "{\"titre\":\"B\",\"corps\":\"\",\"type\":\"grilling\",\"blockedBy\":[1]}");
        send("POST", p + "/questions", "{\"titre\":\"C\",\"corps\":\"\",\"type\":\"grilling\",\"blockedBy\":[2]}");
        String qa = jdbc.sql("SELECT id FROM question WHERE titre = 'A' ORDER BY id DESC LIMIT 1").query(String.class).single();
        long projet = jdbc.sql("SELECT projet_id FROM question WHERE id = ?").param(qa).query(Long.class).single();

        // A bloquée par C fermerait A → C → B → A.
        HttpResponse<String> r = send("PUT", p + "/questions/" + qa, "{\"titre\":\"A\",\"corps\":\"\",\"type\":\"grilling\",\"blockedBy\":[3]}");
        assertThat(r.statusCode()).isEqualTo(400);
        assertThat(r.body()).contains("Dépendance circulaire");
        // Même cycle proposé par le LLM : ignoré.
        maps.appliquer(projet, com.anode.productdesigner.service.Conversation.JSON.readTree("""
                {"questions":[{"titre":"A","corps":"","type":"grilling","blocked_by":["C"]}],"map":{}}"""));
        assertThat(send("GET", p + "/map", null).body()).contains("\"numero\":1,\"titre\":\"A\",\"corps\":\"\",\"type\":\"grilling\",\"statut\":\"open\",\"blockedBy\":[]");

        // A sort du périmètre : B n'est plus bloquée.
        send("POST", p + "/questions/" + qa + "/hors-perimetre", null);
        assertThat(send("GET", p + "/map", null).body()).contains("\"titre\":\"B\",\"corps\":\"\",\"type\":\"grilling\",\"statut\":\"open\",\"blockedBy\":[1],\"ouvrable\":true");
    }
}
