package com.anode.productdesigner.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.nio.file.Files;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/** productdesigner.allowed-users : un compte connecté mais absent de la liste n'entre pas. */
@SpringBootTest(properties = {"anode.security.enabled=true", "anode.security.mock=true", "server.address=127.0.0.1",
        "productdesigner.allowed-users=alice, bob@example.org"})
class SecuriteAutoriseTest {

    @DynamicPropertySource
    static void dataDir(DynamicPropertyRegistry registry) throws Exception {
        String dir = Files.createTempDirectory("productdesigner").toString();
        registry.add("productdesigner.data-dir", () -> dir);
    }

    @Autowired
    WebApplicationContext contexte;

    @Test
    void seulsLesUtilisateursDeLaListeEntrent() throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(contexte).apply(springSecurity()).build();
        // des utilisateurs nommés injectés dans la vraie chaîne (le login mock donne à tous la même identité : voir l'ADR 0006)
        for (String nom : new String[] {"alice", "bob@example.org", "mallory"}) {
            int statut = mvc.perform(get("/api/projets").with(user(nom))).andReturn().getResponse().getStatus();
            assertThat(statut).as(nom).isEqualTo(nom.equals("mallory") ? 403 : 200);
        }
    }
}
