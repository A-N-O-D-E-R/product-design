package com.anode.productdesigner.controller;

import com.anode.productdesigner.service.SpecService;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;

import org.junit.jupiter.api.Test;

class SpecGabaritTest {

    static final String BRUT = """
            # Spec : Notes

            ## 2. Utilisateurs et parcours clés
            Une personne seule.

            ## Architecture
            Un jar unique [D-2].

            ## 1. Problème et Destination
            Prendre des notes.

            ## 5. Exigences
            - **E-1** Créer une note [D-1]
            - **E-2** Exporter en Markdown [D-9]
            - **E-4** Partager [D-2, D-8]
            - **E-3** Rechercher

            ## 4. Modèle de domaine
            - Note : un texte daté.

            ## 6. Décisions structurantes
            - D-1 : local.

            ## 7. Questions ouvertes / Brouillard restant
            Synchro.
            """;

    @Test
    void socleCompletDansLOrdrePuisOptionnelles() {
        String spec = SpecService.normaliser(BRUT);
        int precedent = -1;
        for (String titre : SpecService.SOCLE) {
            int i = spec.indexOf("## " + titre);
            assertThat(i).as(titre).isGreaterThan(precedent);
            precedent = i;
        }
        assertThat(spec).startsWith("# Spec : Notes");
        assertThat(spec).contains("## 3. Périmètre / Hors périmètre\n\nNon décidé.");
        assertThat(spec).contains("## 5. Exigences fonctionnelles\n\n- **E-1** Créer une note [D-1]");
        assertThat(spec.indexOf("## Architecture")).isGreaterThan(spec.indexOf("## 7."));
    }

    @Test
    void exigencesSansDecisionExistanteSignalees() {
        var avertissements = SpecService.verifier(SpecService.normaliser(BRUT), Set.of(1, 2));
        assertThat(avertissements).containsExactly(
                "E-2 ne cite aucune Décision existante (D-9 inconnue(s)).",
                "E-4 cite D-8, inconnue(s).",
                "E-3 ne cite aucune Décision existante.");
    }

    @Test
    void sectionDuSocleReconnueParSonNumeroExact() {
        String spec = SpecService.normaliser("# S\n\n## 10. Annexes\nA\n\n## 1. Problème\nP\n");
        assertThat(spec).contains("## 1. Problème et Destination\n\nP", "## 10. Annexes\n\nA");
    }
}
