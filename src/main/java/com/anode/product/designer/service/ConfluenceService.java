package com.anode.product.designer.service;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.anode.atlassian.confluence.ConfluenceClient;
import com.anode.atlassian.confluence.ConfluenceClient.Body;
import com.anode.atlassian.confluence.ConfluenceClient.NewPage;
import com.anode.atlassian.confluence.ConfluenceClient.PageUpdate;
import com.anode.atlassian.confluence.ConfluenceClient.Version;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.server.ResponseStatusException;

/**
 * Publication d'une version de Spec dans Confluence Cloud (API v2) : une page par Projet, une version Confluence par
 * version de Spec publiée (D-7 : versions simples, sans fusion). Même compte Atlassian que l'export Jira.
 */
@Service
public class ConfluenceService {

    public record Publication(String espace, String url, int versionConfluence, boolean creee) {}

    record Page(String id, int version, String url) {}

    private final JdbcClient jdbc;
    private final ObjectProvider<ConfluenceClient> confluence;
    private final Atlassian atlassian;
    private final String site;

    public ConfluenceService(JdbcClient jdbc, ObjectProvider<ConfluenceClient> confluence, Atlassian atlassian,
                             @Value("${anode.atlassian.base-url:}") String site) {
        this.jdbc = jdbc;
        this.confluence = confluence;
        this.atlassian = atlassian;
        this.site = site.replaceAll("/+$", "");
    }

    private ConfluenceClient confluence() {
        return Atlassian.client(confluence);
    }

    public Publication publier(long specId, String espaceDemande) {
        var spec = jdbc.sql("""
                        SELECT s.projet_id, s.version, s.contenu, p.nom, p.confluence_espace, p.confluence_page_id
                        FROM spec s JOIN projet p ON p.id = s.projet_id WHERE s.id = ?""")
                .param(specId)
                .query((rs, n) -> Map.of("projet", rs.getLong(1), "version", rs.getInt(2), "contenu", rs.getString(3), "nom", rs.getString(4),
                        "espace", String.valueOf(rs.getString(5)), "page", String.valueOf(rs.getString(6))))
                .optional().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Spec introuvable."));
        long projet = (long) spec.get("projet");
        String espace = !"null".equals(spec.get("espace")) ? (String) spec.get("espace")
                : espaceDemande == null ? "" : cle(espaceDemande);
        if (espace.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Clé de l'espace Confluence obligatoire.");
        }
        String titre = "Spec — " + spec.get("nom");
        Body corps = corps(Adf.document(sansTitre((String) spec.get("contenu"))));
        String message = "Spec version " + spec.get("version") + " (productDesigner)";

        String pageId = (String) spec.get("page");
        var existante = "null".equals(pageId) ? Optional.<Page>empty() : page(pageId);
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
        jdbc.sql("UPDATE projet SET confluence_espace = ?, confluence_page_id = ? WHERE id = ?").params(espace, page.id(), projet).update();
        jdbc.sql("UPDATE spec SET confluence_version = ? WHERE id = ?").params(page.version(), specId).update();
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
