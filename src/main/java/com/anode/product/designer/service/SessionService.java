package com.anode.product.designer.service;

import lombok.extern.slf4j.Slf4j;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.anode.product.designer.repository.DecisionRepository;
import com.anode.product.designer.repository.MapRepository;
import com.anode.product.designer.repository.ProjetRepository;
import com.anode.product.designer.repository.ProjetRepository.Projet;
import com.anode.product.designer.repository.SessionRepository;
import com.anode.product.designer.repository.SessionRepository.Artefact;
import com.anode.product.designer.repository.SessionRepository.MessageLigne;
import com.anode.product.designer.repository.SessionRepository.Session;
import com.anode.product.designer.service.Llm.Reponse;

import org.springframework.ai.chat.messages.AssistantMessage.ToolCall;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;

/** Sessions de travail avec le LLM : cycle de vie, tours de conversation, research en tâche de fond (D-26). */
@Service
@Slf4j
public class SessionService {

    public record Contexte(long tokens, long fenetre, double remplissage, boolean seuilAtteint) {}

    public record Affichage(long id, String role, String texte, Object round, boolean incomplet) {}

    /** reessayable : la Session attend une réponse au dernier message de l'utilisateur, masqué ou non (E-34) ; une research en échec se relance plutôt. */
    public record SessionDetail(Session session, String projet, List<Affichage> messages, boolean reessayable) {}

    /** Flux d'événements d'une réponse en cours (SSE côté web) : `delta`, `record`, `avertissement`, puis `fin` ou `erreur`. */
    public interface Flux {
        void envoyer(String evenement, Object donnees) throws IOException;

        void terminer();

        void echec(Exception e);
    }

    public static final String RESEARCH = "Mène la recherche sur la Question à résoudre, puis rédige le rapport final.";

    /** Étapes d'un tour au plus (pauses serveur, appels de l'outil de recherche) : au-delà, le tour échoue. */
    private static final int ETAPES_MAX = 20;

    /** Recherches web client (Tavily) par tour. */
    private static final int RECHERCHES_MAX = 15;

    private final SessionRepository sessions;
    private final ProjetRepository projets;
    private final MapRepository maps;
    private final DecisionRepository decisions;
    private final Llm llm;
    private final Methodes methodes;
    private final MapService map;
    /** Sessions dont une réponse est en cours de stream. */
    private final Set<Long> repondent = ConcurrentHashMap.newKeySet();
    private final long fenetre;
    private final double seuil;

    public SessionService(SessionRepository sessions, ProjetRepository projets, MapRepository maps, DecisionRepository decisions,
                          Llm llm, Methodes methodes, MapService map,
                          @Value("${productdesigner.contexte.fenetre:1000000}") long fenetre,
                          @Value("${productdesigner.contexte.seuil:0.8}") double seuil) {
        this.sessions = sessions;
        this.projets = projets;
        this.maps = maps;
        this.decisions = decisions;
        this.llm = llm;
        this.methodes = methodes;
        this.map = map;
        this.fenetre = fenetre;
        this.seuil = seuil;
    }

    // --- Cycle de vie ---

    public List<Session> lister(long projetId) {
        return sessions.parProjet(projetId);
    }

    public Session creer(long projetId, String methode) {
        methodes.get(methode);
        projet(projetId);
        return session(sessions.creer(projetId, methode, null, null));
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
    @Transactional
    public Session ouvrir(long projetId, long questionId) {
        String type = maps.typeQuestion(questionId, projetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Question introuvable."));
        if (!map.frontiere(projetId).contains(questionId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Cette Question n'est pas dans la frontière (déjà prise, résolue ou bloquée).");
        }
        String methode = methode(type);
        methodes.get(methode);
        if (methode.equals("research") && !llm.rechercheWeb()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, Llm.SANS_RECHERCHE_WEB);
        }
        maps.majStatut(questionId, "claimed");
        long id = sessions.creer(projetId, methode, questionId, null);
        if (methode.equals("research")) {
            llm.verifier();
            sessions.demarrerResearch(id, "Démarrage…");
            enregistrer(id, "user", Conversation.utilisateur(RESEARCH), false);
            apresCommit(() -> research(session(id)));
        }
        return session(id);
    }

    /**
     * Continue dans une nouvelle Session (D-28) : même Méthode et même Question, amorcée avec la Map et les Décisions,
     * sans l'historique. L'ancienne est close sans toucher au statut de la Question.
     */
    @Transactional
    public Session continuer(long id) {
        Session ancienne = active(id);
        sessions.clore(id);
        return session(sessions.creer(ancienne.projetId(), ancienne.methode(), ancienne.questionId(), id));
    }

    /** Clôt la Session ; sa Question passe à `resolved` si au moins une Décision a été confirmée, sinon revient à `open` (E-13). */
    @Transactional
    public Session clore(long id) {
        Session session = active(id);
        sessions.clore(id);
        if (session.questionId() != null) {
            // Une Question passée hors périmètre pendant la Session le reste.
            maps.libererSiReclamee(session.questionId(), decisions.compter(id) > 0 ? "resolved" : "open");
        }
        return session(id);
    }

    // --- Lecture ---

    public SessionDetail detail(long id) {
        Session session = session(id);
        List<Affichage> messages = sessions.messages(id).stream()
                .filter(MessageLigne::visible)
                .map(l -> new Affichage(l.id(), l.role(), Conversation.texte(l.contenu()), round(l.contenu()), l.incomplet()))
                .filter(a -> !a.texte().isEmpty() || a.round() != null)
                .toList();
        List<MessageLigne> historique = historique(id);
        boolean reessayable = session.statut().equals("active") && !"en_cours".equals(session.tache()) && !repondent.contains(id)
                && !historique.isEmpty() && historique.getLast().role().equals("user") && !"echec".equals(session.tache());
        return new SessionDetail(session, projet(session.projetId()).nom(), messages, reessayable);
    }

    public List<Artefact> artefacts(long id) {
        session(id);
        return sessions.artefacts(id);
    }

    /** Remplissage du contexte : taille du prompt du dernier appel (entrée + cache lu + cache écrit), sur la fenêtre du modèle (D-28). */
    public Contexte remplissage(long id) {
        session(id);
        long tokens = sessions.tokensDernierAppel(id);
        double remplissage = (double) tokens / fenetre;
        return new Contexte(tokens, fenetre, remplissage, remplissage >= seuil);
    }

    // --- Research en tâche de fond (D-26) ---

    /**
     * Research en tâche de fond : rend la main tout de suite, écrit sa progression en base après chaque étape
     * (recherche ou pause serveur). Le dernier message donne l'Artefact.
     */
    void research(Session session) {
        long id = session.id();
        Thread.ofVirtual().start(() -> {
            try {
                List<Reponse> reponses = tourComplet(session, t -> {},
                        n -> sessions.progression(id, "Recherche en cours : " + n + " recherche(s) web…"));
                String rapport = rapport(reponses);
                if (rapport.isEmpty()) {
                    throw new IllegalStateException("aucun rapport rédigé");
                }
                Record.appel(reponses.getLast()).ifPresent(a -> map.appliquer(session.projetId(), Record.input(a)));
                sessions.ajouterArtefact(id, "research_md", rapport);
                sessions.terminerResearch(id, "Terminée : " + recherches(reponses) + " recherche(s) web.");
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
            sessions.echecResearch(id, message);
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
    public void interrompreResearches() {
        int n = sessions.echecResearchesEnCours("Interrompue par un redémarrage de l'application.");
        if (n > 0) {
            log.info("{} research(s) interrompue(s) par le redémarrage", n);
        }
    }

    /** Relance une research en échec depuis zéro : seule la consigne de départ est gardée. */
    @Transactional
    public Session relancer(long id) {
        Session session = session(id);
        if (!"echec".equals(session.tache()) || !session.statut().equals("active")) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Seule une research en échec se relance.");
        }
        if (decisions.compter(id) > 0) {
            // Relancer efface les messages, donc en cascade les Décisions confirmées qui en viennent.
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Des Décisions de cette research sont déjà confirmées : clore la Session plutôt que la relancer.");
        }
        llm.verifier();
        sessions.supprimerMessagesApresLePremier(id);
        sessions.supprimerArtefacts(id);
        sessions.demarrerResearch(id, "Relancée…");
        apresCommit(() -> research(session(id)));
        return session(id);
    }

    /** Recherches du tour : appels de l'outil client, plus les étapes de recherche serveur. */
    private static int recherches(List<Reponse> reponses) {
        return reponses.stream().mapToInt(r -> r.recherchesServeur()
                + (int) r.appels().stream().filter(a -> Recherche.NOM.equals(a.name())).count()).sum();
    }

    private static void apresCommit(Runnable action) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }

    // --- Conversation ---

    /** Valide et enregistre le message de l'utilisateur, puis réserve la Session : l'appelant enchaîne avec {@link #repondre}. */
    public Session envoyer(long id, String texte) {
        if (texte == null || texte.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Message vide.");
        }
        Session session = active(id);
        llm.verifier();
        reserver(id);
        try {
            enregistrer(id, "user", messageUtilisateur(id, texte.strip()), true);
            return session;
        } catch (RuntimeException e) {
            repondent.remove(id);
            throw e;
        }
    }

    /** Valide et réserve la Session pour relancer la réponse au dernier message de l'utilisateur (D-30) : suivre avec {@link #repondre}. */
    public Session reessayer(long id) {
        Session session = active(id);
        llm.verifier();
        List<MessageLigne> historique = historique(id);
        if (historique.isEmpty() || !historique.getLast().role().equals("user")) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Rien à réessayer : la dernière réponse est complète.");
        }
        reserver(id);
        return session;
    }

    /** Une seule réponse à la fois par Session (deux onglets, double clic) ; libérée à la fin de {@link #repondre}. */
    private void reserver(long id) {
        if (!repondent.add(id)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Une réponse est déjà en cours dans cette Session.");
        }
    }

    /** Libère une Session réservée par {@link #envoyer} ou {@link #reessayer} dont la réponse n'a pas pu démarrer. */
    public void abandonner(long id) {
        repondent.remove(id);
    }

    /** Produit la réponse de la Session réservée, en relayant les événements ; bloquant, à appeler hors du thread de la requête. */
    public void repondre(Session session, Flux flux) {
        long id = session.id();
        Llm.Deltas deltas = t -> flux.envoyer("delta", Map.of("texte", t));
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
                    sessions.ajouterArtefact(id, "prototype_html", prototype);
                }
                flux.envoyer("record", Record.input(appel.get()));
            } else if (message.refus()) {
                flux.envoyer("erreur", Map.of("message", "Le modèle a refusé de répondre à ce message."));
            } else {
                flux.envoyer("avertissement", Map.of("message",
                        "Réponse non structurée : le panneau Décisions et Questions n'est pas mis à jour."));
            }
            flux.envoyer("fin", Map.of());
            flux.terminer();
        } catch (Exception e) {
            log.warn("Échec de la réponse dans la Session {}", id, e);
            try {
                // Réessayer n'a de sens que si l'échec est survenu avant la fin de la réponse du LLM.
                flux.envoyer("erreur", Map.of("message", Llm.messageErreur(e), "reessayable", pendantLeTour));
                flux.terminer();
            } catch (IOException | IllegalStateException ignored) {
                flux.echec(e);
            }
        } finally {
            repondent.remove(id);
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
                sessions.marquerIncomplet(enregistrer(session.id(), "assistant", Conversation.assistant(recu.toString()), true));
            }
            throw e;
        }
        long messageId = enregistrer(session.id(), "assistant", Conversation.assistant(r), true);
        sessions.majUsage(messageId, r.modele(), r.input(), r.output(), r.cacheRead(), r.cacheWrite());
        return r;
    }

    /** Message utilisateur ; si le tour précédent a appelé des outils, il commence par leurs résultats, attendus par l'API. */
    private String messageUtilisateur(long sessionId, String texte) {
        List<MessageLigne> lignes = historique(sessionId);
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
        messages.addAll(Conversation.messages(historique(session.id()).stream().map(MessageLigne::contenu).toList()));
        return messages;
    }

    /** Contexte stable de la Session : Projet, Destination, et la Question à résoudre s'il y en a une. */
    private String contexte(Session session, Projet projet) {
        StringBuilder sb = new StringBuilder("Projet : ").append(projet.nom()).append("\n\n").append(projet.description());
        String destination = maps.destination(session.projetId());
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
            String corps = maps.corpsQuestion(session.questionId());
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
        return sessions.trouver(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Session introuvable."));
    }

    private Projet projet(long id) {
        return projets.trouver(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Projet introuvable."));
    }

    /** Messages renvoyés à l'API : tous sauf les réponses incomplètes. */
    private List<MessageLigne> historique(long sessionId) {
        return sessions.messages(sessionId).stream().filter(l -> !l.incomplet()).toList();
    }

    private long enregistrer(long sessionId, String role, String contenu, boolean visible) {
        return sessions.ajouterMessage(sessionId, role, contenu, visible);
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
