package productdesigner;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class AdfTest {

    @Test
    void titresParagraphesListesEtCases() {
        Map<String, Object> doc = Adf.document("""
                L'utilisateur crée une **note**
                et la retrouve.

                ## Critères d'acceptation

                - [ ] La note apparaît
                - [x] Elle persiste avec `id`
                """);
        assertThat(doc).containsEntry("type", "doc").containsEntry("version", 1);
        List<?> contenu = (List<?>) doc.get("content");
        assertThat(contenu).hasSize(3);
        assertThat(contenu.get(0).toString()).contains("type=paragraph", "text=L'utilisateur crée une ", "text=note", "type=strong", "et la retrouve.");
        assertThat(contenu.get(1).toString()).contains("type=heading", "level=2", "Critères d'acceptation");
        assertThat(contenu.get(2).toString()).contains("type=bulletList", "☐ La note apparaît", "☑ Elle persiste avec ", "text=id", "type=code");
    }

    @Test
    void videDonneUnDocumentValide() {
        assertThat(Adf.document("")).containsEntry("content", List.of());
    }
}
