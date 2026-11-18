package com.anode.productdesigner.service;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.anode.jira.JiraDocuments;
import com.anode.productdesigner.repository.ProjetRepository;
import com.anode.productdesigner.repository.SpecRepository;
import com.anode.productdesigner.repository.SpecRepository.PourPublication;

import com.anode.confluence.ConfluenceClient;
import com.anode.confluence.ConfluenceClient.Body;
import com.anode.confluence.ConfluenceClient.NewPage;
import com.anode.confluence.ConfluenceClient.PageUpdate;
import com.anode.confluence.ConfluenceClient.Version;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.server.ResponseStatusException;

/**
 * Publication d'une version de Spec dans Confluence Cloud (API v2) : une page par Projet, une version Confluence par
 * version de Spec publiée (D-7 : versions simples, sans fusion). Même compte Atlassian que l'export Jira (anode.confluence.*).
 */
@Service
public class ConfluenceService {

    public record Publication(String espace, String url, int versionConfluence, boolean creee) {}

    record Page(String id, int version, String url) {}

    private final SpecRepository specs;
    private final ProjetRepository projets;
    private final ObjectProvider<ConfluenceClient> confluence;
    private final Atlassian atlassian;
    private final String site;

    public ConfluenceService(SpecRepository specs, ProjetRepository projets, ObjectProvider<ConfluenceClient> confluence, Atlassian atlassian,
                             @Value("${anode.confluence.base-url:}") String site) {
        this.specs = specs;
        this.projets = projets;
        this.confluence = confluence;
        this.atlassian = atlassian;
        this.site = site.replaceAll("/+$", "");
    }

    private ConfluenceClient confluence() {
        return Atlassian.client(confluence);
    }

    public Publication publier(long specId, String espaceDemande) {
        PourPublication spec = specs.pourPublication(specId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Spec introuvable."));
        String espace = spec.espace() != null ? spec.espace() : espaceDemande == null ? "" : cle(espaceDemande);
        if (espace.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Clé de l'espace Confluence obligatoire.");
        }
        String titre = "Spec — " + spec.nom();
        Body corps = corps(JiraDocuments.markdown(sansTitre(spec.contenu())));
        String message = "Spec version " + spec.version() + " (productDesigner)";

        String pageId = spec.pageId();
        var existante = pageId == null ? Optional.<Page>empty() : page(pageId);
        boolean creee = existante.isEmpty();
        Page page;
        try {
            page = creee
                    ? versPage(atlassian.appel(() -> confluence().createPage(new NewPage(espaceId(espace), titre, null, corps), null)))
                    : versPage(atlassian.appel(() -> confluence().updatePage(pageId,
                            new PageUpdate(pageId, "current", titre, corps, new Version(existante.get().version() + 1, message)))));
        } catch (HttpClientErrorException.BadRequest | HttpClientErrorException.Conflict e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Confluence a refusé la page (une page « " + titre + " » existe peut-être déjà dans l'espace " + espace + ") : "
                            + e.getResponseBodyAsString());
        }
        // Mémorisé après succès seulement : une mauvaise clé d'espace ne fige pas la cible.
        projets.memoriserConfluence(spec.projetId(), espace, page.id());
        specs.memoriserConfluence(specId, page.version());
        return new Publication(espace, page.url(), page.version(), creee);
    }

    /** Clés d'espace en majuscules, sauf les espaces personnels (« ~ » suivi d'un id en minuscules). */
    public static String cle(String espace) {
        String c = espace.strip();
        return c.startsWith("~") ? c : c.toUpperCase();
    }

    /** Le titre `# ` de la Spec double celui de la page Confluence. */
    public static String sansTitre(String contenu) {
        return contenu.replaceFirst("^\\s*# [^\\n]*\\n", "");
    }

    /** Id de l'espace d'après sa clé (`GET /wiki/api/v2/spaces?keys=`). */
    private String espaceId(String cle) {
        var espaces = atlassian.appel(() -> confluence().getSpaces(List.of(cle), null, null)).results();
        if (espaces == null || espaces.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Espace Confluence " + cle + " introuvable (ou pas d'accès).");
        }
        return espaces.getFirst().id();
    }

    /** Page courante, ou vide si elle a été supprimée de Confluence. */
    private Optional<Page> page(String id) {
        try {
            return Optional.of(versPage(atlassian.appel(() -> confluence().getPage(id, null))));
        } catch (HttpClientErrorException.NotFound e) {
            return Optional.empty();
        }
    }

    /** Corps en ADF (`atlas_doc_format`), comme les descriptions Jira. */
    private static Body corps(Map<String, Object> adf) {
        return new Body("atlas_doc_format", Conversation.JSON.writeValueAsString(adf));
    }

    private Page versPage(ConfluenceClient.Page p) {
        var liens = p.links();
        String base = liens != null && liens.base() != null ? liens.base() : site + "/wiki";
        String webui = liens != null && liens.webui() != null ? liens.webui() : "";
        return new Page(p.id(), p.version() == null || p.version().number() == null ? 1 : p.version().number(), base + webui);
    }
}
