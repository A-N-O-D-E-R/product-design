package com.anode.product.designer.controller;

import lombok.RequiredArgsConstructor;
import java.util.List;

import com.anode.product.designer.repository.ProjetRepository.Projet;
import com.anode.product.designer.service.ImportIdee;
import com.anode.product.designer.service.ImportIdee.Importe;
import com.anode.product.designer.service.ProjetService;
import com.anode.product.designer.service.ProjetService.Cree;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/projets")
@RequiredArgsConstructor
class ProjetController {

    record NouveauProjet(String nom, String description) {}

    private final ProjetService projets;
    private final ImportIdee importIdee;

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

    /**
     * Importe l'idea.json d'idea-generator (corps JSON) : crée le Projet et amorce sa Map. {@code source} : l'URL de l'idée
     * source, pour la traçabilité (à défaut, la première URL de la lignée). 409 si cette source est déjà importée.
     */
    @PostMapping(path = "/import", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    Importe importer(@RequestBody String ideaJson, @RequestParam(required = false) String source) {
        return importIdee.importer(ideaJson, source);
    }

    /** Même chose avec le fichier idea.json envoyé en multipart (champ {@code file}). */
    @PostMapping(path = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    Importe importerFichier(@RequestParam("file") MultipartFile fichier, @RequestParam(required = false) String source) throws IOException {
        return importIdee.importer(new String(fichier.getBytes(), StandardCharsets.UTF_8), source);
    }
}
