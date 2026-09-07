package ooo.klae.connex.backend.config;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Normalizes a raw request URI into the application-relative path Spring maps handlers against.
 *
 * <p>Both {@code getRequestURI()} and {@code getServletPath()} preserve {@code ;} path parameters
 * and repeated slashes, which handler mapping ignores. A security filter that compares either value
 * literally is therefore skipped by {@code /api/x/exchange;a=1} or {@code //api/x/exchange} while
 * the request still reaches the handler, so every path-matching filter normalizes here first.
 */
final class RequestPathNormalizer {
    private static final Pattern PATH_PARAMETER_MARKER = Pattern.compile("(?i)(?:;|%(?:25)*3b)");
    private static final Pattern REPEATED_SLASHES = Pattern.compile("/{2,}");

    private RequestPathNormalizer() {
    }

    /**
     * Returns the request path with segment parameters stripped, repeated slashes collapsed and any
     * deployment context path removed.
     * @param request request whose URI is being matched
     * @return the application-relative path to compare against a route literal
     */
    static String apiPath(HttpServletRequest request) {
        String uri = REPEATED_SLASHES
            .matcher(stripPathParameters(request.getRequestURI()))
            .replaceAll("/");
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isBlank() && uri.startsWith(contextPath)) {
            return uri.substring(contextPath.length());
        }
        return uri;
    }

    /**
     * Removes the {@code ;} parameter suffix — literal or percent-encoded at any nesting — from
     * every segment of a path.
     * @param path raw request path
     * @return the path without segment parameters
     */
    static String stripPathParameters(String path) {
        Matcher marker = PATH_PARAMETER_MARKER.matcher(path);
        if (!marker.find()) {
            return path;
        }
        StringBuilder normalized = new StringBuilder(path.length());
        int segmentStart = 0;
        do {
            normalized.append(path, segmentStart, marker.start());
            int nextSegment = path.indexOf('/', marker.end());
            if (nextSegment < 0) {
                return normalized.toString();
            }
            normalized.append('/');
            segmentStart = nextSegment + 1;
            marker.region(segmentStart, path.length());
        } while (marker.find());
        normalized.append(path, segmentStart, path.length());
        return normalized.toString();
    }
}
