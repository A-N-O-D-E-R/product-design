package com.anode.product.designer.service;

import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.server.ResponseStatusException;

/**
 * Appels aux clients d'atlassian-springboot-starter (`JiraClient`, `ConfluenceClient`), qui ne font aucune reprise :
 * config absente, 401 et 429 traités ici pour Jira comme pour Confluence (D-11).
 */
@Component
public class Atlassian {

    public static final String CONFIG_ABSENTE = "Config Atlassian absente (Jira et Confluence) : ajouter anode.atlassian.base-url, .email et .api-token"
            + " (et .cloud-id pour un token scopé) dans ~/.productdesigner/application.yml, puis redémarrer.";

    /** Tentatives au plus sur un 429 (D-11). */
    public static final int TENTATIVES = 4;

    private final long baseMs;

    public Atlassian(@Value("${productdesigner.jira.backoff-ms:2000}") long baseMs) {
        this.baseMs = baseMs;
    }

    /** Sans config anode.atlassian.*, le starter ne crée pas les clients : l'app démarre quand même. */
    public static <T> T client(ObjectProvider<T> client) {
        T c = client.getIfAvailable();
        if (c == null) {
            throw new ConfigManquanteException(CONFIG_ABSENTE);
        }
        return c;
    }

    /** Un 4xx dit clairement que rien n'a été créé ; un 5xx, un timeout ou une erreur réseau laissent le doute. */
    public static boolean incertain(RuntimeException e) {
        return !(e instanceof HttpClientErrorException) && !(e instanceof ResponseStatusException);
    }

    /**
     * 401 : message dédié. 429 : attendre `Retry-After`, sinon backoff exponentiel avec jitter
     * (base 2 s, ×0,7 à 1,3, plafond 30 s), {@value #TENTATIVES} tentatives au plus (D-11).
     */
    public <T> T appel(Supplier<T> requete) {
        for (int tentative = 1; ; tentative++) {
            try {
                return requete.get();
            } catch (HttpClientErrorException.Unauthorized e) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Token Jira expiré ou invalide.");
            } catch (HttpClientErrorException.TooManyRequests e) {
                if (tentative == TENTATIVES) {
                    throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Jira limite le débit (429) : réessayer plus tard.");
                }
                attendre(delai(e.getResponseHeaders() == null ? null : e.getResponseHeaders().getFirst("Retry-After"), tentative));
            }
        }
    }

    public void appel(Runnable requete) {
        appel(() -> {
            requete.run();
            return null;
        });
    }

    public long delai(String retryAfter, int tentative) {
        if (retryAfter != null) {
            try {
                return Long.parseLong(retryAfter.strip()) * 1000;
            } catch (NumberFormatException ignored) {
                // Date HTTP : on retombe sur le backoff.
            }
        }
        double jitter = 0.7 + ThreadLocalRandom.current().nextDouble() * 0.6;
        return Math.min(30_000, (long) (baseMs * Math.pow(2, tentative - 1) * jitter));
    }

    private static void attendre(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
