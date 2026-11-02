package productdesigner;

import java.io.IOException;
import java.net.URI;
import java.util.Set;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * L'API n'a pas d'authentification (D-3) : seul le navigateur de la machine, sur l'app elle-même, peut l'appeler.
 * - `Host` doit être local : bloque le DNS rebinding (un domaine externe résolu vers 127.0.0.1).
 * - `Origin`, quand il est présent, doit être local : bloque le CSRF depuis un autre site, et les prototypes
 *   (iframe sandbox sans allow-same-origin, donc `Origin: null`). Sans `Origin` (curl, tests), la requête passe.
 */
@Component
class OrigineFilter extends OncePerRequestFilter {

    private static final Set<String> LOCAUX = Set.of("localhost", "127.0.0.1", "[::1]", "::1");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (request.getRequestURI().startsWith("/api/") && !(hoteLocal(request.getHeader("Host")) && origineLocale(request.getHeader("Origin")))) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "Origine refusée.");
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

    static boolean origineLocale(String origin) {
        if (origin == null) {
            return true;
        }
        try {
            URI uri = URI.create(origin);
            return ("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) && uri.getHost() != null
                    && LOCAUX.contains(uri.getHost().toLowerCase());
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
