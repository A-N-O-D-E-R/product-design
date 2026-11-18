package com.anode.productdesigner.service;

import lombok.RequiredArgsConstructor;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.anode.productdesigner.repository.DecisionRepository;
import com.anode.productdesigner.repository.DecisionRepository.Decision;
import com.anode.productdesigner.repository.SessionRepository;
import com.anode.productdesigner.repository.SessionRepository.MessageLigne;
import com.anode.productdesigner.repository.SessionRepository.Session;

import tools.jackson.databind.JsonNode;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Décisions proposées par record (dérivées des messages, jamais stockées) et Décisions confirmées du Projet. */
@Service
@RequiredArgsConstructor
public class DecisionService {

    public record Proposee(long messageId, int indice, String texte) {}

    public record Panneau(List<Proposee> proposees, List<Decision> decisions) {}

    private final DecisionRepository decisions;
    private final SessionRepository sessions;

    public Panneau panneau(long sessionId) {
        Session session = session(sessionId);
        Set<String> confirmees = decisions.sources(sessionId);
        List<Proposee> proposees = new ArrayList<>();
        for (MessageLigne m : sessions.messagesAssistant(sessionId)) {
            List<String> textes = proposees(m.contenu());
            for (int i = 0; i < textes.size(); i++) {
                if (!confirmees.contains(m.id() + ":" + i)) {
                    proposees.add(new Proposee(m.id(), i, textes.get(i)));
                }
            }
        }
        return new Panneau(proposees, confirmees(session.projetId()));
    }

    public List<Decision> confirmees(long projetId) {
        return decisions.parProjet(projetId);
    }

    /** Persiste une Décision proposée, éventuellement modifiée, avec le numéro D-n suivant du Projet. */
    @Transactional
    public Decision confirmer(long sessionId, long messageId, int indice, String texte) {
        Session session = session(sessionId);
        if (!"active".equals(session.statut())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Session close : impossible de confirmer une Décision.");
        }
        String contenu = sessions.contenuAssistant(messageId, sessionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Message inconnu dans cette Session."));
        if (indice < 0 || indice >= proposees(contenu).size()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Proposition inconnue.");
        }
        if (texte == null || texte.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Le texte de la Décision est obligatoire.");
        }
        return decisions.inserer(session.projetId(), texte.strip(), sessionId, messageId, indice)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "Décision déjà confirmée."));
    }

    private Session session(long id) {
        return sessions.trouver(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Session introuvable."));
    }

    /** Textes des décisions de l'appel record d'un message assistant, dans l'ordre. */
    public static List<String> proposees(String contenu) {
        List<String> textes = new ArrayList<>();
        for (JsonNode bloc : Conversation.contenu(contenu)) {
            if ("tool_use".equals(bloc.path("type").asString()) && Record.NOM.equals(bloc.path("name").asString())) {
                for (JsonNode d : bloc.path("input").path("decisions")) {
                    textes.add(d.path("texte").asString());
                }
            }
        }
        return textes;
    }
}
