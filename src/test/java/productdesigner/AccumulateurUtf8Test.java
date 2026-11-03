package productdesigner;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import com.anthropic.core.ObjectMappers;
import com.anthropic.helpers.MessageAccumulator;
import com.anthropic.models.messages.RawMessageStreamEvent;

import org.junit.jupiter.api.Test;

/** Reproduit le découpage de partial_json observé sur Bedrock (« s ingré » puis « dients »). */
class AccumulateurUtf8Test {

    @Test
    void accentsDansLInputDunToolStreame() throws Exception {
        List<String> evenements = List.of(
                "{\"type\":\"message_start\",\"message\":{\"id\":\"m\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"x\",\"content\":[],\"stop_reason\":null,\"stop_sequence\":null,\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}}",
                "{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"tool_use\",\"id\":\"t\",\"name\":\"note\",\"input\":{}}}",
                "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"phrase\\\": \\\"Le\"}}",
                "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"s ingré\"}}",
                "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"dients structu\"}}",
                "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"rés\\\"}\"}}",
                "{\"type\":\"content_block_stop\",\"index\":0}",
                "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"tool_use\",\"stop_sequence\":null},\"usage\":{\"output_tokens\":5}}",
                "{\"type\":\"message_stop\"}");
        MessageAccumulator acc = MessageAccumulator.create();
        for (String e : evenements) {
            acc.accumulate(ObjectMappers.jsonMapper().readValue(e, RawMessageStreamEvent.class));
        }
        String input = ObjectMappers.jsonMapper().writeValueAsString(acc.message().content().getFirst().asToolUse()._input());
        System.out.println("INPUT=" + input);
        assertThat(input).contains("Les ingrédients structurés");
    }

    @Test
    void toutesLesCoupuresAutourDUnAccent() throws Exception {
        String json = "{\"phrase\": \"ingrédients négociable\"}";
        StringBuilder rapport = new StringBuilder();
        for (int coupe = 1; coupe < json.length(); coupe++) {
            MessageAccumulator acc = MessageAccumulator.create();
            for (String e : List.of(
                    "{\"type\":\"message_start\",\"message\":{\"id\":\"m\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"x\",\"content\":[],\"stop_reason\":null,\"stop_sequence\":null,\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}}",
                    "{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"tool_use\",\"id\":\"t\",\"name\":\"note\",\"input\":{}}}")) {
                acc.accumulate(ObjectMappers.jsonMapper().readValue(e, RawMessageStreamEvent.class));
            }
            for (String morceau : List.of(json.substring(0, coupe), json.substring(coupe))) {
                var delta = ObjectMappers.jsonMapper().createObjectNode().put("type", "content_block_delta").put("index", 0);
                delta.putObject("delta").put("type", "input_json_delta").put("partial_json", morceau);
                acc.accumulate(ObjectMappers.jsonMapper().treeToValue(delta, RawMessageStreamEvent.class));
            }
            for (String e : List.of("{\"type\":\"content_block_stop\",\"index\":0}",
                    "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"tool_use\",\"stop_sequence\":null},\"usage\":{\"output_tokens\":5}}",
                    "{\"type\":\"message_stop\"}")) {
                acc.accumulate(ObjectMappers.jsonMapper().readValue(e, RawMessageStreamEvent.class));
            }
            var message = acc.message();
            String input = ObjectMappers.jsonMapper().writeValueAsString(message.content().getFirst().asToolUse()._input());
            String param = ObjectMappers.jsonMapper().writeValueAsString(message.toParam());
            String tree = ObjectMappers.jsonMapper().valueToTree(message.content().getFirst().asToolUse()._input()).toString();
            if (!input.contains("ingrédients négociable") || !param.contains("ingrédients négociable") || !tree.contains("ingrédients négociable")) {
                rapport.append("coupe ").append(coupe).append(" : input=").append(input).append(" tree=").append(tree).append("\n");
            }
        }
        System.out.println("RAPPORT=" + rapport);
        assertThat(rapport.toString()).isEmpty();
    }
}
