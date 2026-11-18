package com.anode.productdesigner.config;

import java.io.IOException;
import java.util.Set;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Avec le login mock (n'importe quel mot de passe), `Host` doit être local : sans cela, un domaine externe résolu vers
 * 127.0.0.1 (DNS rebinding) pourrait ouvrir une session mock et appeler l'API. Inutile avec OIDC : le cookie de session
 * n'est pas envoyé à un autre nom d'hôte.
 */
class HoteLocalFilter extends OncePerRequestFilter {

    private static final Set<String> LOCAUX = Set.of("localhost", "127.0.0.1", "[::1]", "::1");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!hoteLocal(request.getServerName())) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "Hôte refusé : le login mock n'est servi que sur localhost.");
            return;
        }
        chain.doFilter(request, response);
    }

    static boolean hoteLocal(String host) {
        if (host == null) {
            return false;
        }
        String nom = host.startsWith("[") ? host.substring(0, host.indexOf(']') + 1) : host.replaceFirst(":\\d+$", "");
        return LOCAUX.contains(nom.toLowerCase());
    }
}
