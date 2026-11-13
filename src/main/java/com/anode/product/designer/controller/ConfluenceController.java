package com.anode.product.designer.controller;

import lombok.RequiredArgsConstructor;
import com.anode.product.designer.service.ConfluenceService;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Publication d'une version de Spec dans Confluence Cloud, voir {@link ConfluenceService}. */
@RestController
@RequiredArgsConstructor
class ConfluenceController {

    record Demande(String espace) {}

    private final ConfluenceService confluence;

    @PostMapping("/api/specs/{specId}/export-confluence")
    ConfluenceService.Publication publier(@PathVariable long specId, @RequestBody Demande demande) {
        return confluence.publier(specId, demande.espace());
    }
}
