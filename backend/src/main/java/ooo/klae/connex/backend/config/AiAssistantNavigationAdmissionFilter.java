package ooo.klae.connex.backend.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Admits only the request shapes the assistant SPA actually makes, so an audited read cannot be
 * driven from an attacker's page.
 *
 * <p>The assistant reads write durable {@code ai.assistant.session.read} rows. Any such row reached
 * from a cross-site request would be forgeable under the victim's actor id, so the shape is
 * constrained rather than the method.
 *
 * <p>Keying on navigation alone would be wrong. {@code application.yml} sets the session cookie to
 * {@code ${CONNEX_SESSION_COOKIE_SAME_SITE:lax}}, and {@code SecurityConfig} records that a
 * SAML-enabled deployment must set {@code CONNEX_SESSION_COOKIE_SAME_SITE=none}. With
 * {@code SameSite=None} the cookie rides every cross-site request, including subresources, so an
 * {@code <img>} tag or an opaque {@code no-cors} fetch would reach the handler without ever
 * navigating.
 *
 * <p>Three conditions therefore refuse a request: a {@code Sec-Fetch-Dest} other than
 * {@code empty}, which excludes documents, images, scripts, frames and every other subresource
 * load; a {@code Sec-Fetch-Mode} of {@code no-cors}, which is the opaque fetch the SPA never makes;
 * and a {@code Sec-Fetch-Site} outside {@code same-origin} and {@code none} whose {@code Origin} is
 * not already trusted by the CORS allowlist, which keeps a genuinely cross-origin frontend working
 * while refusing an unapproved one.
 *
 * <p>Residual: this fails open when Fetch Metadata is absent. The headers shipped in Chrome and
 * Edge 80, Firefox 90 and Safari 16.4, so a browser older than those sends the cookie and no
 * metadata and is admitted. Requests without the headers are admitted deliberately, because
 * non-browser clients and server-side fetches hold no ambient cross-site authority and would
 * otherwise break. What a forgery can achieve stays bounded either way: the row names only a
 * session the caller was already granted access to, and carries no caller-supplied text.
 */
public class AiAssistantNavigationAdmissionFilter extends OncePerRequestFilter {
    private static final String PATH_PREFIX = "/api/ai/assistant/";
    private static final String FORBIDDEN = "Assistant API request shape is not allowed";
    private static final String EMPTY_DESTINATION = "empty";
    private static final String OPAQUE_MODE = "no-cors";
    private static final Set<String> TRUSTED_SITES = Set.of("same-origin", "none");

    private final Set<String> allowedOrigins;

    public AiAssistantNavigationAdmissionFilter(String[] allowedOrigins) {
        this.allowedOrigins = Set.copyOf(List.of(allowedOrigins));
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !apiPath(request).startsWith(PATH_PREFIX);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        if (refused(request)) {
            reject(response, HttpServletResponse.SC_FORBIDDEN, FORBIDDEN);
            return;
        }
        chain.doFilter(request, response);
    }

    private boolean refused(HttpServletRequest request) {
        String destination = normalized(request, "Sec-Fetch-Dest");
        if (destination != null && !EMPTY_DESTINATION.equals(destination)) {
            return true;
        }
        if (OPAQUE_MODE.equals(normalized(request, "Sec-Fetch-Mode"))) {
            return true;
        }
        String site = normalized(request, "Sec-Fetch-Site");
        if (site == null || TRUSTED_SITES.contains(site)) {
            return false;
        }
        String origin = request.getHeader("Origin");
        return origin == null || !allowedOrigins.contains(origin);
    }

    private static String normalized(HttpServletRequest request, String headerName) {
        String value = request.getHeader(headerName);
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private static String apiPath(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isBlank() && uri.startsWith(contextPath)) {
            return uri.substring(contextPath.length());
        }
        return uri;
    }

    private static void reject(HttpServletResponse response, int status, String message)
            throws IOException {
        SecurityResponseHeaders.apply(response);
        response.setHeader("Cache-Control", "no-store");
        response.setStatus(status);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.TEXT_PLAIN_VALUE);
        response.getWriter().write(message);
    }
}
