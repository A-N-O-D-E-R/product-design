package productdesigner;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** Tokens et coût estimé (D-29), par Session et par Projet, depuis le usage persisté sur chaque message. */
@RestController
@EnableConfigurationProperties(Consommation.Tarifs.class)
class Consommation {

    /** $ par million de tokens, par modèle. */
    record Tarif(BigDecimal input, BigDecimal output, BigDecimal cacheRead, BigDecimal cacheWrite) {}

    @ConfigurationProperties("productdesigner")
    record Tarifs(Map<String, Tarif> tarifs) {}

    /** cout : null si un modèle utilisé n'a pas de tarif en config. */
    record Total(long inputTokens, long outputTokens, long cacheReadTokens, long cacheWriteTokens, BigDecimal cout) {}

    private record Ligne(String modele, long input, long output, long cacheRead, long cacheWrite) {}

    private static final BigDecimal MILLION = BigDecimal.valueOf(1_000_000);

    private final JdbcClient jdbc;
    private final Map<String, Tarif> tarifs;

    Consommation(JdbcClient jdbc, Tarifs tarifs) {
        this.jdbc = jdbc;
        this.tarifs = tarifs.tarifs() == null ? Map.of() : tarifs.tarifs();
    }

    @GetMapping("/api/sessions/{id}/consommation")
    Total session(@PathVariable long id) {
        return total("m.session_id = ?", id);
    }

    /** Sessions du Projet, plus ses générations de Spec et de Roadmap. */
    @GetMapping("/api/projets/{id}/consommation")
    Total projet(@PathVariable long id) {
        return somme(lignes("s.projet_id = ?", id), jdbc.sql("""
                        SELECT modele, sum(input_tokens) AS input, sum(output_tokens) AS output,
                               sum(cache_read_tokens) AS cache_read, sum(cache_write_tokens) AS cache_write
                        FROM appel WHERE projet_id = ? GROUP BY modele""").param(id).query(Ligne.class).list());
    }

    /** Enregistre l'usage d'un appel hors Session. */
    void enregistrer(long projetId, String objet, Claude.Usage usage) {
        jdbc.sql("""
                        INSERT INTO appel (projet_id, objet, modele, input_tokens, output_tokens, cache_read_tokens, cache_write_tokens)
                        VALUES (?, ?, ?, ?, ?, ?, ?)""")
                .params(projetId, objet, usage.modele(), usage.input(), usage.output(), usage.cacheRead(), usage.cacheWrite())
                .update();
    }

    private Total total(String filtre, long id) {
        return somme(lignes(filtre, id), List.of());
    }

    private List<Ligne> lignes(String filtre, long id) {
        return jdbc.sql("""
                        SELECT m.modele, sum(m.input_tokens) AS input, sum(m.output_tokens) AS output,
                               sum(coalesce(m.cache_read_tokens, 0)) AS cache_read, sum(coalesce(m.cache_write_tokens, 0)) AS cache_write
                        FROM message m JOIN session s ON s.id = m.session_id
                        WHERE m.modele IS NOT NULL AND\s""" + filtre + " GROUP BY m.modele")
                .param(id).query(Ligne.class).list();
    }

    private Total somme(List<Ligne> sessions, List<Ligne> appels) {
        long input = 0, output = 0, cacheRead = 0, cacheWrite = 0;
        BigDecimal cout = BigDecimal.ZERO;
        for (Ligne l : java.util.stream.Stream.concat(sessions.stream(), appels.stream()).toList()) {
            input += l.input();
            output += l.output();
            cacheRead += l.cacheRead();
            cacheWrite += l.cacheWrite();
            Tarif t = tarifs.get(l.modele());
            cout = t == null || cout == null ? null : cout
                    .add(t.input().multiply(BigDecimal.valueOf(l.input())))
                    .add(t.output().multiply(BigDecimal.valueOf(l.output())))
                    .add(t.cacheRead().multiply(BigDecimal.valueOf(l.cacheRead())))
                    .add(t.cacheWrite().multiply(BigDecimal.valueOf(l.cacheWrite())));
        }
        return new Total(input, output, cacheRead, cacheWrite,
                cout == null ? null : cout.divide(MILLION, 6, RoundingMode.HALF_UP));
    }
}
