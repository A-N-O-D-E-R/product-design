package productdesigner;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.anthropic.client.AnthropicClient;
import com.anthropic.core.ObjectMappers;
import com.anthropic.core.http.StreamResponse;
import com.anthropic.errors.BadRequestException;
import com.anthropic.errors.UnauthorizedException;
import com.anthropic.helpers.MessageAccumulator;
import com.anthropic.models.messages.CacheControlEphemeral;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.RawMessageStreamEvent;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.TextBlockParam;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.anthropic.models.messages.WebSearchTool20260209;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
class SessionController {

    private static final Logger log = LoggerFactory.getLogger(SessionController.class);
    private static final JsonMapper JSON = ObjectMappers.jsonMapper();

    /** questionId et question (titre) : null pour la Session qui trace la Map. */
    /** tache, progression : null hors Sessions research (D-26). */
    record Session(long id, long projetId, String methode, String statut, String createdAt, Long questionId, String question,
                   Long continuedFrom, String tache, String progression) {}

    record Artefact(long id, String type, String contenu, String createdAt) {}

    /** Reçoit le texte streamé d'un tour. */
    interface Deltas {
        void texte(String texte) throws IOException;
    }

    record Contexte(long tokens, long fenetre, double remplissage, boolean seuilAtteint) {}

    record NouvelleSession(String methode) {}

    // round : Map/List simples, car Spring sérialise avec Jackson 3 et le SDK fournit du Jackson 2.
    record Affichage(long id, String role, String texte, Object round, boolean incomplet) {}

    record SessionDetail(Session session, String projet, List<Affichage> messages) {}

    record NouveauMessage(String texte) {}

    private record Ligne(long id, String role, String contenu, boolean visible, boolean incomplet) {}

    private final JdbcClient jdbc;
    private final Anthropic anthropic;
    private final Methodes methodes;
    private final MapController map;
    private final long fenetre;
    private final double seuil;

    SessionController(JdbcClient jdbc, Anthropic anthropic, Methodes methodes, MapController map,
                      @Value("${productdesigner.contexte.fenetre:1000000}") long fenetre,
                      @Value("${productdesigner.contexte.seuil:0.8}") double seuil) {
        this.jdbc = jdbc;
        this.anthropic = anthropic;
        this.methodes = methodes;
        this.map = map;
        this.fenetre = fenetre;
        this.seuil = seuil;
    }

    @GetMapping("/api/projets/{projetId}/sessions")
    List<Session> lister(@PathVariable long projetId) {
        return jdbc.sql("""
                        SELECT s.id, s.projet_id, s.methode, s.statut, s.created_at, s.question_id, q.titre AS question, s.continued_from,
                               s.tache, s.progression
                        FROM session s LEFT JOIN question q ON q.id = s.question_id
                        WHERE s.projet_id = ? ORDER BY s.id DESC""")
                .param(projetId).query(Session.class).list();
    }

    @PostMapping("/api/projets/{projetId}/sessions")
    @ResponseStatus(HttpStatus.CREATED)
    Session creer(@PathVariable long projetId, @RequestBody NouvelleSession nouvelle) {
        methodes.get(nouvelle.methode());
        projet(projetId);
        long id = jdbc.sql("INSERT INTO session (projet_id, methode) VALUES (?, ?) RETURNING id")
                .params(projetId, nouvelle.methode()).query(Long.class).single();
        return session(id);
    }

    static final String RESEARCH = "Mène la recherche sur la Question à résoudre, puis rédige le rapport final.";

    /** Pauses serveur (pause_turn) relancées au plus : au-delà, la research échoue. */
    private static final int PAUSES_MAX = 10;

    /** Recherches web par appel (max_uses). */
    private static final long RECHERCHES_MAX = 15;

    /**
     * Research en tâche de fond (D-26) : rend la main tout de suite, écrit sa progression en base. Chaque pause_turn
     * est relancée avec l'historique tel quel (blocs encrypted_content compris). Le dernier message donne l'Artefact.
     */
    void research(AnthropicClient client, Session session) {
        long id = session.id();
        Thread.ofVirtual().start(() -> {
            try {
                Message message = tour(client, session, t -> {});
                int recherches = recherches(message);
                for (int pauses = 0; message.stopReason().filter(StopReason.PAUSE_TURN::equals).isPresent(); pauses++) {
                    if (pauses == PAUSES_MAX) {
                        throw new IllegalStateException("trop de pauses serveur");
                    }
                    progression(id, "Recherche en cours : " + recherches + " recherche(s) web…");
                    message = tour(client, session, t -> {});
                    recherches += recherches(message);
                }
                String rapport = message.content().stream().flatMap(b -> b.text().stream()).map(t -> t.text())
                        .reduce("", String::concat).strip();
                if (rapport.isEmpty()) {
                    throw new IllegalStateException("aucun rapport rédigé");
                }
                jdbc.sql("INSERT INTO artefact (session_id, type, contenu) VALUES (?, 'research_md', ?)").params(id, rapport).update();
                Record.appel(message).ifPresent(a -> map.appliquer(session.projetId(), Record.input(a)));
                jdbc.sql("UPDATE session SET tache = 'terminee', progression = ? WHERE id = ?")
                        .params("Terminée : " + recherches + " recherche(s) web.", id).update();
            } catch (Exception e) {
                log.warn("Échec de la research de la Session {}", id, e);
                jdbc.sql("UPDATE session SET tache = 'echec', progression = ? WHERE id = ?").params(messageErreur(e), id).update();
            }
        });
    }

    /** Au démarrage, une research restée en cours a perdu son thread : elle passe en échec, relançable (E-16). */
    @EventListener(ApplicationReadyEvent.class)
    void interrompreResearches() {
        int n = jdbc.sql("UPDATE session SET tache = 'echec', progression = ? WHERE tache = 'en_cours'")
                .param("Interrompue par un redémarrage de l'application.").update();
        if (n > 0) {
            log.info("{} research(s) interrompue(s) par le redémarrage", n);
        }
    }

    /** Relance une research en échec depuis zéro : seule la consigne de départ est gardée. */
    @PostMapping("/api/sessions/{id}/relancer")
    @Transactional
    public Session relancer(@PathVariable long id) {
        Session session = session(id);
        if (!"echec".equals(session.tache()) || !session.statut().equals("active")) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Seule une research en échec se relance.");
        }
        var client = anthropic.client();
        jdbc.sql("DELETE FROM message WHERE session_id = ? AND id > (SELECT min(id) FROM message WHERE session_id = ?)")
                .params(id, id).update();
        jdbc.sql("UPDATE session SET tache = 'en_cours', progression = 'Relancée…' WHERE id = ?").param(id).update();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                research(client, session(id));
            }
        });
        return session(id);
    }

    private static int recherches(Message message) {
        return (int) message.content().stream().filter(b -> b.webSearchToolResult().isPresent()).count();
    }

    private void progression(long id, String texte) {
        jdbc.sql("UPDATE session SET progression = ? WHERE id = ?").params(texte, id).update();
    }

    @GetMapping("/api/sessions/{id}/artefacts")
    List<Artefact> artefacts(@PathVariable long id) {
        session(id);
        return jdbc.sql("SELECT id, type, contenu, created_at FROM artefact WHERE session_id = ? ORDER BY id")
                .param(id).query(Artefact.class).list();
    }

    /** Méthode appliquée selon le type de la Question (D-4). Une tâche se mène en conversation. */
    static String methode(String type) {
        return switch (type) {
            case "research" -> "research";
            case "prototype" -> "prototype";
            default -> "grilling";
        };
    }

    /** Ouvre une Question de la frontière : Session avec la Méthode de son type, Question `claimed` (E-6). */
    @PostMapping("/api/projets/{projetId}/questions/{questionId}/sessions")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public Session ouvrir(@PathVariable long projetId, @PathVariable long questionId) {
        String type = jdbc.sql("SELECT type FROM question WHERE id = ? AND projet_id = ?").params(questionId, projetId)
                .query(String.class).optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Question introuvable."));
        if (!map.frontiere(projetId).contains(questionId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Cette Question n'est pas dans la frontière (déjà prise, résolue ou bloquée).");
        }
        String methode = methode(type);
        methodes.get(methode);
        jdbc.sql("UPDATE question SET statut = 'claimed' WHERE id = ?").param(questionId).update();
        long id = jdbc.sql("INSERT INTO session (projet_id, methode, question_id) VALUES (?, ?, ?) RETURNING id")
                .params(projetId, methode, questionId).query(Long.class).single();
        if (methode.equals("research")) {
            var client = anthropic.client();
            jdbc.sql("UPDATE session SET tache = 'en_cours', progression = 'Démarrage…' WHERE id = ?").param(id).update();
            enregistrer(id, "user", MessageParam.ofUser(RESEARCH), false);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    research(client, session(id));
                }
            });
        }
        return session(id);
    }

    @GetMapping("/api/sessions/{id}")
    SessionDetail detail(@PathVariable long id) {
        Session session = session(id);
        List<Affichage> messages = lignes(id).stream()
                .filter(Ligne::visible)
                .map(l -> new Affichage(l.id(), l.role(), texte(l.contenu()), round(l.contenu()), l.incomplet()))
                .filter(a -> !a.texte().isEmpty() || a.round() != null)
                .toList();
        return new SessionDetail(session, projet(session.projetId()).get("nom"), messages);
    }

    /** Remplissage du contexte : taille du prompt du dernier appel (entrée + cache lu + cache écrit), sur la fenêtre du modèle (D-28). */
    @GetMapping("/api/sessions/{id}/contexte")
    Contexte remplissage(@PathVariable long id) {
        session(id);
        long tokens = jdbc.sql("""
                        SELECT coalesce(input_tokens, 0) + coalesce(cache_read_tokens, 0) + coalesce(cache_write_tokens, 0)
                        FROM message WHERE session_id = ? AND modele IS NOT NULL ORDER BY id DESC LIMIT 1""")
                .param(id).query(Long.class).optional().orElse(0L);
        double remplissage = (double) tokens / fenetre;
        return new Contexte(tokens, fenetre, remplissage, remplissage >= seuil);
    }

    /**
     * Continue dans une nouvelle Session (D-28) : même Méthode et même Question, amorcée avec la Map et les Décisions,
     * sans l'historique. L'ancienne est close sans toucher au statut de la Question.
     */
    @PostMapping("/api/sessions/{id}/continuer")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public Session continuer(@PathVariable long id) {
        Session ancienne = active(id);
        jdbc.sql("UPDATE session SET statut = 'close' WHERE id = ?").param(id).update();
        long nouvelle = jdbc.sql("""
                        INSERT INTO session (projet_id, methode, question_id, continued_from) VALUES (?, ?, ?, ?) RETURNING id""")
                .params(ancienne.projetId(), ancienne.methode(), ancienne.questionId(), id).query(Long.class).single();
        return session(nouvelle);
    }

    /** Clôt la Session ; sa Question passe à `resolved` si au moins une Décision a été confirmée, sinon revient à `open` (E-13). */
    @PostMapping("/api/sessions/{id}/clore")
    @Transactional
    public Session clore(@PathVariable long id) {
        Session session = active(id);
        jdbc.sql("UPDATE session SET statut = 'close' WHERE id = ?").param(id).update();
        if (session.questionId() != null) {
            int decisions = jdbc.sql("SELECT count(*) FROM decision WHERE session_id = ?").param(id).query(Integer.class).single();
            // Une Question passée hors périmètre pendant la Session le reste.
            jdbc.sql("UPDATE question SET statut = ? WHERE id = ? AND statut = 'claimed'")
                    .params(decisions > 0 ? "resolved" : "open", session.questionId()).update();
        }
        return session(id);
    }

    /** Ajoute le message de l'utilisateur et streame la réponse en SSE : `delta` (texte), puis `fin` ou `erreur`. */
    @PostMapping("/api/sessions/{id}/messages")
    SseEmitter envoyer(@PathVariable long id, @RequestBody NouveauMessage nouveau) {
        if (nouveau.texte() == null || nouveau.texte().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Message vide.");
        }
        Session session = active(id);
        var client = anthropic.client();
        enregistrer(id, "user", messageUtilisateur(id, nouveau.texte().strip()), true);
        return repondre(client, session);
    }

    /** Relance la réponse au dernier message de l'utilisateur, après une coupure ou une erreur (D-30). */
    @PostMapping("/api/sessions/{id}/reessayer")
    SseEmitter reessayer(@PathVariable long id) {
        Session session = active(id);
        var client = anthropic.client();
        List<Ligne> historique = historique(id);
        if (historique.isEmpty() || !historique.getLast().role().equals("user")) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Rien à réessayer : la dernière réponse est complète.");
        }
        return repondre(client, session);
    }

    private SseEmitter repondre(AnthropicClient client, Session session) {
        long id = session.id();
        SseEmitter emitter = new SseEmitter(0L);
        Deltas deltas = t -> emitter.send(SseEmitter.event().name("delta").data(Map.of("texte", t)));
        Thread.ofVirtual().start(() -> {
            try {
                Message message = tour(client, session, deltas);
                if (Record.appel(message).isEmpty() && message.stopReason().filter(StopReason.END_TURN::equals).isPresent()) {
                    // Une relance (D-18), masquée dans le fil.
                    enregistrer(id, "user", MessageParam.ofUser(Record.RELANCE), false);
                    message = tour(client, session, deltas);
                }
                var appel = Record.appel(message);
                if (appel.isPresent()) {
                    map.appliquer(session.projetId(), Record.input(appel.get()));
                    String prototype = Record.input(appel.get()).path("prototype_html").asText("").strip();
                    if (!prototype.isEmpty()) {
                        jdbc.sql("INSERT INTO artefact (session_id, type, contenu) VALUES (?, 'prototype_html', ?)").params(id, prototype).update();
                    }
                    emitter.send(SseEmitter.event().name("record").data(JSON.convertValue(Record.input(appel.get()), Map.class)));
                } else if (message.stopReason().filter(StopReason.REFUSAL::equals).isPresent()) {
                    emitter.send(SseEmitter.event().name("erreur").data(Map.of("message", "Claude a refusé de répondre à ce message.")));
                } else {
                    emitter.send(SseEmitter.event().name("avertissement").data(Map.of("message",
                            "Réponse non structurée : le panneau Décisions et Questions n'est pas mis à jour.")));
                }
                emitter.send(SseEmitter.event().name("fin").data(Map.of()));
                emitter.complete();
            } catch (Exception e) {
                log.warn("Échec de la réponse dans la Session {}", id, e);
                try {
                    emitter.send(SseEmitter.event().name("erreur").data(Map.of("message", messageErreur(e), "reessayable", true)));
                    emitter.complete();
                } catch (IOException | IllegalStateException ignored) {
                    emitter.completeWithError(e);
                }
            }
        });
        return emitter;
    }

    /** Erreurs non récupérables en clair (D-30) ; 408/409/429/5xx et réseau passent d'abord par les 2 retries du SDK. */
    static String messageErreur(Exception e) {
        if (e instanceof UnauthorizedException) {
            return "Clé Anthropic invalide : vérifier productdesigner.anthropic.api-key dans ~/.productdesigner/application.yml.";
        }
        if (e instanceof BadRequestException b) {
            return b.getMessage().contains("too long")
                    ? "Requête trop longue pour le contexte du modèle : continue dans une nouvelle Session."
                    : "Requête refusée par l'API Anthropic : " + b.getMessage();
        }
        return "Réponse interrompue : " + e.getMessage();
    }

    /**
     * Un appel streamé : relaie les deltas de texte, persiste le message assistant complet.
     * Coupé en route, le texte reçu est persisté `incomplet` (affiché, exclu de l'historique), puis l'erreur remonte.
     */
    private Message tour(AnthropicClient client, Session session, Deltas deltas) throws IOException {
        MessageAccumulator accumulator = MessageAccumulator.create();
        StringBuilder recu = new StringBuilder();
        Message message;
        try (StreamResponse<RawMessageStreamEvent> stream = client.messages().createStreaming(params(session))) {
            for (RawMessageStreamEvent event : (Iterable<RawMessageStreamEvent>) stream.stream()::iterator) {
                accumulator.accumulate(event);
                var delta = event.contentBlockDelta().flatMap(d -> d.delta().text());
                if (delta.isPresent()) {
                    recu.append(delta.get().text());
                    deltas.texte(delta.get().text());
                }
            }
            message = accumulator.message();
        } catch (RuntimeException | IOException e) {
            if (!recu.isEmpty()) {
                long partiel = enregistrer(session.id(), "assistant", MessageParam.ofAssistant(recu.toString()), true);
                jdbc.sql("UPDATE message SET incomplet = 1 WHERE id = ?").param(partiel).update();
            }
            throw e;
        }
        long messageId = enregistrer(session.id(), "assistant", message.toParam(), true);
        var usage = message.usage();
        jdbc.sql("UPDATE message SET modele = ?, input_tokens = ?, output_tokens = ?, cache_read_tokens = ?,"
                        + " cache_write_tokens = ? WHERE id = ?")
                .params(anthropic.model(), usage.inputTokens(), usage.outputTokens(),
                        usage.cacheReadInputTokens().orElse(0L), usage.cacheCreationInputTokens().orElse(0L), messageId)
                .update();
        return message;
    }

    /** Message utilisateur ; si le tour précédent a appelé record, il commence par le tool_result attendu par l'API. */
    private MessageParam messageUtilisateur(long sessionId, String texte) {
        List<Ligne> lignes = historique(sessionId);
        List<ContentBlockParam> blocs = new ArrayList<>();
        if (!lignes.isEmpty() && lignes.getLast().role().equals("assistant")) {
            for (JsonNode bloc : contenu(lignes.getLast().contenu())) {
                if ("tool_use".equals(bloc.path("type").asText()) && Record.NOM.equals(bloc.path("name").asText())) {
                    blocs.add(ContentBlockParam.ofToolResult(ToolResultBlockParam.builder()
                            .toolUseId(bloc.path("id").asText()).content("Enregistré.").build()));
                }
            }
        }
        blocs.add(ContentBlockParam.ofText(TextBlockParam.builder().text(texte).build()));
        return MessageParam.ofUserOfContentBlockParams(blocs);
    }

    private MessageCreateParams params(Session session) {
        Map<String, String> projet = projet(session.projetId());
        Methodes.Methode methode = methodes.get(session.methode());
        var cache = CacheControlEphemeral.builder().build();
        MessageCreateParams.Builder builder = MessageCreateParams.builder()
                .model(anthropic.model())
                .maxTokens(64000L)
                .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.MEDIUM).build())
                .systemOfTextBlockParams(List.of(
                        TextBlockParam.builder().text(methode.prompt() + Record.consigne(methode.outputs())).cacheControl(cache).build(),
                        TextBlockParam.builder().text(contexte(session, projet)).build()))
                // Cache automatique : le préfixe d'historique est réutilisé d'un tour à l'autre.
                .addTool(Record.TOOL)
                .cacheControl(cache);
        if (session.methode().equals("research")) {
            builder.addTool(WebSearchTool20260209.builder().maxUses(RECHERCHES_MAX).build())
                    .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.HIGH).build());
        }
        List<MessageParam> historique = new ArrayList<>();
        for (Ligne l : historique(session.id())) {
            historique.add(lire(l.contenu()));
        }
        return builder.messages(historique).build();
    }

    /** Contexte stable de la Session : Projet, Destination, et la Question à résoudre s'il y en a une. */
    private String contexte(Session session, Map<String, String> projet) {
        StringBuilder sb = new StringBuilder("Projet : ").append(projet.get("nom")).append("\n\n").append(projet.get("description"));
        String destination = jdbc.sql("SELECT destination FROM map WHERE projet_id = ?").param(session.projetId())
                .query(String.class).single();
        if (!destination.isBlank()) {
            sb.append("\n\nDestination de la Map : ").append(destination);
        }
        if (session.continuedFrom() != null) {
            sb.append("\n\nCette Session prend la suite d'une Session précédente arrivée en limite de contexte ; "
                    + "son historique n'est pas repris. État actuel :\n\n").append(map.resume(session.projetId()));
        }
        if (session.questionId() != null) {
            String corps = jdbc.sql("SELECT corps FROM question WHERE id = ?").param(session.questionId()).query(String.class).single();
            sb.append("\n\nQuestion à résoudre dans cette Session : ").append(session.question());
            if (!corps.isBlank()) {
                sb.append("\n").append(corps);
            }
        }
        return sb.toString();
    }

    private Session active(long id) {
        Session session = session(id);
        if (!session.statut().equals("active")) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Session close.");
        }
        if ("en_cours".equals(session.tache())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Research en cours : attendre la fin.");
        }
        return session;
    }

    private Session session(long id) {
        return jdbc.sql("""
                        SELECT s.id, s.projet_id, s.methode, s.statut, s.created_at, s.question_id, q.titre AS question, s.continued_from,
                               s.tache, s.progression
                        FROM session s LEFT JOIN question q ON q.id = s.question_id
                        WHERE s.id = ?""")
                .param(id).query(Session.class).optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Session introuvable."));
    }

    private Map<String, String> projet(long id) {
        return jdbc.sql("SELECT nom, description FROM projet WHERE id = ?").param(id)
                .query((rs, n) -> Map.of("nom", rs.getString("nom"), "description", rs.getString("description")))
                .optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Projet introuvable."));
    }

    private List<Ligne> lignes(long sessionId) {
        return jdbc.sql("SELECT id, role, contenu, visible, incomplet FROM message WHERE session_id = ? ORDER BY id")
                .param(sessionId).query(Ligne.class).list();
    }

    /** Messages renvoyés à l'API : tous sauf les réponses incomplètes. */
    private List<Ligne> historique(long sessionId) {
        return lignes(sessionId).stream().filter(l -> !l.incomplet()).toList();
    }

    private long enregistrer(long sessionId, String role, MessageParam param, boolean visible) {
        try {
            return jdbc.sql("INSERT INTO message (session_id, role, contenu, visible) VALUES (?, ?, ?, ?) RETURNING id")
                    .params(sessionId, role, JSON.writeValueAsString(param), visible).query(Long.class).single();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static MessageParam lire(String contenu) {
        try {
            return JSON.readValue(contenu, MessageParam.class);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static JsonNode contenu(String json) {
        try {
            return JSON.readTree(json).path("content");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Texte affichable : contenu chaîne, ou concaténation des blocs `text` (thinking, outils et tool_result exclus). */
    static String texte(String json) {
        JsonNode content = contenu(json);
        if (content.isTextual()) {
            return content.asText();
        }
        StringBuilder sb = new StringBuilder();
        for (JsonNode bloc : content) {
            if ("text".equals(bloc.path("type").asText())) {
                sb.append(bloc.path("text").asText());
            }
        }
        return sb.toString();
    }

    /** Round porté par l'appel record du message, s'il contient au moins une question. */
    static Object round(String json) {
        for (JsonNode bloc : contenu(json)) {
            if ("tool_use".equals(bloc.path("type").asText()) && Record.NOM.equals(bloc.path("name").asText())) {
                JsonNode round = bloc.path("input").path("round");
                return round.path("questions").isEmpty() ? null : JSON.convertValue(round, Map.class);
            }
        }
        return null;
    }
}
