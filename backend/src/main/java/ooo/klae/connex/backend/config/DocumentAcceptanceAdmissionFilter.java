package ooo.klae.connex.backend.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.WebUtils;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.exceptions.TooManyRequestsException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.signature.DocumentAcceptanceRateLimiter;
import ooo.klae.connex.backend.signature.DocumentAcceptanceToken;
import ooo.klae.connex.backend.util.ClientIpResolver;
import ooo.klae.connex.backend.util.OneTimeTokenDigest;

/**
 * Admits token-free document-link requests before any request-body buffering or deserialization.
 * The credential is the purpose-bound grant cookie; a missing or malformed grant gets the uniform
 * unavailable response and only consumes the shared sentinel and source budgets. The exchange
 * endpoint is excluded because its bearer travels in the JSON body, which this filter must not
 * read; {@link OneTimeLinkExchangeAdmissionFilter} budgets it per source before the body is read
 * and the service applies the same per-token and per-source throttle after parsing it.
 *
 * <p>Paths are matched after stripping {@code ;} parameters and collapsing repeated slashes, the
 * same normalisation Spring applies when it maps the handler, so no spelling of a routed request
 * can skip admission.
 */
@RequiredArgsConstructor
public class DocumentAcceptanceAdmissionFilter extends OncePerRequestFilter {
    private static final String PATH = "/api/document-acceptance";
    private static final String EXCHANGE_PATH = PATH + "/exchange";
    private static final Pattern GRANT_PATTERN = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern REPEATED_SLASHES = Pattern.compile("/{2,}");
    private static final String UNAVAILABLE = "Document link is no longer available";
    private static final String UNAVAILABLE_BODY = "{\"code\":\""
        + ResourceNotFoundException.CODE
        + "\",\"message\":\""
        + UNAVAILABLE
        + "\"}";
    private static final String RATE_LIMITED =
        "Too many document-link requests. Please try again later.";

    private final DocumentAcceptanceRateLimiter rateLimiter;
    private final ClientIpResolver clientIpResolver;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = apiPath(request);
        boolean acceptancePath = path.equals(PATH) || path.startsWith(PATH + "/");
        return !acceptancePath || path.equals(EXCHANGE_PATH);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        String grant = grantFrom(request);
        String sourceAddress = clientIpResolver.resolve(request);
        boolean malformed = grant == null || !GRANT_PATTERN.matcher(grant).matches();
        try {
            rateLimiter.acquire(
                malformed
                    ? DocumentAcceptanceToken.hashForAdmission(null)
                    : OneTimeTokenDigest.sha256(grant),
                sourceAddress);
        } catch (TooManyRequestsException exception) {
            reject(response, 429, RATE_LIMITED);
            return;
        }
        if (malformed) {
            rejectUnavailable(response);
            return;
        }
        chain.doFilter(request, response);
    }

    private static String grantFrom(HttpServletRequest request) {
        Cookie cookie = WebUtils.getCookie(request, OneTimeLinkFlowCookie.DOCUMENT_ACCEPTANCE);
        return cookie == null ? null : cookie.getValue();
    }

    private static String apiPath(HttpServletRequest request) {
        String uri = REPEATED_SLASHES.matcher(
            PrivilegedMfaEnforcementFilter.stripPathParameters(request.getRequestURI()))
            .replaceAll("/");
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

    private static void rejectUnavailable(HttpServletResponse response) throws IOException {
        SecurityResponseHeaders.apply(response);
        response.setHeader("Cache-Control", "no-store");
        response.setStatus(HttpServletResponse.SC_NOT_FOUND);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(UNAVAILABLE_BODY);
    }
}
