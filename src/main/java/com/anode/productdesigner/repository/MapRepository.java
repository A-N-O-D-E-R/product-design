package com.anode.productdesigner.repository;

import lombok.RequiredArgsConstructor;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Map d'un Projet : en-tête, Questions et leurs blocages, Brouillard, Hors périmètre. */
@Repository
@RequiredArgsConstructor
public class MapRepository {

    /** Listes de lignes de texte de la Map. */
    public enum Liste {
        BROUILLARD("brouillard"), HORS_PERIMETRE("hors_perimetre");

        private final String table;

        Liste(String table) {
            this.table = table;
        }
    }

    public record EnTete(String destination, String notes) {}

    public record QuestionLigne(long id, int numero, String titre, String corps, String type, String statut) {}

    public record Bloc(long questionId, int numero) {}

    public record Ligne(long id, String texte) {}

    private final JdbcClient jdbc;

    // --- Map ---

    public void creer(long projetId) {
        jdbc.sql("INSERT INTO map (projet_id) VALUES (?)").param(projetId).update();
    }

    public Optional<EnTete> enTete(long projetId) {
        return jdbc.sql("SELECT destination, notes FROM map WHERE projet_id = ?").param(projetId).query(EnTete.class).optional();
    }

    public void majEnTete(long projetId, String destination, String notes) {
        jdbc.sql("UPDATE map SET destination = ?, notes = ? WHERE projet_id = ?").params(destination, notes, projetId).update();
    }

    public void majDestination(long projetId, String destination) {
        jdbc.sql("UPDATE map SET destination = ? WHERE projet_id = ?").params(destination, projetId).update();
    }

    public String destination(long projetId) {
        return jdbc.sql("SELECT destination FROM map WHERE projet_id = ?").param(projetId).query(String.class).single();
    }

    // --- Questions ---

    public List<QuestionLigne> questions(long projetId) {
        return jdbc.sql("SELECT id, numero, titre, corps, type, statut FROM question WHERE projet_id = ? ORDER BY numero")
                .param(projetId).query(QuestionLigne.class).list();
    }

    /** Numéros des Questions bloquantes, par Question. */
    public List<Bloc> blocages(long projetId) {
        return jdbc.sql("""
                        SELECT qb.question_id, par.numero FROM question_bloquee qb
                        JOIN question par ON par.id = qb.bloquee_par_id
                        WHERE par.projet_id = ? ORDER BY par.numero""")
                .param(projetId).query(Bloc.class).list();
    }

    /**
     * Questions `open` dont toutes les bloquantes sont `resolved`, par numéro (E-4). Une bloquante sortie du
     * périmètre ne bloque plus : sa décision ne sera jamais prise.
     */
    public List<Long> frontiere(long projetId) {
        return jdbc.sql("""
                        SELECT q.id FROM question q
                        WHERE q.projet_id = ? AND q.statut = 'open'
                          AND NOT EXISTS (SELECT 1 FROM question_bloquee qb JOIN question par ON par.id = qb.bloquee_par_id
                                          WHERE qb.question_id = q.id AND par.statut NOT IN ('resolved', 'hors_perimetre'))
                        ORDER BY q.numero""")
                .param(projetId).query(Long.class).list();
    }

    public Optional<String> typeQuestion(long id, long projetId) {
        return jdbc.sql("SELECT type FROM question WHERE id = ? AND projet_id = ?").params(id, projetId).query(String.class).optional();
    }

    public String corpsQuestion(long id) {
        return jdbc.sql("SELECT corps FROM question WHERE id = ?").param(id).query(String.class).single();
    }

    public boolean questionExiste(long id, long projetId) {
        return jdbc.sql("SELECT count(*) FROM question WHERE id = ? AND projet_id = ?").params(id, projetId)
                .query(Integer.class).single() > 0;
    }

    public Optional<Long> idParTitre(long projetId, String titre) {
        return jdbc.sql("SELECT id FROM question WHERE projet_id = ? AND titre = ?").params(projetId, titre).query(Long.class).optional();
    }

    public Optional<Long> idParNumero(long projetId, int numero) {
        return jdbc.sql("SELECT id FROM question WHERE projet_id = ? AND numero = ?").params(projetId, numero).query(Long.class).optional();
    }

    public boolean titrePris(long projetId, String titre, long saufId) {
        return jdbc.sql("SELECT count(*) FROM question WHERE projet_id = ? AND titre = ? AND id <> ?")
                .params(projetId, titre, saufId).query(Integer.class).single() > 0;
    }

    public long insererQuestion(long projetId, String titre, String corps, String type) {
        return jdbc.sql("""
                        INSERT INTO question (projet_id, numero, titre, corps, type)
                        VALUES (?, (SELECT coalesce(max(numero), 0) + 1 FROM question WHERE projet_id = ?), ?, ?, ?)
                        RETURNING id""")
                .params(projetId, projetId, titre, corps, type).query(Long.class).single();
    }

    /** Sans effet si une Question de ce titre existe déjà dans le Projet. */
    public void insererQuestionSiNouvelle(long projetId, String titre, String corps, String type) {
        jdbc.sql("""
                        INSERT INTO question (projet_id, numero, titre, corps, type)
                        VALUES (?, (SELECT coalesce(max(numero), 0) + 1 FROM question WHERE projet_id = ?), ?, ?, ?)
                        ON CONFLICT (projet_id, titre) DO NOTHING""")
                .params(projetId, projetId, titre, corps, type).update();
    }

    public void majQuestion(long id, String titre, String corps, String type) {
        jdbc.sql("UPDATE question SET titre = ?, corps = ?, type = ? WHERE id = ?").params(titre, corps, type, id).update();
    }

    public void majStatut(long id, String statut) {
        jdbc.sql("UPDATE question SET statut = ? WHERE id = ?").params(statut, id).update();
    }

    /** Une Question passée hors périmètre pendant la Session le reste : seule une Question `claimed` change. */
    public void libererSiReclamee(long id, String statut) {
        jdbc.sql("UPDATE question SET statut = ? WHERE id = ? AND statut = 'claimed'").params(statut, id).update();
    }

    // --- Blocages ---

    public void supprimerBlocages(long questionId) {
        jdbc.sql("DELETE FROM question_bloquee WHERE question_id = ?").param(questionId).update();
    }

    public void bloquer(long questionId, long parId) {
        jdbc.sql("INSERT OR IGNORE INTO question_bloquee (question_id, bloquee_par_id) VALUES (?, ?)").params(questionId, parId).update();
    }

    /** Vrai si « id bloquée par par » fermerait un cycle : par est elle-même (transitivement) bloquée par id. */
    public boolean cycle(long id, long par) {
        return id == par || jdbc.sql("""
                        WITH RECURSIVE amont(qid) AS (
                            SELECT bloquee_par_id FROM question_bloquee WHERE question_id = ?
                            UNION SELECT qb.bloquee_par_id FROM question_bloquee qb JOIN amont ON qb.question_id = amont.qid)
                        SELECT count(*) FROM amont WHERE qid = ?""")
                .params(par, id).query(Integer.class).single() > 0;
    }

    // --- Brouillard et Hors périmètre ---

    public List<Ligne> lignes(Liste liste, long projetId) {
        return jdbc.sql("SELECT id, texte FROM " + liste.table + " WHERE projet_id = ? ORDER BY id")
                .param(projetId).query(Ligne.class).list();
    }

    public void ajouterLigne(Liste liste, long projetId, String texte) {
        jdbc.sql("INSERT INTO " + liste.table + " (projet_id, texte) VALUES (?, ?)").params(projetId, texte).update();
    }

    /** Vrai si la ligne existait. */
    public boolean modifierLigne(Liste liste, long projetId, long id, String texte) {
        return jdbc.sql("UPDATE " + liste.table + " SET texte = ? WHERE id = ? AND projet_id = ?").params(texte, id, projetId).update() > 0;
    }

    /** Vrai si la ligne existait. */
    public boolean supprimerLigne(Liste liste, long projetId, long id) {
        return jdbc.sql("DELETE FROM " + liste.table + " WHERE id = ? AND projet_id = ?").params(id, projetId).update() > 0;
    }

    public Optional<String> texteBrouillard(long id, long projetId) {
        return jdbc.sql("SELECT texte FROM brouillard WHERE id = ? AND projet_id = ?").params(id, projetId).query(String.class).optional();
    }

    public void supprimerBrouillard(long id) {
        jdbc.sql("DELETE FROM brouillard WHERE id = ?").param(id).update();
    }
}
