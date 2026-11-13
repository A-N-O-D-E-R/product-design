package com.anode.product.designer.controller;

import lombok.RequiredArgsConstructor;
import com.anode.product.designer.service.Llm;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
class StatusController {

    private final Llm llm;

    @GetMapping("/api/status")
    Map<String, Object> status() {
        return llm.configure()
                ? Map.of("configure", true, "fournisseur", llm.fournisseur(), "model", llm.nomModele(), "rechercheWeb", llm.rechercheWeb())
                : Map.of("configure", false, "fournisseur", llm.fournisseur(), "message", Llm.CONFIG_ABSENTE);
    }
}
