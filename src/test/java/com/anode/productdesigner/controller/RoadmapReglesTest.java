package com.anode.productdesigner.controller;

import com.anode.productdesigner.service.RoadmapService;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;

class RoadmapReglesTest {

    @Test
    void titreDeJalonSansNumero() {
        assertThat(RoadmapService.sansNumero("Jalon 1 — Carnet structuré")).isEqualTo("Carnet structuré");
        assertThat(RoadmapService.sansNumero("Jalon 12: Export")).isEqualTo("Export");
        assertThat(RoadmapService.sansNumero("Socle")).isEqualTo("Socle");
    }

    @Test
    void prerequisCiteAvecUneFaute() {
        Map<String, Long> titres = Map.of("Référentiels fermés des rayons et des unités", 1L, "Panier de la semaine", 2L);
        assertThat(RoadmapService.proche(titres, "Référentiels fencés des rayons et des unités")).isEqualTo(1L);
        assertThat(RoadmapService.proche(titres, "Tout autre chose")).isNull();
        // Ambigu : deux titres à portée, aucun choisi.
        assertThat(RoadmapService.proche(Map.of("Lister A", 1L, "Lister B", 2L), "Lister C")).isNull();
    }
}
