package ooo.klae.connex.backend.sso;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import jakarta.servlet.http.HttpServletRequest;
import ooo.klae.connex.backend.util.AuthenticationReturnPath;

/** Carries return navigation only within an admitted start or a matched callback request. */
public final class AuthenticationReturnPathSession {
    private static final String START_ATTRIBUTE = AuthenticationReturnPathSession.class.getName() + ".start";
    private static final String CAPTURED_ATTRIBUTE = AuthenticationReturnPathSession.class.getName() + ".captured";

    private AuthenticationReturnPathSession() {
    }

    /** Makes validated navigation available to the authentication request repository for this start only. */
    static void prepareStart(HttpServletRequest request) {
        String[] values = request.getParameterValues("redirect");
        String destination = values != null && values.length == 1 ? AuthenticationReturnPath.validate(values[0]) : null;
        request.setAttribute(START_ATTRIBUTE, destination);
    }

    /** Resolves navigation admitted by the start filter without reading browser-session fallback state. */
    static String startDestination(HttpServletRequest request) {
        Object candidate = request.getAttribute(START_ATTRIBUTE);
        return candidate instanceof String value ? AuthenticationReturnPath.validate(value) : null;
    }

    /** Publishes navigation only after a repository removes the matching saved authentication attempt. */
    static void captureMatched(HttpServletRequest request, String destination) {
        request.setAttribute(CAPTURED_ATTRIBUTE, AuthenticationReturnPath.validate(destination));
    }

    /** Resolves the matched attempt's destination before authentication can replace the browser session. */
    public static String captured(HttpServletRequest request) {
        Object candidate = request.getAttribute(CAPTURED_ATTRIBUTE);
        return candidate instanceof String value ? AuthenticationReturnPath.validate(value) : null;
    }

    /** Adds only the matched attempt's destination to a local continuation page. */
    public static String continuation(HttpServletRequest request, String path) {
        String destination = captured(request);
        return destination == null ? path : path + (path.contains("?") ? "&" : "?")
                + "redirect=" + URLEncoder.encode(destination, StandardCharsets.UTF_8);
    }

    /** Keeps unmatched authentication failures from consuming any in-flight attempt's destination. */
    public static String failurePath(HttpServletRequest request) {
        return continuation(request, "/auth/login?sso_error=1");
    }
}
