package com.anode.product.designer.service;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Outil `web_search` exécuté par l'app (API Tavily) : la recherche web de la research sur n'importe quel fournisseur.
 * Une erreur revient au modèle en résultat d'outil, la research continue avec ce qu'elle a.
 */
@Component
public class Recherche implements ToolCallback {

    public static final String NOM = "web_search";

    private static final Logger log = LoggerFactory.getLogger(Recherche.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final ToolDefinition DEFINITION = DefaultToolDefinition.builder()
            .name(NOM)
            .description("Recherche sur le web. Renvoie, pour chaque résultat, titre, URL et extrait. Cite les URL des sources dans ton rapport.")
            .inputSchema("""
                    {"type":"object","properties":{"query":{"type":"string","description":"Requête de recherche, précise"}},"required":["query"]}""")
            .build();

    private final String cle;
    private final RestClient tavily;

    Recherche(@Value("${productdesigner.recherche.tavily-api-key:}") String cle,
              @Value("${productdesigner.recherche.tavily-url:https://api.tavily.com}") String url) {
        this.cle = cle.strip();
        this.tavily = RestClient.builder().baseUrl(url).build();
    }

    public boolean disponible() {
        return !cle.isEmpty();
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return DEFINITION;
    }

    @Override
    public String call(String arguments) {
        String requete = JSON.readTree(arguments).path("query").asString("").strip();
        if (requete.isEmpty()) {
            return "Requête vide : précise query.";
        }
        try {
            JsonNode r = tavily.post().uri("/search").header("Authorization", "Bearer " + cle)
                    .body(Map.of("query", requete, "max_results", 5))
                    .retrieve().body(JsonNode.class);
            StringBuilder sb = new StringBuilder();
            for (JsonNode resultat : r == null ? JSON.createArrayNode() : r.path("results")) {
                sb.append("## ").append(resultat.path("title").asString("")).append("\n")
                        .append(resultat.path("url").asString("")).append("\n")
                        .append(resultat.path("content").asString("")).append("\n\n");
            }
            return sb.isEmpty() ? "Aucun résultat." : sb.toString().strip();
        } catch (RuntimeException e) {
            log.warn("Échec de la recherche web « {} »", requete, e);
            return "Recherche impossible : " + e.getMessage();
        }
    }
}
