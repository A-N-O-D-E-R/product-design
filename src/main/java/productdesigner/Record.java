package productdesigner;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.anthropic.core.JsonValue;
import com.anthropic.core.ObjectMappers;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.ToolUseBlock;
import com.fasterxml.jackson.databind.JsonNode;

/** Tool `record` : sorties structurées d'un tour d'Interview, au schéma unique commun à toutes les Méthodes (D-16). */
final class Record {

    static final String NOM = "record";

    static final String RELANCE = "Appelle maintenant le tool record pour structurer ta réponse précédente."
            + " Ne répète pas le texte.";

    private Record() {}

    private static Map<String, Object> objet(Map<String, Object> properties) {
        return Map.of("type", "object", "properties", properties,
                "required", List.copyOf(properties.keySet()), "additionalProperties", false);
    }

    private static Map<String, Object> liste(Map<String, Object> item) {
        return Map.of("type", "array", "items", item);
    }

    private static final Map<String, Object> TEXTE = Map.of("type", "string");

    static final Tool TOOL = Tool.builder()
            .name(NOM)
            .description("""
                    Enregistre les sorties structurées de ta réponse. Appelle-le une fois, à la fin de chaque réponse, \
                    après ton texte ; listes vides si rien à enregistrer.
                    - round.questions : les questions du round que tu viens de poser, id "Q1", "Q2"…, \
                    chacune avec ta recommandation.
                    - decisions : les décisions que l'utilisateur vient de trancher, formulées en une phrase autonome.
                    - questions : les Questions nouvelles à ajouter à la Map (type grilling, research, prototype ou tache) ; \
                    blocked_by liste les titres exacts des Questions qui doivent être tranchées avant.
                    - map : destination (chaîne vide si inchangée), brouillard et hors_perimetre à ajouter.
                    - prototype_html : document HTML complet et autonome du prototype ; chaîne vide sinon.""")
            .inputSchema(Tool.InputSchema.builder()
                    .properties(JsonValue.from(Map.of(
                            "round", objet(Map.of("questions", liste(objet(Map.of(
                                    "id", TEXTE, "titre", TEXTE, "corps", TEXTE, "recommandation", TEXTE))))),
                            "decisions", liste(objet(Map.of("texte", TEXTE))),
                            "questions", liste(objet(Map.of(
                                    "titre", TEXTE, "corps", TEXTE, "blocked_by", liste(TEXTE),
                                    "type", Map.of("type", "string",
                                            "enum", List.of("grilling", "research", "prototype", "tache"))))),
                            "map", objet(Map.of(
                                    "destination", TEXTE, "brouillard", liste(TEXTE), "hors_perimetre", liste(TEXTE))),
                            "prototype_html", TEXTE)))
                    .required(List.of("round", "decisions", "questions", "map", "prototype_html"))
                    .putAdditionalProperty("additionalProperties", JsonValue.from(false))
                    .build())
            .strict(true)
            .build();

    /** Consigne ajoutée au prompt de la Méthode, selon les champs déclarés dans son frontmatter. */
    static String consigne(List<String> outputs) {
        return "\n\nÀ la fin de chaque réponse, appelle le tool record. Champs utilisés par cette Méthode : "
                + String.join(", ", outputs) + ". Laisse les autres listes vides (map.destination et prototype_html : chaînes vides). L'interface affiche le round en carte "
                + "à partir de record : ton texte se limite à l'introduction et aux commentaires, sans recopier les questions.";
    }

    static Optional<ToolUseBlock> appel(Message message) {
        return message.content().stream()
                .flatMap(b -> b.toolUse().stream())
                .filter(t -> NOM.equals(t.name()))
                .findFirst();
    }

    static JsonNode input(ToolUseBlock appel) {
        return ObjectMappers.jsonMapper().valueToTree(appel._input());
    }
}
