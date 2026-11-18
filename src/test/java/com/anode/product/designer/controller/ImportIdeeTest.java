package com.anode.product.designer.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** POST /api/projets/import : l'idea.json d'idea-generator (idea-brief.schema.json) amorce le Projet et sa Map. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ImportIdeeTest {

    @DynamicPropertySource
    static void dataDir(DynamicPropertyRegistry registry) throws Exception {
        String dir = Files.createTempDirectory("productdesigner").toString();
        registry.add("productdesigner.data-dir", () -> dir);
    }

    @LocalServerPort
    int port;

    @Autowired
    JdbcClient jdbc;

    final JsonMapper json = JsonMapper.builder().build();

    static String exemple() throws Exception {
        return Files.readString(Path.of(ImportIdeeTest.class.getResource("/contracts/idea-brief.example.json").toURI()));
    }

    HttpResponse<String> importer(String corps, String query) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/projets/import" + query))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(corps)).build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void ideeAvecRechercheAmorceLaMapEtGardeLaSource() throws Exception {
        HttpResponse<String> reponse = importer(exemple(), "");
        assertThat(reponse.statusCode()).isEqualTo(201);
        JsonNode cree = json.readTree(reponse.body());
        long id = cree.path("id").asLong();
        assertThat(cree.path("nom").asString()).isEqualTo("ReagentLedger");
        assertThat(cree.path("sourceIdeaUrl").asString()).isEqualTo("https://example.org/forum/thread/881");
        assertThat(cree.path("questions").asInt()).isEqualTo(2);
        assertThat(cree.path("horsPerimetre").asInt()).isEqualTo(2);
        assertThat(cree.path("brouillard").asInt()).isEqualTo(4);

        // la Map : Destination = problem, notes = public / MVP / stack / premiers pas
        assertThat(jdbc.sql("SELECT destination FROM map WHERE projet_id = ?").param(id).query(String.class).single())
                .isEqualTo("Small labs find out a reagent ran out in the middle of an experiment.");
        assertThat(jdbc.sql("SELECT notes FROM map WHERE projet_id = ?").param(id).query(String.class).single())
                .contains("Public : Lab managers", "- Low-stock alert", "Stack : Spring Boot, Postgres, React", "1. Interview three lab managers");
        // Questions grilling ouvertes, numérotées
        assertThat(jdbc.sql("SELECT titre || '|' || type || '|' || statut FROM question WHERE projet_id = ? ORDER BY numero")
                .param(id).query(String.class).list())
                .containsExactly("Which barcode formats do suppliers use?|grilling|open", "Is a mobile app needed?|grilling|open");
        assertThat(jdbc.sql("SELECT texte FROM hors_perimetre WHERE projet_id = ? ORDER BY id").param(id).query(String.class).list())
                .containsExactly("Purchasing", "Safety data sheets");
        assertThat(jdbc.sql("SELECT texte FROM brouillard WHERE projet_id = ? ORDER BY id").param(id).query(String.class).list())
                .containsExactly("Risque : Labs will not scan consistently", "Risque : Supplier barcodes are not standard",
                        "Risque (recherche) : Adoption depends on scanning discipline",
                        "Hypothèse à vérifier : A scan-in/scan-out ledger cuts stock-out incidents by half in labs under 30 people.");

        // la recherche : artefact research_md d'une Session research close
        long session = cree.path("sessionResearchId").asLong();
        assertThat(jdbc.sql("SELECT methode || '|' || statut FROM session WHERE id = ?").param(session).query(String.class).single())
                .isEqualTo("research|close");
        assertThat(jdbc.sql("SELECT contenu FROM artefact WHERE session_id = ? AND type = 'research_md'").param(session).query(String.class).single())
                .contains("Verdict de nouveauté : incremental", "https://ai-scientist.example.org/v1/jobs/9f2c",
                        "LIMS products cover inventory", "Adoption depends on scanning discipline");
        // la Session qui trace la Map existe aussi
        assertThat(jdbc.sql("SELECT methode FROM session WHERE id = ?").param(cree.path("sessionTraceId").asLong()).query(String.class).single())
                .isEqualTo("wayfinder");
        // traçabilité : la provenance de l'idée est gardée
        JsonNode provenance = json.readTree(jdbc.sql("SELECT source_idea FROM projet WHERE id = ?").param(id).query(String.class).single());
        assertThat(provenance.path("ideeId").asLong()).isEqualTo(42);
        assertThat(provenance.path("recherche").asString()).endsWith("/v1/jobs/9f2c");
        assertThat(provenance.path("verdictNouveaute").asString()).isEqualTo("incremental");
    }

    @Test
    void memeSourceDeuxFoisEst409EtNeCreeRien() throws Exception {
        String corps = exemple().replace("thread/881", "thread/dup-1");
        assertThat(importer(corps, "").statusCode()).isEqualTo(201);
        int avant = jdbc.sql("SELECT count(*) FROM projet").query(Integer.class).single();
        HttpResponse<String> deuxieme = importer(corps, "");
        assertThat(deuxieme.statusCode()).isEqualTo(409);
        assertThat(deuxieme.body()).contains("déjà importée");
        assertThat(jdbc.sql("SELECT count(*) FROM projet").query(Integer.class).single()).isEqualTo(avant);
    }

    @Test
    void ideeSansRechercheEtSourceDonneeParLAppelant() throws Exception {
        JsonNode idee = json.readTree(exemple());
        ((tools.jackson.databind.node.ObjectNode) idee).remove("research");
        HttpResponse<String> reponse = importer(idee.toString(), "?source=https://ideas.example.org/boards/7/ideas/42");
        assertThat(reponse.statusCode()).isEqualTo(201);
        JsonNode cree = json.readTree(reponse.body());
        assertThat(cree.path("sourceIdeaUrl").asString()).isEqualTo("https://ideas.example.org/boards/7/ideas/42");
        assertThat(cree.path("sessionResearchId").isNull()).isTrue();
        assertThat(cree.path("brouillard").asInt()).isEqualTo(2);
    }

    @Test
    void ideaJsonInvalideEstRefuseSansRienCreer() throws Exception {
        int avant = jdbc.sql("SELECT count(*) FROM projet").query(Integer.class).single();
        JsonNode sansBrief = json.readTree(exemple());
        ((tools.jackson.databind.node.ObjectNode) sansBrief).remove("brief");
        HttpResponse<String> invalide = importer(sansBrief.toString(), "");
        assertThat(invalide.statusCode()).isEqualTo(422);
        assertThat(invalide.body()).contains("idea-brief.schema.json", "brief");

        JsonNode verdictInconnu = json.readTree(exemple());
        ((tools.jackson.databind.node.ObjectNode) verdictInconnu.path("research")).put("noveltyVerdict", "peut-être");
        assertThat(importer(verdictInconnu.toString(), "").statusCode()).isEqualTo(422);

        assertThat(importer("pas du json", "").statusCode()).isEqualTo(400);
        assertThat(importer("  ", "").statusCode()).isEqualTo(400);
        assertThat(jdbc.sql("SELECT count(*) FROM projet").query(Integer.class).single()).isEqualTo(avant);
    }

    @Test
    void leFichierIdeaJsonPeutEtreEnvoyeEnMultipart() throws Exception {
        String corps = exemple().replace("thread/881", "thread/upload-1");
        String frontiere = "----pdtest";
        String multipart = "--" + frontiere + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"idea.json\"\r\n"
                + "Content-Type: application/json\r\n\r\n" + corps + "\r\n--" + frontiere + "--\r\n";
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/projets/import"))
                .header("Content-Type", "multipart/form-data; boundary=" + frontiere)
                .POST(HttpRequest.BodyPublishers.ofString(multipart, StandardCharsets.UTF_8)).build();
        HttpResponse<String> reponse = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(reponse.statusCode()).isEqualTo(201);
        assertThat(json.readTree(reponse.body()).path("sourceIdeaUrl").asString()).isEqualTo("https://example.org/forum/thread/upload-1");
    }
}
