package com.anode.product.designer.controller;

import lombok.RequiredArgsConstructor;
import com.anode.product.designer.service.ConsommationService;
import com.anode.product.designer.service.ConsommationService.Total;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** Tokens et coût estimé (D-29), voir {@link ConsommationService}. */
@RestController
@RequiredArgsConstructor
class ConsommationController {

    private final ConsommationService consommation;

    @GetMapping("/api/sessions/{id}/consommation")
    Total session(@PathVariable long id) {
        return consommation.session(id);
    }

    @GetMapping("/api/projets/{id}/consommation")
    Total projet(@PathVariable long id) {
        return consommation.projet(id);
    }
}
