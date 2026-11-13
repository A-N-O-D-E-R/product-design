package com.anode.product.designer.repository;

import lombok.RequiredArgsConstructor;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class ProjetRepository {

    public record Projet(long id, String nom, String description, String createdAt) {}

    private final JdbcClient jdbc;

    public List<Projet> lister() {
        return jdbc.sql("SELECT id, nom, description, created_at FROM projet ORDER BY id DESC").query(Projet.class).list();
    }

    public Optional<Projet> trouver(long id) {
        return jdbc.sql("SELECT id, nom, description, created_at FROM projet WHERE id = ?").param(id).query(Projet.class).optional();
    }

    public Projet creer(String nom, String description) {
        return jdbc.sql("INSERT INTO projet (nom, description) VALUES (?, ?) RETURNING id, nom, description, created_at")
                .params(nom, description).query(Projet.class).single();
    }

    /** Mémorisée après une publication réussie seulement. */
    public void memoriserConfluence(long projetId, String espace, String pageId) {
        jdbc.sql("UPDATE projet SET confluence_espace = ?, confluence_page_id = ? WHERE id = ?").params(espace, pageId, projetId).update();
    }
}
