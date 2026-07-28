package ooo.klae.connex.backend.observability;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Enumeration;
import java.util.List;

import org.springframework.http.HttpHeaders;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Authenticates only the metrics scrape route with the operator-configured bearer token.
 */
public class MetricsScrapeTokenFilter extends OncePerRequestFilter {
    /**
     * The only authority that may read the metrics endpoint.
     */
    public static final String SCRAPE_AUTHORITY = "METRICS_SCRAPE";

    private static final String METRICS_PATH = "/api/metrics";
    private static final String BEARER_PREFIX = "Bearer ";
    private static final String PRINCIPAL = "metrics-scraper";
    private static final SimpleGrantedAuthority AUTHORITY =
            new SimpleGrantedAuthority(SCRAPE_AUTHORITY);

    private final byte[] configuredToken;

    public MetricsScrapeTokenFilter(String configuredToken) {
        this.configuredToken = configuredToken == null || configuredToken.isBlank()
                ? null
                : configuredToken.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return configuredToken == null
                || !"GET".equals(request.getMethod())
                || !METRICS_PATH.equals(requestPath(request));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!matchesConfiguredToken(request)) {
            chain.doFilter(request, response);
            return;
        }

        SecurityContext previous = SecurityContextHolder.getContext();
        SecurityContext scrapeContext = SecurityContextHolder.createEmptyContext();
        scrapeContext.setAuthentication(new PreAuthenticatedAuthenticationToken(
                PRINCIPAL,
                null,
                List.of(AUTHORITY)));
        SecurityContextHolder.setContext(scrapeContext);
        try {
            chain.doFilter(request, response);
        } finally {
            SecurityContextHolder.setContext(previous);
        }
    }

    private boolean matchesConfiguredToken(HttpServletRequest request) {
        Enumeration<String> headers = request.getHeaders(HttpHeaders.AUTHORIZATION);
        if (headers == null || !headers.hasMoreElements()) {
            return false;
        }
        String authorization = headers.nextElement();
        if (headers.hasMoreElements()
                || authorization == null
                || !authorization.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            return false;
        }
        byte[] presentedToken = authorization.substring(BEARER_PREFIX.length())
                .getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(configuredToken, presentedToken);
    }

    private static String requestPath(HttpServletRequest request) {
        String path = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isBlank() && path.startsWith(contextPath)) {
            return path.substring(contextPath.length());
        }
        return path;
    }
}
