package ooo.klae.connex.backend.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Refuses browser document navigation to assistant APIs before an audited read can run.
 *
 * <p>{@code JSESSIONID} is {@code SameSite=Lax}, and Lax is still attached to a cross-site
 * top-level navigation, so a {@code GET} that writes an audit row is otherwise forgeable by an
 * attacker page that navigates a signed-in victim to the URL. Refusing navigation closes that
 * vector without refusing the SPA's ordinary same-origin or cross-origin CORS fetches.
 *
 * <p>Requests carrying no Fetch Metadata are admitted, because non-browser clients and server-side
 * fetches hold no ambient cross-site browser authority and would otherwise break.
 *
 * <p>Residual, stated precisely rather than assumed away: this filter fails open, and Fetch
 * Metadata is younger than {@code SameSite}. {@code Sec-Fetch-Dest} and {@code Sec-Fetch-Mode}
 * shipped in Chrome and Edge 80, Firefox 90 and Safari 16.4, whereas the explicit
 * {@code SameSite=Lax} attribute has been honoured since Chrome 51, Firefox 60 and Safari 12. A
 * browser inside that gap — most significantly Safari before 16.4 — sends the Lax cookie on a
 * cross-site navigation and sends no Fetch Metadata, so this filter admits it and the row is still
 * forgeable from that client. The vector is narrowed, not eliminated. What the forgery can achieve
 * stays bounded either way: the row can only ever name a session the caller had already been
 * granted access to, and it carries no caller-supplied text.
 */
public class AiAssistantNavigationAdmissionFilter extends OncePerRequestFilter {
    private static final String PATH_PREFIX = "/api/ai/assistant/";
    private static final String FORBIDDEN = "Assistant API navigation is not allowed";

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !apiPath(request).startsWith(PATH_PREFIX);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        if (headerEquals(request, "Sec-Fetch-Dest", "document")
                || headerEquals(request, "Sec-Fetch-Mode", "navigate")) {
            reject(response, HttpServletResponse.SC_FORBIDDEN, FORBIDDEN);
            return;
        }
        chain.doFilter(request, response);
    }

    private static boolean headerEquals(
            HttpServletRequest request, String headerName, String expected) {
        String value = request.getHeader(headerName);
        return value != null && !value.isBlank() && expected.equalsIgnoreCase(value.trim());
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
