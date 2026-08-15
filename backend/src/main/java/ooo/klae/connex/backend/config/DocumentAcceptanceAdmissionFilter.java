package ooo.klae.connex.backend.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.exceptions.TooManyRequestsException;
import ooo.klae.connex.backend.signature.DocumentAcceptanceRateLimiter;
import ooo.klae.connex.backend.signature.DocumentAcceptanceToken;
import ooo.klae.connex.backend.util.ClientIpResolver;

/** Admits public document-link requests before any request-body buffering or deserialization. */
@RequiredArgsConstructor
public class DocumentAcceptanceAdmissionFilter extends OncePerRequestFilter {
    private static final String PATH_PREFIX = "/api/document-acceptance/";
    private static final String UNAVAILABLE = "Document link is no longer available";
    private static final String RATE_LIMITED =
        "Too many document-link requests. Please try again later.";

    private final DocumentAcceptanceRateLimiter rateLimiter;
    private final ClientIpResolver clientIpResolver;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !apiPath(request).startsWith(PATH_PREFIX);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        String token = tokenFrom(apiPath(request));
        if (!DocumentAcceptanceToken.hasValidShape(token)) {
            reject(response, HttpServletResponse.SC_NOT_FOUND, UNAVAILABLE);
            return;
        }
        try {
            rateLimiter.acquire(
                DocumentAcceptanceToken.hash(token),
                clientIpResolver.resolve(request));
        } catch (TooManyRequestsException exception) {
            reject(response, 429, RATE_LIMITED);
            return;
        }
        chain.doFilter(request, response);
    }

    private static String apiPath(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isBlank() && uri.startsWith(contextPath)) {
            return uri.substring(contextPath.length());
        }
        return uri;
    }

    private static String tokenFrom(String path) {
        String suffix = path.substring(PATH_PREFIX.length());
        int nextSeparator = suffix.indexOf('/');
        return nextSeparator < 0 ? suffix : suffix.substring(0, nextSeparator);
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
