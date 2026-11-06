package ooo.klae.connex.backend.sso;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;
import ooo.klae.connex.backend.config.RequestPathNormalizer;

/** Captures return navigation only at an OAuth2/social or SAML authentication-start endpoint. */
public final class AuthenticationReturnPathFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String path = RequestPathNormalizer.apiPath(request);
        if ("GET".equals(request.getMethod()) && (path.matches("/api/oauth2/authorization/[a-zA-Z0-9_-]+")
                || path.matches("/saml2/authenticate/org-[0-9]+"))) {
            AuthenticationReturnPathSession.remember(request);
        }
        filterChain.doFilter(request, response);
    }
}
