package com.anode.productdesigner.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import com.anode.productdesigner.repository.ConsommationRepository;
import com.anode.productdesigner.repository.ConsommationRepository.Ligne;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;

/** Tokens et coût estimé (D-29), par Session et par Projet, depuis le usage persisté sur chaque message. */
@Service
@EnableConfigurationProperties(ConsommationService.Tarifs.class)
public class ConsommationService {

    /** $ par million de tokens, par modèle. */
    public record Tarif(BigDecimal input, BigDecimal output, BigDecimal cacheRead, BigDecimal cacheWrite) {}

    @ConfigurationProperties("productdesigner")
    public record Tarifs(Map<String, Tarif> tarifs) {}

    /** cout : null si un modèle utilisé n'a pas de tarif en config. */
    public record Total(long inputTokens, long outputTokens, long cacheReadTokens, long cacheWriteTokens, BigDecimal cout) {}

    private static final BigDecimal MILLION = BigDecimal.valueOf(1_000_000);

    private final ConsommationRepository consommations;
    private final Map<String, Tarif> tarifs;

    public ConsommationService(ConsommationRepository consommations, Tarifs tarifs) {
        this.consommations = consommations;
        this.tarifs = tarifs.tarifs() == null ? Map.of() : tarifs.tarifs();
    }

    public Total session(long sessionId) {
        return somme(consommations.parSession(sessionId), List.of());
    }

    /** Sessions du Projet, plus ses générations de Spec et de Roadmap. */
    public Total projet(long projetId) {
        return somme(consommations.messagesDuProjet(projetId), consommations.appelsDuProjet(projetId));
    }

    /** Enregistre l'usage d'un appel hors Session. */
    public void enregistrer(long projetId, String objet, Llm.Reponse r) {
        consommations.enregistrerAppel(projetId, objet, r.modele(), r.input(), r.output(), r.cacheRead(), r.cacheWrite());
    }

    private Total somme(List<Ligne> sessions, List<Ligne> appels) {
        long input = 0, output = 0, cacheRead = 0, cacheWrite = 0;
        BigDecimal cout = BigDecimal.ZERO;
        for (Ligne l : Stream.concat(sessions.stream(), appels.stream()).toList()) {
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
