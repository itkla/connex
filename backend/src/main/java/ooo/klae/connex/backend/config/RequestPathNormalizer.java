package ooo.klae.connex.backend.config;

import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

import org.springframework.security.web.firewall.RequestRejectedException;
import org.springframework.web.util.UriUtils;

import jakarta.servlet.http.HttpServletRequest;

/** Normalizes security policy paths and rejects ambiguous encodings before handler routing. */
public final class RequestPathNormalizer {
    private static final Pattern ENCODED_SEPARATOR = Pattern.compile("(?i)%(?:2f|5c)");
    private static final Pattern PATH_PARAMETERS = Pattern.compile(";[^/]*");
    private static final Pattern REPEATED_SLASHES = Pattern.compile("/{2,}");
    private static final Pattern DOT_SEGMENT = Pattern.compile("(?:^|/)\\.{1,2}(?:/|$)");

    private RequestPathNormalizer() {
    }

    /**
     * Returns a once-decoded application-relative path without matrix parameters or duplicate slashes.
     * Encoded separators, residual escapes, traversal and control characters are rejected rather
     * than interpreted differently by filters and the servlet container.
     * @param request request whose URI is being matched
     * @return the application-relative path to compare against a route literal
     * @throws RequestRejectedException when the request path is ambiguous or malformed
     */
    public static String apiPath(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (uri == null || !uri.startsWith("/")) {
            throw invalidPath();
        }
        if (contextPath != null && !contextPath.isEmpty()) {
            if (!uri.equals(contextPath) && !uri.startsWith(contextPath + "/")) {
                throw invalidPath();
            }
            uri = uri.substring(contextPath.length());
        }
        if (ENCODED_SEPARATOR.matcher(uri).find()) {
            throw invalidPath();
        }
        String decoded;
        try {
            decoded = UriUtils.decode(uri, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException exception) {
            throw invalidPath();
        }
        if (decoded.indexOf('%') >= 0 || decoded.indexOf('\\') >= 0
                || decoded.codePoints().anyMatch(Character::isISOControl)) {
            throw invalidPath();
        }
        String path = REPEATED_SLASHES.matcher(PATH_PARAMETERS.matcher(decoded).replaceAll(""))
                .replaceAll("/");
        if (DOT_SEGMENT.matcher(path).find()) {
            throw invalidPath();
        }
        return path;
    }

    private static RequestRejectedException invalidPath() {
        return new RequestRejectedException("Invalid request path");
    }
}
