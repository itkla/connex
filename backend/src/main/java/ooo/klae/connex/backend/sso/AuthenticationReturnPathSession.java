package ooo.klae.connex.backend.sso;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import ooo.klae.connex.backend.util.AuthenticationReturnPath;

/** Carries a validated local destination through one browser authentication ceremony and session rotation. */
public final class AuthenticationReturnPathSession {
    private static final String SESSION_ATTRIBUTE = AuthenticationReturnPathSession.class.getName() + ".destination";
    private static final String REQUEST_ATTRIBUTE = AuthenticationReturnPathSession.class.getName() + ".captured";

    private AuthenticationReturnPathSession() {
    }

    /** Stores only one validated start destination, clearing a preceding ceremony's value otherwise. */
    public static void remember(HttpServletRequest request) {
        String[] values = request.getParameterValues("redirect");
        String destination = values != null && values.length == 1 ? AuthenticationReturnPath.validate(values[0]) : null;
        HttpSession session = request.getSession(destination != null);
        if (session != null) {
            if (destination == null) session.removeAttribute(SESSION_ATTRIBUTE);
            else session.setAttribute(SESSION_ATTRIBUTE, destination);
        }
    }

    /** Takes the destination before authentication can replace the session. */
    public static void capture(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        Object candidate = session == null ? null : session.getAttribute(SESSION_ATTRIBUTE);
        if (session != null) session.removeAttribute(SESSION_ATTRIBUTE);
        request.setAttribute(REQUEST_ATTRIBUTE, candidate instanceof String value ? AuthenticationReturnPath.validate(value) : null);
    }

    /** Resolves the captured value through the same validator used at authentication start. */
    public static String captured(HttpServletRequest request) {
        Object candidate = request.getAttribute(REQUEST_ATTRIBUTE);
        return candidate instanceof String value ? AuthenticationReturnPath.validate(value) : null;
    }

    /** Adds only the validated captured destination to a local continuation page. */
    public static String continuation(HttpServletRequest request, String path) {
        String destination = captured(request);
        return destination == null ? path : path + (path.contains("?") ? "&" : "?")
                + "redirect=" + URLEncoder.encode(destination, StandardCharsets.UTF_8);
    }

    /** Returns to sign-in after a failed ceremony without losing the invitation destination. */
    public static String failurePath(HttpServletRequest request) {
        capture(request);
        return continuation(request, "/auth/login?sso_error=1");
    }
}
