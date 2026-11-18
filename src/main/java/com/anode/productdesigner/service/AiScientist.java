package com.anode.productdesigner.service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Outil `ai_scientist` : pour une question technique ou de machine learning, un job d'ideation d'ai-scientist (`POST /v1/ideations`,
 * puis `GET /v1/jobs/{id}` jusqu'à sa fin) : idées de recherche avec hypothèse, travaux connexes (recherche bibliographique Semantic
 * Scholar) et risques. Client `RestClient` simple, le contrat est l'OpenAPI d'ai-scientist (`AiScientistContratTest`). Authentifié par une
 * clé `ais_...` (`productdesigner.ai-scientist.api-key`) ; le coût est plafonné par `budget-usd`.
 * <p>
 * Un échec ou un délai revient au modèle en résultat d'outil : la research continue avec ce qu'elle a. Le résultat complet est aussi
 * enregistré comme Artefact de la Session par {@link SessionService}.
 */
@Component
public class AiScientist implements ToolCallback {

    public static final String NOM = "ai_scientist";

    /** Résultat rendu au modèle : l'Artefact garde tout. */
    static final int SORTIE_MAX = 14_000;

    private static final Logger log = LoggerFactory.getLogger(AiScientist.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final ToolDefinition DEFINITION = DefaultToolDefinition.builder()
            .name(NOM)
            .description("""
                    Pour une question technique ou de machine learning : demande à un chercheur IA (AI Scientist) de proposer des idées de \
                    recherche sur un sujet, avec pour chacune une hypothèse, les travaux connexes (recherche bibliographique) et les risques. \
                    LENT (plusieurs minutes) et PAYANT : au plus une fois par recherche, seulement si la Question porte sur un problème \
                    technique ou ML où l'état de l'art compte ; pour tout le reste, utilise web_search. \
                    topic : le sujet, au format « # Title: … », « ## Keywords », « ## TL;DR », « ## Abstract » (court).""")
            .inputSchema("""
                    {"type":"object","properties":{"topic":{"type":"string","description":"Le sujet de recherche, précis, au format Title / Keywords / TL;DR / Abstract"}},"required":["topic"]}""")
            .build();

    /** Ce que l'app fait d'un appel : le texte rendu au modèle, et l'Artefact à enregistrer (null si le job n'a rien donné). */
    public record Resultat(String sortie, String artefact) {}

    private final String url;
    private final String cle;
    private final int generations;
    private final double budget;
    private final Duration delai;
    private final Duration intervalle;
    private final RestClient client;

    AiScientist(@Value("${productdesigner.ai-scientist.url:}") String url,
                @Value("${productdesigner.ai-scientist.api-key:}") String cle,
                @Value("${productdesigner.ai-scientist.max-generations:3}") int generations,
                @Value("${productdesigner.ai-scientist.budget-usd:2}") double budget,
                @Value("${productdesigner.ai-scientist.timeout:20m}") Duration delai,
                @Value("${productdesigner.ai-scientist.poll-interval:10s}") Duration intervalle) {
        this.url = url.strip().replaceAll("/+$", "");
        this.cle = cle.strip();
        this.generations = generations;
        this.budget = budget;
        this.delai = delai;
        this.intervalle = intervalle;
        this.client = RestClient.builder().baseUrl(this.url.isEmpty() ? "http://invalide.local" : this.url)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + this.cle).build();
    }

    public boolean disponible() {
        return !url.isEmpty() && !cle.isEmpty();
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return DEFINITION;
    }

    @Override
    public String call(String arguments) {
        return executer(arguments, p -> { }).sortie();
    }

    /** Lance le job et attend sa fin ; progression reçoit un texte d'avancement à chaque relevé. */
    public Resultat executer(String arguments, Consumer<String> progression) {
        String sujet = JSON.readTree(arguments).path("topic").asString("").strip();
        if (sujet.isEmpty()) {
            return new Resultat("Sujet vide : précise topic.", null);
        }
        try {
            JsonNode cree = client.post().uri("/v1/ideations")
                    .body(Map.of("topic", sujet, "maxGenerations", generations, "budgetUsd", budget))
                    .retrieve().body(JsonNode.class);
            String id = cree == null ? "" : cree.path("id").asString("");
            if (id.isEmpty()) {
                return new Resultat("AI Scientist n'a pas renvoyé d'identifiant de job.", null);
            }
            String lienJob = url + "/v1/jobs/" + id;
            Instant limite = Instant.now().plus(delai);
            while (true) {
                JsonNode job = client.get().uri("/v1/jobs/{id}", id).retrieve().body(JsonNode.class);
                String statut = job == null ? "" : job.path("status").asString("");
                String etape = job == null ? "" : job.path("stage").asString("");
                progression.accept("AI Scientist : " + statut.toLowerCase() + (etape.isEmpty() ? "" : " (" + etape + ")"));
                switch (statut) {
                    case "DONE" -> {
                        String md = rendre(sujet, lienJob, job.path("result"));
                        String sortie = md.length() <= SORTIE_MAX ? md : md.substring(0, SORTIE_MAX) + "\n\n[… tronqué ; le résultat complet est dans l'Artefact de la Session]";
                        return new Resultat("Résultat de l'AI Scientist (job " + id + ") :\n\n" + sortie, md);
                    }
                    case "FAILED", "CANCELLED", "REJECTED" -> {
                        return new Resultat("AI Scientist : le job a fini en " + statut.toLowerCase() + detail(job) + ". Continue sans lui.", null);
                    }
                    case "PAUSED" -> {
                        return new Resultat("AI Scientist : le job est en pause" + detail(job) + " (budget ou clé ?). Continue sans lui ; reprise possible côté ai-scientist : " + lienJob, null);
                    }
                    default -> { }
                }
                if (Instant.now().isAfter(limite)) {
                    return new Resultat("AI Scientist : pas de résultat après " + delai.toMinutes() + " minutes ; le job continue côté ai-scientist ("
                            + lienJob + "). Continue sans lui.", null);
                }
                Thread.sleep(intervalle);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Resultat("AI Scientist : attente interrompue.", null);
        } catch (RestClientResponseException e) {
            log.warn("AI Scientist a répondu {}", e.getStatusCode());
            return new Resultat("AI Scientist a refusé la demande (" + e.getStatusCode().value() + ")" + problem(e) + ". Continue sans lui.", null);
        } catch (RuntimeException e) {
            log.warn("AI Scientist injoignable", e);
            return new Resultat("AI Scientist injoignable : " + e.getMessage() + ". Continue sans lui.", null);
        }
    }

    private static String detail(JsonNode job) {
        String raison = job.path("error").asString(job.path("pauseReason").asString(""));
        return raison.isBlank() ? "" : " : " + raison;
    }

    /** Le détail d'un problème RFC 9457 d'ai-scientist (`detail`, à défaut `title`). */
    private static String problem(RestClientResponseException e) {
        try {
            JsonNode p = JSON.readTree(e.getResponseBodyAsString());
            String texte = p.path("detail").asString(p.path("title").asString(""));
            return texte.isBlank() ? "" : " : " + texte;
        } catch (RuntimeException ignore) {
            return "";
        }
    }

    /** Les idées de l'ideation (noms de champs d'AI-Scientist-v2) en markdown. */
    static String rendre(String sujet, String lienJob, JsonNode idees) {
        StringBuilder sb = new StringBuilder("# AI Scientist : idées de recherche\n\n");
        sb.append("Sujet :\n\n").append(sujet.lines().map(l -> "> " + l).reduce((a, b) -> a + "\n" + b).orElse("")).append("\n\n");
        sb.append("Job : ").append(lienJob).append("\n");
        int n = 0;
        for (JsonNode idee : idees) {
            sb.append("\n## ").append(++n).append(". ").append(idee.path("Title").asString(idee.path("Name").asString("Idée"))).append("\n\n");
            champ(sb, "Hypothèse", idee.path("Short Hypothesis"));
            champ(sb, "Travaux connexes", idee.path("Related Work"));
            if (idee.path("Experiments").isArray() && !idee.path("Experiments").isEmpty()) {
                sb.append("**Expériences :**\n\n");
                idee.path("Experiments").forEach(e -> sb.append("- ").append(e.asString("")).append('\n'));
                sb.append('\n');
            }
            champ(sb, "Risques et limites", idee.path("Risk Factors and Limitations"));
            champ(sb, "Résumé", idee.path("Abstract"));
        }
        if (n == 0) {
            sb.append("\nAucune idée n'a été produite.\n");
        }
        return sb.toString().strip();
    }

    private static void champ(StringBuilder sb, String nom, JsonNode valeur) {
        String texte = valeur.asString("").strip();
        if (!texte.isEmpty()) {
            sb.append("**").append(nom).append(".** ").append(texte).append("\n\n");
        }
    }
}
