package productdesigner;

import java.util.HashMap;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** La Map d'un Projet : lecture, et application des sorties `questions` et `map` de record. */
@RestController
class MapController {

    /** ouvrable : dans la frontière (E-4). */
    record Question(long id, int numero, String titre, String corps, String type, String statut, List<Integer> blockedBy, boolean ouvrable) {}

    record Ligne(long id, String texte) {}

    record Carte(String destination, String notes, List<Question> questions, List<Ligne> brouillard, List<Ligne> horsPerimetre) {}

    record EnTete(String destination, String notes) {}

    record QuestionEditee(String titre, String corps, String type, List<Integer> blockedBy) {}

    record Texte(String texte) {}

    record Promotion(String type) {}

    private record QuestionLigne(long id, int numero, String titre, String corps, String type, String statut) {}

    private record Bloc(long questionId, int numero) {}

    private final JdbcClient jdbc;
    private final DecisionController decisions;

    MapController(JdbcClient jdbc, DecisionController decisions) {
        this.jdbc = jdbc;
        this.decisions = decisions;
    }

    /** Map et Décisions confirmées du Projet, en texte : amorce d'une Session de suite, entrée de la Spec. */
    String resume(long projetId) {
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
        for (DecisionController.Decision d : decisions.confirmees(projetId)) {
            sb.append("- D-").append(d.numero()).append(" : ").append(d.texte()).append("\n");
        }
        return sb.toString();
    }


    @GetMapping("/api/projets/{projetId}/map")
    Carte carte(@PathVariable long projetId) {
        var map = jdbc.sql("SELECT destination, notes FROM map WHERE projet_id = ?").param(projetId)
                .query((rs, n) -> new String[] {rs.getString(1), rs.getString(2)}).optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Projet introuvable."));
        var blocages = new HashMap<Long, List<Integer>>();
        for (Bloc b : jdbc.sql("""
                        SELECT qb.question_id, par.numero FROM question_bloquee qb
                        JOIN question par ON par.id = qb.bloquee_par_id
                        WHERE par.projet_id = ? ORDER BY par.numero""")
                .param(projetId).query(Bloc.class).list()) {
            blocages.computeIfAbsent(b.questionId(), k -> new java.util.ArrayList<>()).add(b.numero());
        }
        List<Long> frontiere = frontiere(projetId);
        List<Question> questions = jdbc.sql("SELECT id, numero, titre, corps, type, statut FROM question WHERE projet_id = ? ORDER BY numero")
                .param(projetId).query(QuestionLigne.class).list().stream()
                .map(q -> new Question(q.id(), q.numero(), q.titre(), q.corps(), q.type(), q.statut(),
                        blocages.getOrDefault(q.id(), List.of()), frontiere.contains(q.id())))
                .toList();
        return new Carte(map[0], map[1], questions,
                jdbc.sql("SELECT id, texte FROM brouillard WHERE projet_id = ? ORDER BY id").param(projetId).query(Ligne.class).list(),
                jdbc.sql("SELECT id, texte FROM hors_perimetre WHERE projet_id = ? ORDER BY id").param(projetId).query(Ligne.class).list());
    }

    /**
     * Applique un appel record à la Map, sans confirmation (seules les Décisions en demandent, D-17).
     * Une Question dont le titre existe déjà est ignorée ; `blocked_by` se résout par titre, une fois toutes les Questions créées.
     */
    @Transactional
    void appliquer(long projetId, JsonNode input) {
        JsonNode map = input.path("map");
        String destination = map.path("destination").asText("").strip();
        if (!destination.isEmpty()) {
            jdbc.sql("UPDATE map SET destination = ? WHERE projet_id = ?").params(destination, projetId).update();
        }
        for (JsonNode texte : map.path("brouillard")) {
            jdbc.sql("INSERT INTO brouillard (projet_id, texte) VALUES (?, ?)").params(projetId, texte.asText().strip()).update();
        }
        for (JsonNode texte : map.path("hors_perimetre")) {
            jdbc.sql("INSERT INTO hors_perimetre (projet_id, texte) VALUES (?, ?)").params(projetId, texte.asText().strip()).update();
        }
        for (JsonNode q : input.path("questions")) {
            jdbc.sql("""
                            INSERT INTO question (projet_id, numero, titre, corps, type)
                            VALUES (?, (SELECT coalesce(max(numero), 0) + 1 FROM question WHERE projet_id = ?), ?, ?, ?)
                            ON CONFLICT (projet_id, titre) DO NOTHING""")
                    .params(projetId, projetId, q.path("titre").asText().strip(), q.path("corps").asText(""), q.path("type").asText("grilling"))
                    .update();
        }
        for (JsonNode q : input.path("questions")) {
            for (JsonNode par : q.path("blocked_by")) {
                jdbc.sql("""
                                INSERT OR IGNORE INTO question_bloquee (question_id, bloquee_par_id)
                                SELECT q.id, par.id FROM question q, question par
                                WHERE q.projet_id = ? AND q.titre = ? AND par.projet_id = ? AND par.titre = ? AND q.id <> par.id""")
                        .params(projetId, q.path("titre").asText().strip(), projetId, par.asText().strip())
                        .update();
            }
        }
    }

    /** Questions `open` dont toutes les bloquantes sont `resolved`, par numéro (E-4). */
    List<Long> frontiere(long projetId) {
        return jdbc.sql("""
                        SELECT q.id FROM question q
                        WHERE q.projet_id = ? AND q.statut = 'open'
                          AND NOT EXISTS (SELECT 1 FROM question_bloquee qb JOIN question par ON par.id = qb.bloquee_par_id
                                          WHERE qb.question_id = q.id AND par.statut <> 'resolved')
                        ORDER BY q.numero""")
                .param(projetId).query(Long.class).list();
    }

    // --- Édition manuelle (E-3, E-5) ---

    @PutMapping("/api/projets/{projetId}/map")
    Carte modifierEnTete(@PathVariable long projetId, @RequestBody EnTete e) {
        jdbc.sql("UPDATE map SET destination = ?, notes = ? WHERE projet_id = ?")
                .params(strip(e.destination()), strip(e.notes()), projetId).update();
        return carte(projetId);
    }

    @PostMapping("/api/projets/{projetId}/questions")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public Carte creerQuestion(@PathVariable long projetId, @RequestBody QuestionEditee q) {
        long id = inserer(projetId, obligatoire(q.titre()), strip(q.corps()), type(q.type()));
        bloquer(projetId, id, q.blockedBy());
        return carte(projetId);
    }

    @PutMapping("/api/projets/{projetId}/questions/{id}")
    @Transactional
    public Carte modifierQuestion(@PathVariable long projetId, @PathVariable long id, @RequestBody QuestionEditee q) {
        trouverQuestion(projetId, id);
        titreLibre(projetId, obligatoire(q.titre()), id);
        jdbc.sql("UPDATE question SET titre = ?, corps = ?, type = ? WHERE id = ?")
                .params(obligatoire(q.titre()), strip(q.corps()), type(q.type()), id).update();
        jdbc.sql("DELETE FROM question_bloquee WHERE question_id = ?").param(id).update();
        bloquer(projetId, id, q.blockedBy());
        return carte(projetId);
    }

    /** Toute Question peut sortir du périmètre, quel que soit son statut (D-20). */
    @PostMapping("/api/projets/{projetId}/questions/{id}/hors-perimetre")
    Carte horsPerimetre(@PathVariable long projetId, @PathVariable long id) {
        trouverQuestion(projetId, id);
        jdbc.sql("UPDATE question SET statut = 'hors_perimetre' WHERE id = ?").param(id).update();
        return carte(projetId);
    }

    @PostMapping("/api/projets/{projetId}/{liste:brouillard|hors-perimetre}")
    @ResponseStatus(HttpStatus.CREATED)
    Carte ajouterLigne(@PathVariable long projetId, @PathVariable String liste, @RequestBody Texte t) {
        carte(projetId);
        jdbc.sql("INSERT INTO " + table(liste) + " (projet_id, texte) VALUES (?, ?)").params(projetId, obligatoire(t.texte())).update();
        return carte(projetId);
    }

    @PutMapping("/api/projets/{projetId}/{liste:brouillard|hors-perimetre}/{id}")
    Carte modifierLigne(@PathVariable long projetId, @PathVariable String liste, @PathVariable long id, @RequestBody Texte t) {
        trouve(jdbc.sql("UPDATE " + table(liste) + " SET texte = ? WHERE id = ? AND projet_id = ?")
                .params(obligatoire(t.texte()), id, projetId).update());
        return carte(projetId);
    }

    @DeleteMapping("/api/projets/{projetId}/{liste:brouillard|hors-perimetre}/{id}")
    Carte supprimerLigne(@PathVariable long projetId, @PathVariable String liste, @PathVariable long id) {
        trouve(jdbc.sql("DELETE FROM " + table(liste) + " WHERE id = ? AND projet_id = ?").params(id, projetId).update());
        return carte(projetId);
    }

    /** Brouillard devenu net : la ligne disparaît, une Question `open` prend son texte pour titre (D-20). */
    @PostMapping("/api/projets/{projetId}/brouillard/{id}/promouvoir")
    @Transactional
    public Carte promouvoir(@PathVariable long projetId, @PathVariable long id, @RequestBody Promotion p) {
        String texte = jdbc.sql("SELECT texte FROM brouillard WHERE id = ? AND projet_id = ?").params(id, projetId)
                .query(String.class).optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Brouillard introuvable."));
        inserer(projetId, texte, "", type(p.type()));
        jdbc.sql("DELETE FROM brouillard WHERE id = ?").param(id).update();
        return carte(projetId);
    }

    private long inserer(long projetId, String titre, String corps, String type) {
        titreLibre(projetId, titre, -1);
        return jdbc.sql("""
                        INSERT INTO question (projet_id, numero, titre, corps, type)
                        VALUES (?, (SELECT coalesce(max(numero), 0) + 1 FROM question WHERE projet_id = ?), ?, ?, ?)
                        RETURNING id""")
                .params(projetId, projetId, titre, corps, type).query(Long.class).single();
    }

    private void bloquer(long projetId, long id, List<Integer> numeros) {
        for (int numero : numeros == null ? List.<Integer>of() : numeros) {
            long par = jdbc.sql("SELECT id FROM question WHERE projet_id = ? AND numero = ?").params(projetId, numero)
                    .query(Long.class).optional()
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Question " + numero + " introuvable."));
            if (par == id) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Une Question ne peut pas se bloquer elle-même.");
            }
            jdbc.sql("INSERT OR IGNORE INTO question_bloquee (question_id, bloquee_par_id) VALUES (?, ?)").params(id, par).update();
        }
    }

    private void trouverQuestion(long projetId, long id) {
        trouve(jdbc.sql("SELECT count(*) FROM question WHERE id = ? AND projet_id = ?").params(id, projetId)
                .query(Integer.class).single());
    }

    private static void trouve(int lignes) {
        if (lignes == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Élément introuvable.");
        }
    }

    private void titreLibre(long projetId, String titre, long saufId) {
        if (jdbc.sql("SELECT count(*) FROM question WHERE projet_id = ? AND titre = ? AND id <> ?")
                .params(projetId, titre, saufId).query(Integer.class).single() > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Une Question porte déjà ce titre.");
        }
    }

    private static String table(String liste) {
        return liste.equals("brouillard") ? "brouillard" : "hors_perimetre";
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
