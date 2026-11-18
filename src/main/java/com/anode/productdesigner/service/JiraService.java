package com.anode.productdesigner.service;

import lombok.RequiredArgsConstructor;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.anode.jira.JiraClient;
import com.anode.jira.JiraDocuments;
import com.anode.jira.client.model.CreatedIssues;
import com.anode.jira.client.model.IssueBean;
import com.anode.jira.client.model.IssueLinkType;
import com.anode.jira.client.model.IssueUpdateDetails;
import com.anode.jira.client.model.IssuesUpdateBean;
import com.anode.jira.client.model.LinkIssueRequestJsonBean;
import com.anode.jira.client.model.LinkedIssue;
import com.anode.jira.client.model.SearchAndReconcileRequestBean;
import com.anode.productdesigner.repository.RoadmapRepository;
import com.anode.productdesigner.repository.RoadmapRepository.Lien;
import com.anode.productdesigner.service.RoadmapService.Jalon;
import com.anode.productdesigner.service.RoadmapService.Roadmap;
import com.anode.productdesigner.service.RoadmapService.Ticket;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.server.ResponseStatusException;

/**
 * Export d'une Roadmap vers Jira Cloud (E-26 à E-30, D-10) : Jalon = Epic, Ticket = Story rattachée par `parent`.
 * Idempotent : seuls les éléments sans `jira_key` sont créés, donc relancer l'export rejoue uniquement les échecs.
 */
@Service
@RequiredArgsConstructor
public class JiraService {

    private static final int LOT = 50;

    public record Echec(String titre, String erreur) {}

    /**
     * recuperes : issues retrouvées dans Jira après une création à l'issue inconnue, au lieu d'être recréées.
     * orphelines : issues Jira dont l'élément local n'existe plus, laissées intactes (E-28).
     */
    public record Resultat(String projet, int epicsCrees, int storiesCreees, int liensCrees, int misesAJour, int recuperes, List<Echec> echecs,
                           List<String> orphelines) {}

    record Types(String epic, String story) {}

    /** Résultat d'un bulk create : clé par élément envoyé (null si échec) et erreurs par index. */
    record Bulk(List<String> cles, Map<Integer, String> erreurs) {}

    private final RoadmapRepository repository;
    private final RoadmapService roadmaps;
    private final ObjectProvider<JiraClient> jira;
    private final Atlassian atlassian;

    public boolean configure() {
        return jira.getIfAvailable() != null;
    }

    private JiraClient jira() {
        return Atlassian.client(jira);
    }

    public Resultat exporter(long specId, String projetDemande) {
        Roadmap roadmap = roadmaps.roadmap(specId);
        if (roadmap.jalons().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Roadmap vide : rien à exporter.");
        }
        String projet = roadmap.jiraProjet() != null ? roadmap.jiraProjet()
                : projetDemande == null ? "" : projetDemande.strip().toUpperCase();
        if (projet.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Clé du projet Jira obligatoire.");
        }
        Types types = types(projet);
        Set<Long> jalonsIncertains = repository.jalonsIncertains(specId);
        Set<Long> ticketsIncertains = repository.ticketsIncertains(specId);

        List<Echec> echecs = new ArrayList<>();
        int epics = 0, misesAJour = 0, recuperes = 0;
        Map<Long, String> epicDuJalon = new HashMap<>();
        for (Jalon j : roadmap.jalons()) {
            String cle = j.jiraKey();
            if (cle == null && jalonsIncertains.contains(j.id())) {
                cle = chercher(projet, types.epic(), j.titre(), null).orElse(null);
                if (cle != null) {
                    repository.majCleJalon(j.id(), cle);
                    recuperes++;
                }
            }
            if (cle != null) {
                misesAJour += maj(echecs, "Jalon « " + j.titre() + " »", cle, Map.of("summary", j.titre()));
            } else {
                try {
                    var champs = new IssueUpdateDetails().fields(champs(projet, types.epic(), j.titre(), null, null));
                    cle = atlassian.appel(() -> jira().issues().createIssue(champs, null)).getKey();
                    repository.majCleJalon(j.id(), cle);
                    epics++;
                } catch (ResponseStatusException e) {
                    throw e;
                } catch (RuntimeException e) {
                    repository.majIncertainJalon(j.id(), Atlassian.incertain(e));
                    echecs.add(new Echec("Jalon « " + j.titre() + " »", e.getMessage()));
                    continue;
                }
            }
            epicDuJalon.put(j.id(), cle);
        }
        // Projet mémorisé seulement une fois quelque chose créé ou retrouvé : une mauvaise clé ne fige pas la cible.
        if (roadmap.jiraProjet() == null && !epicDuJalon.isEmpty()) {
            repository.memoriserProjetJira(specId, projet);
        }

        List<Ticket> aCreer = new ArrayList<>();
        List<Map<String, Object>> champs = new ArrayList<>();
        for (Jalon j : roadmap.jalons()) {
            for (Ticket t : j.tickets()) {
                String cle = t.jiraKey();
                String epic = epicDuJalon.get(j.id());
                if (cle == null && epic != null && ticketsIncertains.contains(t.id())) {
                    cle = chercher(projet, types.story(), t.titre(), epic).orElse(null);
                    if (cle != null) {
                        repository.majCleTicket(t.id(), cle);
                        recuperes++;
                    }
                }
                if (cle != null) {
                    // Ré-export (E-28) : titre, description et Epic parent (le Ticket a pu changer de Jalon).
                    Map<String, Object> champsMaj = new HashMap<>(Map.of("summary", t.titre(), "description", description(t)));
                    if (epic != null) {
                        champsMaj.put("parent", Map.of("key", epic));
                    }
                    misesAJour += maj(echecs, t.titre(), cle, champsMaj);
                    continue;
                }
                if (epic == null) {
                    echecs.add(new Echec(t.titre(), "Epic de son Jalon non créé."));
                    continue;
                }
                aCreer.add(t);
                champs.add(champs(projet, types.story(), t.titre(), description(t), epic));
            }
        }
        int stories = 0;
        for (int debut = 0; debut < champs.size(); debut += LOT) {
            int fin = Math.min(debut + LOT, champs.size());
            Bulk bulk;
            try {
                bulk = creerEnMasse(champs.subList(debut, fin));
            } catch (ResponseStatusException e) {
                throw e;
            } catch (RuntimeException e) {
                // Lot entier en échec : chaque Ticket est signalé ; marqué incertain si Jira a pu le créer quand même.
                for (Ticket t : aCreer.subList(debut, fin)) {
                    repository.majIncertainTicket(t.id(), Atlassian.incertain(e));
                    echecs.add(new Echec(t.titre(), e.getMessage()));
                }
                continue;
            }
            for (int i = 0; i < bulk.cles().size(); i++) {
                Ticket t = aCreer.get(debut + i);
                String cle = bulk.cles().get(i);
                if (cle == null) {
                    repository.majIncertainTicket(t.id(), false);
                    echecs.add(new Echec(t.titre(), bulk.erreurs().getOrDefault(i, "Échec sans détail.")));
                } else {
                    repository.majCleTicket(t.id(), cle);
                    stories++;
                }
            }
        }

        int liens = 0;
        for (Lien l : repository.liensANouer(specId)) {
            try {
                atlassian.appel(() -> jira().issueLinks().linkIssues(bloque(l.parCle(), l.cle())));
                repository.marquerLie(l.ticketId(), l.parId());
                liens++;
            } catch (ResponseStatusException e) {
                throw e;
            } catch (RuntimeException e) {
                echecs.add(new Echec("Lien " + l.parCle() + " bloque " + l.cle(), e.getMessage()));
            }
        }
        List<String> orphelines = repository.orphelines(specId);
        return new Resultat(projet, epics, stories, liens, misesAJour, recuperes, echecs, orphelines);
    }

    private int maj(List<Echec> echecs, String titre, String cle, Map<String, Object> champs) {
        try {
            atlassian.appel(() -> jira().issues().editIssue(cle, new IssueUpdateDetails().fields(champs), null, null, null, null, null));
            return 1;
        } catch (ResponseStatusException e) {
            throw e;
        } catch (RuntimeException e) {
            echecs.add(new Echec(titre, "Mise à jour de " + cle + " : " + e.getMessage()));
            return 0;
        }
    }

    /**
     * Epic = hierarchyLevel 1 ; Story = « Story » de niveau 0, sinon le premier type de niveau 0 (D-10). La réponse est lue
     * en `Map` : Jira Cloud la donne sous `issueTypes` ou sous `values` selon l'API, et le modèle généré ne connaît que le premier.
     */
    @SuppressWarnings("unchecked")
    private Types types(String projet) {
        String epic = null, story = null, premierNiveau0 = null;
        int debut = 0;
        while (true) {
            int startAt = debut;
            Map<String, Object> page = atlassian.appel(() -> jira().issues().getCreateIssueMetaIssueTypesWithResponseSpec(projet, startAt, 50)
                    .body(new ParameterizedTypeReference<Map<String, Object>>() {}));
            Object brut = page == null ? null : page.getOrDefault("issueTypes", page.get("values"));
            List<Map<String, Object>> types = brut instanceof List<?> l ? (List<Map<String, Object>>) l : List.of();
            for (var t : types) {
                int niveau = t.get("hierarchyLevel") instanceof Number n ? n.intValue() : 0;
                String id = String.valueOf(t.get("id"));
                if (niveau == 1 && epic == null) {
                    epic = id;
                } else if (niveau == 0) {
                    premierNiveau0 = premierNiveau0 == null ? id : premierNiveau0;
                    if ("Story".equalsIgnoreCase(String.valueOf(t.get("name")))) {
                        story = id;
                    }
                }
            }
            debut += types.size();
            int total = page != null && page.get("total") instanceof Number n ? n.intValue() : 0;
            if (types.isEmpty() || page != null && Boolean.TRUE.equals(page.get("isLast")) || debut >= total) {
                break;
            }
        }
        if (epic == null || premierNiveau0 == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Le projet Jira " + projet + " n'a pas de type Epic (niveau 1) ou de type de niveau 0.");
        }
        return new Types(epic, story != null ? story : premierNiveau0);
    }

    /** `blocage` bloque `bloque`. Sur Jira Cloud c'est `inwardIssue` qui « bloque » `outwardIssue`, l'inverse de ce que les noms suggèrent. */
    static LinkIssueRequestJsonBean bloque(String blocage, String bloque) {
        return new LinkIssueRequestJsonBean().type(new IssueLinkType().name("Blocks"))
                .inwardIssue(new LinkedIssue().key(blocage)).outwardIssue(new LinkedIssue().key(bloque));
    }

    /** Bulk create (50 au plus) : un 201 peut cacher des échecs partiels, on lit toujours `errors` (D-10). */
    private Bulk creerEnMasse(List<Map<String, Object>> champs) {
        var elements = new IssuesUpdateBean().issueUpdates(champs.stream().map(c -> new IssueUpdateDetails().fields(c)).toList());
        CreatedIssues r;
        try {
            r = atlassian.appel(() -> jira().issues().createIssues(elements));
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode().value() != 400) {
                throw e;
            }
            // 400 : tous les éléments ont échoué ; même corps (issues vides + errors).
            r = e.getResponseBodyAs(CreatedIssues.class);
        }
        Map<Integer, String> erreurs = new HashMap<>();
        for (var err : r.getErrors() == null ? List.<com.anode.jira.client.model.BulkOperationErrorResult>of() : r.getErrors()) {
            var detail = err.getElementErrors();
            erreurs.put(err.getFailedElementNumber(), String.valueOf(detail != null && detail.getErrors() != null && !detail.getErrors().isEmpty()
                    ? detail.getErrors() : detail != null && detail.getErrorMessages() != null ? detail.getErrorMessages() : err));
        }
        var creees = r.getIssues() == null ? List.<com.anode.jira.client.model.CreatedIssue>of() : r.getIssues();
        List<String> cles = new ArrayList<>();
        int suivante = 0;
        for (int i = 0; i < champs.size(); i++) {
            cles.add(erreurs.containsKey(i) || suivante >= creees.size() ? null : creees.get(suivante++).getKey());
        }
        return new Bulk(cles, erreurs);
    }

    /**
     * Issue déjà créée par une tentative à l'issue inconnue : même projet, type, titre exact et, pour une Story,
     * même Epic parent. JQL `summary ~` est une recherche plein texte : le filtre exact se fait ici.
     */
    @SuppressWarnings("unchecked")
    private Optional<String> chercher(String projet, String type, String titre, String parent) {
        // Guillemets et antislashs retirés de la phrase recherchée ; l'égalité exacte est vérifiée ci-dessous.
        String phrase = titre.replaceAll("[\"\\\\]", " ");
        String jql = "project = \"" + projet + "\" AND issuetype = " + type + " AND summary ~ \"\\\"" + phrase + "\\\"\" ORDER BY created DESC";
        var r = atlassian.appel(() -> jira().search().searchAndReconsileIssuesUsingJqlPost(
                new SearchAndReconcileRequestBean().jql(jql).fields(List.of("summary", "parent")).maxResults(50)));
        for (IssueBean issue : r.getIssues() == null ? List.<IssueBean>of() : r.getIssues()) {
            Map<String, Object> champs = issue.getFields() == null ? Map.of() : issue.getFields();
            Map<String, Object> parentTrouve = (Map<String, Object>) champs.get("parent");
            if (titre.equals(champs.get("summary"))
                    && (parent == null || parentTrouve != null && parent.equals(parentTrouve.get("key")))) {
                return Optional.of(issue.getKey());
            }
        }
        return Optional.empty();
    }

    private static Map<String, Object> champs(String projet, String type, String resume, Map<String, Object> description, String parent) {
        Map<String, Object> champs = new HashMap<>();
        champs.put("project", Map.of("key", projet));
        champs.put("issuetype", Map.of("id", type));
        champs.put("summary", resume);
        if (description != null) {
            champs.put("description", description);
        }
        if (parent != null) {
            champs.put("parent", Map.of("key", parent));
        }
        return champs;
    }

    /** Corps du Ticket en ADF, avec les exigences couvertes en pied. */
    static Map<String, Object> description(Ticket t) {
        String corps = t.corps();
        if (!t.exigences().isEmpty()) {
            corps += "\n\n**Exigences :** " + String.join(", ", t.exigences());
        }
        return JiraDocuments.markdown(corps);
    }
}
