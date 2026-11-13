package com.anode.product.designer.repository;

import lombok.RequiredArgsConstructor;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Jalons, Tickets et leurs prérequis d'une Spec, avec le suivi de l'export Jira. */
@Repository
@RequiredArgsConstructor
public class RoadmapRepository {

    /** jiraProjet : projet Jira de la première exportation, null avant. */
    public record SpecLigne(int version, String jiraProjet) {}

    public record JalonLigne(long id, int ordre, String titre, String jiraKey) {}

    public record TicketLigne(long id, long jalonId, int ordre, String titre, String corps, String exigences, String jiraKey) {}

    public record Bloc(long ticketId, long parId) {}

    /** Prérequis entre deux Tickets exportés, pas encore liés dans Jira. */
    public record Lien(long ticketId, long parId, String cle, String parCle) {}

    private final JdbcClient jdbc;

    // --- Lecture ---

    public Optional<SpecLigne> spec(long specId) {
        return jdbc.sql("SELECT version, jira_projet FROM spec WHERE id = ?").param(specId).query(SpecLigne.class).optional();
    }

    public List<JalonLigne> jalons(long specId) {
        return jdbc.sql("SELECT id, ordre, titre, jira_key FROM jalon WHERE spec_id = ? ORDER BY ordre")
                .param(specId).query(JalonLigne.class).list();
    }

    public List<TicketLigne> tickets(long specId) {
        return jdbc.sql("""
                        SELECT t.id, t.jalon_id, t.ordre, t.titre, t.corps, t.exigences, t.jira_key FROM ticket t
                        JOIN jalon j ON j.id = t.jalon_id WHERE j.spec_id = ? ORDER BY t.ordre""")
                .param(specId).query(TicketLigne.class).list();
    }

    public List<Bloc> blocages(long specId) {
        return jdbc.sql("""
                        SELECT tb.ticket_id, tb.bloque_par_id AS par_id FROM ticket_bloque tb
                        JOIN ticket t ON t.id = tb.ticket_id JOIN jalon j ON j.id = t.jalon_id WHERE j.spec_id = ?""")
                .param(specId).query(Bloc.class).list();
    }

    public Optional<Long> specDuJalon(long jalonId) {
        return jdbc.sql("SELECT spec_id FROM jalon WHERE id = ?").param(jalonId).query(Long.class).optional();
    }

    public Optional<Long> specDuTicket(long ticketId) {
        return jdbc.sql("SELECT j.spec_id FROM ticket t JOIN jalon j ON j.id = t.jalon_id WHERE t.id = ?").param(ticketId)
                .query(Long.class).optional();
    }

    public long jalonDuTicket(long ticketId) {
        return jdbc.sql("SELECT jalon_id FROM ticket WHERE id = ?").param(ticketId).query(Long.class).single();
    }

    public int ordreSuivant(long specId) {
        return jdbc.sql("SELECT coalesce(max(t.ordre), 0) + 1 FROM ticket t JOIN jalon j ON j.id = t.jalon_id WHERE j.spec_id = ?")
                .param(specId).query(Integer.class).single();
    }

    // --- Écriture ---

    public long insererJalon(long specId, int ordre, String titre) {
        return jdbc.sql("INSERT INTO jalon (spec_id, ordre, titre) VALUES (?, ?, ?) RETURNING id")
                .params(specId, ordre, titre).query(Long.class).single();
    }

    public long insererTicket(long jalonId, int ordre, String titre, String corps, String exigences) {
        return jdbc.sql("INSERT INTO ticket (jalon_id, ordre, titre, corps, exigences) VALUES (?, ?, ?, ?, ?) RETURNING id")
                .params(jalonId, ordre, titre, corps, exigences).query(Long.class).single();
    }

    public long insererTicket(long jalonId, int ordre, String titre) {
        return jdbc.sql("INSERT INTO ticket (jalon_id, ordre, titre) VALUES (?, ?, ?) RETURNING id")
                .params(jalonId, ordre, titre).query(Long.class).single();
    }

    public void renommerJalon(long id, String titre) {
        jdbc.sql("UPDATE jalon SET titre = ? WHERE id = ?").params(titre, id).update();
    }

    public void majTicket(long id, String titre, String corps, String exigences) {
        jdbc.sql("UPDATE ticket SET titre = ?, corps = ?, exigences = ? WHERE id = ?").params(titre, corps, exigences, id).update();
    }

    public void deplacerTicket(long id, long jalonId, int ordre) {
        jdbc.sql("UPDATE ticket SET jalon_id = ?, ordre = ? WHERE id = ?").params(jalonId, ordre, id).update();
    }

    public void supprimerTicket(long id) {
        jdbc.sql("DELETE FROM ticket WHERE id = ?").param(id).update();
    }

    public void supprimerJalons(long specId) {
        jdbc.sql("DELETE FROM jalon WHERE spec_id = ?").param(specId).update();
    }

    public void supprimerPrerequis(long ticketId) {
        jdbc.sql("DELETE FROM ticket_bloque WHERE ticket_id = ?").param(ticketId).update();
    }

    public void ajouterPrerequis(long ticketId, long parId) {
        jdbc.sql("INSERT OR IGNORE INTO ticket_bloque (ticket_id, bloque_par_id) VALUES (?, ?)").params(ticketId, parId).update();
    }

    // --- Éléments Jira laissés orphelins (E-28) ---

    /** Les issues Jira de tous les Jalons et Tickets de la Spec, avant qu'ils soient remplacés. */
    public void orphelinerRoadmap(long specId) {
        jdbc.sql("""
                        INSERT OR IGNORE INTO jira_orphelin (spec_id, cle, titre)
                        SELECT ?, jira_key, titre FROM jalon WHERE spec_id = ? AND jira_key IS NOT NULL
                        UNION ALL SELECT ?, t.jira_key, t.titre FROM ticket t JOIN jalon j ON j.id = t.jalon_id
                        WHERE j.spec_id = ? AND t.jira_key IS NOT NULL""")
                .params(specId, specId, specId, specId).update();
    }

    public void orphelinerTicket(long specId, long ticketId) {
        jdbc.sql("INSERT OR IGNORE INTO jira_orphelin (spec_id, cle, titre) SELECT ?, jira_key, titre FROM ticket WHERE id = ? AND jira_key IS NOT NULL")
                .params(specId, ticketId).update();
    }

    public List<String> orphelines(long specId) {
        return jdbc.sql("SELECT cle || ' (' || titre || ')' FROM jira_orphelin WHERE spec_id = ? ORDER BY id")
                .param(specId).query(String.class).list();
    }

    // --- Suivi de l'export Jira ---

    public Set<Long> jalonsIncertains(long specId) {
        return Set.copyOf(jdbc.sql("SELECT id FROM jalon WHERE spec_id = ? AND jira_incertain = 1").param(specId).query(Long.class).list());
    }

    public Set<Long> ticketsIncertains(long specId) {
        return Set.copyOf(jdbc.sql("SELECT t.id FROM ticket t JOIN jalon j ON j.id = t.jalon_id WHERE j.spec_id = ? AND t.jira_incertain = 1")
                .param(specId).query(Long.class).list());
    }

    public void majCleJalon(long id, String cle) {
        jdbc.sql("UPDATE jalon SET jira_key = ?, jira_incertain = 0 WHERE id = ?").params(cle, id).update();
    }

    public void majCleTicket(long id, String cle) {
        jdbc.sql("UPDATE ticket SET jira_key = ?, jira_incertain = 0 WHERE id = ?").params(cle, id).update();
    }

    public void majIncertainJalon(long id, boolean incertain) {
        jdbc.sql("UPDATE jalon SET jira_incertain = ? WHERE id = ?").params(incertain, id).update();
    }

    public void majIncertainTicket(long id, boolean incertain) {
        jdbc.sql("UPDATE ticket SET jira_incertain = ? WHERE id = ?").params(incertain, id).update();
    }

    public List<Lien> liensANouer(long specId) {
        return jdbc.sql("""
                        SELECT tb.ticket_id, tb.bloque_par_id AS par_id, t.jira_key AS cle, par.jira_key AS par_cle
                        FROM ticket_bloque tb JOIN ticket t ON t.id = tb.ticket_id JOIN ticket par ON par.id = tb.bloque_par_id
                        JOIN jalon j ON j.id = t.jalon_id
                        WHERE j.spec_id = ? AND tb.jira_lie = 0 AND t.jira_key IS NOT NULL AND par.jira_key IS NOT NULL""")
                .param(specId).query(Lien.class).list();
    }

    public void marquerLie(long ticketId, long parId) {
        jdbc.sql("UPDATE ticket_bloque SET jira_lie = 1 WHERE ticket_id = ? AND bloque_par_id = ?").params(ticketId, parId).update();
    }

    public void memoriserProjetJira(long specId, String projet) {
        jdbc.sql("UPDATE spec SET jira_projet = ? WHERE id = ?").params(projet, specId).update();
    }
}
