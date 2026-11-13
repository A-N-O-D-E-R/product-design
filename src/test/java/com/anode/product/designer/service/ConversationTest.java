package com.anode.product.designer.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.mock.env.MockEnvironment;

class ConversationTest {

    @Test
    void messagesEnregistresAvantSpringAiRelusSansLesBlocsPropresAAnthropic() {
        var messages = Conversation.messages(List.of(
                "{\"role\":\"user\",\"content\":\"Salut\"}",
                """
                {"role":"assistant","content":[{"type":"thinking","thinking":"hmm","signature":"sig"},
                 {"type":"server_tool_use","id":"srv_1","name":"web_search","input":{}},
                 {"type":"web_search_tool_result","tool_use_id":"srv_1","content":[{"encrypted_content":"ENC"}]},
                 {"type":"text","text":"Voici Q1."},
                 {"type":"tool_use","id":"toolu_1","name":"record","input":{"decisions":[]}}]}""",
                """
                {"role":"user","content":[{"tool_use_id":"toolu_1","type":"tool_result","content":"Enregistré."},{"type":"text","text":"Q1: oui"}]}"""));

        assertThat(messages).hasSize(4);
        assertThat(messages.get(0)).isInstanceOf(UserMessage.class);
        var assistant = (AssistantMessage) messages.get(1);
        assertThat(assistant.getText()).isEqualTo("Voici Q1.");
        assertThat(assistant.getToolCalls()).singleElement()
                .satisfies(a -> assertThat(a.name() + " " + a.arguments()).isEqualTo("record {\"decisions\":[]}"));
        var resultat = ((ToolResponseMessage) messages.get(2)).getResponses().getFirst();
        assertThat(resultat.id() + " " + resultat.name() + " " + resultat.responseData()).isEqualTo("toolu_1 record Enregistré.");
        assertThat(messages.get(3).getText()).isEqualTo("Q1: oui");
    }

    @Test
    void rechercheWebSelonLeFournisseurEtLaCleTavily() {
        var sansModele = new StaticListableBeanFactory().getBeanProvider(org.springframework.ai.chat.model.ChatModel.class);
        var env = new MockEnvironment();
        assertThat(new Llm(sansModele, env, new Recherche("", "http://x"), "anthropic").rechercheServeur()).isTrue();
        assertThat(new Llm(sansModele, env, new Recherche("", "http://x"), "openai").rechercheWeb()).isFalse();
        var tavily = new Llm(sansModele, env, new Recherche("tvly", "http://x"), "anthropic");
        assertThat(tavily.rechercheWeb()).isTrue();
        assertThat(tavily.rechercheServeur()).isFalse();
        assertThat(tavily.outilsRecherche()).hasSize(1);
        assertThat(new Llm(sansModele, env, new Recherche("", "http://x"), "anthropic").configure()).isFalse();
    }
}
