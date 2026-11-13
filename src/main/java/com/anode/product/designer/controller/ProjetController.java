package com.anode.product.designer.controller;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/projets")
class ProjetController {

    record Projet(long id, String nom, String description, String createdAt) {}

    record Cree(long id, String nom, String description, String createdAt, long sessionTraceId) {}

    record NouveauProjet(String nom, String description) {}

    private final JdbcClient jdbc;

    ProjetController(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping
    List<Projet> lister() {
        return jdbc.sql("SELECT id, nom, description, created_at FROM projet ORDER BY id DESC")
                .query(Projet.class).list();
    }

    @GetMapping("/{id}")
    Projet detail(@PathVariable long id) {
        return jdbc.sql("SELECT id, nom, description, created_at FROM projet WHERE id = ?").param(id)
                .query(Projet.class).optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Projet introuvable."));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    Cree creer(@RequestBody NouveauProjet nouveau) {
        if (nouveau.nom() == null || nouveau.nom().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Le nom du Projet est obligatoire.");
        }
        String description = nouveau.description() == null ? "" : nouveau.description().strip();
        Projet projet = jdbc.sql("INSERT INTO projet (nom, description) VALUES (?, ?)"
                        + " RETURNING id, nom, description, created_at")
                .params(nouveau.nom().strip(), description)
                .query(Projet.class).single();
        jdbc.sql("INSERT INTO map (projet_id) VALUES (?)").param(projet.id()).update();
        // La 1re Session trace la Map (D-6).
        long session = jdbc.sql("INSERT INTO session (projet_id, methode) VALUES (?, 'wayfinder') RETURNING id")
                .param(projet.id()).query(Long.class).single();
        return new Cree(projet.id(), projet.nom(), projet.description(), projet.createdAt(), session);
    }
}
