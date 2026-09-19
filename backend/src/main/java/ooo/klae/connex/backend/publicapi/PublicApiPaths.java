package ooo.klae.connex.backend.publicapi;

import java.nio.charset.StandardCharsets;

import org.springframework.security.web.firewall.RequestRejectedException;
import org.springframework.web.util.UriUtils;

import jakarta.servlet.http.HttpServletRequest;
import ooo.klae.connex.backend.config.RequestPathNormalizer;

/** Classifies a request path at the versioned public API namespace boundary. */
public final class PublicApiPaths {
    private static final String NAMESPACE = "/api/v1";

    private PublicApiPaths() {
    }

    /**
     * Returns whether the request belongs to the public API. The normalized application path from
     * {@link RequestPathNormalizer#apiPath} is classified first, so an accepted request is placed on
     * the same path the public security chain and every other policy filter match on. A path the
     * normalizer rejects is still classified leniently, so the firewall and error handlers that
     * choose a response shape for it stay total: the context is removed only at a segment boundary,
     * the raw query is removed, and the rest is decoded exactly once. The namespace boundary is
     * end-of-path, slash, or semicolon; a decoded question mark remains ordinary path data.
     * Malformed escapes qualify only when the literal namespace already ends at one of those
     * boundaries.
     */
    public static boolean isPublicRequest(HttpServletRequest request) {
        try {
            return isPublicNamespace(RequestPathNormalizer.apiPath(request));
        } catch (RequestRejectedException exception) {
            return isLenientlyPublic(request);
        }
    }

    private static boolean isLenientlyPublic(HttpServletRequest request) {
        String rawPath = pathOnly(requestPath(request));
        try {
            return isPublicNamespace(UriUtils.decode(rawPath, StandardCharsets.UTF_8));
        } catch (IllegalArgumentException exception) {
            return isPublicNamespace(rawPath);
        }
    }

    private static String requestPath(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (path == null) {
            return "";
        }
        String contextPath = request.getContextPath();
        return contextPath == null || contextPath.isEmpty()
                || !(path.equals(contextPath) || path.startsWith(contextPath + "/"))
            ? path
            : path.substring(contextPath.length());
    }

    private static String pathOnly(String rawPath) {
        int queryStart = rawPath.indexOf('?');
        return queryStart < 0 ? rawPath : rawPath.substring(0, queryStart);
    }

    private static boolean isPublicNamespace(String path) {
        if (!path.startsWith(NAMESPACE)) {
            return false;
        }
        if (path.length() == NAMESPACE.length()) {
            return true;
        }
        return switch (path.charAt(NAMESPACE.length())) {
            case '/', ';' -> true;
            default -> false;
        };
    }
}
