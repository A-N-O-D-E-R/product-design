package com.anode.productdesigner.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.zip.CRC32;

import lombok.RequiredArgsConstructor;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.DefaultOAuth2AuthenticatedPrincipal;
import org.springframework.security.oauth2.server.resource.introspection.BadOpaqueTokenException;
import org.springframework.security.oauth2.core.OAuth2AuthenticatedPrincipal;
import org.springframework.security.oauth2.server.resource.introspection.OpaqueTokenIntrospector;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Clés d'API personnelles `pd_<aléa><crc32>` (même forme que `ais_` d'ai-scientist et `idg_` d'idea-generator) : 32 octets
 * aléatoires en base64url plus un CRC32, pour repérer une faute de frappe ou un secret dans un dépôt sans interroger la base.
 * Seul le SHA-256 est stocké. Une clé ouvre l'API comme un utilisateur connecté, sans session ni jeton CSRF.
 */
@Service
@RequiredArgsConstructor
public class CleApi implements OpaqueTokenIntrospector {

    public static final String PREFIXE = "pd_";
    public static final int MAX_ACTIVES = 10;
    public static final Duration DUREE_DEFAUT = Duration.ofDays(90);
    /** Autorité des requêtes authentifiées par une clé plutôt que par une session de navigateur. */
    public static final String AUTORITE = "API_KEY";

    public record Creee(String id, String nom, String cle, OffsetDateTime expireLe) {}

    public record Cle(String id, String nom, String indice, String creePar, OffsetDateTime createdAt, OffsetDateTime expireLe, OffsetDateTime utiliseeLe) {}

    private static final SecureRandom ALEA = new SecureRandom();

    private final JdbcClient jdbc;

    @Transactional
    public Creee creer(String creePar, String nom, Duration duree) {
        int actives = jdbc.sql("SELECT count(*) FROM cle_api WHERE revoquee_le IS NULL AND expire_le > ?").param(maintenant()).query(Integer.class).single();
        if (actives >= MAX_ACTIVES) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Il y a déjà " + MAX_ACTIVES + " clés d'API actives : en révoquer une d'abord.");
        }
        var octets = new byte[32];
        ALEA.nextBytes(octets);
        String alea = Base64.getUrlEncoder().withoutPadding().encodeToString(octets);
        String cle = PREFIXE + alea + somme(alea);
        String id = UUID.randomUUID().toString();
        OffsetDateTime expireLe = maintenant().plus(duree);
        jdbc.sql("INSERT INTO cle_api (id, nom, hash, indice, cree_par, expire_le) VALUES (?, ?, ?, ?, ?, ?)")
                .params(id, nom, sha256(cle), cle.substring(0, PREFIXE.length() + 4) + "…", creePar, expireLe).update();
        return new Creee(id, nom, cle, expireLe);
    }

    /** Les clés actives seulement. */
    public List<Cle> lister() {
        return jdbc.sql("""
                        SELECT id, nom, indice, cree_par, created_at, expire_le, utilisee_le FROM cle_api
                        WHERE revoquee_le IS NULL AND expire_le > ? ORDER BY created_at""")
                .param(maintenant()).query(Cle.class).list();
    }

    public boolean revoquer(String id) {
        return jdbc.sql("UPDATE cle_api SET revoquee_le = ? WHERE id = ? AND revoquee_le IS NULL").params(maintenant(), id).update() == 1;
    }

    /** Point d'entrée de Spring Security : une clé active devient une identité « api-key:<id> ». */
    @Override
    public OAuth2AuthenticatedPrincipal introspect(String jeton) {
        return authentifier(jeton).orElseThrow(() -> new BadOpaqueTokenException("clé d'API invalide, expirée ou révoquée"));
    }

    Optional<OAuth2AuthenticatedPrincipal> authentifier(String jeton) {
        if (!bienFormee(jeton)) {
            return Optional.empty();
        }
        OffsetDateTime maintenant = maintenant();
        return jdbc.sql("""
                        UPDATE cle_api SET utilisee_le = ?
                        WHERE hash = ? AND revoquee_le IS NULL AND expire_le > ? RETURNING id""")
                .params(maintenant, sha256(jeton), maintenant).query(String.class).optional()
                .map(id -> new DefaultOAuth2AuthenticatedPrincipal("api-key:" + id, Map.of("cleApi", id),
                        List.of(new SimpleGrantedAuthority(AUTORITE))));
    }

    static boolean bienFormee(String jeton) {
        if (jeton == null || !jeton.startsWith(PREFIXE) || jeton.length() != PREFIXE.length() + 43 + 8) {
            return false;
        }
        String alea = jeton.substring(PREFIXE.length(), PREFIXE.length() + 43);
        return somme(alea).equals(jeton.substring(PREFIXE.length() + 43));
    }

    private static OffsetDateTime maintenant() {
        return OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS);
    }

    private static String somme(String alea) {
        var crc = new CRC32();
        crc.update(alea.getBytes(StandardCharsets.US_ASCII));
        return "%08x".formatted(crc.getValue());
    }

    private static byte[] sha256(String cle) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(cle.getBytes(StandardCharsets.US_ASCII));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
