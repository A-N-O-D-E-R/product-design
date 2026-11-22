package com.anode.productdesigner.repository;

import java.time.OffsetDateTime;
import lombok.RequiredArgsConstructor;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class DecisionRepository {

    public record Decision(long id, int numero, String texte, long sessionId, OffsetDateTime confirmedAt) {}

    private final JdbcClient jdbc;

    /** Sources « messageId:indice » des Décisions confirmées de la Session. */
    public Set<String> sources(long sessionId) {
        return Set.copyOf(jdbc.sql("SELECT message_id || ':' || indice FROM decision WHERE session_id = ?")
                .param(sessionId).query(String.class).list());
    }

    public List<Decision> parProjet(long projetId) {
        return jdbc.sql("SELECT id, numero, texte, session_id, confirmed_at FROM decision WHERE projet_id = ? ORDER BY numero")
                .param(projetId).query(Decision.class).list();
    }

    public int compter(long sessionId) {
        return jdbc.sql("SELECT count(*) FROM decision WHERE session_id = ?").param(sessionId).query(Integer.class).single();
    }

    /** Numéro D-n suivant du Projet ; vide si la proposition est déjà confirmée. */
    public Optional<Decision> inserer(long projetId, String texte, long sessionId, long messageId, int indice) {
        return jdbc.sql("""
                        INSERT INTO decision (projet_id, numero, texte, session_id, message_id, indice)
                        VALUES (?, (SELECT coalesce(max(numero), 0) + 1 FROM decision WHERE projet_id = ?), ?, ?, ?, ?)
                        ON CONFLICT (message_id, indice) DO NOTHING
                        RETURNING id, numero, texte, session_id, confirmed_at""")
                .params(projetId, projetId, texte, sessionId, messageId, indice)
                .query(Decision.class).optional();
    }
}
