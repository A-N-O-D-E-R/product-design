package productdesigner;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "productdesigner.anthropic.api-key=")
class SocleTest {

    static Path dataDir;

    @DynamicPropertySource
    static void dataDir(DynamicPropertyRegistry registry) throws Exception {
        dataDir = Files.createTempDirectory("productdesigner");
        registry.add("productdesigner.data-dir", dataDir::toString);
    }

    @LocalServerPort
    int port;

    @Autowired
    JdbcClient jdbc;

    HttpResponse<String> get(String path) throws Exception {
        return HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void urlProfondeRenvoieLUi() throws Exception {
        HttpResponse<String> response = get("/projets/1");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("<div id=\"root\">");
    }

    @Test
    void apiInconnueRenvoie404() throws Exception {
        assertThat(get("/api/inconnu").statusCode()).isEqualTo(404);
    }

    @Test
    void cleAbsenteDonneUnMessageExplicite() throws Exception {
        assertThat(get("/api/status").body()).contains("\"anthropic\":false", "Clé Anthropic absente");
    }

    @Test
    void baseEtMigrationsCreees() {
        assertThat(dataDir.resolve("data.db")).exists();
        assertThat(jdbc.sql("select count(*) from flyway_schema_history where success = 0")
                .query(Integer.class).single()).isZero();
    }
}
