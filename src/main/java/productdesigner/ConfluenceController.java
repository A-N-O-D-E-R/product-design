package productdesigner;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.server.ResponseStatusException;

/**
 * Publication d'une version de Spec dans Confluence Cloud : une page par Projet, une version Confluence par
 * version de Spec publiée (D-7 : versions simples, sans fusion). Même compte Atlassian que l'export Jira.
 */
@RestController
class ConfluenceController {

    record Demande(String espace) {}

    record Publication(String espace, String url, int versionConfluence, boolean creee) {}

    private final JdbcClient jdbc;
    private final AtlassianClient atlassian;

    ConfluenceController(JdbcClient jdbc, AtlassianClient atlassian) {
        this.jdbc = jdbc;
        this.atlassian = atlassian;
    }

    @PostMapping("/api/specs/{specId}/export-confluence")
    Publication publier(@PathVariable long specId, @RequestBody Demande demande) {
        var spec = jdbc.sql("""
                        SELECT s.projet_id, s.version, s.contenu, p.nom, p.confluence_espace, p.confluence_page_id
                        FROM spec s JOIN projet p ON p.id = s.projet_id WHERE s.id = ?""")
                .param(specId)
                .query((rs, n) -> Map.of("projet", rs.getLong(1), "version", rs.getInt(2), "contenu", rs.getString(3), "nom", rs.getString(4),
                        "espace", String.valueOf(rs.getString(5)), "page", String.valueOf(rs.getString(6))))
                .optional().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Spec introuvable."));
        long projet = (long) spec.get("projet");
        String espace = !"null".equals(spec.get("espace")) ? (String) spec.get("espace")
                : demande.espace() == null ? "" : demande.espace().strip().toUpperCase();
        if (espace.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Clé de l'espace Confluence obligatoire.");
        }
        String titre = "Spec — " + spec.get("nom");
        var adf = Adf.document(sansTitre((String) spec.get("contenu")));
        String message = "Spec version " + spec.get("version") + " (productDesigner)";

        String pageId = (String) spec.get("page");
        var existante = "null".equals(pageId) ? java.util.Optional.<AtlassianClient.Page>empty() : atlassian.page(pageId);
        AtlassianClient.Page page;
        boolean creee = existante.isEmpty();
        try {
            page = creee ? atlassian.creerPage(atlassian.espace(espace), titre, adf)
                    : atlassian.modifierPage(pageId, existante.get().version(), titre, adf, message);
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

    /** Le titre `# ` de la Spec double celui de la page Confluence. */
    static String sansTitre(String contenu) {
        return contenu.replaceFirst("^\\s*# [^\\n]*\\n", "");
    }
}
