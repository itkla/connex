package ooo.klae.connex.backend.publicapi;

import java.nio.charset.StandardCharsets;

import org.springframework.web.util.UriUtils;

import jakarta.servlet.http.HttpServletRequest;

/** Classifies the raw request path at the versioned public API namespace boundary. */
public final class PublicApiPaths {
    private static final String NAMESPACE = "/api/v1";

    private PublicApiPaths() {
    }

    /**
     * Returns whether the raw path belongs to the public API after removing its raw query and
     * decoding exactly once. The namespace boundary is end-of-path, slash, or semicolon; a decoded
     * question mark remains ordinary path data. Malformed escapes qualify only when the literal
     * namespace already ends at one of those boundaries.
     */
    public static boolean isPublicRequest(HttpServletRequest request) {
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
        return contextPath == null || contextPath.isEmpty() || !path.startsWith(contextPath)
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
