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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
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
    void tokenClaudeEcritSeulementJamaisRelu() throws Exception {
        assertThat(get("/api/status").body()).contains("\"model\":\"opus\"", "\"configure\":false");
        HttpResponse<String> put = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/config/token"))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString("{\"token\":\" 'sk-ant-oat01-SECRET-XYZ' \"}")).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(put.body()).contains("\"configure\":true", "\"type\":\"oauth_token\"", "sk-ant-oat").doesNotContain("SECRET");
        assertThat(dataDir.resolve("claude-token")).hasContent("sk-ant-oat01-SECRET-XYZ");
        assertThat(Files.getPosixFilePermissions(dataDir.resolve("claude-token")).toString()).isEqualTo("[OWNER_READ, OWNER_WRITE]");
        assertThat(get("/api/status").body()).doesNotContain("SECRET");

        HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/config/token")).DELETE().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(dataDir.resolve("claude-token")).doesNotExist();
        assertThat(get("/api/config/token").body()).contains("\"configure\":false");
    }

    @Test
    void baseEtMigrationsCreees() {
        assertThat(dataDir.resolve("data.db")).exists();
        assertThat(jdbc.sql("select count(*) from flyway_schema_history where success = 0")
                .query(Integer.class).single()).isZero();
    }

    @Value("${server.address}")
    String adresse;

    @Test
    void ecouteSeulementEnLocal() {
        assertThat(adresse).isEqualTo("127.0.0.1");
    }
}
