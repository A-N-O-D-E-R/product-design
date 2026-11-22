package com.anode.productdesigner.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import com.networknt.schema.InputFormat;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * L'outil ai_scientist contre un faux ai-scientist, et son contrat : l'OpenAPI d'ai-scientist (copie de son instantané
 * `openapi.json`, à resynchroniser quand l'API d'ai-scientist change) valide ce que le client envoie et ce qu'il lit.
 */
class AiScientistTest {

    static final JsonMapper JSON = JsonMapper.builder().build();

    HttpServer serveur;
    final List<String> requetes = new CopyOnWriteArrayList<>();
    final List<String> autorisations = new CopyOnWriteArrayList<>();
    final AtomicInteger relevés = new AtomicInteger();
    volatile int statutCreation = 201;
    volatile String corpsCreation = "{\"id\":\"job-1\",\"status\":\"QUEUED\"}";
    /** Les réponses successives de GET /v1/jobs/job-1 ; la dernière se répète. */
    volatile List<String> jobs = List.of();

    static final String IDEES = """
            [{"Name":"a","Title":"Ledger codes","Short Hypothesis":"Les codes-barres réduisent les ruptures.","Related Work":"Les LIMS couvrent l'inventaire.",
              "Abstract":"Résumé.","Experiments":["Mesurer les ruptures avant/après"],"Risk Factors and Limitations":"Discipline de scan."},
             {"Name":"b","Title":"Caméras","Short Hypothesis":"Les caméras remplacent le scan.","Related Work":"Vision en rayon mature.",
              "Abstract":"Autre résumé.","Experiments":[],"Risk Factors and Limitations":"Vie privée."}]""";

    @BeforeEach
    void demarrer() throws Exception {
        serveur = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        serveur.createContext("/v1/ideations", ex -> {
            requetes.add("POST /v1/ideations " + new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            autorisations.add(String.valueOf(ex.getRequestHeaders().getFirst("Authorization")));
            repondre(ex, statutCreation, corpsCreation);
        });
        serveur.createContext("/v1/jobs/job-1", ex -> {
            requetes.add("GET /v1/jobs/job-1");
            autorisations.add(String.valueOf(ex.getRequestHeaders().getFirst("Authorization")));
            int i = Math.min(relevés.getAndIncrement(), jobs.size() - 1);
            repondre(ex, 200, jobs.get(i));
        });
        serveur.start();
    }

    @AfterEach
    void arreter() {
        if (serveur != null) {
            serveur.stop(0);
        }
    }

    static void repondre(com.sun.net.httpserver.HttpExchange ex, int statut, String corps) throws java.io.IOException {
        byte[] out = corps.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", statut < 400 ? "application/json" : "application/problem+json");
        ex.sendResponseHeaders(statut, out.length);
        ex.getResponseBody().write(out);
        ex.close();
    }

    AiScientist client(Duration delai) {
        return new AiScientist("http://127.0.0.1:" + serveur.getAddress().getPort() + "/", "ais_test", 3, 2, delai, Duration.ofMillis(10));
    }

    static final String SUJET = "{\"topic\":\"# Title: Suivi de réactifs\\n\\n## TL;DR\\nScanner les flacons.\"}";

    @Test
    void unJobTermineDonneLesIdeesEnMarkdownEtEnArtefact() {
        jobs = List.of("{\"id\":\"job-1\",\"status\":\"QUEUED\"}", "{\"id\":\"job-1\",\"status\":\"RUNNING\",\"stage\":\"idea 1/3\"}",
                "{\"id\":\"job-1\",\"status\":\"DONE\",\"result\":" + IDEES + "}");
        List<String> progression = new ArrayList<>();

        AiScientist.Resultat r = client(Duration.ofSeconds(5)).executer(SUJET, progression::add);

        assertThat(r.artefact()).startsWith("# AI Scientist : idées de recherche").contains("> # Title: Suivi de réactifs",
                "## 1. Ledger codes", "**Hypothèse.** Les codes-barres réduisent les ruptures.", "**Travaux connexes.** Les LIMS couvrent l'inventaire.",
                "- Mesurer les ruptures avant/après", "**Risques et limites.** Discipline de scan.", "## 2. Caméras", "/v1/jobs/job-1");
        assertThat(r.sortie()).startsWith("Résultat de l'AI Scientist (job job-1)").contains("## 1. Ledger codes");
        assertThat(progression).contains("AI Scientist : queued", "AI Scientist : running (idea 1/3)", "AI Scientist : done");
        assertThat(autorisations).allMatch("Bearer ais_test"::equals);
        // le sujet part tel quel, avec les bornes de coût et de durée
        JsonNode envoye = JSON.readTree(requetes.getFirst().substring("POST /v1/ideations ".length()));
        assertThat(envoye.path("topic").asString()).startsWith("# Title: Suivi de réactifs");
        assertThat(envoye.path("maxGenerations").asInt()).isEqualTo(3);
        assertThat(envoye.path("budgetUsd").asDouble()).isEqualTo(2.0);
    }

    @Test
    void unEchecUnePauseOuUnRefusRevientAuModeleSansArtefact() {
        jobs = List.of("{\"id\":\"job-1\",\"status\":\"FAILED\",\"error\":\"budget épuisé\"}");
        AiScientist.Resultat echec = client(Duration.ofSeconds(5)).executer(SUJET, p -> { });
        assertThat(echec.artefact()).isNull();
        assertThat(echec.sortie()).contains("failed", "budget épuisé", "Continue sans lui");

        relevés.set(0);
        jobs = List.of("{\"id\":\"job-1\",\"status\":\"PAUSED\",\"pauseReason\":\"clé Anthropic invalide\"}");
        assertThat(client(Duration.ofSeconds(5)).executer(SUJET, p -> { }).sortie()).contains("en pause", "clé Anthropic invalide");

        statutCreation = 429;
        corpsCreation = "{\"type\":\"/problems/daily-submissions\",\"title\":\"Too many\",\"detail\":\"30 soumissions par jour\"}";
        AiScientist.Resultat refus = client(Duration.ofSeconds(5)).executer(SUJET, p -> { });
        assertThat(refus.artefact()).isNull();
        assertThat(refus.sortie()).contains("refusé", "429", "30 soumissions par jour");
    }

    @Test
    void sansResultatDansLeDelaiOnAbandonneSansBloquerLaResearch() {
        jobs = List.of("{\"id\":\"job-1\",\"status\":\"RUNNING\"}");
        AiScientist.Resultat r = client(Duration.ofMillis(100)).executer(SUJET, p -> { });
        assertThat(r.artefact()).isNull();
        assertThat(r.sortie()).contains("pas de résultat", "le job continue côté ai-scientist", "/v1/jobs/job-1");
    }

    @Test
    void injoignableOuSujetVideOuRenduTropLong() {
        serveur.stop(0);
        assertThat(client(Duration.ofSeconds(1)).executer(SUJET, p -> { }).sortie()).contains("injoignable");
        serveur = null;

        AiScientist.Resultat vide = new AiScientist("", "", 3, 2, Duration.ofSeconds(1), Duration.ofMillis(10)).executer("{\"topic\":\" \"}", p -> { });
        assertThat(vide.sortie()).contains("Sujet vide");
        assertThat(new AiScientist("", "", 3, 2, Duration.ofSeconds(1), Duration.ofMillis(10)).disponible()).isFalse();
        assertThat(new AiScientist("http://x", "ais_k", 3, 2, Duration.ofSeconds(1), Duration.ofMillis(10)).disponible()).isTrue();

        // le résultat rendu au modèle est borné, l'Artefact garde tout
        String enorme = "x".repeat(AiScientist.SORTIE_MAX * 2);
        String md = AiScientist.rendre("s", "http://j", JSON.readTree("[{\"Title\":\"T\",\"Short Hypothesis\":\"" + enorme + "\"}]"));
        assertThat(md.length()).isGreaterThan(AiScientist.SORTIE_MAX);
    }

    // --- le contrat : l'OpenAPI d'ai-scientist ---

    static JsonNode contrat() throws Exception {
        return JSON.readTree(Files.readString(Path.of(AiScientistTest.class.getResource("/contracts/ai-scientist-openapi.json").toURI())));
    }

    @Test
    void lesRoutesUtiliseesExistentDansLeContratDAiScientist() throws Exception {
        JsonNode spec = contrat();
        assertThat(spec.path("paths").path("/v1/ideations").has("post")).isTrue();
        assertThat(spec.path("paths").path("/v1/jobs/{id}").has("get")).isTrue();
    }

    @Test
    void laRequeteEnvoyeeRespecteLeSchemaIdeationRequest() throws Exception {
        jobs = List.of("{\"id\":\"job-1\",\"status\":\"FAILED\"}");
        client(Duration.ofSeconds(5)).executer(SUJET, p -> { });
        String corps = requetes.getFirst().substring("POST /v1/ideations ".length());

        String schema = JSON.writeValueAsString(contrat().path("components").path("schemas").path("IdeationRequest"));
        var erreurs = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12, b -> { })
                .getSchema(schema, InputFormat.JSON).validate(corps, InputFormat.JSON);
        assertThat(erreurs).as(corps).isEmpty();
        // et un corps hors contrat serait refusé : le test détecte une dérive
        assertThat(SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12, b -> { })
                .getSchema(schema, InputFormat.JSON).validate("{\"sujet\":\"x\"}", InputFormat.JSON)).isNotEmpty();
    }

    @Test
    void lesStatutsEtChampsLusParLeClientSontCeuxDeJobView() throws Exception {
        JsonNode jobView = contrat().path("components").path("schemas").path("JobView").path("properties");
        List<String> statuts = new ArrayList<>();
        jobView.path("status").path("enum").forEach(s -> statuts.add(s.asString()));
        // ceux que le client traite explicitement, plus ceux qu'il attend
        assertThat(statuts).contains("QUEUED", "RUNNING", "PAUSED", "DONE", "FAILED", "CANCELLED", "REJECTED");
        assertThat(jobView.propertyNames()).contains("id", "status", "stage", "result", "error", "pauseReason");
    }
}
