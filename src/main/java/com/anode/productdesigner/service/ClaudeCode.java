package com.anode.productdesigner.service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.anode.ai.claude.ClaudeCodeAgent;
import com.anode.ai.claude.ClaudeCodeException;
import com.anode.ai.claude.ClaudeCodeOptions;
import com.anode.ai.claude.ClaudeCodeRequest;
import com.anode.ai.claude.ClaudeCodeResult;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.AssistantMessage.ToolCall;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Fournisseur `claude-code` : chaque appel est un `claude -p` sans état (spring-ai-claude-code-starter), hors Spring AI.
 * L'historique est rejoué en texte, la base reste la source de vérité. `record` devient la sortie structurée (--json-schema)
 * et revient comme un appel d'outil, pour que le reste de l'app ne voie pas la différence. Le CLI ne peut pas appeler les
 * outils de l'app (Tavily, ai_scientist) : la recherche web est le WebSearch du CLI. Isolé de la configuration de la
 * machine : aucun réglage utilisateur ni plugin, aucun serveur MCP, aucun outil sauf WebSearch quand il est demandé.
 */
@Component
public class ClaudeCode {

    private final ClaudeCodeAgent agent;
    private final String model;
    private final Path dossier;

    ClaudeCode(ClaudeCodeAgent agent,
               @Value("${spring.ai.claude-code.model:opus}") String model,
               @Value("${user.home}/.productdesigner/claude-workspace") Path dossier) {
        this.agent = agent;
        this.model = model;
        this.dossier = dossier;
    }

    public String model() {
        return model;
    }

    Llm.Reponse stream(List<Message> messages, List<ToolCallback> outils, String schema, boolean rechercheWeb, Llm.Deltas deltas) {
        ToolCallback record = outils.stream().filter(o -> Record.NOM.equals(o.getToolDefinition().name())).findFirst().orElse(null);
        String systeme = systeme(messages, record);
        String jsonSchema = record != null ? record.getToolDefinition().inputSchema() : schema;
        List<String> outilsCli = rechercheWeb ? List.of("WebSearch") : List.of();
        var options = ClaudeCodeOptions.builder()
                .permissionMode("dontAsk")
                .builtinTools(outilsCli)
                .allowedTools(outilsCli)
                .strictMcpConfig(true)
                .settingSources("")
                .replaceSystemPrompt(true)
                .jsonSchema(jsonSchema)
                .build();
        try {
            Files.createDirectories(dossier);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        ClaudeCodeResult r = agent.stream(new ClaudeCodeRequest(prompt(messages), null, dossier, null, systeme, options), t -> {
            try {
                deltas.texte(t);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
        if (r.isError()) {
            throw new ClaudeCodeException(r.raw().path("result").asText(String.valueOf(r.subtype())));
        }
        String sortie = r.structuredOutput() == null ? null : r.structuredOutput().toString();
        String texte = r.text() == null ? "" : r.text();
        List<ToolCall> appels = new ArrayList<>();
        if (record != null && sortie != null) {
            appels.add(new ToolCall(Record.NOM + "_" + UUID.randomUUID().toString().replace("-", ""), "function", Record.NOM, sortie));
        } else if (record == null && schema != null) {
            // Sortie structurée hors record (Roadmap) : c'est elle que l'appelant lit comme texte.
            texte = sortie == null ? "" : sortie;
        }
        var u = r.raw().path("usage");
        var modeles = r.raw().path("modelUsage").fieldNames();
        return new Llm.Reponse(texte, List.copyOf(appels), appels.isEmpty() ? "end_turn" : "tool_use",
                modeles.hasNext() ? modeles.next() : r.model() == null ? model : r.model(),
                u.path("input_tokens").asLong(), u.path("output_tokens").asLong(), u.path("cache_read_input_tokens").asLong(),
                u.path("cache_creation_input_tokens").asLong(), u.path("server_tool_use").path("web_search_requests").asInt());
    }

    /** Messages système joints ; avec record, sa description, puisque le CLI ne voit pas de tool. */
    static String systeme(List<Message> messages, ToolCallback record) {
        StringBuilder sb = new StringBuilder();
        for (Message m : messages) {
            if (m instanceof SystemMessage) {
                sb.append(sb.isEmpty() ? "" : "\n\n").append(m.getText());
            }
        }
        if (record != null) {
            sb.append("\n\nIci, record n'est pas un tool à appeler : c'est la sortie structurée imposée de ta réponse (schéma JSON),"
                    + " produite après ton texte.\n").append(record.getToolDefinition().description());
        }
        return sb.toString();
    }

    /** Une seule demande : son texte. Sinon l'historique rejoué en texte, record compris (le CLI est sans état). */
    static String prompt(List<Message> messages) {
        List<Message> fil = messages.stream().filter(m -> !(m instanceof SystemMessage)).toList();
        if (fil.size() == 1 && !(fil.getFirst() instanceof AssistantMessage) && !(fil.getFirst() instanceof ToolResponseMessage)) {
            return fil.getFirst().getText();
        }
        StringBuilder sb = new StringBuilder("Conversation jusqu'ici. Réponds au dernier message de l'utilisateur.\n");
        for (Message m : fil) {
            if (m instanceof ToolResponseMessage t) {
                for (var reponse : t.getResponses()) {
                    if (!Record.NOM.equals(reponse.name())) {
                        sb.append("\n<tool_result name=\"").append(reponse.name()).append("\">\n")
                                .append(reponse.responseData()).append("\n</tool_result>\n");
                    }
                }
                continue;
            }
            String role = m instanceof AssistantMessage ? "assistant" : "user";
            sb.append("\n<").append(role).append(">\n").append(m.getText() == null ? "" : m.getText());
            if (m instanceof AssistantMessage a) {
                for (ToolCall appel : a.getToolCalls()) {
                    String balise = Record.NOM.equals(appel.name()) ? "record" : "tool_use name=\"" + appel.name() + "\"";
                    sb.append("\n<").append(balise).append(">").append(appel.arguments())
                            .append("</").append(Record.NOM.equals(appel.name()) ? "record" : "tool_use").append(">");
                }
            }
            sb.append("\n</").append(role).append(">\n");
        }
        return sb.toString();
    }
}
