package com.anode.productdesigner.repository;

import lombok.RequiredArgsConstructor;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class ProjetRepository {

    public record Projet(long id, String nom, String description, String createdAt, String sourceIdeaUrl) {}

    private static final String COLONNES = "id, nom, description, created_at, source_idea_url";

    private final JdbcClient jdbc;

    public List<Projet> lister() {
        return jdbc.sql("SELECT " + COLONNES + " FROM projet ORDER BY id DESC").query(Projet.class).list();
    }

    public Optional<Projet> trouver(long id) {
        return jdbc.sql("SELECT " + COLONNES + " FROM projet WHERE id = ?").param(id).query(Projet.class).optional();
    }

    public Projet creer(String nom, String description) {
        return jdbc.sql("INSERT INTO projet (nom, description) VALUES (?, ?) RETURNING " + COLONNES)
                .params(nom, description).query(Projet.class).single();
    }

    /** Un Projet importé d'une idée : son URL source et sa provenance (JSON), pour la traçabilité jusqu'à la PR. */
    public Projet creerImporte(String nom, String description, String sourceIdeaUrl, String sourceIdea) {
        return jdbc.sql("INSERT INTO projet (nom, description, source_idea_url, source_idea) VALUES (?, ?, ?, ?) RETURNING " + COLONNES)
                .params(nom, description, sourceIdeaUrl, sourceIdea).query(Projet.class).single();
    }

    public Optional<Projet> parSource(String sourceIdeaUrl) {
        return jdbc.sql("SELECT " + COLONNES + " FROM projet WHERE source_idea_url = ? ORDER BY id LIMIT 1")
                .param(sourceIdeaUrl).query(Projet.class).optional();
    }

    /** Mémorisée après une publication réussie seulement. */
    public void memoriserConfluence(long projetId, String espace, String pageId) {
        jdbc.sql("UPDATE projet SET confluence_espace = ?, confluence_page_id = ? WHERE id = ?").params(espace, pageId, projetId).update();
    }
}
