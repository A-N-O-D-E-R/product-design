package com.anode.productdesigner.controller;

import lombok.RequiredArgsConstructor;
import java.util.List;

import com.anode.productdesigner.repository.SessionRepository.Artefact;
import com.anode.productdesigner.repository.SessionRepository.Session;
import com.anode.productdesigner.service.SessionService;
import com.anode.productdesigner.service.SessionService.Contexte;
import com.anode.productdesigner.service.SessionService.SessionDetail;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Sessions de travail avec le LLM, voir {@link SessionService}. */
@RestController
@RequiredArgsConstructor
class SessionController {

    record NouvelleSession(String methode) {}

    record NouveauMessage(String texte) {}

    private final SessionService sessions;

    @GetMapping("/api/projets/{projetId}/sessions")
    List<Session> lister(@PathVariable long projetId) {
        return sessions.lister(projetId);
    }

    @PostMapping("/api/projets/{projetId}/sessions")
    @ResponseStatus(HttpStatus.CREATED)
    Session creer(@PathVariable long projetId, @RequestBody NouvelleSession nouvelle) {
        return sessions.creer(projetId, nouvelle.methode());
    }

    @PostMapping("/api/projets/{projetId}/questions/{questionId}/sessions")
    @ResponseStatus(HttpStatus.CREATED)
    Session ouvrir(@PathVariable long projetId, @PathVariable long questionId) {
        return sessions.ouvrir(projetId, questionId);
    }

    @GetMapping("/api/sessions/{id}")
    SessionDetail detail(@PathVariable long id) {
        return sessions.detail(id);
    }

    @GetMapping("/api/sessions/{id}/artefacts")
    List<Artefact> artefacts(@PathVariable long id) {
        return sessions.artefacts(id);
    }

    @GetMapping("/api/sessions/{id}/contexte")
    Contexte remplissage(@PathVariable long id) {
        return sessions.remplissage(id);
    }

    @PostMapping("/api/sessions/{id}/relancer")
    Session relancer(@PathVariable long id) {
        return sessions.relancer(id);
    }

    @PostMapping("/api/sessions/{id}/continuer")
    @ResponseStatus(HttpStatus.CREATED)
    Session continuer(@PathVariable long id) {
        return sessions.continuer(id);
    }

    @PostMapping("/api/sessions/{id}/clore")
    Session clore(@PathVariable long id) {
        return sessions.clore(id);
    }

    /** Ajoute le message de l'utilisateur et streame la réponse en SSE : `delta` (texte), puis `fin` ou `erreur`. */
    @PostMapping("/api/sessions/{id}/messages")
    SseEmitter envoyer(@PathVariable long id, @RequestBody NouveauMessage nouveau) {
        return repondre(sessions.envoyer(id, nouveau.texte()));
    }

    /** Relance la réponse au dernier message de l'utilisateur, après une coupure ou une erreur (D-30). */
    @PostMapping("/api/sessions/{id}/reessayer")
    SseEmitter reessayer(@PathVariable long id) {
        return repondre(sessions.reessayer(id));
    }

    private SseEmitter repondre(Session session) {
        SseEmitter emitter = new SseEmitter(0L);
        try {
            Thread.ofVirtual().start(() -> sessions.repondre(session, Sse.flux(emitter)));
        } catch (RuntimeException e) {
            sessions.abandonner(session.id());
            throw e;
        }
        return emitter;
    }
}
