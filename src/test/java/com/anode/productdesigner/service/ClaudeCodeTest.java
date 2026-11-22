package com.anode.productdesigner.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.anode.ai.claude.ClaudeCodeRequest;
import com.anode.ai.claude.ClaudeCodeResult;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.AssistantMessage.ToolCall;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

/** Fournisseur claude-code contre un faux CLI : historique rejoué en texte, record en sortie structurée revenue en appel d'outil. */
class ClaudeCodeTest {

    final ObjectMapper json = new ObjectMapper();

    @Test
    void recordDevientSortieStructureeEtRevientEnAppel(@TempDir Path dossier) throws Exception {
        List<ClaudeCodeRequest> requetes = new ArrayList<>();
        var raw = json.readTree("""
                {"result":"{}","usage":{"input_tokens":10,"output_tokens":5,"cache_read_input_tokens":2,
                 "server_tool_use":{"web_search_requests":3}},"modelUsage":{"claude-opus-5-5":{}}}""");
        var sortie = json.readTree("{\"decisions\":[{\"texte\":\"D\"}]}");
        var claude = new ClaudeCode(new com.anode.ai.claude.ClaudeCodeAgent() {
            @Override
            public ClaudeCodeResult run(ClaudeCodeRequest r) {
                throw new UnsupportedOperationException();
            }

            @Override
            public ClaudeCodeResult stream(ClaudeCodeRequest r, java.util.function.Consumer<String> onText) {
                requetes.add(r);
                onText.accept("Bonjour");
                return new ClaudeCodeResult("Bonjour", "s", "opus", false, raw, null, 1, 1L, "success", sortie);
            }
        }, "opus", dossier);

        StringBuilder recu = new StringBuilder();
        var messages = List.<org.springframework.ai.chat.messages.Message>of(new SystemMessage("Méthode"), new UserMessage("Q ?"),
                AssistantMessage.builder().content("R").toolCalls(List.of(new ToolCall("r1", "function", Record.NOM, "{\"a\":1}"))).build(),
                ToolResponseMessage.builder().responses(List.of(new ToolResponseMessage.ToolResponse("r1", Record.NOM, "Enregistré."))).build(),
                new UserMessage("Oui"));
        Llm.Reponse r = claude.stream(messages, List.of(Record.TOOL), null, true, recu::append);

        assertThat(recu).hasToString("Bonjour");
        assertThat(Record.appel(r)).hasValueSatisfying(a -> assertThat(a.arguments()).isEqualTo(sortie.toString()));
        assertThat(r.texte()).isEqualTo("Bonjour");
        assertThat(r.modele()).isEqualTo("claude-opus-5-5");
        assertThat(r.input()).isEqualTo(10);
        assertThat(r.recherchesServeur()).isEqualTo(3);

        ClaudeCodeRequest q = requetes.getFirst();
        assertThat(q.systemPrompt()).startsWith("Méthode").contains("sortie structurée imposée");
        assertThat(q.prompt()).contains("<user>\nQ ?", "<record>{\"a\":1}</record>", "<user>\nOui").doesNotContain("Enregistré.");
        assertThat(q.options().jsonSchema()).contains("\"decisions\"");
        assertThat(q.options().builtinTools()).containsExactly("WebSearch");
    }
}
