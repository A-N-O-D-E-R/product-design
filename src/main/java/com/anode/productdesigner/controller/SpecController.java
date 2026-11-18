package com.anode.productdesigner.controller;

import lombok.extern.slf4j.Slf4j;
import lombok.RequiredArgsConstructor;
import java.util.List;
import java.util.Map;

import com.anode.productdesigner.repository.SpecRepository.Spec;
import com.anode.productdesigner.repository.SpecRepository.Version;
import com.anode.productdesigner.service.Llm;
import com.anode.productdesigner.service.SpecService;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Versions de Spec d'un Projet, voir {@link SpecService}. */
@RestController
@RequiredArgsConstructor
@Slf4j
class SpecController {

    private final SpecService specs;

    @GetMapping("/api/projets/{projetId}/specs")
    List<Version> versions(@PathVariable long projetId) {
        return specs.versions(projetId);
    }

    @GetMapping("/api/specs/{id}")
    Spec spec(@PathVariable long id) {
        return specs.spec(id);
    }

    /** Génère une nouvelle version, streamée en SSE : `delta`, puis `fin` {id, version, avertissements} ou `erreur`. */
    @PostMapping("/api/projets/{projetId}/specs")
    SseEmitter generer(@PathVariable long projetId) {
        specs.verifierGeneration(projetId);
        SseEmitter emitter = new SseEmitter(0L);
        Thread.ofVirtual().start(() -> {
            try {
                var spec = specs.generer(projetId, t -> Sse.envoyer(emitter, "delta", Map.of("texte", t)));
                Sse.envoyer(emitter, "fin", spec);
                emitter.complete();
            } catch (Exception e) {
                log.warn("Échec de la génération de Spec du Projet {}", projetId, e);
                Sse.erreur(emitter, e, Map.of("message", Llm.messageErreur(e)));
            }
        });
        return emitter;
    }
}
