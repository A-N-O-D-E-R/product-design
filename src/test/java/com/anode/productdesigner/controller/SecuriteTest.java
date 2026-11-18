package com.anode.productdesigner.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.nio.file.Files;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Le starter de sécurité allumé, login mock : /api demande une connexion, CSRF sur les écritures, clés d'API en Bearer. */
@SpringBootTest(properties = {"anode.security.enabled=true", "anode.security.mock=true", "server.address=127.0.0.1"})
class SecuriteTest {

    @DynamicPropertySource
    static void dataDir(DynamicPropertyRegistry registry) throws Exception {
        String dir = Files.createTempDirectory("productdesigner").toString();
        registry.add("productdesigner.data-dir", () -> dir);
    }

    @Autowired
    WebApplicationContext contexte;

    MockMvc mvc;
    final JsonMapper json = JsonMapper.builder().build();

    @BeforeEach
    void init() {
        mvc = MockMvcBuilders.webAppContextSetup(contexte).apply(springSecurity()).build();
    }

    MockHttpSession connecte(String nom) throws Exception {
        return (MockHttpSession) mvc.perform(post("/login").with(csrf()).param("username", nom).param("password", "x"))
                .andReturn().getRequest().getSession();
    }

    MockHttpServletResponse envoyer(MockHttpServletRequestBuilder requete) throws Exception {
        return mvc.perform(requete).andReturn().getResponse();
    }

    static MockHttpServletRequestBuilder corps(MockHttpServletRequestBuilder r, String json) {
        return r.contentType("application/json").content(json);
    }

    @Test
    void sansConnexionLApiRepond401EnProblemJsonEtLaPageDeLoginEstPublique() throws Exception {
        MockHttpServletResponse r = envoyer(get("/api/projets"));
        assertThat(r.getStatus()).isEqualTo(401);
        assertThat(r.getContentType()).startsWith("application/problem+json");
        JsonNode p = json.readTree(r.getContentAsString());
        assertThat(p.path("status").asInt()).isEqualTo(401);
        assertThat(p.path("detail").asString()).isEqualTo("Connexion requise.");

        assertThat(envoyer(get("/login")).getStatus()).isEqualTo(200);
        // le front statique n'est pas derrière la connexion : il se charge, puis l'API répond 401
        assertThat(envoyer(get("/")).getStatus()).isNotIn(401, 403);
    }

    @Test
    void connecteLApiRepondEtLesEcrituresDemandentLeJetonCsrf() throws Exception {
        MockHttpSession session = connecte("alice");
        assertThat(envoyer(get("/api/projets").session(session)).getStatus()).isEqualTo(200);

        MockHttpServletResponse sansJeton = envoyer(corps(post("/api/projets").session(session), "{\"nom\":\"sans jeton\"}"));
        assertThat(sansJeton.getStatus()).isEqualTo(403);
        assertThat(json.readTree(sansJeton.getContentAsString()).path("detail").asString()).isEqualTo("Jeton CSRF absent ou invalide.");

        assertThat(envoyer(corps(post("/api/projets").session(session).with(csrf()), "{\"nom\":\"avec jeton\"}")).getStatus()).isEqualTo(201);
    }

    @Test
    void uneRequeteCrossSiteOuDUneIframeSandboxSansSessionEst401() throws Exception {
        // une iframe sandbox (Origin: null) et un autre site n'ont pas le cookie de session
        for (String origine : new String[] {"null", "https://evil.example"}) {
            MockHttpServletResponse r = envoyer(corps(post("/api/projets").header("Origin", origine), "{\"nom\":\"pirate\"}"));
            assertThat(r.getStatus()).as(origine).isIn(401, 403);
        }
        assertThat(envoyer(get("/api/projets").session(connecte("alice"))).getContentAsString()).doesNotContain("pirate");
    }

    @Test
    void avecLeLoginMockUnHoteNonLocalEstRefuse() throws Exception {
        assertThat(envoyer(get("/login").with(r -> {
            r.setServerName("rebind.evil.example");
            return r;
        })).getStatus()).isEqualTo(403);
        assertThat(envoyer(get("/login").with(r -> {
            r.setServerName("localhost");
            return r;
        })).getStatus()).isEqualTo(200);
    }

    @Test
    void uneCleDApiOuvreLApiSansSessionNiCsrfEtNeGerePasLesCles() throws Exception {
        MockHttpSession session = connecte("alice");
        JsonNode creee = json.readTree(envoyer(corps(post("/api/cles-api").session(session).with(csrf()), "{\"nom\":\"idea-generator\"}"))
                .getContentAsString());
        String cle = creee.path("cle").asString();
        assertThat(cle).startsWith("pd_");

        // sans session ni jeton CSRF
        assertThat(envoyer(get("/api/projets").header("Authorization", "Bearer " + cle)).getStatus()).isEqualTo(200);
        assertThat(envoyer(corps(post("/api/projets").header("Authorization", "Bearer " + cle), "{\"nom\":\"par clé\"}")).getStatus()).isEqualTo(201);

        // une clé ne crée, ne liste ni ne révoque des clés
        MockHttpServletResponse interdit = envoyer(corps(post("/api/cles-api").header("Authorization", "Bearer " + cle), "{\"nom\":\"x\"}"));
        assertThat(interdit.getStatus()).isEqualTo(403);
        assertThat(interdit.getContentAsString()).contains("session de navigateur");
        assertThat(envoyer(get("/api/cles-api").header("Authorization", "Bearer " + cle)).getStatus()).isEqualTo(403);

        // la liste ne montre jamais la clé
        String liste = envoyer(get("/api/cles-api").session(session)).getContentAsString();
        assertThat(liste).contains("idea-generator").doesNotContain(cle);
    }

    @Test
    void uneCleRevoqueeOuMalFormeeEst401() throws Exception {
        MockHttpSession session = connecte("alice");
        JsonNode creee = json.readTree(envoyer(corps(post("/api/cles-api").session(session).with(csrf()), "{\"nom\":\"courte\"}")).getContentAsString());
        String cle = creee.path("cle").asString();
        assertThat(envoyer(get("/api/projets").header("Authorization", "Bearer " + cle)).getStatus()).isEqualTo(200);

        assertThat(envoyer(delete("/api/cles-api/" + creee.path("id").asString()).session(session).with(csrf())).getStatus()).isEqualTo(200);
        assertThat(envoyer(get("/api/projets").header("Authorization", "Bearer " + cle)).getStatus()).isEqualTo(401);
        assertThat(envoyer(get("/api/projets").header("Authorization", "Bearer pd_pas-une-cle")).getStatus()).isEqualTo(401);
        assertThat(envoyer(get("/api/projets").header("Authorization", "Bearer " + cle.substring(0, cle.length() - 1) + "0")).getStatus()).isEqualTo(401);
    }

    @Test
    void lesClesSontLimitees() throws Exception {
        MockHttpSession session = connecte("limite");
        int creees = 0;
        for (int i = 0; i < 20 && creees < 10; i++) {
            if (envoyer(corps(post("/api/cles-api").session(session).with(csrf()), "{\"nom\":\"k" + i + "\"}")).getStatus() == 201) {
                creees++;
            }
        }
        // d'autres tests ont pu en créer : la limite se atteint avant 10 ou à 10, jamais au-delà
        MockHttpServletResponse trop = envoyer(corps(post("/api/cles-api").session(session).with(csrf()), "{\"nom\":\"de trop\"}"));
        assertThat(trop.getStatus()).isEqualTo(409);
        assertThat(trop.getContentAsString()).contains("clés d'API actives");
    }
}
