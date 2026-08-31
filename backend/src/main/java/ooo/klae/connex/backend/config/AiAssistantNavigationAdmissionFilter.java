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
 * <p>Requests without Fetch Metadata are admitted because non-browser clients and server-side
 * fetches carry no ambient cross-site browser authority. Browsers that attach SameSite=Lax cookies
 * to top-level navigation also send Fetch Metadata, so rejecting navigation closes that forgery
 * vector without rejecting legitimate cross-origin CORS fetches.
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
