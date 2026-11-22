package productdesigner;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Roadmap d'une version de Spec (E-23 à E-25) : Jalons et Tickets générés en structured output (D-22). */
@RestController
class RoadmapController {

    private static final Logger log = LoggerFactory.getLogger(RoadmapController.class);

    // --- Sortie structurée du LLM ---

    record RoadmapGeneree(List<JalonGenere> jalons) {}

    record JalonGenere(String titre, List<TicketGenere> tickets) {}

    record TicketGenere(String titre, String corps, List<String> criteres, List<String> exigences, List<String> blockedBy) {}

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Schéma JSON de RoadmapGeneree, passé au CLI en --json-schema. */
    static final String SCHEMA = """
            {"type":"object","additionalProperties":false,"required":["jalons"],"properties":{"jalons":{"type":"array","items":{
              "type":"object","additionalProperties":false,"required":["titre","tickets"],"properties":{
                "titre":{"type":"string"},
                "tickets":{"type":"array","items":{"type":"object","additionalProperties":false,
                  "required":["titre","corps","criteres","exigences","blockedBy"],"properties":{
                    "titre":{"type":"string","description":"Titre court, unique dans toute la Roadmap"},
                    "corps":{"type":"string","description":"Comportement de bout en bout rendu possible, du point de vue de l'utilisateur"},
                    "criteres":{"type":"array","items":{"type":"string"}},
                    "exigences":{"type":"array","items":{"type":"string"},"description":"Numéros des exigences couvertes, ex. E-3"},
                    "blockedBy":{"type":"array","items":{"type":"string"},"description":"Titres exacts des Tickets prérequis"}}}}}}}}}
            """;

    // --- Lecture ---

    record Ticket(long id, int ordre, String titre, String corps, List<String> exigences, List<Long> blockedBy, String jiraKey) {}

    record Jalon(long id, int ordre, String titre, String jiraKey, List<Ticket> tickets) {}

    /** jiraProjet : projet Jira de la première exportation, null avant. */
    record Roadmap(long specId, int version, String jiraProjet, List<Jalon> jalons) {}

    record TicketEdite(long jalonId, String titre, String corps, List<String> exigences, List<Long> blockedBy) {}

    record Titre(String titre) {}

    private record JalonLigne(long id, int ordre, String titre, String jiraKey) {}

    private record TicketLigne(long id, long jalonId, int ordre, String titre, String corps, String exigences, String jiraKey) {}

    private record Bloc(long ticketId, long parId) {}

    private final JdbcClient jdbc;
    private final Claude claude;
    private final TransactionTemplate transaction;
    private final Consommation consommation;
    private final String prompt;

    RoadmapController(JdbcClient jdbc, Claude claude, PlatformTransactionManager transactions, Consommation consommation)
            throws IOException {
        this.jdbc = jdbc;
        this.consommation = consommation;
        this.claude = claude;
        this.transaction = new TransactionTemplate(transactions);
        this.prompt = new ClassPathResource("prompts/roadmap.md").getContentAsString(StandardCharsets.UTF_8)
                .replaceAll("(?s)<!--.*?-->", "").strip();
    }

    @GetMapping("/api/specs/{specId}/roadmap")
    public Roadmap roadmap(@PathVariable long specId) {
        Object[] spec = jdbc.sql("SELECT version, jira_projet FROM spec WHERE id = ?").param(specId)
                .query((rs, n) -> new Object[] {rs.getInt(1), rs.getString(2)}).optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Spec introuvable."));
        Map<Long, List<Long>> blocages = new HashMap<>();
        for (Bloc b : jdbc.sql("""
                        SELECT tb.ticket_id, tb.bloque_par_id AS par_id FROM ticket_bloque tb
                        JOIN ticket t ON t.id = tb.ticket_id JOIN jalon j ON j.id = t.jalon_id WHERE j.spec_id = ?""")
                .param(specId).query(Bloc.class).list()) {
            blocages.computeIfAbsent(b.ticketId(), k -> new ArrayList<>()).add(b.parId());
        }
        Map<Long, List<Ticket>> parJalon = new HashMap<>();
        for (TicketLigne t : jdbc.sql("""
                        SELECT t.id, t.jalon_id, t.ordre, t.titre, t.corps, t.exigences, t.jira_key FROM ticket t
                        JOIN jalon j ON j.id = t.jalon_id WHERE j.spec_id = ? ORDER BY t.ordre""")
                .param(specId).query(TicketLigne.class).list()) {
            List<String> exigences = t.exigences().isBlank() ? List.of() : List.of(t.exigences().split(",\\s*"));
            parJalon.computeIfAbsent(t.jalonId(), k -> new ArrayList<>()).add(new Ticket(t.id(), t.ordre(), t.titre(), t.corps(),
                    exigences, blocages.getOrDefault(t.id(), List.of()), t.jiraKey()));
        }
        List<Jalon> jalons = jdbc.sql("SELECT id, ordre, titre, jira_key FROM jalon WHERE spec_id = ? ORDER BY ordre")
                .param(specId).query(JalonLigne.class).list().stream()
                .map(j -> new Jalon(j.id(), j.ordre(), j.titre(), j.jiraKey(), parJalon.getOrDefault(j.id(), List.of())))
                .toList();
        return new Roadmap(specId, (int) spec[0], (String) spec[1], jalons);
    }

    /**
     * Génère la Roadmap d'une version de Spec, en remplaçant l'existante (D-22). SSE : `progression` {caracteres},
     * puis `fin` {avertissements} ou `erreur`.
     */
    @PostMapping("/api/specs/{specId}/roadmap")
    SseEmitter generer(@PathVariable long specId) {
        String spec = jdbc.sql("SELECT contenu FROM spec WHERE id = ?").param(specId).query(String.class).optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Spec introuvable."));
        long projet = jdbc.sql("SELECT projet_id FROM spec WHERE id = ?").param(specId).query(Long.class).single();
        SseEmitter emitter = new SseEmitter(0L);
        Thread.ofVirtual().start(() -> {
            try {
                int[] caracteres = {0};
                var resultat = claude.appeler(prompt, "Spec :\n\n" + spec, SCHEMA, false, t -> {
                    caracteres[0] += t.length();
                    try {
                        emitter.send(SseEmitter.event().name("progression").data(Map.of("caracteres", caracteres[0])));
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                });
                consommation.enregistrer(projet, "roadmap", Claude.usage(resultat));
                RoadmapGeneree roadmap = resultat.structuredOutput() == null ? new RoadmapGeneree(null)
                        : JSON.treeToValue(resultat.structuredOutput(), RoadmapGeneree.class);
                if (roadmap.jalons() == null || roadmap.jalons().stream().allMatch(j -> j.tickets() == null || j.tickets().isEmpty())) {
                    // Sortie inexploitable : l'ancienne Roadmap (et ses éditions) reste en place.
                    log.warn("Roadmap sans Ticket pour la Spec {} ; sortie brute : {}", specId, resultat.raw());
                    throw new IllegalStateException("la Roadmap générée ne contient aucun Ticket ; Roadmap existante conservée");
                }
                // Appel depuis ce thread, hors proxy Spring : la transaction est explicite (tout ou rien).
                List<String> avertissements = transaction.execute(t -> enregistrer(specId, roadmap, exigences(spec)));
                emitter.send(SseEmitter.event().name("fin").data(Map.of("avertissements", avertissements)));
                emitter.complete();
            } catch (Exception e) {
                log.warn("Échec de la génération de Roadmap de la Spec {}", specId, e);
                try {
                    emitter.send(SseEmitter.event().name("erreur").data(Map.of("message", SessionController.messageErreur(e))));
                    emitter.complete();
                } catch (IOException | IllegalStateException ignored) {
                    emitter.completeWithError(e);
                }
            }
        });
        return emitter;
    }

    /** Remplace la Roadmap de la Spec ; renvoie les écarts aux règles de D-21 (tailles de Jalon, exigences, blocages). */
    @Transactional
    public List<String> enregistrer(long specId, RoadmapGeneree roadmap, Set<String> exigencesSpec) {
        jdbc.sql("""
                        INSERT OR IGNORE INTO jira_orphelin (spec_id, cle, titre)
                        SELECT ?, jira_key, titre FROM jalon WHERE spec_id = ? AND jira_key IS NOT NULL
                        UNION ALL SELECT ?, t.jira_key, t.titre FROM ticket t JOIN jalon j ON j.id = t.jalon_id
                        WHERE j.spec_id = ? AND t.jira_key IS NOT NULL""")
                .params(specId, specId, specId, specId).update();
        jdbc.sql("DELETE FROM jalon WHERE spec_id = ?").param(specId).update();
        List<String> avertissements = new ArrayList<>();
        Map<String, Long> parTitre = new HashMap<>();
        Set<String> couvertes = new TreeSet<>();
        int ordreJalon = 1, ordreTicket = 1;
        for (JalonGenere j : roadmap.jalons()) {
            long jalonId = jdbc.sql("INSERT INTO jalon (spec_id, ordre, titre) VALUES (?, ?, ?) RETURNING id")
                    .params(specId, ordreJalon++, sansNumero(j.titre())).query(Long.class).single();
            if (j.tickets().size() < 3 || j.tickets().size() > 10) {
                avertissements.add("Jalon « " + j.titre() + " » : " + j.tickets().size() + " Tickets (attendu : 3 à 10).");
            }
            for (TicketGenere t : j.tickets()) {
                List<String> inconnues = t.exigences().stream().filter(e -> !exigencesSpec.contains(e)).toList();
                if (!inconnues.isEmpty()) {
                    avertissements.add("Ticket « " + t.titre() + " » cite des exigences absentes de la Spec : " + String.join(", ", inconnues) + ".");
                }
                couvertes.addAll(t.exigences());
                long ticketId = jdbc.sql("INSERT INTO ticket (jalon_id, ordre, titre, corps, exigences) VALUES (?, ?, ?, ?, ?) RETURNING id")
                        .params(jalonId, ordreTicket++, t.titre(), corps(t), String.join(", ", t.exigences()))
                        .query(Long.class).single();
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
                        jdbc.sql("INSERT OR IGNORE INTO ticket_bloque (ticket_id, bloque_par_id) VALUES (?, ?)")
                                .params(parTitre.get(t.titre()), parId).update();
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

    /** Le numéro est porté par l'ordre : « Jalon 1 — Carnet » devient « Carnet ». */
    static String sansNumero(String titre) {
        return titre.replaceFirst("^\\s*Jalon\\s*\\d+\\s*[—–:.\\-]*\\s*", "").strip();
    }

    /**
     * Prérequis cité avec une petite faute (le modèle a écrit « fencés » pour « fermés ») : seul titre à 3 éditions
     * près, insensible à la casse ; null s'il n'y en a pas, ou plusieurs.
     */
    static Long proche(Map<String, Long> parTitre, String cite) {
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
        int debut = spec.indexOf("## " + SpecController.SOCLE.get(4));
        int fin = spec.indexOf("## " + SpecController.SOCLE.get(5));
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

    @PutMapping("/api/jalons/{id}")
    Roadmap renommerJalon(@PathVariable long id, @RequestBody Titre t) {
        long specId = specDuJalon(id);
        jdbc.sql("UPDATE jalon SET titre = ? WHERE id = ?").params(obligatoire(t.titre()), id).update();
        return roadmap(specId);
    }

    @PostMapping("/api/jalons/{id}/tickets")
    @Transactional
    public Roadmap ajouterTicket(@PathVariable long id, @RequestBody TicketEdite t) {
        long specId = specDuJalon(id);
        long ticketId = jdbc.sql("INSERT INTO ticket (jalon_id, ordre, titre) VALUES (?, ?, ?) RETURNING id")
                .params(id, ordreSuivant(specId), obligatoire(t.titre())).query(Long.class).single();
        ecrire(ticketId, specId, new TicketEdite(id, t.titre(), t.corps(), t.exigences(), t.blockedBy()));
        return roadmap(specId);
    }

    /** Modifie un Ticket ; changer de Jalon le place en dernier dans le Jalon cible. */
    @PutMapping("/api/tickets/{id}")
    @Transactional
    public Roadmap modifierTicket(@PathVariable long id, @RequestBody TicketEdite t) {
        long specId = specDuTicket(id);
        long jalonActuel = jdbc.sql("SELECT jalon_id FROM ticket WHERE id = ?").param(id).query(Long.class).single();
        if (t.jalonId() != jalonActuel) {
            if (specDuJalon(t.jalonId()) != specId) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Jalon d'une autre Roadmap.");
            }
            jdbc.sql("UPDATE ticket SET jalon_id = ?, ordre = ? WHERE id = ?").params(t.jalonId(), ordreSuivant(specId), id).update();
        }
        ecrire(id, specId, t);
        return roadmap(specId);
    }

    @DeleteMapping("/api/tickets/{id}")
    @Transactional
    public Roadmap supprimerTicket(@PathVariable long id) {
        long specId = specDuTicket(id);
        jdbc.sql("INSERT OR IGNORE INTO jira_orphelin (spec_id, cle, titre) SELECT ?, jira_key, titre FROM ticket WHERE id = ? AND jira_key IS NOT NULL")
                .params(specId, id).update();
        jdbc.sql("DELETE FROM ticket WHERE id = ?").param(id).update();
        return roadmap(specId);
    }

    private void ecrire(long id, long specId, TicketEdite t) {
        jdbc.sql("UPDATE ticket SET titre = ?, corps = ?, exigences = ? WHERE id = ?")
                .params(obligatoire(t.titre()), t.corps() == null ? "" : t.corps().strip(),
                        String.join(", ", t.exigences() == null ? List.of() : t.exigences()), id).update();
        jdbc.sql("DELETE FROM ticket_bloque WHERE ticket_id = ?").param(id).update();
        for (long par : t.blockedBy() == null ? List.<Long>of() : t.blockedBy()) {
            if (par == id || specDuTicket(par) != specId) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Prérequis invalide : " + par + ".");
            }
            jdbc.sql("INSERT OR IGNORE INTO ticket_bloque (ticket_id, bloque_par_id) VALUES (?, ?)").params(id, par).update();
        }
    }

    private int ordreSuivant(long specId) {
        return jdbc.sql("SELECT coalesce(max(t.ordre), 0) + 1 FROM ticket t JOIN jalon j ON j.id = t.jalon_id WHERE j.spec_id = ?")
                .param(specId).query(Integer.class).single();
    }

    private long specDuJalon(long jalonId) {
        return jdbc.sql("SELECT spec_id FROM jalon WHERE id = ?").param(jalonId).query(Long.class).optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Jalon introuvable."));
    }

    private long specDuTicket(long ticketId) {
        return jdbc.sql("SELECT j.spec_id FROM ticket t JOIN jalon j ON j.id = t.jalon_id WHERE t.id = ?").param(ticketId)
                .query(Long.class).optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Ticket introuvable."));
    }

    private static String obligatoire(String texte) {
        if (texte == null || texte.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Le titre est obligatoire.");
        }
        return texte.strip();
    }
}
