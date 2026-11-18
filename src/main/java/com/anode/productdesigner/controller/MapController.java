package com.anode.productdesigner.controller;

import lombok.RequiredArgsConstructor;
import com.anode.productdesigner.repository.MapRepository.Liste;
import com.anode.productdesigner.service.MapService;
import com.anode.productdesigner.service.MapService.Carte;
import com.anode.productdesigner.service.MapService.QuestionEditee;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** La Map d'un Projet : lecture et édition manuelle (E-3, E-5), voir {@link MapService}. */
@RestController
@RequiredArgsConstructor
class MapController {

    record EnTete(String destination, String notes) {}

    record Texte(String texte) {}

    record Promotion(String type) {}

    private final MapService maps;

    @GetMapping("/api/projets/{projetId}/map")
    Carte carte(@PathVariable long projetId) {
        return maps.carte(projetId);
    }

    @PutMapping("/api/projets/{projetId}/map")
    Carte modifierEnTete(@PathVariable long projetId, @RequestBody EnTete e) {
        return maps.modifierEnTete(projetId, e.destination(), e.notes());
    }

    @PostMapping("/api/projets/{projetId}/questions")
    @ResponseStatus(HttpStatus.CREATED)
    Carte creerQuestion(@PathVariable long projetId, @RequestBody QuestionEditee q) {
        return maps.creerQuestion(projetId, q);
    }

    @PutMapping("/api/projets/{projetId}/questions/{id}")
    Carte modifierQuestion(@PathVariable long projetId, @PathVariable long id, @RequestBody QuestionEditee q) {
        return maps.modifierQuestion(projetId, id, q);
    }

    @PostMapping("/api/projets/{projetId}/questions/{id}/hors-perimetre")
    Carte horsPerimetre(@PathVariable long projetId, @PathVariable long id) {
        return maps.horsPerimetre(projetId, id);
    }

    @PostMapping("/api/projets/{projetId}/{liste:brouillard|hors-perimetre}")
    @ResponseStatus(HttpStatus.CREATED)
    Carte ajouterLigne(@PathVariable long projetId, @PathVariable String liste, @RequestBody Texte t) {
        return maps.ajouterLigne(projetId, liste(liste), t.texte());
    }

    @PutMapping("/api/projets/{projetId}/{liste:brouillard|hors-perimetre}/{id}")
    Carte modifierLigne(@PathVariable long projetId, @PathVariable String liste, @PathVariable long id, @RequestBody Texte t) {
        return maps.modifierLigne(projetId, liste(liste), id, t.texte());
    }

    @DeleteMapping("/api/projets/{projetId}/{liste:brouillard|hors-perimetre}/{id}")
    Carte supprimerLigne(@PathVariable long projetId, @PathVariable String liste, @PathVariable long id) {
        return maps.supprimerLigne(projetId, liste(liste), id);
    }

    @PostMapping("/api/projets/{projetId}/brouillard/{id}/promouvoir")
    Carte promouvoir(@PathVariable long projetId, @PathVariable long id, @RequestBody Promotion p) {
        return maps.promouvoir(projetId, id, p.type());
    }

    private static Liste liste(String liste) {
        return liste.equals("brouillard") ? Liste.BROUILLARD : Liste.HORS_PERIMETRE;
    }
}
