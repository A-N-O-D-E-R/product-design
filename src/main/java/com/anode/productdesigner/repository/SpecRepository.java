package com.anode.productdesigner.repository;

import lombok.RequiredArgsConstructor;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class SpecRepository {

    /** confluenceVersion : version de la page Confluence publiée depuis cette Spec (null sinon). */
    public record Spec(long id, int version, String contenu, String createdAt, Integer confluenceVersion, String confluenceEspace) {}

    public record Version(long id, int version, String createdAt) {}

    public record Creee(long id, int version) {}

    /** Spec et cible Confluence de son Projet ; espace et pageId null avant la première publication. */
    public record PourPublication(long projetId, int version, String contenu, String nom, String espace, String pageId) {}

    private final JdbcClient jdbc;

    public List<Version> versions(long projetId) {
        return jdbc.sql("SELECT id, version, created_at FROM spec WHERE projet_id = ? ORDER BY version DESC")
                .param(projetId).query(Version.class).list();
    }

    public Optional<Spec> trouver(long id) {
        return jdbc.sql("""
                        SELECT s.id, s.version, s.contenu, s.created_at, s.confluence_version, p.confluence_espace
                        FROM spec s JOIN projet p ON p.id = s.projet_id WHERE s.id = ?""").param(id).query(Spec.class).optional();
    }

    public Optional<String> contenu(long id) {
        return jdbc.sql("SELECT contenu FROM spec WHERE id = ?").param(id).query(String.class).optional();
    }

    public Optional<Long> projetId(long id) {
        return jdbc.sql("SELECT projet_id FROM spec WHERE id = ?").param(id).query(Long.class).optional();
    }

    /** Nouvelle version, numérotée à la suite de celles du Projet. */
    public Creee creer(long projetId, String contenu) {
        return jdbc.sql("""
                        INSERT INTO spec (projet_id, version, contenu)
                        VALUES (?, (SELECT coalesce(max(version), 0) + 1 FROM spec WHERE projet_id = ?), ?)
                        RETURNING id, version""")
                .params(projetId, projetId, contenu).query(Creee.class).single();
    }

    public Optional<PourPublication> pourPublication(long id) {
        return jdbc.sql("""
                        SELECT s.projet_id, s.version, s.contenu, p.nom, p.confluence_espace AS espace, p.confluence_page_id AS page_id
                        FROM spec s JOIN projet p ON p.id = s.projet_id WHERE s.id = ?""")
                .param(id).query(PourPublication.class).optional();
    }

    public void memoriserConfluence(long id, int version) {
        jdbc.sql("UPDATE spec SET confluence_version = ? WHERE id = ?").params(version, id).update();
    }
}
