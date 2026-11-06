package ooo.klae.connex.backend.sso;

import java.io.Serializable;
import java.time.Clock;
import java.time.Duration;
import java.util.Collections;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import ooo.klae.connex.backend.util.OneTimeTokenDigest;

/** Stores separate, bounded session attributes so one authentication attempt never rewrites another. */
final class AuthenticationAttemptStore {
    private static final String PREFIX = AuthenticationAttemptStore.class.getName() + ".attempt.";
    private static final long LIFETIME_MILLIS = Duration.ofMinutes(10).toMillis();
    private static final int MAX_ATTEMPTS = 4;
    private final Clock clock;

    record Attempt(String identity, Serializable authorization, String destination, long expiresAt) implements Serializable {
    }

    AuthenticationAttemptStore(Clock clock) {
        this.clock = clock;
    }

    void save(String protocol, String identity, Serializable authorization, HttpServletRequest request) {
        if (identity == null || identity.isBlank() || identity.length() > 512) {
            throw new IllegalArgumentException("Authentication attempt identity is missing or malformed");
        }
        HttpSession session = request.getSession(true);
        synchronized (session) {
            int live = 0;
            for (String name : Collections.list(session.getAttributeNames())) {
                if (!name.startsWith(PREFIX)) continue;
                Object value = session.getAttribute(name);
                if (value instanceof Attempt attempt && attempt.expiresAt() <= clock.millis()) {
                    session.removeAttribute(name);
                } else {
                    live++;
                }
            }
            String key = key(protocol, identity);
            if (session.getAttribute(key) != null || live >= MAX_ATTEMPTS) {
                throw new IllegalStateException("Authentication attempt cannot replace in-flight work");
            }
            session.setAttribute(key, new Attempt(identity, authorization,
                    AuthenticationReturnPathSession.startDestination(request), clock.millis() + LIFETIME_MILLIS));
        }
    }

    Attempt load(String protocol, String identity, HttpServletRequest request) {
        if (identity == null || identity.isBlank() || identity.length() > 512) return null;
        HttpSession session = request.getSession(false);
        if (session == null) return null;
        String key = key(protocol, identity);
        Object value = session.getAttribute(key);
        if (!(value instanceof Attempt attempt) || !identity.equals(attempt.identity())) return null;
        if (attempt.expiresAt() <= clock.millis()) {
            session.removeAttribute(key);
            return null;
        }
        return attempt;
    }

    void remove(String protocol, String identity, HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) session.removeAttribute(key(protocol, identity));
    }

    static String parameter(HttpServletRequest request, String name) {
        String[] values = request.getParameterValues(name);
        return values != null && values.length == 1 ? values[0] : null;
    }

    private static String key(String protocol, String identity) {
        return PREFIX + protocol + "." + OneTimeTokenDigest.sha256(identity);
    }
}
