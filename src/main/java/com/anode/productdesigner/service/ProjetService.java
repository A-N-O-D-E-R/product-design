package com.anode.productdesigner.service;

import java.time.OffsetDateTime;
import lombok.RequiredArgsConstructor;
import java.util.List;

import com.anode.productdesigner.mapper.ProjetMapper;
import com.anode.productdesigner.repository.MapRepository;
import com.anode.productdesigner.repository.ProjetRepository;
import com.anode.productdesigner.repository.ProjetRepository.Projet;
import com.anode.productdesigner.repository.SessionRepository;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class ProjetService {

    public record Cree(long id, String nom, String description, OffsetDateTime createdAt, long sessionTraceId) {}

    private final ProjetRepository projets;
    private final MapRepository maps;
    private final SessionRepository sessions;
    private final ProjetMapper mapper;

    public List<Projet> lister() {
        return projets.lister();
    }

    public Projet detail(long id) {
        return projets.trouver(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Projet introuvable."));
    }

    /** Crée le Projet, sa Map vide et la Session qui trace la Map (D-6). */
    @Transactional
    public Cree creer(String nom, String description) {
        if (nom == null || nom.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Le nom du Projet est obligatoire.");
        }
        Projet projet = projets.creer(nom.strip(), description == null ? "" : description.strip());
        maps.creer(projet.id());
        long session = sessions.creer(projet.id(), "wayfinder", null, null);
        return mapper.versCree(projet, session);
    }
}
