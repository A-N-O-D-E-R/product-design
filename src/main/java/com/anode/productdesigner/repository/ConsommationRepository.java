package com.anode.productdesigner.repository;

import lombok.RequiredArgsConstructor;
import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Usage en tokens : porté par chaque message de Session, et par les appels hors Session (Spec, Roadmap). */
@Repository
@RequiredArgsConstructor
public class ConsommationRepository {

    public record Ligne(String modele, long input, long output, long cacheRead, long cacheWrite) {}

    private static final String USAGE_MESSAGES = """
            SELECT m.modele, sum(m.input_tokens) AS input, sum(m.output_tokens) AS output,
                   sum(coalesce(m.cache_read_tokens, 0)) AS cache_read, sum(coalesce(m.cache_write_tokens, 0)) AS cache_write
            FROM message m JOIN session s ON s.id = m.session_id
            WHERE m.modele IS NOT NULL AND\s""";

    private final JdbcClient jdbc;

    public List<Ligne> parSession(long sessionId) {
        return jdbc.sql(USAGE_MESSAGES + "m.session_id = ? GROUP BY m.modele").param(sessionId).query(Ligne.class).list();
    }

    /** Messages des Sessions du Projet. */
    public List<Ligne> messagesDuProjet(long projetId) {
        return jdbc.sql(USAGE_MESSAGES + "s.projet_id = ? GROUP BY m.modele").param(projetId).query(Ligne.class).list();
    }

    /** Appels hors Session du Projet. */
    public List<Ligne> appelsDuProjet(long projetId) {
        return jdbc.sql("""
                        SELECT modele, sum(input_tokens) AS input, sum(output_tokens) AS output,
                               sum(cache_read_tokens) AS cache_read, sum(cache_write_tokens) AS cache_write
                        FROM appel WHERE projet_id = ? GROUP BY modele""").param(projetId).query(Ligne.class).list();
    }

    public void enregistrerAppel(long projetId, String objet, String modele, long input, long output, long cacheRead, long cacheWrite) {
        jdbc.sql("""
                        INSERT INTO appel (projet_id, objet, modele, input_tokens, output_tokens, cache_read_tokens, cache_write_tokens)
                        VALUES (?, ?, ?, ?, ?, ?, ?)""")
                .params(projetId, objet, modele, input, output, cacheRead, cacheWrite).update();
    }
}
