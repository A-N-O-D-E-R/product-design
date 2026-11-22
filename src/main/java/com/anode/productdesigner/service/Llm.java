package com.anode.productdesigner.service;

import java.io.IOException;
import java.net.ConnectException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.anode.ai.claude.ClaudeCodeException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.models.messages.OutputConfig;
import com.openai.errors.OpenAIServiceException;

import org.springframework.ai.anthropic.AnthropicCacheOptions;
import org.springframework.ai.anthropic.AnthropicCacheStrategy;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.anthropic.AnthropicWebSearchTool;
import org.springframework.ai.chat.messages.AssistantMessage.ToolCall;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.StructuredOutputChatOptions;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.exception.SdkClientException;

/**
 * Appels au LLM, quel que soit le fournisseur (`spring.ai.model.chat`) : claude-code (CLI Claude Code, cf. {@link ClaudeCode}),
 * ou via Spring AI anthropic, openai, bedrock-converse, ollama. Les outils ne sont jamais exécutés par Spring AI : les appels
 * reviennent à l'app. Seul Anthropic reçoit des options propres (effort, prompt caching, recherche web serveur).
 */
@Component
public class Llm {

    public static final String CLAUDE_CODE = "claude-code";

    public static final String CONFIG_ABSENTE = "Fournisseur LLM non configuré : régler spring.ai.model.chat (claude-code, anthropic, openai,"
            + " bedrock-converse ou ollama) et sa clé (ex. spring.ai.anthropic.api-key) dans ~/.productdesigner/application.yml, puis redémarrer.";

    public static final String SANS_RECHERCHE_WEB = "La research a besoin de la recherche web : configurer productdesigner.recherche.tavily-api-key"
            + " (tout fournisseur), ou utiliser le fournisseur anthropic. Sinon, passer cette Question en grilling.";

    /** Recherches web serveur par appel (max_uses, Anthropic). */
    public static final long RECHERCHES_SERVEUR_MAX = 15;

    public enum Effort { MOYEN, ELEVE }

    /** Reçoit le texte streamé. */
    public interface Deltas {
        void texte(String texte) throws IOException;
    }

    /**
     * Une réponse complète. fin : raison d'arrêt du fournisseur, en minuscules (end_turn, stop, tool_use, tool_calls,
     * pause_turn, refusal…) ; recherchesServeur : tours de recherche web serveur (Anthropic) ayant rendu des résultats.
     */
    public record Reponse(String texte, List<ToolCall> appels, String fin, String modele, long input, long output, long cacheRead,
                          long cacheWrite, int recherchesServeur) {

        /** Pause serveur (Anthropic) : le tour continue au prochain appel. */
        public boolean pause() {
            return fin.equals("pause_turn");
        }

        public boolean refus() {
            return List.of("refusal", "content_filter", "content_filtered", "guardrail_intervened").contains(fin);
        }

        /** Fin normale du texte, sans appel d'outil ni coupure. */
        public boolean terminee() {
            return appels.isEmpty() && List.of("end_turn", "stop", "stop_sequence").contains(fin);
        }
    }

    private final ObjectProvider<ChatModel> modeles;
    private final ObjectProvider<ClaudeCode> claudeCode;
    private final Environment env;
    private final Recherche recherche;
    private final String fournisseur;

    Llm(ObjectProvider<ChatModel> modeles, ObjectProvider<ClaudeCode> claudeCode, Environment env, Recherche recherche,
        @Value("${spring.ai.model.chat:claude-code}") String fournisseur) {
        this.modeles = modeles;
        this.claudeCode = claudeCode;
        this.env = env;
        this.recherche = recherche;
        this.fournisseur = fournisseur;
    }

    private boolean cli() {
        return fournisseur.equals(CLAUDE_CODE);
    }

    public String fournisseur() {
        return fournisseur;
    }

    /** Bedrock et Ollama : identifiants AWS ou serveur local vérifiés au premier appel ; claude-code : au premier appel aussi. */
    public boolean configure() {
        if (cli()) {
            return claudeCode.getIfAvailable() != null;
        }
        if (modeles.getIfAvailable() == null) {
            return false;
        }
        return switch (fournisseur) {
            case "anthropic" -> cle("spring.ai.anthropic.api-key", "ANTHROPIC_API_KEY");
            case "openai" -> cle("spring.ai.openai.api-key", "OPENAI_API_KEY");
            default -> true;
        };
    }

    private boolean cle(String propriete, String variable) {
        return !env.getProperty(propriete, env.getProperty(variable, "")).isBlank();
    }

    private ChatModel modele() {
        if (!configure()) {
            throw new ConfigManquanteException(CONFIG_ABSENTE);
        }
        return modeles.getObject();
    }

    /** Lève {@link ConfigManquanteException} si aucun fournisseur n'est utilisable. */
    public void verifier() {
        if (!configure()) {
            throw new ConfigManquanteException(CONFIG_ABSENTE);
        }
    }

    public String nomModele() {
        if (cli()) {
            ClaudeCode c = claudeCode.getIfAvailable();
            return c == null ? "" : c.model();
        }
        ChatModel m = modeles.getIfAvailable();
        String nom = m == null ? null : m.getDefaultOptions().getModel();
        return nom == null ? "" : nom;
    }

    /** Recherche web : WebSearch du CLI (claude-code), outil client (Tavily) sur tout autre fournisseur, sinon recherche serveur d'Anthropic. */
    public boolean rechercheWeb() {
        return cli() || recherche.disponible() || fournisseur.equals("anthropic");
    }

    /** Outils de recherche à proposer au modèle : l'outil client, ou rien (recherche serveur, cf. {@link #rechercheServeur()}). */
    public List<ToolCallback> outilsRecherche() {
        return recherche.disponible() && !cli() ? List.of(recherche) : List.of();
    }

    /** Recherche menée par le fournisseur lui-même : WebSearch du CLI, ou recherche serveur d'Anthropic sans Tavily. */
    public boolean rechercheServeur() {
        return cli() || !recherche.disponible() && fournisseur.equals("anthropic");
    }

    public String rechercher(String arguments) {
        return recherche.call(arguments);
    }

    /**
     * Un appel streamé. schema : JSON Schema de la sortie (décodage contraint natif du fournisseur), ou null.
     * rechercheServeur : ajoute la recherche web serveur d'Anthropic.
     */
    public Reponse stream(List<Message> messages, Effort effort, List<ToolCallback> outils, String schema, boolean rechercheServeur,
                          Deltas deltas) throws IOException {
        if (cli()) {
            verifier();
            return claudeCode.getObject().stream(messages, outils, schema, rechercheServeur, deltas);
        }
        ChatModel m = modele();
        ChatOptions.Builder<?> options = m.getDefaultOptions().mutate();
        if (!outils.isEmpty()) {
            ((ToolCallingChatOptions.Builder<?>) options).toolCallbacks(outils);
        }
        if (schema != null) {
            ((StructuredOutputChatOptions.Builder<?>) options).outputSchema(schema);
        }
        if (options instanceof AnthropicChatOptions.Builder a) {
            a.maxTokens(64000).effort(effort == Effort.ELEVE ? OutputConfig.Effort.HIGH : OutputConfig.Effort.MEDIUM)
                    // Cache du préfixe (système, outils, historique) d'un tour à l'autre.
                    .cacheOptions(AnthropicCacheOptions.builder().strategy(AnthropicCacheStrategy.CONVERSATION_HISTORY).build());
            if (rechercheServeur) {
                a.webSearchTool(AnthropicWebSearchTool.builder().maxUses(RECHERCHES_SERVEUR_MAX).build());
            }
        }
        if (options instanceof OpenAiChatOptions.Builder o) {
            o.streamUsage(true);
        }

        StringBuilder texte = new StringBuilder();
        List<ToolCall> appels = new ArrayList<>();
        String fin = "", modele = nomModele();
        long input = 0, output = 0, cacheRead = 0, cacheWrite = 0;
        int recherches = 0;
        for (ChatResponse r : m.stream(new Prompt(messages, options.build())).toIterable()) {
            for (var g : r.getResults()) {
                String t = g.getOutput().getText();
                if (t != null && !t.isEmpty()) {
                    texte.append(t);
                    deltas.texte(t);
                }
                appels.addAll(g.getOutput().getToolCalls());
                String raison = g.getMetadata().getFinishReason();
                if (raison != null && !raison.isBlank()) {
                    fin = raison.toLowerCase(Locale.ROOT);
                }
            }
            var meta = r.getMetadata();
            if (meta.getModel() != null && !meta.getModel().isBlank()) {
                modele = meta.getModel();
            }
            var usage = meta.getUsage();
            if (usage != null && (usage.getPromptTokens() > 0 || usage.getCompletionTokens() > 0)) {
                input = usage.getPromptTokens();
                output = usage.getCompletionTokens();
                cacheRead = usage.getCacheReadInputTokens() == null ? 0 : usage.getCacheReadInputTokens();
                cacheWrite = usage.getCacheWriteInputTokens() == null ? 0 : usage.getCacheWriteInputTokens();
            }
            if (meta.get("web-search-results") instanceof List<?> l && !l.isEmpty()) {
                recherches++;
            }
        }
        if (fin.isEmpty()) {
            // Connexion coupée en route : Spring AI termine le flux sans erreur, mais aucune raison d'arrêt n'est arrivée.
            throw new IllegalStateException("flux coupé avant la fin de la réponse");
        }
        return new Reponse(texte.toString(), List.copyOf(appels), fin, modele, input, output, cacheRead, cacheWrite, recherches);
    }

    /** Erreurs non récupérables en clair (D-30), d'après le statut HTTP du fournisseur quand il y en a un. */
    public static String messageErreur(Exception e) {
        String message = String.valueOf(e.getMessage());
        for (Throwable c = e; c != null; c = c.getCause()) {
            if (c instanceof SdkClientException) {
                return "Identifiants AWS indisponibles ou expirés (relancer aws sso login ?) : " + c.getMessage();
            }
            if (c instanceof ClaudeCodeException) {
                return messageClaudeCode(String.valueOf(c.getMessage()));
            }
            if (c instanceof ConnectException) {
                return "Fournisseur LLM injoignable (serveur Ollama démarré ? URL de base ?) : " + c.getMessage();
            }
        }
        return switch (statut(e)) {
            case 401 -> "Clé du fournisseur LLM invalide : vérifier sa clé (ex. spring.ai.anthropic.api-key) dans ~/.productdesigner/application.yml.";
            case 403 -> "Accès refusé par le fournisseur LLM (sur Bedrock : accès au modèle dans la console, permission"
                    + " bedrock:InvokeModelWithResponseStream). " + message;
            case 404 -> "Modèle indisponible pour ce compte ou cette région : régler le modèle du fournisseur"
                    + " (ex. spring.ai.anthropic.chat.options.model). " + message;
            case 400 -> message.contains("too long") || message.contains("context length")
                    ? "Requête trop longue pour le contexte du modèle : continue dans une nouvelle Session."
                    : "Requête refusée par le fournisseur LLM : " + message;
            default -> "Réponse interrompue : " + message;
        };
    }

    /** Erreurs du CLI Claude Code : pas de statut HTTP, seulement son message. */
    private static String messageClaudeCode(String m) {
        String bas = m.toLowerCase(Locale.ROOT);
        if (bas.contains("not logged in") || bas.contains("invalid api key") || bas.contains("authentication")
                || bas.contains("oauth") || bas.contains("401")) {
            return "Claude Code n'est pas authentifié : saisir un token (claude setup-token) dans l'application. " + m;
        }
        if (bas.contains("no such file") || bas.contains("cannot run program")) {
            return "CLI Claude Code introuvable : l'installer (npm install -g @anthropic-ai/claude-code). " + m;
        }
        if (bas.contains("too long")) {
            return "Requête trop longue pour le contexte du modèle : continue dans une nouvelle Session.";
        }
        return "Réponse interrompue : " + m;
    }

    /** Statut HTTP de l'erreur du fournisseur, 0 si ce n'en est pas une. */
    static int statut(Throwable e) {
        for (Throwable c = e; c != null; c = c.getCause()) {
            if (c instanceof AnthropicServiceException a) {
                return a.statusCode();
            }
            if (c instanceof OpenAIServiceException o) {
                return o.statusCode();
            }
            if (c instanceof AwsServiceException a) {
                return a.statusCode();
            }
            if (c instanceof RestClientResponseException r) {
                return r.getStatusCode().value();
            }
            if (c instanceof WebClientResponseException w) {
                return w.getStatusCode().value();
            }
        }
        return 0;
    }
}
