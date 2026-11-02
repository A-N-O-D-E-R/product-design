package productdesigner;

import java.math.BigDecimal;
import java.math.RoundingMode;
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

    @GetMapping("/api/projets/{id}/consommation")
    Total projet(@PathVariable long id) {
        return total("s.projet_id = ?", id);
    }

    private Total total(String filtre, long id) {
        var lignes = jdbc.sql("""
                        SELECT m.modele, sum(m.input_tokens) AS input, sum(m.output_tokens) AS output,
                               sum(coalesce(m.cache_read_tokens, 0)) AS cache_read, sum(coalesce(m.cache_write_tokens, 0)) AS cache_write
                        FROM message m JOIN session s ON s.id = m.session_id
                        WHERE m.modele IS NOT NULL AND\s""" + filtre + " GROUP BY m.modele")
                .param(id).query(Ligne.class).list();
        long input = 0, output = 0, cacheRead = 0, cacheWrite = 0;
        BigDecimal cout = BigDecimal.ZERO;
        for (Ligne l : lignes) {
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
