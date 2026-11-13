package com.anode.product.designer.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Lecture d'une Roadmap (E-23 à E-25), partagée par l'édition et l'export Jira. */
@Service
public class RoadmapService {

    public record Ticket(long id, int ordre, String titre, String corps, List<String> exigences, List<Long> blockedBy, String jiraKey) {}

    public record Jalon(long id, int ordre, String titre, String jiraKey, List<Ticket> tickets) {}

    /** jiraProjet : projet Jira de la première exportation, null avant. */
    public record Roadmap(long specId, int version, String jiraProjet, List<Jalon> jalons) {}

    private record JalonLigne(long id, int ordre, String titre, String jiraKey) {}

    private record TicketLigne(long id, long jalonId, int ordre, String titre, String corps, String exigences, String jiraKey) {}

    private record Bloc(long ticketId, long parId) {}

    private final JdbcClient jdbc;

    public RoadmapService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Roadmap roadmap(long specId) {
        Object[] spec = jdbc.sql("SELECT version, jira_projet FROM spec WHERE id = ?").param(specId)
                .query((rs, n) -> new Object[] {rs.getInt(1), rs.getString(2)}).optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Spec introuvable."));
        Map<Long, List<Long>> blocages = new HashMap<>();
        for (Bloc b : jdbc.sql("""
                        SELECT tb.ticket_id, tb.bloque_par_id AS par_id FROM ticket_bloque tb
                        JOIN ticket t ON t.id = tb.ticket_id JOIN jalon j ON j.id = t.jalon_id WHERE j.spec_id = ?""")
                .param(specId).query(Bloc.class).list()) {
            blocages.computeIfAbsent(b.ticketId(), k -> new ArrayList<>()).add(b.parId());
        }
        Map<Long, List<Ticket>> parJalon = new HashMap<>();
        for (TicketLigne t : jdbc.sql("""
                        SELECT t.id, t.jalon_id, t.ordre, t.titre, t.corps, t.exigences, t.jira_key FROM ticket t
                        JOIN jalon j ON j.id = t.jalon_id WHERE j.spec_id = ? ORDER BY t.ordre""")
                .param(specId).query(TicketLigne.class).list()) {
            List<String> exigences = t.exigences().isBlank() ? List.of() : List.of(t.exigences().split(",\\s*"));
            parJalon.computeIfAbsent(t.jalonId(), k -> new ArrayList<>()).add(new Ticket(t.id(), t.ordre(), t.titre(), t.corps(),
                    exigences, blocages.getOrDefault(t.id(), List.of()), t.jiraKey()));
        }
        List<Jalon> jalons = jdbc.sql("SELECT id, ordre, titre, jira_key FROM jalon WHERE spec_id = ? ORDER BY ordre")
                .param(specId).query(JalonLigne.class).list().stream()
                .map(j -> new Jalon(j.id(), j.ordre(), j.titre(), j.jiraKey(), parJalon.getOrDefault(j.id(), List.of())))
                .toList();
        return new Roadmap(specId, (int) spec[0], (String) spec[1], jalons);
    }
}
