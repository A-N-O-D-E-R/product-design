package com.anode.product.designer.controller;

import lombok.RequiredArgsConstructor;
import java.util.List;

import com.anode.product.designer.repository.ProjetRepository.Projet;
import com.anode.product.designer.service.ProjetService;
import com.anode.product.designer.service.ProjetService.Cree;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/projets")
@RequiredArgsConstructor
class ProjetController {

    record NouveauProjet(String nom, String description) {}

    private final ProjetService projets;

    @GetMapping
    List<Projet> lister() {
        return projets.lister();
    }

    @GetMapping("/{id}")
    Projet detail(@PathVariable long id) {
        return projets.detail(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    Cree creer(@RequestBody NouveauProjet nouveau) {
        return projets.creer(nouveau.nom(), nouveau.description());
    }
}
