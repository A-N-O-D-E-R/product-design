package com.anode.product.designer.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.AssistantMessage.ToolCall;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Messages persistés (`message.contenu`), indépendants du fournisseur : `{"role", "content"}` où content est une chaîne
 * ou une liste de blocs `text`, `tool_use` {id, name, input} et `tool_result` {tool_use_id, content}. Les autres blocs
 * des messages enregistrés avant Spring AI (thinking, recherche web serveur) sont ignorés à la relecture.
 */
public final class Conversation {

    public static final JsonMapper JSON = JsonMapper.builder().build();

    private Conversation() {}

    public static String utilisateur(String texte) {
        return JSON.createObjectNode().put("role", "user").put("content", texte).toString();
    }

    /** Message utilisateur en blocs (résultats d'outils, puis texte éventuel). */
    public static String utilisateur(ArrayNode blocs) {
        ObjectNode m = JSON.createObjectNode().put("role", "user");
        m.set("content", blocs);
        return m.toString();
    }

    public static ObjectNode resultat(String toolUseId, String contenu) {
        return JSON.createObjectNode().put("type", "tool_result").put("tool_use_id", toolUseId).put("content", contenu);
    }

    public static ObjectNode texteBloc(String texte) {
        return JSON.createObjectNode().put("type", "text").put("text", texte);
    }

    /** Réponse partielle (coupée en route) : son texte seul. */
    public static String assistant(String texte) {
        return JSON.createObjectNode().put("role", "assistant").put("content", texte).toString();
    }

    public static String assistant(Llm.Reponse r) {
        ArrayNode blocs = JSON.createArrayNode();
        if (!r.texte().isEmpty()) {
            blocs.add(texteBloc(r.texte()));
        }
        for (ToolCall a : r.appels()) {
            ObjectNode bloc = blocs.addObject().put("type", "tool_use").put("id", a.id()).put("name", a.name());
            bloc.set("input", Record.input(a));
        }
        ObjectNode m = JSON.createObjectNode().put("role", "assistant");
        m.set("content", blocs);
        return m.toString();
    }

    public static JsonNode contenu(String json) {
        return JSON.readTree(json).path("content");
    }

    /** Blocs `tool_use` d'un message. */
    public static List<JsonNode> appels(String json) {
        List<JsonNode> appels = new ArrayList<>();
        for (JsonNode bloc : contenu(json)) {
            if ("tool_use".equals(bloc.path("type").asString())) {
                appels.add(bloc);
            }
        }
        return appels;
    }

    /** Texte affichable : contenu chaîne, ou concaténation des blocs `text` (outils et résultats exclus). */
    public static String texte(String json) {
        return texte(contenu(json));
    }

    private static String texte(JsonNode content) {
        if (content.isString()) {
            return content.asString();
        }
        StringBuilder sb = new StringBuilder();
        for (JsonNode bloc : content) {
            if ("text".equals(bloc.path("type").asString())) {
                sb.append(bloc.path("text").asString());
            }
        }
        return sb.toString();
    }

    /** Historique persisté vers les messages Spring AI, dans l'ordre. */
    public static List<Message> messages(List<String> contenus) {
        List<Message> messages = new ArrayList<>();
        Map<String, String> noms = new HashMap<>();
        for (String json : contenus) {
            JsonNode m = JSON.readTree(json);
            JsonNode content = m.path("content");
            String texte = texte(content);
            if (m.path("role").asString().equals("assistant")) {
                List<ToolCall> appels = new ArrayList<>();
                for (JsonNode a : appels(json)) {
                    noms.put(a.path("id").asString(), a.path("name").asString());
                    appels.add(new ToolCall(a.path("id").asString(), "function", a.path("name").asString(), a.path("input").toString()));
                }
                if (!texte.isEmpty() || !appels.isEmpty()) {
                    messages.add(AssistantMessage.builder().content(texte).toolCalls(appels).build());
                }
                continue;
            }
            List<ToolResponseMessage.ToolResponse> resultats = new ArrayList<>();
            for (JsonNode bloc : content.isArray() ? content : JSON.createArrayNode()) {
                if ("tool_result".equals(bloc.path("type").asString())) {
                    String id = bloc.path("tool_use_id").asString();
                    resultats.add(new ToolResponseMessage.ToolResponse(id, noms.getOrDefault(id, ""), texte(bloc.path("content"))));
                }
            }
            if (!resultats.isEmpty()) {
                messages.add(ToolResponseMessage.builder().responses(resultats).build());
            }
            if (!texte.isEmpty()) {
                messages.add(new UserMessage(texte));
            }
        }
        return messages;
    }
}
