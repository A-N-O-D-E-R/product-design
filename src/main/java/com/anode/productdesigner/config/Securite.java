package com.anode.productdesigner.config;

import java.net.InetAddress;
import java.net.URI;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import com.anode.productdesigner.service.CleApi;
import com.anode.security.AuthorizationRules;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.AuthenticationManagerResolver;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.server.resource.authentication.OpaqueTokenAuthenticationProvider;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.NegatedRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import tools.jackson.databind.json.JsonMapper;

/**
 * Ce qui est propre à productDesigner au-dessus du starter de sécurité (`anode.security.*` : OIDC par `env-oidc`, login mock
 * pour le poste local par `mock`, CSRF des SPA). Le starter construit la chaîne ; ici, des beans qu'il reprend :
 * <ul>
 *   <li>seul `/api/**` demande une connexion (le front statique et la page de login sont publics) ;</li>
 *   <li>`productdesigner.allowed-users` : qui peut entrer (nom, ou e-mail vérifié d'OIDC) ; vide = tout compte connecté ;</li>
 *   <li>clés d'API `pd_...` en jeton Bearer pour les autres applications (sans session ni CSRF) ;</li>
 *   <li>erreurs 401 et 403 de l'API en RFC 9457 (`application/problem+json`) ;</li>
 *   <li>avec le login mock : `Host` local seulement (DNS rebinding), et le serveur doit écouter en local.</li>
 * </ul>
 * Aucune donnée n'est propre à un utilisateur : tout compte autorisé voit tous les Projets.
 */
@Configuration
@ConditionalOnProperty(name = "anode.security.enabled", havingValue = "true", matchIfMissing = true)
class Securite {

    static final RequestMatcher API = PathPatternRequestMatcher.withDefaults().matcher("/api/**");

    @Bean
    AuthorizationRules apiProtegee(@Value("${productdesigner.allowed-users:}") String autorises) {
        Set<String> permis = Arrays.stream(autorises.split("[,\\s]+")).filter(x -> !x.isBlank()).collect(Collectors.toSet());
        return rules -> {
            rules.requestMatchers(new NegatedRequestMatcher(API)).permitAll();
            rules.requestMatchers(API).access((auth, contexte) -> {
                Authentication a = auth.get();
                boolean connecte = a != null && a.isAuthenticated() && !(a instanceof AnonymousAuthenticationToken);
                return new AuthorizationDecision(connecte && autorise(a, permis));
            });
        };
    }

    static boolean autorise(Authentication a, Set<String> permis) {
        if (permis.isEmpty() || a.getAuthorities().stream().anyMatch(g -> CleApi.AUTORITE.equals(g.getAuthority()))) {
            return true; // une clé d'API a été créée par un utilisateur autorisé
        }
        return permis.contains(a.getName())
                || a.getPrincipal() instanceof OidcUser u && Boolean.TRUE.equals(u.getEmailVerified()) && u.getEmail() != null && permis.contains(u.getEmail());
    }

    /** Clés d'API `pd_...` en jeton Bearer (le starter résoudrait sinon des émetteurs JWT). */
    @Bean
    AuthenticationManagerResolver<HttpServletRequest> cleApiBearer(CleApi cles) {
        var manager = new ProviderManager(new OpaqueTokenAuthenticationProvider(cles));
        return requete -> manager;
    }

    /** 401 et 403 de `/api` en problem+json ; les pages vont au login. */
    @Bean
    Customizer<HttpSecurity> erreursProblem(JsonMapper json) {
        return http -> http.exceptionHandling(e -> e
                .defaultAuthenticationEntryPointFor((req, res, ex) -> problem(json, res, HttpStatus.UNAUTHORIZED, "Non authentifié",
                        "Connexion requise."), API)
                .accessDeniedHandler((req, res, ex) -> problem(json, res, HttpStatus.FORBIDDEN, "Interdit",
                        ex instanceof org.springframework.security.web.csrf.CsrfException ? "Jeton CSRF absent ou invalide." : "Accès refusé.")));
    }

    private static void problem(JsonMapper json, jakarta.servlet.http.HttpServletResponse res, HttpStatus statut, String titre, String detail)
            throws java.io.IOException {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(statut, detail);
        p.setTitle(titre);
        res.setStatus(statut.value());
        res.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        res.setCharacterEncoding("UTF-8");
        res.getWriter().write(json.writeValueAsString(p));
    }

    /** Login mock : `Host` local seulement. */
    @Bean
    Customizer<HttpSecurity> hoteLocalSiMock(@Value("${anode.security.mock:false}") boolean mock) {
        return http -> {
            if (mock) {
                http.addFilterBefore(new HoteLocalFilter(), SecurityContextHolderFilter.class);
            }
        };
    }

    /** Le démarrage échoue plutôt que d'exposer l'API : un login sans mot de passe sur une interface réseau, ou aucun login. */
    @Bean
    GardeSecurite gardeSecurite(@Value("${anode.security.mock:false}") boolean mock, @Value("${anode.security.env-oidc:false}") boolean oidc,
                                @Value("${server.address:}") String adresse) {
        if (!mock && !oidc) {
            throw new IllegalStateException("Aucun login configuré : anode.security.mock=true (poste local, mot de passe libre, loopback"
                    + " seulement) ou anode.security.env-oidc=true avec spring.security.oauth2.client.registration.oidc.* (voir README).");
        }
        if (mock && !boucleLocale(adresse)) {
            throw new IllegalStateException("anode.security.mock=true est un login sans mot de passe : server.address doit être une adresse"
                    + " locale (127.0.0.1), pas '" + adresse + "'. Pour servir d'autres machines : anode.security.env-oidc=true.");
        }
        return new GardeSecurite();
    }

    static boolean boucleLocale(String adresse) {
        try {
            return adresse != null && !adresse.isBlank() && InetAddress.getByName(adresse).isLoopbackAddress();
        } catch (java.net.UnknownHostException e) {
            return false;
        }
    }

    /** Marqueur : la garde a passé. */
    record GardeSecurite() {}
}
