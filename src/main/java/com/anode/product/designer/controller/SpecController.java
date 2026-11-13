package com.anode.product.designer.controller;

import com.anode.product.designer.service.Llm;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Spec d'un Projet (E-19 à E-22) : générée depuis la Map et les Décisions, au gabarit fixe (D-13), versionnée sans fusion. */
@RestController
class SpecController {

    private static final Logger log = LoggerFactory.getLogger(SpecController.class);

    /** Socle du gabarit (D-13), dans l'ordre. */
    static final List<String> SOCLE = List.of(
            "1. Problème et Destination",
            "2. Utilisateurs et parcours clés",
            "3. Périmètre / Hors périmètre",
            "4. Modèle de domaine",
            "5. Exigences fonctionnelles",
            "6. Décisions structurantes",
            "7. Questions ouvertes / Brouillard restant");

    /** confluenceVersion : version de la page Confluence publiée depuis cette Spec (null sinon). */
    record Spec(long id, int version, String contenu, String createdAt, Integer confluenceVersion, String confluenceEspace) {}

    record Version(long id, int version, String createdAt) {}

    private final JdbcClient jdbc;
    private final Llm llm;
    private final MapController map;
    private final DecisionController decisions;
    private final Consommation consommation;
    private final String prompt;

    SpecController(JdbcClient jdbc, Llm llm, MapController map, DecisionController decisions, Consommation consommation)
            throws IOException {
        this.jdbc = jdbc;
        this.consommation = consommation;
        this.llm = llm;
        this.map = map;
        this.decisions = decisions;
        this.prompt = new ClassPathResource("prompts/spec.md").getContentAsString(StandardCharsets.UTF_8);
    }

    @GetMapping("/api/projets/{projetId}/specs")
    List<Version> versions(@PathVariable long projetId) {
        return jdbc.sql("SELECT id, version, created_at FROM spec WHERE projet_id = ? ORDER BY version DESC")
                .param(projetId).query(Version.class).list();
    }

    @GetMapping("/api/specs/{id}")
    Spec spec(@PathVariable long id) {
        return jdbc.sql("""
                        SELECT s.id, s.version, s.contenu, s.created_at, s.confluence_version, p.confluence_espace
                        FROM spec s JOIN projet p ON p.id = s.projet_id WHERE s.id = ?""").param(id).query(Spec.class).optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Spec introuvable."));
    }

    /** Génère une nouvelle version, streamée en SSE : `delta`, puis `fin` {id, version, avertissements} ou `erreur`. */
    @PostMapping("/api/projets/{projetId}/specs")
    SseEmitter generer(@PathVariable long projetId) {
        String nom = jdbc.sql("SELECT nom FROM projet WHERE id = ?").param(projetId).query(String.class).optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Projet introuvable."));
        llm.verifier();
        String destination = map.carte(projetId).destination();
        var messages = List.<Message>of(new SystemMessage(prompt), new UserMessage("Projet : " + nom + "\n\nDestination : "
                + (destination.isBlank() ? "non fixée" : destination) + "\n\n" + map.resume(projetId)));
        Set<Integer> numeros = decisions.confirmees(projetId).stream().map(DecisionController.Decision::numero).collect(Collectors.toSet());

        SseEmitter emitter = new SseEmitter(0L);
        Thread.ofVirtual().start(() -> {
            try {
                Llm.Reponse r = llm.stream(messages, Llm.Effort.ELEVE, List.of(), null, false,
                        t -> emitter.send(SseEmitter.event().name("delta").data(Map.of("texte", t))));
                consommation.enregistrer(projetId, "spec", r);
                String texte = r.texte();
                String contenu = normaliser(texte);
                List<String> avertissements = verifier(contenu, numeros);
                var spec = jdbc.sql("""
                                INSERT INTO spec (projet_id, version, contenu)
                                VALUES (?, (SELECT coalesce(max(version), 0) + 1 FROM spec WHERE projet_id = ?), ?)
                                RETURNING id, version""")
                        .params(projetId, projetId, contenu)
                        .query((rs, n) -> Map.of("id", rs.getLong(1), "version", rs.getInt(2))).single();
                emitter.send(SseEmitter.event().name("fin").data(Map.of("id", spec.get("id"), "version", spec.get("version"),
                        "avertissements", avertissements)));
                emitter.complete();
            } catch (Exception e) {
                log.warn("Échec de la génération de Spec du Projet {}", projetId, e);
                try {
                    emitter.send(SseEmitter.event().name("erreur").data(Map.of("message", Llm.messageErreur(e))));
                    emitter.complete();
                } catch (IOException | IllegalStateException ignored) {
                    emitter.completeWithError(e);
                }
            }
        });
        return emitter;
    }

    /**
     * Impose le gabarit : préambule, puis les 7 sections du socle dans l'ordre (« Non décidé. » si absente),
     * puis les sections optionnelles dans l'ordre où le modèle les a écrites.
     */
    static String normaliser(String texte) {
        String preambule = texte;
        Map<String, String> sections = new LinkedHashMap<>();
        Matcher m = Pattern.compile("(?m)^## +(.+?)\\s*$").matcher(texte);
        List<int[]> positions = new ArrayList<>();
        List<String> titres = new ArrayList<>();
        while (m.find()) {
            positions.add(new int[] {m.start(), m.end()});
            titres.add(m.group(1));
        }
        if (!positions.isEmpty()) {
            preambule = texte.substring(0, positions.getFirst()[0]);
        }
        for (int i = 0; i < positions.size(); i++) {
            int fin = i + 1 < positions.size() ? positions.get(i + 1)[0] : texte.length();
            sections.putIfAbsent(titres.get(i), texte.substring(positions.get(i)[1], fin).strip());
        }
        StringBuilder sb = new StringBuilder(preambule.strip()).append("\n\n");
        for (String titre : SOCLE) {
            String corps = sections.remove(socle(sections, titre));
            sb.append("## ").append(titre).append("\n\n").append(corps == null || corps.isBlank() ? "Non décidé." : corps).append("\n\n");
        }
        sections.forEach((titre, corps) -> sb.append("## ").append(titre).append("\n\n").append(corps).append("\n\n"));
        return sb.toString().strip() + "\n";
    }

    /** Titre écrit par le modèle pour une section du socle : exact, ou au même numéro (« 5. Exigences »…). */
    private static String socle(Map<String, String> sections, String titre) {
        if (sections.containsKey(titre)) {
            return titre;
        }
        // « 1. Problème » mais pas « 10. Annexes » ni « 1.1 Détail ».
        Pattern numero = Pattern.compile("^" + Pattern.quote(titre.substring(0, titre.indexOf('.') + 1)) + "(\\s|$)");
        return sections.keySet().stream().filter(t -> numero.matcher(t).find()).findFirst().orElse(titre);
    }

    /** Chaque exigence E-n de la section 5 doit citer au moins une Décision D-n existante (E-21). */
    static List<String> verifier(String contenu, Set<Integer> decisions) {
        int debut = contenu.indexOf("## " + SOCLE.get(4));
        int fin = contenu.indexOf("## " + SOCLE.get(5));
        String exigences = contenu.substring(debut, fin);
        List<String> avertissements = new ArrayList<>();
        Matcher ligne = Pattern.compile("(?m)^.*\\bE-(\\d+)\\b.*$").matcher(exigences);
        while (ligne.find()) {
            Matcher ref = Pattern.compile("\\bD-(\\d+)\\b").matcher(ligne.group());
            boolean citee = false;
            List<String> inconnues = new ArrayList<>();
            while (ref.find()) {
                if (decisions.contains(Integer.parseInt(ref.group(1)))) {
                    citee = true;
                } else {
                    inconnues.add("D-" + ref.group(1));
                }
            }
            String e = "E-" + ligne.group(1);
            if (!citee) {
                avertissements.add(e + " ne cite aucune Décision existante"
                        + (inconnues.isEmpty() ? "." : " (" + String.join(", ", inconnues) + " inconnue(s))."));
            } else if (!inconnues.isEmpty()) {
                avertissements.add(e + " cite " + String.join(", ", inconnues) + ", inconnue(s).");
            }
        }
        return avertissements;
    }
}
