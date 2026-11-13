package com.anode.product.designer.service;

import lombok.extern.slf4j.Slf4j;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.anode.product.designer.mapper.RoadmapMapper;
import com.anode.product.designer.repository.RoadmapRepository;
import com.anode.product.designer.repository.RoadmapRepository.Bloc;
import com.anode.product.designer.repository.RoadmapRepository.SpecLigne;
import com.anode.product.designer.repository.RoadmapRepository.TicketLigne;
import com.anode.product.designer.repository.SpecRepository;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/** Roadmap d'une version de Spec (E-23 à E-25) : lecture, génération (D-22) en structured output, édition avant export. */
@Service
@Slf4j
public class RoadmapService {

    // --- Lecture ---

    public record Ticket(long id, int ordre, String titre, String corps, List<String> exigences, List<Long> blockedBy, String jiraKey) {}

    public record Jalon(long id, int ordre, String titre, String jiraKey, List<Ticket> tickets) {}

    /** jiraProjet : projet Jira de la première exportation, null avant. */
    public record Roadmap(long specId, int version, String jiraProjet, List<Jalon> jalons) {}

    // --- Sortie structurée du LLM ---

    public record RoadmapGeneree(List<JalonGenere> jalons) {}

    public record JalonGenere(String titre, List<TicketGenere> tickets) {}

    public record TicketGenere(
            @JsonPropertyDescription("Titre court, unique dans toute la Roadmap") String titre,
            @JsonPropertyDescription("Comportement de bout en bout rendu possible, du point de vue de l'utilisateur") String corps,
            List<String> criteres,
            @JsonPropertyDescription("Numéros des exigences couvertes, ex. E-3") List<String> exigences,
            @JsonPropertyDescription("Titres exacts des Tickets prérequis") List<String> blockedBy) {}

    // --- Édition ---

    public record TicketEdite(long jalonId, String titre, String corps, List<String> exigences, List<Long> blockedBy) {}

    /** Reçoit le nombre de caractères générés jusque-là. */
    public interface Progression {
        void caracteres(int total) throws IOException;
    }

    private final RoadmapRepository roadmaps;
    private final SpecRepository specs;
    private final Llm llm;
    private final ConsommationService consommation;
    private final RoadmapMapper mapper;
    private final TransactionTemplate transaction;
    private final String prompt;

    public RoadmapService(RoadmapRepository roadmaps, SpecRepository specs, Llm llm, ConsommationService consommation,
                          RoadmapMapper mapper, PlatformTransactionManager transactions) throws IOException {
        this.roadmaps = roadmaps;
        this.specs = specs;
        this.llm = llm;
        this.consommation = consommation;
        this.mapper = mapper;
        this.transaction = new TransactionTemplate(transactions);
        this.prompt = new ClassPathResource("prompts/roadmap.md").getContentAsString(StandardCharsets.UTF_8)
                .replaceAll("(?s)<!--.*?-->", "").strip();
    }

    public Roadmap roadmap(long specId) {
        SpecLigne spec = roadmaps.spec(specId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Spec introuvable."));
        Map<Long, List<Long>> blocages = new HashMap<>();
        for (Bloc b : roadmaps.blocages(specId)) {
            blocages.computeIfAbsent(b.ticketId(), k -> new ArrayList<>()).add(b.parId());
        }
        Map<Long, List<Ticket>> parJalon = new HashMap<>();
        for (TicketLigne t : roadmaps.tickets(specId)) {
            parJalon.computeIfAbsent(t.jalonId(), k -> new ArrayList<>())
                    .add(mapper.versTicket(t, blocages.getOrDefault(t.id(), List.of())));
        }
        List<Jalon> jalons = roadmaps.jalons(specId).stream()
                .map(j -> mapper.versJalon(j, parJalon.getOrDefault(j.id(), List.of())))
                .toList();
        return mapper.versRoadmap(specId, spec, jalons);
    }

    // --- Génération ---

    /** Ce qui doit échouer avant d'ouvrir le flux : Spec inconnue, LLM non configuré. */
    public void verifierGeneration(long specId) {
        contenuSpec(specId);
        llm.verifier();
    }

    /**
     * Génère la Roadmap d'une version de Spec, en remplaçant l'existante (D-22) ; bloquant. Renvoie les écarts aux règles
     * de D-21. Une sortie sans Ticket échoue et laisse l'ancienne Roadmap (et ses éditions) en place.
     */
    public List<String> generer(long specId, Progression progression) throws IOException {
        String spec = contenuSpec(specId);
        long projet = specs.projetId(specId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Spec introuvable."));
        var messages = List.<Message>of(new SystemMessage(prompt), new UserMessage("Spec :\n\n" + spec));
        // Décodage contraint natif du fournisseur, sur le schéma de RoadmapGeneree.
        var format = new BeanOutputConverter<>(RoadmapGeneree.class);

        int[] caracteres = {0};
        Llm.Reponse r = llm.stream(messages, Llm.Effort.ELEVE, List.of(), format.getJsonSchema(), false, t -> {
            caracteres[0] += t.length();
            progression.caracteres(caracteres[0]);
        });
        consommation.enregistrer(projet, "roadmap", r);
        RoadmapGeneree roadmap = r.texte().isBlank() ? new RoadmapGeneree(null) : format.convert(r.texte());
        if (roadmap.jalons() == null || roadmap.jalons().stream().allMatch(j -> j.tickets() == null || j.tickets().isEmpty())) {
            log.warn("Roadmap sans Ticket pour la Spec {} ; sortie brute : {}", specId, r.texte());
            throw new IllegalStateException("la Roadmap générée ne contient aucun Ticket ; Roadmap existante conservée");
        }
        // Appel depuis un thread de fond, hors proxy Spring : la transaction est explicite (tout ou rien).
        return transaction.execute(t -> enregistrer(specId, roadmap, exigences(spec)));
    }

    /** Remplace la Roadmap de la Spec ; renvoie les écarts aux règles de D-21 (tailles de Jalon, exigences, blocages). */
    @Transactional
    public List<String> enregistrer(long specId, RoadmapGeneree roadmap, Set<String> exigencesSpec) {
        roadmaps.orphelinerRoadmap(specId);
        roadmaps.supprimerJalons(specId);
        List<String> avertissements = new ArrayList<>();
        Map<String, Long> parTitre = new HashMap<>();
        Set<String> couvertes = new TreeSet<>();
        int ordreJalon = 1, ordreTicket = 1;
        for (JalonGenere j : roadmap.jalons()) {
            long jalonId = roadmaps.insererJalon(specId, ordreJalon++, sansNumero(j.titre()));
            if (j.tickets().size() < 3 || j.tickets().size() > 10) {
                avertissements.add("Jalon « " + j.titre() + " » : " + j.tickets().size() + " Tickets (attendu : 3 à 10).");
            }
            for (TicketGenere t : j.tickets()) {
                List<String> inconnues = t.exigences().stream().filter(e -> !exigencesSpec.contains(e)).toList();
                if (!inconnues.isEmpty()) {
                    avertissements.add("Ticket « " + t.titre() + " » cite des exigences absentes de la Spec : " + String.join(", ", inconnues) + ".");
                }
                couvertes.addAll(t.exigences());
                long ticketId = roadmaps.insererTicket(jalonId, ordreTicket++, t.titre(), corps(t), String.join(", ", t.exigences()));
                parTitre.putIfAbsent(t.titre(), ticketId);
            }
        }
        for (JalonGenere j : roadmap.jalons()) {
            for (TicketGenere t : j.tickets()) {
                for (String par : t.blockedBy()) {
                    Long parId = parTitre.containsKey(par) ? parTitre.get(par) : proche(parTitre, par);
                    if (parId == null) {
                        avertissements.add("Ticket « " + t.titre() + " » : prérequis inconnu « " + par + " », ignoré.");
                    } else if (!parId.equals(parTitre.get(t.titre()))) {
                        roadmaps.ajouterPrerequis(parTitre.get(t.titre()), parId);
                    }
                }
            }
        }
        Set<String> oubliees = new TreeSet<>(exigencesSpec);
        oubliees.removeAll(couvertes);
        if (!oubliees.isEmpty()) {
            avertissements.add("Exigences non couvertes : " + String.join(", ", oubliees) + ".");
        }
        return avertissements;
    }

    private String contenuSpec(long specId) {
        return specs.contenu(specId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Spec introuvable."));
    }

    /** Le numéro est porté par l'ordre : « Jalon 1 — Carnet » devient « Carnet ». */
    public static String sansNumero(String titre) {
        return titre.replaceFirst("^\\s*Jalon\\s*\\d+\\s*[—–:.\\-]*\\s*", "").strip();
    }

    /**
     * Prérequis cité avec une petite faute (le modèle a écrit « fencés » pour « fermés ») : seul titre à 3 éditions
     * près, insensible à la casse ; null s'il n'y en a pas, ou plusieurs.
     */
    public static Long proche(Map<String, Long> parTitre, String cite) {
        List<String> candidats = parTitre.keySet().stream().filter(t -> distance(t.toLowerCase(), cite.toLowerCase()) <= 3).toList();
        return candidats.size() == 1 ? parTitre.get(candidats.getFirst()) : null;
    }

    static int distance(String a, String b) {
        int[] precedente = new int[b.length() + 1], courante = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            precedente[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            courante[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                courante[j] = Math.min(Math.min(courante[j - 1], precedente[j]) + 1,
                        precedente[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1));
            }
            int[] t = precedente;
            precedente = courante;
            courante = t;
        }
        return precedente[b.length()];
    }

    /** Corps markdown du Ticket : comportement, puis critères d'acceptation en cases à cocher. */
    static String corps(TicketGenere t) {
        StringBuilder sb = new StringBuilder(t.corps().strip());
        if (!t.criteres().isEmpty()) {
            sb.append("\n\n## Critères d'acceptation\n");
            t.criteres().forEach(c -> sb.append("\n- [ ] ").append(c.strip()));
        }
        return sb.toString();
    }

    /** Numéros E-n présents dans la section Exigences de la Spec. */
    static Set<String> exigences(String spec) {
        Set<String> numeros = new TreeSet<>();
        int debut = spec.indexOf("## " + SpecService.SOCLE.get(4));
        int fin = spec.indexOf("## " + SpecService.SOCLE.get(5));
        if (debut < 0 || fin < debut) {
            return numeros;
        }
        Matcher m = Pattern.compile("\\bE-\\d+\\b").matcher(spec.substring(debut, fin));
        while (m.find()) {
            numeros.add(m.group());
        }
        return numeros;
    }

    // --- Édition avant export (E-25) : chaque écriture renvoie la Roadmap à jour ---

    public Roadmap renommerJalon(long id, String titre) {
        long specId = specDuJalon(id);
        roadmaps.renommerJalon(id, obligatoire(titre));
        return roadmap(specId);
    }

    @Transactional
    public Roadmap ajouterTicket(long jalonId, TicketEdite t) {
        long specId = specDuJalon(jalonId);
        long ticketId = roadmaps.insererTicket(jalonId, roadmaps.ordreSuivant(specId), obligatoire(t.titre()));
        ecrire(ticketId, specId, new TicketEdite(jalonId, t.titre(), t.corps(), t.exigences(), t.blockedBy()));
        return roadmap(specId);
    }

    /** Modifie un Ticket ; changer de Jalon le place en dernier dans le Jalon cible. */
    @Transactional
    public Roadmap modifierTicket(long id, TicketEdite t) {
        long specId = specDuTicket(id);
        if (t.jalonId() != roadmaps.jalonDuTicket(id)) {
            if (specDuJalon(t.jalonId()) != specId) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Jalon d'une autre Roadmap.");
            }
            roadmaps.deplacerTicket(id, t.jalonId(), roadmaps.ordreSuivant(specId));
        }
        ecrire(id, specId, t);
        return roadmap(specId);
    }

    @Transactional
    public Roadmap supprimerTicket(long id) {
        long specId = specDuTicket(id);
        roadmaps.orphelinerTicket(specId, id);
        roadmaps.supprimerTicket(id);
        return roadmap(specId);
    }

    private void ecrire(long id, long specId, TicketEdite t) {
        roadmaps.majTicket(id, obligatoire(t.titre()), t.corps() == null ? "" : t.corps().strip(),
                String.join(", ", t.exigences() == null ? List.of() : t.exigences()));
        roadmaps.supprimerPrerequis(id);
        for (long par : t.blockedBy() == null ? List.<Long>of() : t.blockedBy()) {
            if (par == id || specDuTicket(par) != specId) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Prérequis invalide : " + par + ".");
            }
            roadmaps.ajouterPrerequis(id, par);
        }
    }

    private long specDuJalon(long jalonId) {
        return roadmaps.specDuJalon(jalonId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Jalon introuvable."));
    }

    private long specDuTicket(long ticketId) {
        return roadmaps.specDuTicket(ticketId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Ticket introuvable."));
    }

    private static String obligatoire(String texte) {
        if (texte == null || texte.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Le titre est obligatoire.");
        }
        return texte.strip();
    }
}
