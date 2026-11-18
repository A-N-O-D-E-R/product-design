package com.anode.productdesigner.service;

import java.util.function.Supplier;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.server.ResponseStatusException;

/**
 * Appels aux clients des starters Jira et Confluence de la collection (`JiraClient`, `ConfluenceClient`). Les reprises sur
 * 429 (Retry-After compris) et 502/503/504 des requêtes idempotentes sont faites par les starters (`anode.jira.retry.*`,
 * `anode.confluence.retry.*`) ; restent ici la config absente et les messages dédiés pour 401 et pour un 429 persistant,
 * pour Jira comme pour Confluence (D-11).
 */
@Component
public class Atlassian {

    public static final String CONFIG_ABSENTE = "Config Atlassian absente : ajouter anode.jira.* (Jira) et anode.confluence.* (Confluence) :"
            + " base-url, email et api-token (et cloud-id pour un token scopé) dans ~/.productdesigner/application.yml, puis redémarrer.";

    /** Sans config anode.jira.* / anode.confluence.*, le starter ne crée pas le client : l'app démarre quand même. */
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

    /** 401 : message dédié. 429 : les reprises du starter sont épuisées (D-11). */
    public <T> T appel(Supplier<T> requete) {
        try {
            return requete.get();
        } catch (HttpClientErrorException.Unauthorized e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Token Jira expiré ou invalide.");
        } catch (HttpClientErrorException.TooManyRequests e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Jira limite le débit (429) : réessayer plus tard.");
        }
    }

    public void appel(Runnable requete) {
        appel(() -> {
            requete.run();
            return null;
        });
    }
}
