package com.anode.productdesigner.controller;

import java.io.IOException;
import java.util.Map;

import com.anode.productdesigner.service.SessionService.Flux;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Aides SSE partagées par les contrôleurs qui streament une génération. */
final class Sse {

    private Sse() {}

    static void envoyer(SseEmitter emitter, String evenement, Object donnees) throws IOException {
        emitter.send(SseEmitter.event().name(evenement).data(donnees));
    }

    /** Événement `erreur` puis fin du flux ; si le client est parti, le flux se termine en erreur. */
    static void erreur(SseEmitter emitter, Exception e, Map<String, Object> donnees) {
        try {
            envoyer(emitter, "erreur", donnees);
            emitter.complete();
        } catch (IOException | IllegalStateException ignored) {
            emitter.completeWithError(e);
        }
    }

    static Flux flux(SseEmitter emitter) {
        return new Flux() {
            @Override
            public void envoyer(String evenement, Object donnees) throws IOException {
                Sse.envoyer(emitter, evenement, donnees);
            }

            @Override
            public void terminer() {
                emitter.complete();
            }

            @Override
            public void echec(Exception e) {
                emitter.completeWithError(e);
            }
        };
    }
}
