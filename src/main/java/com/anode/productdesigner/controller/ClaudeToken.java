package com.anode.productdesigner.controller;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.HashMap;
import java.util.Map;

import com.anode.ai.claude.ClaudeCodeEnvironmentProvider;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Token Claude saisi dans l'interface (OAuth de `claude setup-token`, ou clé API Console), injecté dans chaque
 * processus du CLI Claude Code. Écrit en lecture seule pour le propriétaire ; jamais renvoyé ni journalisé.
 * Sans token, le CLI garde la connexion locale de la machine (`claude login`).
 */
@RestController
@RequestMapping("/api/config/token")
class ClaudeToken implements ClaudeCodeEnvironmentProvider {

    private static final Logger log = LoggerFactory.getLogger(ClaudeToken.class);

    record Saisie(String token) {}

    /** type : oauth_token ou api_key ; indice : 10 premiers caractères. null si aucun token. */
    record Etat(boolean configure, String type, String indice) {}

    private final Path fichier;
    private volatile String token;

    ClaudeToken(@Value("${user.home}/.productdesigner/claude-token") Path fichier) {
        this.fichier = fichier;
        try {
            if (Files.isRegularFile(fichier)) {
                token = Files.readString(fichier, StandardCharsets.UTF_8).strip();
            }
        } catch (IOException e) {
            log.warn("Token Claude illisible dans {} : {}", fichier, e.getMessage());
        }
    }

    private boolean present() {
        return token != null && !token.isEmpty();
    }

    /** Les clés API Console passent par ANTHROPIC_API_KEY, le reste (OAuth) par CLAUDE_CODE_OAUTH_TOKEN. */
    private boolean cleApi() {
        return token.startsWith("sk-ant-api");
    }

    @GetMapping
    Etat etat() {
        return present() ? new Etat(true, cleApi() ? "api_key" : "oauth_token", token.substring(0, Math.min(10, token.length())) + "…")
                : new Etat(false, null, null);
    }

    @PutMapping
    synchronized Etat enregistrer(@RequestBody Saisie saisie) {
        String valeur = saisie == null || saisie.token() == null ? "" : saisie.token().strip();
        if (valeur.length() >= 2 && (valeur.startsWith("'") && valeur.endsWith("'") || valeur.startsWith("\"") && valeur.endsWith("\""))) {
            valeur = valeur.substring(1, valeur.length() - 1).strip();
        }
        if (valeur.isEmpty() || valeur.chars().anyMatch(Character::isWhitespace)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Token attendu : une seule valeur, sans espace ni retour à la ligne.");
        }
        token = valeur;
        try {
            Files.createDirectories(fichier.toAbsolutePath().getParent());
            if (!Files.exists(fichier)) {
                try {
                    Files.createFile(fichier, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
                } catch (UnsupportedOperationException e) {
                    Files.createFile(fichier);
                }
            }
            Files.writeString(fichier, token, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("Token Claude gardé en mémoire seulement, écriture impossible dans {} : {}", fichier, e.getMessage());
        }
        return etat();
    }

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    synchronized void effacer() {
        token = null;
        try {
            Files.deleteIfExists(fichier);
        } catch (IOException e) {
            log.warn("Suppression impossible de {} : {}", fichier, e.getMessage());
        }
    }

    @Override
    public Map<String, String> environment() {
        Map<String, String> env = new HashMap<>();
        String t = token;
        if (t == null || t.isEmpty()) {
            return env;
        }
        boolean cle = t.startsWith("sk-ant-api");
        // null retire la variable héritée : un seul mode d'authentification à la fois.
        env.put("ANTHROPIC_API_KEY", cle ? t : null);
        env.put("CLAUDE_CODE_OAUTH_TOKEN", cle ? null : t);
        return env;
    }
}
