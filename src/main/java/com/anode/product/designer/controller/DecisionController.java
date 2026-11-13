package com.anode.product.designer.controller;

import com.anode.product.designer.service.Record;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import tools.jackson.databind.JsonNode;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Décisions proposées par record (dérivées des messages, jamais stockées) et Décisions confirmées du Projet. */
@RestController
class DecisionController {

    record Proposee(long messageId, int indice, String texte) {}

    record Decision(long id, int numero, String texte, long sessionId, String confirmedAt) {}

    record Panneau(List<Proposee> proposees, List<Decision> decisions) {}

    record Confirmation(long messageId, int indice, String texte) {}

    private record Ligne(long id, String contenu) {}

    private final JdbcClient jdbc;

    DecisionController(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/api/sessions/{sessionId}/decisions")
    Panneau panneau(@PathVariable long sessionId) {
        long projetId = projetId(sessionId);
        Set<String> confirmees = new HashSet<>(jdbc.sql(
                        "SELECT message_id || ':' || indice FROM decision WHERE session_id = ?")
                .param(sessionId).query(String.class).list());
        List<Proposee> proposees = new ArrayList<>();
        for (Ligne l : jdbc.sql("SELECT id, contenu FROM message WHERE session_id = ? AND role = 'assistant' ORDER BY id")
                .param(sessionId).query(Ligne.class).list()) {
            List<String> textes = proposees(l.contenu());
            for (int i = 0; i < textes.size(); i++) {
                if (!confirmees.contains(l.id() + ":" + i)) {
                    proposees.add(new Proposee(l.id(), i, textes.get(i)));
                }
            }
        }
        return new Panneau(proposees, confirmees(projetId));
    }

    List<Decision> confirmees(long projetId) {
        return jdbc.sql("SELECT id, numero, texte, session_id, confirmed_at FROM decision WHERE projet_id = ? ORDER BY numero")
                .param(projetId).query(Decision.class).list();
    }

    /** Persiste une Décision proposée, éventuellement modifiée, avec le numéro D-n suivant du Projet. */
    @PostMapping("/api/sessions/{sessionId}/decisions")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    Decision confirmer(@PathVariable long sessionId, @RequestBody Confirmation c) {
        long projetId = projetId(sessionId);
        if (!"active".equals(jdbc.sql("SELECT statut FROM session WHERE id = ?").param(sessionId).query(String.class).single())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Session close : impossible de confirmer une Décision.");
        }
        String contenu = jdbc.sql("SELECT contenu FROM message WHERE id = ? AND session_id = ? AND role = 'assistant'")
                .params(c.messageId(), sessionId).query(String.class).optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Message inconnu dans cette Session."));
        if (c.indice() < 0 || c.indice() >= proposees(contenu).size()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Proposition inconnue.");
        }
        if (c.texte() == null || c.texte().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Le texte de la Décision est obligatoire.");
        }
        return jdbc.sql("""
                        INSERT INTO decision (projet_id, numero, texte, session_id, message_id, indice)
                        VALUES (?, (SELECT coalesce(max(numero), 0) + 1 FROM decision WHERE projet_id = ?), ?, ?, ?, ?)
                        ON CONFLICT (message_id, indice) DO NOTHING
                        RETURNING id, numero, texte, session_id, confirmed_at""")
                .params(projetId, projetId, c.texte().strip(), sessionId, c.messageId(), c.indice())
                .query(Decision.class).optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "Décision déjà confirmée."));
    }

    private long projetId(long sessionId) {
        return jdbc.sql("SELECT projet_id FROM session WHERE id = ?").param(sessionId).query(Long.class).optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Session introuvable."));
    }

    /** Textes des décisions de l'appel record d'un message assistant, dans l'ordre. */
    static List<String> proposees(String contenu) {
        List<String> textes = new ArrayList<>();
        for (JsonNode bloc : com.anode.product.designer.service.Conversation.contenu(contenu)) {
            if ("tool_use".equals(bloc.path("type").asString()) && Record.NOM.equals(bloc.path("name").asString())) {
                for (JsonNode d : bloc.path("input").path("decisions")) {
                    textes.add(d.path("texte").asString());
                }
            }
        }
        return textes;
    }
}
