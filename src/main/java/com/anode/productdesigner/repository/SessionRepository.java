package com.anode.productdesigner.repository;

import java.time.OffsetDateTime;
import lombok.RequiredArgsConstructor;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Sessions, leurs messages et leurs Artefacts. */
@Repository
@RequiredArgsConstructor
public class SessionRepository {

    /** questionId et question (titre) : null pour la Session qui trace la Map. tache, progression : null hors Sessions research (D-26). */
    public record Session(long id, long projetId, String methode, String statut, OffsetDateTime createdAt, Long questionId, String question,
                          Long continuedFrom, String tache, String progression) {}

    public record Artefact(long id, String type, String contenu, OffsetDateTime createdAt) {}

    public record MessageLigne(long id, String role, String contenu, boolean visible, boolean incomplet) {}

    private static final String SESSION = """
            SELECT s.id, s.projet_id, s.methode, s.statut, s.created_at, s.question_id, q.titre AS question, s.continued_from,
                   s.tache, s.progression
            FROM session s LEFT JOIN question q ON q.id = s.question_id\s""";

    private final JdbcClient jdbc;

    // --- Sessions ---

    public List<Session> parProjet(long projetId) {
        return jdbc.sql(SESSION + "WHERE s.projet_id = ? ORDER BY s.id DESC").param(projetId).query(Session.class).list();
    }

    public Optional<Session> trouver(long id) {
        return jdbc.sql(SESSION + "WHERE s.id = ?").param(id).query(Session.class).optional();
    }

    public long creer(long projetId, String methode, Long questionId, Long continuedFrom) {
        return jdbc.sql("INSERT INTO session (projet_id, methode, question_id, continued_from) VALUES (?, ?, ?, ?) RETURNING id")
                .params(projetId, methode, questionId, continuedFrom).query(Long.class).single();
    }

    public void clore(long id) {
        jdbc.sql("UPDATE session SET statut = 'close' WHERE id = ?").param(id).update();
    }

    // --- Research en tâche de fond (D-26) ---

    public void demarrerResearch(long id, String progression) {
        jdbc.sql("UPDATE session SET tache = 'en_cours', progression = ? WHERE id = ?").params(progression, id).update();
    }

    public void progression(long id, String texte) {
        jdbc.sql("UPDATE session SET progression = ? WHERE id = ?").params(texte, id).update();
    }

    public void terminerResearch(long id, String progression) {
        jdbc.sql("UPDATE session SET tache = 'terminee', progression = ? WHERE id = ?").params(progression, id).update();
    }

    /** Sans effet si la research est déjà terminée ou en échec. */
    public void echecResearch(long id, String message) {
        jdbc.sql("UPDATE session SET tache = 'echec', progression = ? WHERE id = ? AND tache = 'en_cours'").params(message, id).update();
    }

    /** Toutes les researches encore en cours passent en échec ; renvoie leur nombre. */
    public int echecResearchesEnCours(String message) {
        return jdbc.sql("UPDATE session SET tache = 'echec', progression = ? WHERE tache = 'en_cours'").param(message).update();
    }

    // --- Messages ---

    public List<MessageLigne> messages(long sessionId) {
        return jdbc.sql("SELECT id, role, contenu, visible, incomplet FROM message WHERE session_id = ? ORDER BY id")
                .param(sessionId).query(MessageLigne.class).list();
    }

    public List<MessageLigne> messagesAssistant(long sessionId) {
        return jdbc.sql("SELECT id, role, contenu, visible, incomplet FROM message WHERE session_id = ? AND role = 'assistant' ORDER BY id")
                .param(sessionId).query(MessageLigne.class).list();
    }

    public Optional<String> contenuAssistant(long messageId, long sessionId) {
        return jdbc.sql("SELECT contenu FROM message WHERE id = ? AND session_id = ? AND role = 'assistant'")
                .params(messageId, sessionId).query(String.class).optional();
    }

    public long ajouterMessage(long sessionId, String role, String contenu, boolean visible) {
        return jdbc.sql("INSERT INTO message (session_id, role, contenu, visible) VALUES (?, ?, ?, ?) RETURNING id")
                .params(sessionId, role, contenu, visible).query(Long.class).single();
    }

    public void marquerIncomplet(long messageId) {
        jdbc.sql("UPDATE message SET incomplet = true WHERE id = ?").param(messageId).update();
    }

    public void majUsage(long messageId, String modele, long input, long output, long cacheRead, long cacheWrite) {
        jdbc.sql("UPDATE message SET modele = ?, input_tokens = ?, output_tokens = ?, cache_read_tokens = ?, cache_write_tokens = ? WHERE id = ?")
                .params(modele, input, output, cacheRead, cacheWrite, messageId).update();
    }

    /** Taille du prompt du dernier appel (entrée + cache lu + cache écrit) ; 0 s'il n'y en a pas. */
    public long tokensDernierAppel(long sessionId) {
        return jdbc.sql("""
                        SELECT coalesce(input_tokens, 0) + coalesce(cache_read_tokens, 0) + coalesce(cache_write_tokens, 0)
                        FROM message WHERE session_id = ? AND modele IS NOT NULL ORDER BY id DESC LIMIT 1""")
                .param(sessionId).query(Long.class).optional().orElse(0L);
    }

    /** Efface tous les messages sauf le premier ; en cascade, les Décisions qui en viennent. */
    public void supprimerMessagesApresLePremier(long sessionId) {
        jdbc.sql("DELETE FROM message WHERE session_id = ? AND id > (SELECT min(id) FROM message WHERE session_id = ?)")
                .params(sessionId, sessionId).update();
    }

    // --- Artefacts ---

    public List<Artefact> artefacts(long sessionId) {
        return jdbc.sql("SELECT id, type, contenu, created_at FROM artefact WHERE session_id = ? ORDER BY id")
                .param(sessionId).query(Artefact.class).list();
    }

    public void ajouterArtefact(long sessionId, String type, String contenu) {
        jdbc.sql("INSERT INTO artefact (session_id, type, contenu) VALUES (?, ?, ?)").params(sessionId, type, contenu).update();
    }

    public void supprimerArtefacts(long sessionId) {
        jdbc.sql("DELETE FROM artefact WHERE session_id = ?").param(sessionId).update();
    }
}
