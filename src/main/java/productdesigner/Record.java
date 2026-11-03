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

/** Tool `record` : sorties structurées d'un tour d'Interview, au schéma unique commun à toutes les Méthodes (D-16), non strict. */
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
            // Pas de strict : en décodage contraint, le modèle échappe les accents en \\u00XX et glisse parfois des
            // \\n parasites à leur place (observé sur Bedrock / Opus 5). La lecture de l'input est tolérante (path()).
            .build();

    /** Consigne ajoutée au prompt de la Méthode, selon les champs déclarés dans son frontmatter. */
    static String consigne(List<String> outputs) {
        return "\n\nÀ la fin de chaque réponse, appelle le tool record. Champs utilisés par cette Méthode : "
                + String.join(", ", outputs) + ". Laisse les autres listes vides (map.destination et prototype_html : chaînes vides). L'interface affiche le round en carte "
                + "à partir de record : ton texte se limite à l'introduction et aux commentaires, sans recopier les questions.";
    }

    /**
     * Écarts au schéma (le tool n'est pas strict) dans les éléments présents : champs obligatoires absents ou vides.
     * Une liste ou un objet de haut niveau absent vaut « rien à enregistrer ». Liste vide si l'input est exploitable.
     */
    static List<String> erreurs(JsonNode input) {
        List<String> erreurs = new java.util.ArrayList<>();
        int i = 0;
        for (JsonNode q : input.path("round").path("questions")) {
            i++;
            for (String champ : List.of("id", "titre", "corps", "recommandation")) {
                if (q.path(champ).asText("").isBlank()) {
                    erreurs.add("round.questions[" + i + "]." + champ + " vide ou absent");
                }
            }
        }
        i = 0;
        for (JsonNode d : input.path("decisions")) {
            i++;
            if (d.path("texte").asText("").isBlank()) {
                erreurs.add("decisions[" + i + "].texte vide ou absent");
            }
        }
        i = 0;
        for (JsonNode q : input.path("questions")) {
            i++;
            if (q.path("titre").asText("").isBlank()) {
                erreurs.add("questions[" + i + "].titre vide ou absent");
            }
            if (!List.of("grilling", "research", "prototype", "tache").contains(q.path("type").asText(""))) {
                erreurs.add("questions[" + i + "].type invalide");
            }
        }
        return erreurs;
    }

    static String relanceInvalide(List<String> erreurs) {
        return "Ton appel à record ne respecte pas le schéma : " + String.join(" ; ", erreurs)
                + ". Rappelle record avec un input complet (chaque question de round a son champ recommandation à part)."
                + " Ne répète pas le texte.";
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
