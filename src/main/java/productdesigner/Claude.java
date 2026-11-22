package productdesigner;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

import com.anode.ai.claude.ClaudeCodeAgent;
import com.anode.ai.claude.ClaudeCodeException;
import com.anode.ai.claude.ClaudeCodeOptions;
import com.anode.ai.claude.ClaudeCodeRequest;
import com.anode.ai.claude.ClaudeCodeResult;
import com.fasterxml.jackson.databind.JsonNode;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Appels à Claude par le CLI Claude Code (spring-ai-claude-code-starter). Chaque appel est un `claude -p` sans état :
 * la base reste la source de vérité de l'historique. Isolé de la configuration de la machine : aucun réglage
 * utilisateur ni plugin, aucun serveur MCP, aucun outil sauf la recherche web quand elle est demandée.
 */
@Component
public class Claude {

    /** Usage d'un appel, tous tours du CLI confondus ; modele : identifiant réel (ex. claude-opus-5-5). */
    record Usage(String modele, long input, long output, long cacheRead, long cacheWrite) {}

    private final ClaudeCodeAgent agent;
    private final String model;
    private final Path dossier;

    Claude(ClaudeCodeAgent agent,
           @Value("${spring.ai.claude-code.model:opus}") String model,
           @Value("${productdesigner.data-dir:${user.home}/.productdesigner}/claude-workspace") Path dossier) {
        this.agent = agent;
        this.model = model;
        this.dossier = dossier;
    }

    public String model() {
        return model;
    }

    /**
     * Un appel streamé. systeme remplace le prompt système de Claude Code ; schema (facultatif) impose la sortie
     * structurée, lue dans {@link ClaudeCodeResult#structuredOutput()}, le texte restant dans {@link ClaudeCodeResult#text()}.
     */
    ClaudeCodeResult appeler(String systeme, String prompt, String schema, boolean rechercheWeb, Consumer<String> texte) {
        try {
            Files.createDirectories(dossier);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        List<String> outils = rechercheWeb ? List.of("WebSearch") : List.of();
        var options = ClaudeCodeOptions.builder()
                .permissionMode("dontAsk")
                .builtinTools(outils)
                .allowedTools(outils)
                .strictMcpConfig(true)
                .settingSources("")
                .replaceSystemPrompt(true)
                .jsonSchema(schema)
                .build();
        ClaudeCodeResult resultat = agent.stream(new ClaudeCodeRequest(prompt, null, dossier, null, systeme, options), texte);
        if (resultat.isError()) {
            throw new ClaudeCodeException(resultat.raw().path("result").asText(String.valueOf(resultat.subtype())));
        }
        return resultat;
    }

    static Usage usage(ClaudeCodeResult resultat) {
        JsonNode u = resultat.raw().path("usage");
        var modeles = resultat.raw().path("modelUsage").fieldNames();
        return new Usage(modeles.hasNext() ? modeles.next() : resultat.model(), u.path("input_tokens").asLong(),
                u.path("output_tokens").asLong(), u.path("cache_read_input_tokens").asLong(),
                u.path("cache_creation_input_tokens").asLong());
    }
}
