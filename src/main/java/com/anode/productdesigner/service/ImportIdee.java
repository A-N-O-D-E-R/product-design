package com.anode.productdesigner.service;

import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import com.anode.productdesigner.repository.MapRepository;
import com.anode.productdesigner.repository.MapRepository.Liste;
import com.anode.productdesigner.repository.ProjetRepository;
import com.anode.productdesigner.repository.ProjetRepository.Projet;
import com.anode.productdesigner.repository.SessionRepository;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Importe l'idea.json d'idea-generator (contrat idea-brief.schema.json, copie de digital-factory/factory-contracts) :
 * il crée le Projet, sa Map et la Session qui la trace, puis amorce la Map à partir du brief.
 * <ul>
 *   <li>{@code brief.problem} → Destination ; audience, MVP, stack et premiers pas → notes de la Map ;</li>
 *   <li>{@code brief.openQuestions} → Questions (grilling) ; {@code brief.outOfScope} → Hors périmètre ;</li>
 *   <li>{@code brief.risks}, et les risques et hypothèses de {@code research} → Brouillard ;</li>
 *   <li>{@code research} en entier → artefact research_md d'une Session research close.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class ImportIdee {

    public record Importe(long id, String nom, String description, String createdAt, String sourceIdeaUrl, long sessionTraceId,
                          int questions, int horsPerimetre, int brouillard, Long sessionResearchId) {}

    private static final Schema SCHEMA = chargerSchema();
    private static final int TITRE_MAX = 120;

    private final ProjetRepository projets;
    private final MapRepository maps;
    private final SessionRepository sessions;
    private final JsonMapper json;

    private static Schema chargerSchema() {
        try (var in = ImportIdee.class.getResourceAsStream("/contracts/idea-brief.schema.json")) {
            return SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12, b -> { })
                    .getSchema(new String(in.readAllBytes(), StandardCharsets.UTF_8), InputFormat.JSON);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** @param source URL de l'idée source si l'appelant la connaît (sinon : la première URL de la lignée, ou celle de l'idée). */
    @Transactional
    public Importe importer(String ideaJson, String source) {
        JsonNode idee = valider(ideaJson);
        JsonNode brief = idee.path("brief");
        String urlSource = source != null && !source.isBlank() ? source.strip() : urlDeLaLignee(idee);
        if (urlSource != null) {
            Projet deja = projets.parSource(urlSource).orElse(null);
            if (deja != null) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Cette idée est déjà importée : Projet " + deja.id() + " (« " + deja.nom() + " »).");
            }
        }
        JsonNode research = idee.path("research");
        Projet projet = projets.creerImporte(texte(brief, "name"), texte(brief, "oneLiner"), urlSource, provenance(idee, urlSource));
        maps.creer(projet.id());
        long sessionTrace = sessions.creer(projet.id(), "wayfinder", null, null);

        maps.majEnTete(projet.id(), texte(brief, "problem"), notes(brief));

        int questions = 0;
        for (String q : lignes(brief.path("openQuestions"))) {
            maps.insererQuestionSiNouvelle(projet.id(), coupe(q, TITRE_MAX), q, "grilling");
            questions++;
        }
        List<String> horsPerimetre = lignes(brief.path("outOfScope"));
        horsPerimetre.forEach(t -> maps.ajouterLigne(Liste.HORS_PERIMETRE, projet.id(), t));

        List<String> brouillard = new ArrayList<>();
        lignes(brief.path("risks")).forEach(r -> brouillard.add("Risque : " + r));
        lignes(research.path("riskFactors")).forEach(r -> brouillard.add("Risque (recherche) : " + r));
        lignes(research.path("hypotheses")).forEach(h -> brouillard.add("Hypothèse à vérifier : " + h));
        brouillard.forEach(t -> maps.ajouterLigne(Liste.BROUILLARD, projet.id(), t));

        Long sessionResearch = null;
        if (research.isObject() && !research.isEmpty()) {
            sessionResearch = sessions.creer(projet.id(), "research", null, null);
            sessions.ajouterArtefact(sessionResearch, "research_md", rapport(research));
            sessions.terminerResearch(sessionResearch, "Importé d'idea-generator (ai-scientist)");
            sessions.clore(sessionResearch);
        }
        return new Importe(projet.id(), projet.nom(), projet.description(), projet.createdAt(), urlSource, sessionTrace,
                questions, horsPerimetre.size(), brouillard.size(), sessionResearch);
    }

    private JsonNode valider(String ideaJson) {
        if (ideaJson == null || ideaJson.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "L'idea.json est vide.");
        }
        JsonNode idee;
        try {
            idee = json.readTree(ideaJson);
        } catch (JacksonException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "L'idea.json n'est pas du JSON valide.");
        }
        var erreurs = SCHEMA.validate(ideaJson, InputFormat.JSON);
        if (!erreurs.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "L'idea.json ne respecte pas idea-brief.schema.json : "
                    + erreurs.stream().limit(5).map(e -> e.getMessage()).collect(Collectors.joining(" ; ")));
        }
        return idee;
    }

    /** La première URL source de la lignée (l'origine de l'idée), à défaut celle de l'idée elle-même. */
    private static String urlDeLaLignee(JsonNode idee) {
        for (JsonNode ancetre : idee.path("lineage")) {
            String url = texte(ancetre, "sourceUrl");
            if (!url.isEmpty()) {
                return url;
            }
        }
        String url = texte(idee.path("idea"), "sourceUrl");
        return url.isEmpty() ? null : url;
    }

    private String provenance(JsonNode idee, String urlSource) {
        var p = json.createObjectNode();
        p.put("ideeId", idee.path("idea").path("id").asLong(0));
        p.put("tableauId", idee.path("idea").path("boardId").asLong(0));
        p.put("titre", texte(idee.path("idea"), "title"));
        p.put("objectif", texte(idee, "goal"));
        p.put("creeLe", texte(idee, "createdAt"));
        p.put("sourceUrl", urlSource);
        p.put("lignee", idee.path("lineage").size());
        if (idee.path("research").isObject()) {
            p.put("recherche", texte(idee.path("research"), "jobUrl"));
            p.put("verdictNouveaute", texte(idee.path("research"), "noveltyVerdict"));
        }
        return p.toString();
    }

    private static String notes(JsonNode brief) {
        var sb = new StringBuilder();
        ajouter(sb, "Public", texte(brief, "audience"));
        liste(sb, "MVP", lignes(brief.path("mvp")), false);
        ajouter(sb, "Stack", texte(brief, "stack"));
        liste(sb, "Premiers pas", lignes(brief.path("firstSteps")), true);
        return sb.toString().strip();
    }

    private static String rapport(JsonNode research) {
        var sb = new StringBuilder("# Recherche ai-scientist (importée d'idea-generator)\n\n");
        ajouter(sb, "Verdict de nouveauté", texte(research, "noveltyVerdict"));
        ajouter(sb, "Résultat complet", texte(research, "jobUrl"));
        liste(sb, "Hypothèses", lignes(research.path("hypotheses")), false);
        String travaux = texte(research, "relatedWork");
        if (!travaux.isEmpty()) {
            sb.append("\n## Travaux connexes\n\n").append(travaux).append('\n');
        }
        liste(sb, "Risques et limites", lignes(research.path("riskFactors")), false);
        return sb.toString().strip();
    }

    private static void ajouter(StringBuilder sb, String libelle, String valeur) {
        if (!valeur.isEmpty()) {
            sb.append(libelle).append(" : ").append(valeur).append("\n\n");
        }
    }

    private static void liste(StringBuilder sb, String libelle, List<String> elements, boolean numerotee) {
        if (elements.isEmpty()) {
            return;
        }
        sb.append(libelle).append(" :\n");
        IntStream.range(0, elements.size()).forEach(i -> sb.append(numerotee ? (i + 1) + ". " : "- ").append(elements.get(i)).append('\n'));
        sb.append('\n');
    }

    private static List<String> lignes(JsonNode tableau) {
        List<String> out = new ArrayList<>();
        for (JsonNode n : tableau) {
            String t = n.asString("").strip();
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out;
    }

    private static String texte(JsonNode n, String champ) {
        return n.path(champ).asString("").strip();
    }

    private static String coupe(String t, int max) {
        return t.length() <= max ? t : t.substring(0, max - 1) + "…";
    }
}
