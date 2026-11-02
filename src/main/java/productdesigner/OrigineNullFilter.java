package productdesigner;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Un prototype tourne dans une iframe sandbox sans allow-same-origin : ses requêtes portent `Origin: null`.
 * Le CORS bloque déjà la lecture des réponses ; ce filtre bloque aussi les écritures « simples » (POST sans corps JSON),
 * en complément de la CSP injectée côté front.
 */
@Component
class OrigineNullFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (request.getRequestURI().startsWith("/api/") && "null".equals(request.getHeader("Origin"))) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "Origine refusée.");
            return;
        }
        chain.doFilter(request, response);
    }
}
