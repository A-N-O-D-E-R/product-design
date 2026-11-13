package com.anode.product.designer.controller;

import com.anode.product.designer.service.Conversation;
import com.anode.product.designer.service.Llm;
import com.anode.product.designer.service.Llm.Reponse;
import com.anode.product.designer.service.Methodes;
import com.anode.product.designer.service.Recherche;
import com.anode.product.designer.service.Record;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage.ToolCall;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.tool.ToolCallback;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;

@RestController
class SessionController {

    private static final Logger log = LoggerFactory.getLogger(SessionController.class);
    /** questionId et question (titre) : null pour la Session qui trace la Map. */
    /** tache, progression : null hors Sessions research (D-26). */
    record Session(long id, long projetId, String methode, String statut, String createdAt, Long questionId, String question,
                   Long continuedFrom, String tache, String progression) {}

    record Artefact(long id, String type, String contenu, String createdAt) {}

    record Contexte(long tokens, long fenetre, double remplissage, boolean seuilAtteint) {}

    record NouvelleSession(String methode) {}

    record Affichage(long id, String role, String texte, Object round, boolean incomplet) {}

    /** reessayable : la Session attend une réponse au dernier message de l'utilisateur, masqué ou non (E-34) ; une research en échec se relance plutôt. */
    record SessionDetail(Session session, String projet, List<Affichage> messages, boolean reessayable) {}

    record NouveauMessage(String texte) {}

    private record Ligne(long id, String role, String contenu, boolean visible, boolean incomplet) {}

    private final JdbcClient jdbc;
    private final Llm llm;
    private final Methodes methodes;
    private final MapController map;
    /** Sessions dont une réponse est en cours de stream. */
    private final Set<Long> repondent = ConcurrentHashMap.newKeySet();
    private final long fenetre;
    private final double seuil;

    SessionController(JdbcClient jdbc, Llm llm, Methodes methodes, MapController map,
                      @Value("${productdesigner.contexte.fenetre:1000000}") long fenetre,
                      @Value("${productdesigner.contexte.seuil:0.8}") double seuil) {
        this.jdbc = jdbc;
        this.llm = llm;
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

    /** Étapes d'un tour au plus (pauses serveur, appels de l'outil de recherche) : au-delà, le tour échoue. */
    private static final int ETAPES_MAX = 20;

    /** Recherches web client (Tavily) par tour. */
    private static final int RECHERCHES_MAX = 15;

    /**
     * Research en tâche de fond (D-26) : rend la main tout de suite, écrit sa progression en base après chaque étape
     * (recherche ou pause serveur). Le dernier message donne l'Artefact.
     */
    void research(Session session) {
        long id = session.id();
        Thread.ofVirtual().start(() -> {
            try {
                List<Reponse> reponses = tourComplet(session, t -> {},
                        n -> progression(id, "Recherche en cours : " + n + " recherche(s) web…"));
                String rapport = rapport(reponses);
                if (rapport.isEmpty()) {
                    throw new IllegalStateException("aucun rapport rédigé");
                }
                Record.appel(reponses.getLast()).ifPresent(a -> map.appliquer(session.projetId(), Record.input(a)));
                jdbc.sql("INSERT INTO artefact (session_id, type, contenu) VALUES (?, 'research_md', ?)").params(id, rapport).update();
                jdbc.sql("UPDATE session SET tache = 'terminee', progression = ? WHERE id = ?")
                        .params("Terminée : " + recherches(reponses) + " recherche(s) web.", id).update();
            } catch (Throwable e) {
                log.warn("Échec de la research de la Session {}", id, e);
                echec(id, e instanceof Exception ex ? Llm.messageErreur(ex) : "Erreur interne : " + e);
            } finally {
                // Filet : quoi qu'il arrive, la research ne reste pas « en cours » (sinon la Session est bloquée).
                echec(id, "Interrompue par une erreur interne.");
            }
        });
    }

    /** Passe une research encore en cours en échec ; sans effet si elle est déjà terminée ou en échec. */
    private void echec(long id, String message) {
        try {
            jdbc.sql("UPDATE session SET tache = 'echec', progression = ? WHERE id = ? AND tache = 'en_cours'").params(message, id).update();
        } catch (RuntimeException e) {
            log.error("Impossible de marquer la research {} en échec", id, e);
        }
    }

    /**
     * Rapport de research : texte de tout le tour (il peut avoir été rédigé à cheval sur une pause_turn), à partir
     * du dernier titre `# ` pour écarter les messages intermédiaires ; tout le texte s'il n'y a pas de titre.
     */
    static String rapport(List<Reponse> reponses) {
        String texte = reponses.stream().map(Reponse::texte).reduce("", String::concat);
        Matcher titre = Pattern.compile("(?m)^# ").matcher(texte);
        int debut = -1;
        while (titre.find()) {
            debut = titre.start();
        }
        return (debut >= 0 ? texte.substring(debut) : texte).strip();
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
        if (jdbc.sql("SELECT count(*) FROM decision WHERE session_id = ?").param(id).query(Integer.class).single() > 0) {
            // Relancer efface les messages, donc en cascade les Décisions confirmées qui en viennent.
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Des Décisions de cette research sont déjà confirmées : clore la Session plutôt que la relancer.");
        }
        llm.verifier();
        jdbc.sql("DELETE FROM message WHERE session_id = ? AND id > (SELECT min(id) FROM message WHERE session_id = ?)")
                .params(id, id).update();
        jdbc.sql("DELETE FROM artefact WHERE session_id = ?").param(id).update();
        jdbc.sql("UPDATE session SET tache = 'en_cours', progression = 'Relancée…' WHERE id = ?").param(id).update();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                research(session(id));
            }
        });
        return session(id);
    }

    /** Recherches du tour : appels de l'outil client, plus les étapes de recherche serveur. */
    private static int recherches(List<Reponse> reponses) {
        return reponses.stream().mapToInt(r -> r.recherchesServeur()
                + (int) r.appels().stream().filter(a -> Recherche.NOM.equals(a.name())).count()).sum();
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
        if (methode.equals("research") && !llm.rechercheWeb()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, Llm.SANS_RECHERCHE_WEB);
        }
        jdbc.sql("UPDATE question SET statut = 'claimed' WHERE id = ?").param(questionId).update();
        long id = jdbc.sql("INSERT INTO session (projet_id, methode, question_id) VALUES (?, ?, ?) RETURNING id")
                .params(projetId, methode, questionId).query(Long.class).single();
        if (methode.equals("research")) {
            llm.verifier();
            jdbc.sql("UPDATE session SET tache = 'en_cours', progression = 'Démarrage…' WHERE id = ?").param(id).update();
            enregistrer(id, "user", Conversation.utilisateur(RESEARCH), false);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    research(session(id));
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
                .map(l -> new Affichage(l.id(), l.role(), Conversation.texte(l.contenu()), round(l.contenu()), l.incomplet()))
                .filter(a -> !a.texte().isEmpty() || a.round() != null)
                .toList();
        List<Ligne> historique = historique(id);
        boolean reessayable = session.statut().equals("active") && !"en_cours".equals(session.tache()) && !repondent.contains(id)
                && !historique.isEmpty() && historique.getLast().role().equals("user") && !"echec".equals(session.tache());
        return new SessionDetail(session, projet(session.projetId()).get("nom"), messages, reessayable);
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
        llm.verifier();
        reserver(id);
        try {
            enregistrer(id, "user", messageUtilisateur(id, nouveau.texte().strip()), true);
            return repondre(session);
        } catch (RuntimeException e) {
            repondent.remove(id);
            throw e;
        }
    }

    /** Relance la réponse au dernier message de l'utilisateur, après une coupure ou une erreur (D-30). */
    @PostMapping("/api/sessions/{id}/reessayer")
    SseEmitter reessayer(@PathVariable long id) {
        Session session = active(id);
        llm.verifier();
        List<Ligne> historique = historique(id);
        if (historique.isEmpty() || !historique.getLast().role().equals("user")) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Rien à réessayer : la dernière réponse est complète.");
        }
        reserver(id);
        try {
            return repondre(session);
        } catch (RuntimeException e) {
            repondent.remove(id);
            throw e;
        }
    }

    private SseEmitter repondre(Session session) {
        long id = session.id();
        SseEmitter emitter = new SseEmitter(0L);
        Llm.Deltas deltas = t -> emitter.send(SseEmitter.event().name("delta").data(Map.of("texte", t)));
        Thread.ofVirtual().start(() -> {
            boolean pendantLeTour = true;
            try {
                Reponse message = tourComplet(session, deltas, n -> {}).getLast();
                var premier = Record.appel(message);
                List<String> erreurs = premier.map(a -> Record.erreurs(Record.input(a))).orElse(List.of());
                if (premier.isEmpty() && message.terminee()) {
                    // Une relance (D-18), masquée dans le fil.
                    enregistrer(id, "user", Conversation.utilisateur(Record.RELANCE), false);
                    message = tourComplet(session, deltas, n -> {}).getLast();
                } else if (!erreurs.isEmpty()) {
                    // record non conforme (schéma non strict) : une relance masquée, avec le tool_result attendu.
                    log.info("record non conforme dans la Session {} : {}", id, erreurs);
                    enregistrer(id, "user", messageUtilisateur(id, Record.relanceInvalide(erreurs)), false);
                    Reponse corrige = tourComplet(session, deltas, n -> {}).getLast();
                    if (Record.appel(corrige).isPresent()) {
                        message = corrige;
                    }
                }
                pendantLeTour = false;
                var appel = Record.appel(message);
                if (appel.isPresent()) {
                    map.appliquer(session.projetId(), Record.input(appel.get()));
                    String prototype = Record.input(appel.get()).path("prototype_html").asString("").strip();
                    if (!prototype.isEmpty()) {
                        jdbc.sql("INSERT INTO artefact (session_id, type, contenu) VALUES (?, 'prototype_html', ?)").params(id, prototype).update();
                    }
                    emitter.send(SseEmitter.event().name("record").data(Record.input(appel.get())));
                } else if (message.refus()) {
                    emitter.send(SseEmitter.event().name("erreur").data(Map.of("message", "Le modèle a refusé de répondre à ce message.")));
                } else {
                    emitter.send(SseEmitter.event().name("avertissement").data(Map.of("message",
                            "Réponse non structurée : le panneau Décisions et Questions n'est pas mis à jour.")));
                }
                emitter.send(SseEmitter.event().name("fin").data(Map.of()));
                emitter.complete();
            } catch (Exception e) {
                log.warn("Échec de la réponse dans la Session {}", id, e);
                try {
                    // Réessayer n'a de sens que si l'échec est survenu avant la fin de la réponse du LLM.
                    emitter.send(SseEmitter.event().name("erreur").data(Map.of("message", Llm.messageErreur(e), "reessayable", pendantLeTour)));
                    emitter.complete();
                } catch (IOException | IllegalStateException ignored) {
                    emitter.completeWithError(e);
                }
            } finally {
                repondent.remove(id);
            }
        });
        return emitter;
    }

    /** Une seule réponse à la fois par Session (deux onglets, double clic) ; libérée à la fin du thread de réponse. */
    private void reserver(long id) {
        if (!repondent.add(id)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Une réponse est déjà en cours dans cette Session.");
        }
    }

    /**
     * Un tour complet. Tant que le modèle appelle l'outil de recherche (sans record), l'app l'exécute et renvoie les
     * résultats ; une pause serveur (pause_turn) est relancée par un message masqué. {@value #ETAPES_MAX} étapes au plus.
     * apresEtape reçoit le nombre de recherches du tour. Renvoie les réponses du tour, la dernière portant la fin réelle.
     */
    private List<Reponse> tourComplet(Session session, Llm.Deltas deltas, IntConsumer apresEtape) throws IOException {
        List<Reponse> reponses = new ArrayList<>(List.of(tour(session, deltas)));
        int recherchesClient = 0;
        while (true) {
            Reponse r = reponses.getLast();
            List<ToolCall> aExecuter = Record.appel(r).isPresent() ? List.of()
                    : r.appels().stream().filter(a -> Recherche.NOM.equals(a.name())).toList();
            if (aExecuter.isEmpty() && !r.pause()) {
                return reponses;
            }
            if (reponses.size() >= ETAPES_MAX) {
                throw new IllegalStateException("trop d'étapes de recherche dans un tour");
            }
            if (aExecuter.isEmpty()) {
                // ponytail: Spring AI ne garde pas les blocs de recherche serveur ; la reprise après pause_turn repart du texte seul.
                enregistrer(session.id(), "user", Conversation.utilisateur("Continue."), false);
            } else {
                ArrayNode resultats = Conversation.JSON.createArrayNode();
                for (ToolCall a : aExecuter) {
                    resultats.add(Conversation.resultat(a.id(), recherchesClient++ < RECHERCHES_MAX ? llm.rechercher(a.arguments())
                            : "Limite de recherches atteinte : rédige maintenant avec ce que tu as."));
                }
                enregistrer(session.id(), "user", Conversation.utilisateur(resultats), false);
            }
            apresEtape.accept(recherches(reponses));
            reponses.add(tour(session, deltas));
        }
    }

    /**
     * Un appel streamé : relaie les deltas de texte, persiste le message assistant complet.
     * Coupé en route, le texte reçu est persisté `incomplet` (affiché, exclu de l'historique), puis l'erreur remonte.
     */
    private Reponse tour(Session session, Llm.Deltas deltas) throws IOException {
        StringBuilder recu = new StringBuilder();
        boolean research = session.methode().equals("research");
        List<ToolCallback> outils = new ArrayList<>(List.of(Record.TOOL));
        if (research) {
            outils.addAll(llm.outilsRecherche());
        }
        Reponse r;
        try {
            r = llm.stream(messages(session), research ? Llm.Effort.ELEVE : Llm.Effort.MOYEN, outils, null,
                    research && llm.rechercheServeur(), t -> {
                        recu.append(t);
                        deltas.texte(t);
                    });
        } catch (RuntimeException | IOException e) {
            if (!recu.isEmpty()) {
                long partiel = enregistrer(session.id(), "assistant", Conversation.assistant(recu.toString()), true);
                jdbc.sql("UPDATE message SET incomplet = 1 WHERE id = ?").param(partiel).update();
            }
            throw e;
        }
        long messageId = enregistrer(session.id(), "assistant", Conversation.assistant(r), true);
        jdbc.sql("UPDATE message SET modele = ?, input_tokens = ?, output_tokens = ?, cache_read_tokens = ?,"
                        + " cache_write_tokens = ? WHERE id = ?")
                .params(r.modele(), r.input(), r.output(), r.cacheRead(), r.cacheWrite(), messageId)
                .update();
        return r;
    }

    /** Message utilisateur ; si le tour précédent a appelé des outils, il commence par leurs résultats, attendus par l'API. */
    private String messageUtilisateur(long sessionId, String texte) {
        List<Ligne> lignes = historique(sessionId);
        ArrayNode blocs = Conversation.JSON.createArrayNode();
        if (!lignes.isEmpty() && lignes.getLast().role().equals("assistant")) {
            for (JsonNode appel : Conversation.appels(lignes.getLast().contenu())) {
                blocs.add(Conversation.resultat(appel.path("id").asString(),
                        Record.NOM.equals(appel.path("name").asString()) ? "Enregistré." : "Non exécuté."));
            }
        }
        blocs.add(Conversation.texteBloc(texte));
        return Conversation.utilisateur(blocs);
    }

    /** Prompt d'un tour : prompt de la Méthode (stable, mis en cache), contexte de la Session, puis l'historique. */
    private List<Message> messages(Session session) {
        Methodes.Methode methode = methodes.get(session.methode());
        List<Message> messages = new ArrayList<>(List.of(
                new SystemMessage(methode.prompt() + Record.consigne(methode.outputs())),
                new SystemMessage(contexte(session, projet(session.projetId())))));
        messages.addAll(Conversation.messages(historique(session.id()).stream().map(Ligne::contenu).toList()));
        return messages;
    }

    /** Contexte stable de la Session : Projet, Destination, et la Question à résoudre s'il y en a une. */
    private String contexte(Session session, Map<String, String> projet) {
        StringBuilder sb = new StringBuilder("Projet : ").append(projet.get("nom")).append("\n\n").append(projet.get("description"));
        String destination = jdbc.sql("SELECT destination FROM map WHERE projet_id = ?").param(session.projetId())
                .query(String.class).single();
        if (!destination.isBlank()) {
            sb.append("\n\nDestination de la Map : ").append(destination);
        }
        if (!llm.rechercheWeb()) {
            sb.append("\n\nAucune recherche web n'est disponible dans cette installation : ne crée pas de Question de type"
                    + " research ; formule les faits à vérifier comme des Questions grilling ou tache.");
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
        if (repondent.contains(id)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Une réponse est déjà en cours dans cette Session.");
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

    private long enregistrer(long sessionId, String role, String contenu, boolean visible) {
        return jdbc.sql("INSERT INTO message (session_id, role, contenu, visible) VALUES (?, ?, ?, ?) RETURNING id")
                .params(sessionId, role, contenu, visible).query(Long.class).single();
    }

    /** Round porté par l'appel record du message, s'il contient au moins une question. */
    static Object round(String json) {
        for (JsonNode bloc : Conversation.appels(json)) {
            if (Record.NOM.equals(bloc.path("name").asString())) {
                JsonNode round = bloc.path("input").path("round");
                return round.path("questions").isEmpty() ? null : round;
            }
        }
        return null;
    }
}
