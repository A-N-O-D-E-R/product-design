package productdesigner;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
    record Resultat(String projet, int epicsCrees, int storiesCreees, int liensCrees, int misesAJour, List<Echec> echecs,
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
        String dejaExporte = jdbc.sql("SELECT jira_projet FROM spec WHERE id = ?").param(specId).query(String.class).optional().orElse(null);
        String projet = dejaExporte != null ? dejaExporte : demande.projet() == null ? "" : demande.projet().strip().toUpperCase();
        if (projet.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Clé du projet Jira obligatoire.");
        }
        JiraClient.Types types = jira.types(projet);
        jdbc.sql("UPDATE spec SET jira_projet = ? WHERE id = ?").params(projet, specId).update();

        List<Echec> echecs = new ArrayList<>();
        int epics = 0;
        Map<Long, String> epicDuJalon = new HashMap<>();
        int misesAJour = 0;
        for (RoadmapController.Jalon j : roadmap.jalons()) {
            String cle = j.jiraKey();
            if (cle != null) {
                misesAJour += maj(echecs, "Jalon « " + j.titre() + " »", cle, Map.of("summary", j.titre()));
            } else {
                try {
                    cle = jira.creer(champs(projet, types.epic(), j.titre(), null, null));
                    jdbc.sql("UPDATE jalon SET jira_key = ? WHERE id = ?").params(cle, j.id()).update();
                    epics++;
                } catch (ResponseStatusException e) {
                    throw e;
                } catch (RuntimeException e) {
                    echecs.add(new Echec("Jalon « " + j.titre() + " »", e.getMessage()));
                    continue;
                }
            }
            epicDuJalon.put(j.id(), cle);
        }

        List<RoadmapController.Ticket> aCreer = new ArrayList<>();
        List<Map<String, Object>> champs = new ArrayList<>();
        for (RoadmapController.Jalon j : roadmap.jalons()) {
            for (RoadmapController.Ticket t : j.tickets()) {
                if (t.jiraKey() != null) {
                    // Ré-export (E-28) : titre, description et Epic parent (le Ticket a pu changer de Jalon).
                    Map<String, Object> champsMaj = new HashMap<>(Map.of("summary", t.titre(), "description", description(t)));
                    if (epicDuJalon.containsKey(j.id())) {
                        champsMaj.put("parent", Map.of("key", epicDuJalon.get(j.id())));
                    }
                    misesAJour += maj(echecs, t.titre(), t.jiraKey(), champsMaj);
                    continue;
                }
                if (!epicDuJalon.containsKey(j.id())) {
                    echecs.add(new Echec(t.titre(), "Epic de son Jalon non créé."));
                    continue;
                }
                aCreer.add(t);
                champs.add(champs(projet, types.story(), t.titre(), description(t), epicDuJalon.get(j.id())));
            }
        }
        int stories = 0;
        for (int debut = 0; debut < champs.size(); debut += LOT) {
            int fin = Math.min(debut + LOT, champs.size());
            JiraClient.Bulk bulk = jira.creerEnMasse(champs.subList(debut, fin));
            for (int i = 0; i < bulk.cles().size(); i++) {
                RoadmapController.Ticket t = aCreer.get(debut + i);
                String cle = bulk.cles().get(i);
                if (cle == null) {
                    echecs.add(new Echec(t.titre(), bulk.erreurs().getOrDefault(i, "Échec sans détail.")));
                } else {
                    jdbc.sql("UPDATE ticket SET jira_key = ? WHERE id = ?").params(cle, t.id()).update();
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
        return new Resultat(projet, epics, stories, liens, misesAJour, echecs, orphelines);
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
