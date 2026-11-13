package com.anode.product.designer.service;

import lombok.RequiredArgsConstructor;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.anode.product.designer.mapper.MapMapper;
import com.anode.product.designer.repository.DecisionRepository.Decision;
import com.anode.product.designer.repository.MapRepository;
import com.anode.product.designer.repository.MapRepository.Bloc;
import com.anode.product.designer.repository.MapRepository.EnTete;
import com.anode.product.designer.repository.MapRepository.Liste;
import com.anode.product.designer.repository.MapRepository.Ligne;

import tools.jackson.databind.JsonNode;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** La Map d'un Projet : lecture, édition manuelle, et application des sorties `questions` et `map` de record. */
@Service
@RequiredArgsConstructor
public class MapService {

    /** ouvrable : dans la frontière (E-4). */
    public record Question(long id, int numero, String titre, String corps, String type, String statut, List<Integer> blockedBy, boolean ouvrable) {}

    public record Carte(String destination, String notes, List<Question> questions, List<Ligne> brouillard, List<Ligne> horsPerimetre) {}

    public record QuestionEditee(String titre, String corps, String type, List<Integer> blockedBy) {}

    private final MapRepository maps;
    private final DecisionService decisions;
    private final MapMapper mapper;

    // --- Lecture ---

    public Carte carte(long projetId) {
        EnTete enTete = maps.enTete(projetId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Projet introuvable."));
        Map<Long, List<Integer>> blocages = new HashMap<>();
        for (Bloc b : maps.blocages(projetId)) {
            blocages.computeIfAbsent(b.questionId(), k -> new ArrayList<>()).add(b.numero());
        }
        List<Long> frontiere = frontiere(projetId);
        List<Question> questions = maps.questions(projetId).stream()
                .map(q -> mapper.versQuestion(q, blocages.getOrDefault(q.id(), List.of()), frontiere.contains(q.id())))
                .toList();
        return mapper.versCarte(enTete, questions, maps.lignes(Liste.BROUILLARD, projetId), maps.lignes(Liste.HORS_PERIMETRE, projetId));
    }

    /** Questions ouvrables : `open` dont toutes les bloquantes sont résolues (E-4). */
    public List<Long> frontiere(long projetId) {
        return maps.frontiere(projetId);
    }

    /** Map et Décisions confirmées du Projet, en texte : amorce d'une Session de suite, entrée de la Spec. */
    public String resume(long projetId) {
        Carte carte = carte(projetId);
        StringBuilder sb = new StringBuilder("Map\n");
        for (Question q : carte.questions()) {
            sb.append("- Question ").append(q.numero()).append(" [").append(q.type()).append(", ").append(q.statut()).append("] ")
                    .append(q.titre());
            if (!q.blockedBy().isEmpty()) {
                sb.append(" (bloquée par ").append(q.blockedBy()).append(")");
            }
            sb.append("\n");
        }
        carte.brouillard().forEach(b -> sb.append("- Brouillard : ").append(b.texte()).append("\n"));
        carte.horsPerimetre().forEach(h -> sb.append("- Hors périmètre : ").append(h.texte()).append("\n"));
        sb.append("\nDécisions confirmées\n");
        for (Decision d : decisions.confirmees(projetId)) {
            sb.append("- D-").append(d.numero()).append(" : ").append(d.texte()).append("\n");
        }
        return sb.toString();
    }

    // --- Sortie du LLM ---

    /**
     * Applique un appel record à la Map, sans confirmation (seules les Décisions en demandent, D-17).
     * Une Question dont le titre existe déjà est ignorée ; `blocked_by` se résout par titre, une fois toutes les Questions créées.
     */
    @Transactional
    public void appliquer(long projetId, JsonNode input) {
        JsonNode map = input.path("map");
        String destination = map.path("destination").asString("").strip();
        if (!destination.isEmpty()) {
            maps.majDestination(projetId, destination);
        }
        for (JsonNode texte : map.path("brouillard")) {
            maps.ajouterLigne(Liste.BROUILLARD, projetId, texte.asString().strip());
        }
        for (JsonNode texte : map.path("hors_perimetre")) {
            maps.ajouterLigne(Liste.HORS_PERIMETRE, projetId, texte.asString().strip());
        }
        for (JsonNode q : input.path("questions")) {
            maps.insererQuestionSiNouvelle(projetId, q.path("titre").asString().strip(), q.path("corps").asString(""),
                    q.path("type").asString("grilling"));
        }
        for (JsonNode q : input.path("questions")) {
            Long id = maps.idParTitre(projetId, q.path("titre").asString().strip()).orElse(null);
            for (JsonNode par : q.path("blocked_by")) {
                Long parId = maps.idParTitre(projetId, par.asString().strip()).orElse(null);
                // Dépendance inconnue ou qui fermerait un cycle : ignorée (elle rendrait des Questions inouvrables).
                if (id != null && parId != null && !maps.cycle(id, parId)) {
                    maps.bloquer(id, parId);
                }
            }
        }
    }

    // --- Édition manuelle (E-3, E-5) ---

    public Carte modifierEnTete(long projetId, String destination, String notes) {
        maps.majEnTete(projetId, strip(destination), strip(notes));
        return carte(projetId);
    }

    @Transactional
    public Carte creerQuestion(long projetId, QuestionEditee q) {
        long id = inserer(projetId, obligatoire(q.titre()), strip(q.corps()), type(q.type()));
        bloquer(projetId, id, q.blockedBy());
        return carte(projetId);
    }

    @Transactional
    public Carte modifierQuestion(long projetId, long id, QuestionEditee q) {
        trouverQuestion(projetId, id);
        titreLibre(projetId, obligatoire(q.titre()), id);
        maps.majQuestion(id, obligatoire(q.titre()), strip(q.corps()), type(q.type()));
        maps.supprimerBlocages(id);
        bloquer(projetId, id, q.blockedBy());
        return carte(projetId);
    }

    /** Toute Question peut sortir du périmètre, quel que soit son statut (D-20). */
    public Carte horsPerimetre(long projetId, long id) {
        trouverQuestion(projetId, id);
        maps.majStatut(id, "hors_perimetre");
        return carte(projetId);
    }

    public Carte ajouterLigne(long projetId, Liste liste, String texte) {
        carte(projetId);
        maps.ajouterLigne(liste, projetId, obligatoire(texte));
        return carte(projetId);
    }

    public Carte modifierLigne(long projetId, Liste liste, long id, String texte) {
        trouve(maps.modifierLigne(liste, projetId, id, obligatoire(texte)));
        return carte(projetId);
    }

    public Carte supprimerLigne(long projetId, Liste liste, long id) {
        trouve(maps.supprimerLigne(liste, projetId, id));
        return carte(projetId);
    }

    /** Brouillard devenu net : la ligne disparaît, une Question `open` prend son texte pour titre (D-20). */
    @Transactional
    public Carte promouvoir(long projetId, long id, String type) {
        String texte = maps.texteBrouillard(id, projetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Brouillard introuvable."));
        inserer(projetId, texte, "", type(type));
        maps.supprimerBrouillard(id);
        return carte(projetId);
    }

    // --- Règles ---

    private long inserer(long projetId, String titre, String corps, String type) {
        titreLibre(projetId, titre, -1);
        return maps.insererQuestion(projetId, titre, corps, type);
    }

    private void bloquer(long projetId, long id, List<Integer> numeros) {
        for (int numero : numeros == null ? List.<Integer>of() : numeros) {
            long par = maps.idParNumero(projetId, numero)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Question " + numero + " introuvable."));
            if (maps.cycle(id, par)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Dépendance circulaire : la Question " + numero + " dépend déjà de celle-ci.");
            }
            maps.bloquer(id, par);
        }
    }

    private void trouverQuestion(long projetId, long id) {
        trouve(maps.questionExiste(id, projetId));
    }

    private static void trouve(boolean existe) {
        if (!existe) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Élément introuvable.");
        }
    }

    private void titreLibre(long projetId, String titre, long saufId) {
        if (maps.titrePris(projetId, titre, saufId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Une Question porte déjà ce titre.");
        }
    }

    private static String type(String type) {
        if (!List.of("grilling", "research", "prototype", "tache").contains(type)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Type de Question inconnu : " + type);
        }
        return type;
    }

    private static String obligatoire(String texte) {
        if (texte == null || texte.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Le texte est obligatoire.");
        }
        return texte.strip();
    }

    private static String strip(String texte) {
        return texte == null ? "" : texte.strip();
    }
}
