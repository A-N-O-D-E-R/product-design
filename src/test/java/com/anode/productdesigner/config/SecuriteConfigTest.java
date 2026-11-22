package com.anode.productdesigner.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Set;

import com.anode.productdesigner.service.CleApi;

import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;

class SecuriteConfigTest {

    @Test
    void leLoginMockExigeUneAdresseLocale() {
        var s = new Securite();
        assertThat(s.gardeSecurite(true, false, "127.0.0.1", false)).isNotNull();
        assertThat(s.gardeSecurite(true, false, "::1", false)).isNotNull();
        assertThatThrownBy(() -> s.gardeSecurite(true, false, "0.0.0.0", false)).isInstanceOf(IllegalStateException.class).hasMessageContaining("sans mot de passe");
        assertThatThrownBy(() -> s.gardeSecurite(true, false, "", false)).isInstanceOf(IllegalStateException.class);
        // dans un conteneur dont le port n'est publié qu'en local, l'opérateur le déclare
        assertThat(s.gardeSecurite(true, false, "0.0.0.0", true)).isNotNull();
        // OIDC, lui, peut servir le réseau
        assertThat(s.gardeSecurite(false, true, "0.0.0.0", false)).isNotNull();
    }

    @Test
    void sansAucunLoginLeDemarrageEchoue() {
        assertThatThrownBy(() -> new Securite().gardeSecurite(false, false, "127.0.0.1", false))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Aucun login configuré");
    }

    @Test
    void laListeDesUtilisateursAutorises() {
        var alice = new TestingAuthenticationToken("alice", "x", "ROLE_USER");
        assertThat(Securite.autorise(alice, Set.of())).as("liste vide : tout compte connecté").isTrue();
        assertThat(Securite.autorise(alice, Set.of("alice", "bob"))).isTrue();
        assertThat(Securite.autorise(new TestingAuthenticationToken("mallory", "x", "ROLE_USER"), Set.of("alice"))).isFalse();
        // une clé d'API a été créée par un utilisateur autorisé
        assertThat(Securite.autorise(new TestingAuthenticationToken("api-key:1", "x", CleApi.AUTORITE), Set.of("alice"))).isTrue();
    }
}
