package com.anode.productdesigner.controller;

import lombok.extern.slf4j.Slf4j;
import lombok.RequiredArgsConstructor;
import java.util.Map;

import com.anode.productdesigner.service.Llm;
import com.anode.productdesigner.service.RoadmapService;
import com.anode.productdesigner.service.RoadmapService.Roadmap;
import com.anode.productdesigner.service.RoadmapService.TicketEdite;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Roadmap d'une version de Spec (E-23 à E-25), voir {@link RoadmapService}. */
@RestController
@RequiredArgsConstructor
@Slf4j
class RoadmapController {

    record Titre(String titre) {}

    private final RoadmapService roadmaps;

    @GetMapping("/api/specs/{specId}/roadmap")
    Roadmap roadmap(@PathVariable long specId) {
        return roadmaps.roadmap(specId);
    }

    /**
     * Génère la Roadmap d'une version de Spec, en remplaçant l'existante (D-22). SSE : `progression` {caracteres},
     * puis `fin` {avertissements} ou `erreur`.
     */
    @PostMapping("/api/specs/{specId}/roadmap")
    SseEmitter generer(@PathVariable long specId) {
        roadmaps.verifierGeneration(specId);
        SseEmitter emitter = new SseEmitter(0L);
        Thread.ofVirtual().start(() -> {
            try {
                var avertissements = roadmaps.generer(specId, n -> Sse.envoyer(emitter, "progression", Map.of("caracteres", n)));
                Sse.envoyer(emitter, "fin", Map.of("avertissements", avertissements));
                emitter.complete();
            } catch (Exception e) {
                log.warn("Échec de la génération de Roadmap de la Spec {}", specId, e);
                Sse.erreur(emitter, e, Map.of("message", Llm.messageErreur(e)));
            }
        });
        return emitter;
    }

    @PutMapping("/api/jalons/{id}")
    Roadmap renommerJalon(@PathVariable long id, @RequestBody Titre t) {
        return roadmaps.renommerJalon(id, t.titre());
    }

    @PostMapping("/api/jalons/{id}/tickets")
    Roadmap ajouterTicket(@PathVariable long id, @RequestBody TicketEdite t) {
        return roadmaps.ajouterTicket(id, t);
    }

    @PutMapping("/api/tickets/{id}")
    Roadmap modifierTicket(@PathVariable long id, @RequestBody TicketEdite t) {
        return roadmaps.modifierTicket(id, t);
    }

    @DeleteMapping("/api/tickets/{id}")
    Roadmap supprimerTicket(@PathVariable long id) {
        return roadmaps.supprimerTicket(id);
    }
}
