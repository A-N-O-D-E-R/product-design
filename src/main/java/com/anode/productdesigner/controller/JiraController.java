package com.anode.productdesigner.controller;

import lombok.RequiredArgsConstructor;
import com.anode.productdesigner.service.Atlassian;
import com.anode.productdesigner.service.JiraService;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Export d'une Roadmap vers Jira Cloud (E-26 à E-30), voir {@link JiraService}. */
@RestController
@RequiredArgsConstructor
class JiraController {

    record Demande(String projet) {}

    private final JiraService jira;

    @GetMapping("/api/jira/status")
    Map<String, Object> status() {
        return jira.configure() ? Map.of("configure", true) : Map.of("configure", false, "message", Atlassian.CONFIG_ABSENTE);
    }

    @PostMapping("/api/specs/{specId}/export-jira")
    JiraService.Resultat exporter(@PathVariable long specId, @RequestBody Demande demande) {
        return jira.exporter(specId, demande.projet());
    }
}
