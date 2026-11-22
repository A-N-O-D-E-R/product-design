package com.anode.productdesigner.controller;

import lombok.RequiredArgsConstructor;
import com.anode.productdesigner.repository.DecisionRepository.Decision;
import com.anode.productdesigner.service.DecisionService;
import com.anode.productdesigner.service.DecisionService.Panneau;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Décisions proposées et confirmées d'une Session, voir {@link DecisionService}. */
@RestController
@RequiredArgsConstructor
class DecisionController {

    record Confirmation(long messageId, int indice, String texte) {}

    private final DecisionService decisions;

    @GetMapping("/api/sessions/{sessionId}/decisions")
    Panneau panneau(@PathVariable long sessionId) {
        return decisions.panneau(sessionId);
    }

    @PostMapping("/api/sessions/{sessionId}/decisions")
    @ResponseStatus(HttpStatus.CREATED)
    Decision confirmer(@PathVariable long sessionId, @RequestBody Confirmation c) {
        return decisions.confirmer(sessionId, c.messageId(), c.indice(), c.texte());
    }
}
