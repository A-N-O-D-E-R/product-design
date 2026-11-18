package com.anode.productdesigner.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;

import com.anode.productdesigner.TestDb;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Le contrat de l'API : `/api/openapi.json` comparé à l'instantané versionné `src/test/resources/openapi.json`. Si l'API change,
 * relire le diff puis régénérer : `UPDATE_OPENAPI=1 mvn test -Dtest=OpenApiTest` (le front en tire ses types : `pnpm api:types`).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OpenApiTest {

    static final Path INSTANTANE = Path.of("src/test/resources/openapi.json");

    @DynamicPropertySource
    static void baseDeTest(DynamicPropertyRegistry registry) {
        TestDb.enregistrer(registry);
    }

    @LocalServerPort
    int port;

    final JsonMapper json = JsonMapper.builder().build();

    @Test
    void lApiCorrespondALInstantane() throws Exception {
        HttpResponse<String> r = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/openapi.json")).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).isEqualTo(200);
        JsonNode spec = json.readTree(r.body());
        assertThat(spec.path("openapi").asString()).startsWith("3.");
        assertThat(spec.path("paths").propertyNames()).contains("/api/projets", "/api/projets/import", "/api/cles-api");

        String joli = json.writerWithDefaultPrettyPrinter().writeValueAsString(spec) + "\n";
        if ("1".equals(System.getenv("UPDATE_OPENAPI")) || !Files.exists(INSTANTANE)) {
            Files.writeString(INSTANTANE, joli);
        }
        assertThat(json.readTree(Files.readString(INSTANTANE)))
                .as("Le contrat de l'API a changé : relire le diff, puis régénérer avec UPDATE_OPENAPI=1 mvn test -Dtest=OpenApiTest")
                .isEqualTo(spec);
    }
}
