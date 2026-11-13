package com.anode.product.designer.service;

import lombok.RequiredArgsConstructor;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.anode.atlassian.jira.JiraClient;
import com.anode.atlassian.jira.JiraClient.BulkCreated;
import com.anode.atlassian.jira.JiraClient.BulkIssues;
import com.anode.atlassian.jira.JiraClient.IssueFields;
import com.anode.atlassian.jira.JiraClient.IssueLink;
import com.anode.atlassian.jira.JiraClient.Search;
import com.anode.product.designer.repository.RoadmapRepository;
import com.anode.product.designer.repository.RoadmapRepository.Lien;
import com.anode.product.designer.service.RoadmapService.Jalon;
import com.anode.product.designer.service.RoadmapService.Roadmap;
import com.anode.product.designer.service.RoadmapService.Ticket;

import org.springframework.beans.factory.ObjectProvider;
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
                    var champs = new IssueFields(champs(projet, types.epic(), j.titre(), null, null));
                    cle = atlassian.appel(() -> jira().createIssue(champs)).key();
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
                atlassian.appel(() -> jira().linkIssues(IssueLink.blocks(l.parCle(), l.cle())));
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
            atlassian.appel(() -> jira().editIssue(cle, new IssueFields(champs)));
            return 1;
        } catch (ResponseStatusException e) {
            throw e;
        } catch (RuntimeException e) {
            echecs.add(new Echec(titre, "Mise à jour de " + cle + " : " + e.getMessage()));
            return 0;
        }
    }

    /** Epic = hierarchyLevel 1 ; Story = « Story » de niveau 0, sinon le premier type de niveau 0 (D-10). */
    private Types types(String projet) {
        String epic = null, story = null, premierNiveau0 = null;
        int debut = 0;
        while (true) {
            int startAt = debut;
            var page = atlassian.appel(() -> jira().getCreateIssueTypes(projet, startAt, 50));
            var types = page.issueTypes() == null ? List.<JiraClient.IssueType>of() : page.issueTypes();
            for (var t : types) {
                int niveau = t.hierarchyLevel() == null ? 0 : t.hierarchyLevel();
                if (niveau == 1 && epic == null) {
                    epic = t.id();
                } else if (niveau == 0) {
                    premierNiveau0 = premierNiveau0 == null ? t.id() : premierNiveau0;
                    if ("Story".equalsIgnoreCase(t.name())) {
                        story = t.id();
                    }
                }
            }
            debut += types.size();
            if (types.isEmpty() || Boolean.TRUE.equals(page.isLast()) || debut >= (page.total() == null ? 0 : page.total())) {
                break;
            }
        }
        if (epic == null || premierNiveau0 == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Le projet Jira " + projet + " n'a pas de type Epic (niveau 1) ou de type de niveau 0.");
        }
        return new Types(epic, story != null ? story : premierNiveau0);
    }

    /** Bulk create (50 au plus) : un 201 peut cacher des échecs partiels, on lit toujours `errors` (D-10). */
    private Bulk creerEnMasse(List<Map<String, Object>> champs) {
        var elements = new BulkIssues(champs.stream().map(IssueFields::new).toList());
        BulkCreated r;
        try {
            r = atlassian.appel(() -> jira().createIssues(elements));
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode().value() != 400) {
                throw e;
            }
            // 400 : tous les éléments ont échoué ; même corps (issues vides + errors).
            r = e.getResponseBodyAs(BulkCreated.class);
        }
        Map<Integer, String> erreurs = new HashMap<>();
        for (var err : r.errors() == null ? List.<JiraClient.BulkError>of() : r.errors()) {
            var detail = err.elementErrors();
            erreurs.put(err.failedElementNumber(), String.valueOf(detail != null && detail.errors() != null ? detail.errors() : err));
        }
        var creees = r.issues() == null ? List.<JiraClient.CreatedIssue>of() : r.issues();
        List<String> cles = new ArrayList<>();
        int suivante = 0;
        for (int i = 0; i < champs.size(); i++) {
            cles.add(erreurs.containsKey(i) || suivante >= creees.size() ? null : creees.get(suivante++).key());
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
        var r = atlassian.appel(() -> jira().search(new Search(jql, List.of("summary", "parent"), 50, null)));
        for (var issue : r.issues() == null ? List.<JiraClient.Issue>of() : r.issues()) {
            Map<String, Object> champs = issue.fields() == null ? Map.of() : issue.fields();
            Map<String, Object> parentTrouve = (Map<String, Object>) champs.get("parent");
            if (titre.equals(champs.get("summary"))
                    && (parent == null || parentTrouve != null && parent.equals(parentTrouve.get("key")))) {
                return Optional.of(issue.key());
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
        return Adf.document(corps);
    }
}
