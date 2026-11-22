package com.anode.productdesigner.controller;

import lombok.RequiredArgsConstructor;
import com.anode.productdesigner.service.Llm;

import java.util.HashMap;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
class StatusController {

    private final Llm llm;
    private final ClaudeToken token;

    /** token (claude-code seulement) : état du token saisi dans l'interface ; sans token, la connexion locale du CLI. */
    @GetMapping("/api/status")
    Map<String, Object> status() {
        Map<String, Object> status = new HashMap<>(llm.configure()
                ? Map.of("configure", true, "fournisseur", llm.fournisseur(), "model", llm.nomModele(), "rechercheWeb", llm.rechercheWeb())
                : Map.of("configure", false, "fournisseur", llm.fournisseur(), "message", Llm.CONFIG_ABSENTE));
        if (llm.fournisseur().equals(Llm.CLAUDE_CODE)) {
            status.put("token", token.etat());
        }
        return status;
    }
}
