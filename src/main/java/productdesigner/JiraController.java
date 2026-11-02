package productdesigner;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Export d'une Roadmap vers Jira Cloud (E-26 à E-30) : Jalon = Epic, Ticket = Story rattachée par `parent`.
 * Idempotent : seuls les éléments sans `jira_key` sont créés, donc relancer l'export rejoue uniquement les échecs.
 */
@RestController
class JiraController {

    private static final int LOT = 50;

    record Demande(String projet) {}

    record Echec(String titre, String erreur) {}

    /** orphelines : issues Jira dont l'élément local n'existe plus, laissées intactes (E-28). */
    /** recuperes : issues retrouvées dans Jira après une création à l'issue inconnue, au lieu d'être recréées. */
    record Resultat(String projet, int epicsCrees, int storiesCreees, int liensCrees, int misesAJour, int recuperes, List<Echec> echecs,
                    List<String> orphelines) {}

    private record Lien(long ticketId, long parId, String cle, String parCle) {}

    private final JdbcClient jdbc;
    private final JiraClient jira;
    private final RoadmapController roadmaps;

    JiraController(JdbcClient jdbc, JiraClient jira, RoadmapController roadmaps) {
        this.jdbc = jdbc;
        this.jira = jira;
        this.roadmaps = roadmaps;
    }

    @GetMapping("/api/jira/status")
    Map<String, Object> status() {
        return jira.configure() ? Map.of("configure", true) : Map.of("configure", false, "message", JiraClient.CONFIG_ABSENTE);
    }

    @PostMapping("/api/specs/{specId}/export-jira")
    Resultat exporter(@PathVariable long specId, @RequestBody Demande demande) {
        RoadmapController.Roadmap roadmap = roadmaps.roadmap(specId);
        if (roadmap.jalons().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Roadmap vide : rien à exporter.");
        }
        String projet = roadmap.jiraProjet() != null ? roadmap.jiraProjet()
                : demande.projet() == null ? "" : demande.projet().strip().toUpperCase();
        if (projet.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Clé du projet Jira obligatoire.");
        }
        JiraClient.Types types = jira.types(projet);
        Set<Long> jalonsIncertains = Set.copyOf(jdbc.sql("SELECT id FROM jalon WHERE spec_id = ? AND jira_incertain = 1")
                .param(specId).query(Long.class).list());
        Set<Long> ticketsIncertains = Set.copyOf(jdbc.sql(
                        "SELECT t.id FROM ticket t JOIN jalon j ON j.id = t.jalon_id WHERE j.spec_id = ? AND t.jira_incertain = 1")
                .param(specId).query(Long.class).list());

        List<Echec> echecs = new ArrayList<>();
        int epics = 0, misesAJour = 0, recuperes = 0;
        Map<Long, String> epicDuJalon = new HashMap<>();
        for (RoadmapController.Jalon j : roadmap.jalons()) {
            String cle = j.jiraKey();
            if (cle == null && jalonsIncertains.contains(j.id())) {
                cle = jira.chercher(projet, types.epic(), j.titre(), null).orElse(null);
                if (cle != null) {
                    jdbc.sql("UPDATE jalon SET jira_key = ?, jira_incertain = 0 WHERE id = ?").params(cle, j.id()).update();
                    recuperes++;
                }
            }
            if (cle != null) {
                misesAJour += maj(echecs, "Jalon « " + j.titre() + " »", cle, Map.of("summary", j.titre()));
            } else {
                try {
                    cle = jira.creer(champs(projet, types.epic(), j.titre(), null, null));
                    jdbc.sql("UPDATE jalon SET jira_key = ?, jira_incertain = 0 WHERE id = ?").params(cle, j.id()).update();
                    epics++;
                } catch (ResponseStatusException e) {
                    throw e;
                } catch (RuntimeException e) {
                    jdbc.sql("UPDATE jalon SET jira_incertain = ? WHERE id = ?").params(JiraClient.incertain(e), j.id()).update();
                    echecs.add(new Echec("Jalon « " + j.titre() + " »", e.getMessage()));
                    continue;
                }
            }
            epicDuJalon.put(j.id(), cle);
        }
        // Projet mémorisé seulement une fois quelque chose créé ou retrouvé : une mauvaise clé ne fige pas la cible.
        if (roadmap.jiraProjet() == null && !epicDuJalon.isEmpty()) {
            jdbc.sql("UPDATE spec SET jira_projet = ? WHERE id = ?").params(projet, specId).update();
        }

        List<RoadmapController.Ticket> aCreer = new ArrayList<>();
        List<Map<String, Object>> champs = new ArrayList<>();
        for (RoadmapController.Jalon j : roadmap.jalons()) {
            for (RoadmapController.Ticket t : j.tickets()) {
                String cle = t.jiraKey();
                String epic = epicDuJalon.get(j.id());
                if (cle == null && epic != null && ticketsIncertains.contains(t.id())) {
                    cle = jira.chercher(projet, types.story(), t.titre(), epic).orElse(null);
                    if (cle != null) {
                        jdbc.sql("UPDATE ticket SET jira_key = ?, jira_incertain = 0 WHERE id = ?").params(cle, t.id()).update();
                        recuperes++;
                    }
                }
                if (cle != null) {
                    // Ré-export (E-28) : titre, description et Epic parent (le Ticket a pu changer de Jalon).
                    Map<String, Object> champsMaj = new HashMap<>(Map.of("summary", t.titre(), "description", description(t)));
                    if (epic != null) {
                        champsMaj.put("parent", Map.of("key", epic));
                    }
                    misesAJour += maj(echecs, t.titre(), cle, champsMaj);
                    continue;
                }
                if (epic == null) {
                    echecs.add(new Echec(t.titre(), "Epic de son Jalon non créé."));
                    continue;
                }
                aCreer.add(t);
                champs.add(champs(projet, types.story(), t.titre(), description(t), epic));
            }
        }
        int stories = 0;
        for (int debut = 0; debut < champs.size(); debut += LOT) {
            int fin = Math.min(debut + LOT, champs.size());
            JiraClient.Bulk bulk;
            try {
                bulk = jira.creerEnMasse(champs.subList(debut, fin));
            } catch (ResponseStatusException e) {
                throw e;
            } catch (RuntimeException e) {
                // Lot entier en échec : chaque Ticket est signalé ; marqué incertain si Jira a pu le créer quand même.
                for (RoadmapController.Ticket t : aCreer.subList(debut, fin)) {
                    jdbc.sql("UPDATE ticket SET jira_incertain = ? WHERE id = ?").params(JiraClient.incertain(e), t.id()).update();
                    echecs.add(new Echec(t.titre(), e.getMessage()));
                }
                continue;
            }
            for (int i = 0; i < bulk.cles().size(); i++) {
                RoadmapController.Ticket t = aCreer.get(debut + i);
                String cle = bulk.cles().get(i);
                if (cle == null) {
                    jdbc.sql("UPDATE ticket SET jira_incertain = 0 WHERE id = ?").param(t.id()).update();
                    echecs.add(new Echec(t.titre(), bulk.erreurs().getOrDefault(i, "Échec sans détail.")));
                } else {
                    jdbc.sql("UPDATE ticket SET jira_key = ?, jira_incertain = 0 WHERE id = ?").params(cle, t.id()).update();
                    stories++;
                }
            }
        }

        int liens = 0;
        for (Lien l : jdbc.sql("""
                        SELECT tb.ticket_id, tb.bloque_par_id AS par_id, t.jira_key AS cle, par.jira_key AS par_cle
                        FROM ticket_bloque tb JOIN ticket t ON t.id = tb.ticket_id JOIN ticket par ON par.id = tb.bloque_par_id
                        JOIN jalon j ON j.id = t.jalon_id
                        WHERE j.spec_id = ? AND tb.jira_lie = 0 AND t.jira_key IS NOT NULL AND par.jira_key IS NOT NULL""")
                .param(specId).query(Lien.class).list()) {
            try {
                jira.lierBlocage(l.parCle(), l.cle());
                jdbc.sql("UPDATE ticket_bloque SET jira_lie = 1 WHERE ticket_id = ? AND bloque_par_id = ?").params(l.ticketId(), l.parId()).update();
                liens++;
            } catch (ResponseStatusException e) {
                throw e;
            } catch (RuntimeException e) {
                echecs.add(new Echec("Lien " + l.parCle() + " bloque " + l.cle(), e.getMessage()));
            }
        }
        List<String> orphelines = jdbc.sql("SELECT cle || ' (' || titre || ')' FROM jira_orphelin WHERE spec_id = ? ORDER BY id")
                .param(specId).query(String.class).list();
        return new Resultat(projet, epics, stories, liens, misesAJour, recuperes, echecs, orphelines);
    }

    private int maj(List<Echec> echecs, String titre, String cle, Map<String, Object> champs) {
        try {
            jira.modifier(cle, champs);
            return 1;
        } catch (ResponseStatusException e) {
            throw e;
        } catch (RuntimeException e) {
            echecs.add(new Echec(titre, "Mise à jour de " + cle + " : " + e.getMessage()));
            return 0;
        }
    }

    private static Map<String, Object> champs(String projet, String type, String resume, Map<String, Object> description, String parent) {
        Map<String, Object> champs = new HashMap<>();
        champs.put("project", Map.of("key", projet));
        champs.put("issuetype", Map.of("id", type));
        champs.put("summary", resume);
        if (description != null) {
            champs.put("description", description);
        }
        if (parent != null) {
            champs.put("parent", Map.of("key", parent));
        }
        return champs;
    }

    /** Corps du Ticket en ADF, avec les exigences couvertes en pied. */
    static Map<String, Object> description(RoadmapController.Ticket t) {
        String corps = t.corps();
        if (!t.exigences().isEmpty()) {
            corps += "\n\n**Exigences :** " + String.join(", ", t.exigences());
        }
        return Adf.document(corps);
    }
}
