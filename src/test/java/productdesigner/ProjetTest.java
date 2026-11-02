package productdesigner;

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
class ProjetTest {

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
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .method(method, json == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json));
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void projetCreeEstListeAvecUneMapVide() throws Exception {
        HttpResponse<String> cree = send("POST", "/api/projets", "{\"nom\":\" Mon idée \",\"description\":\"Un truc\"}");
        assertThat(cree.statusCode()).isEqualTo(201);
        assertThat(cree.body()).contains("\"nom\":\"Mon idée\"");

        assertThat(send("GET", "/api/projets", null).body()).contains("\"nom\":\"Mon idée\"", "\"description\":\"Un truc\"");

        long id = jdbc.sql("SELECT id FROM projet WHERE nom = 'Mon idée'").query(Long.class).single();
        assertThat(jdbc.sql("SELECT count(*) FROM map WHERE projet_id = ? AND destination = '' AND notes = ''")
                .param(id).query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void nomVideRefuse() throws Exception {
        assertThat(send("POST", "/api/projets", "{\"nom\":\"  \"}").statusCode()).isEqualTo(400);
        assertThat(jdbc.sql("SELECT count(*) FROM projet WHERE nom = ''").query(Integer.class).single()).isZero();
    }
}
