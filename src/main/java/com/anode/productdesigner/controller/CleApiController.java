package com.anode.productdesigner.controller;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import com.anode.productdesigner.service.CleApi;
import com.anode.productdesigner.service.CleApi.Cle;
import com.anode.productdesigner.service.CleApi.Creee;

import lombok.RequiredArgsConstructor;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Clés d'API pour les autres applications (`Authorization: Bearer pd_...`). Gérées depuis une session de navigateur seulement. */
@RestController
@RequestMapping("/api/cles-api")
@RequiredArgsConstructor
class CleApiController {

    record NouvelleCle(String nom, Integer jours) {}

    private final CleApi cles;

    /** La clé n'est dans cette réponse que cette fois : elle ne peut pas être relue. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    Creee creer(Authentication auth, @RequestBody NouvelleCle nouvelle) {
        seulementSession(auth);
        String nom = nouvelle == null || nouvelle.nom() == null ? "" : nouvelle.nom().strip();
        if (nom.isEmpty() || nom.length() > 80) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Donner à la clé un nom de 1 à 80 caractères.");
        }
        long jours = nouvelle.jours() == null ? CleApi.DUREE_DEFAUT.toDays() : nouvelle.jours();
        if (jours < 1 || jours > 365) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Une clé dure de 1 à 365 jours.");
        }
        return cles.creer(auth.getName(), nom, Duration.ofDays(jours));
    }

    @GetMapping
    Map<String, List<Cle>> lister(Authentication auth) {
        seulementSession(auth);
        return Map.of("cles", cles.lister());
    }

    @DeleteMapping("/{id}")
    Map<String, Boolean> revoquer(Authentication auth, @PathVariable String id) {
        seulementSession(auth);
        if (!cles.revoquer(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Aucune clé active de cet identifiant.");
        }
        return Map.of("revoquee", true);
    }

    private static void seulementSession(Authentication auth) {
        if (auth.getAuthorities().stream().anyMatch(g -> CleApi.AUTORITE.equals(g.getAuthority()))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Les clés d'API se gèrent depuis une session de navigateur, pas avec une clé.");
        }
    }
}
