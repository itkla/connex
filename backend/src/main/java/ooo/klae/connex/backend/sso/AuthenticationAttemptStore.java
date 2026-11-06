package ooo.klae.connex.backend.sso;

import java.io.Serializable;
import java.time.Clock;
import java.time.Duration;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import ooo.klae.connex.backend.util.OneTimeTokenDigest;

/** Bounds session storage by replacing one immutable map of complete, independently bound attempts. */
final class AuthenticationAttemptStore {
    private static final String ATTRIBUTE = AuthenticationAttemptStore.class.getName() + ".attempts";
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
        if (AuthenticationReturnPathFilter.isCrossSiteSubresource(request)) {
            throw new IllegalStateException("Authentication attempt cannot begin from a cross-site subresource");
        }
        HttpSession session = request.getSession(true);
        long now = clock.millis();
        Map<String, Attempt> attempts = snapshot(session);
        attempts.values().removeIf(attempt -> attempt.expiresAt() <= now);
        String key = key(protocol, identity);
        if (attempts.containsKey(key)) {
            throw new IllegalStateException("Authentication attempt cannot replace in-flight work");
        }
        while (attempts.size() >= MAX_ATTEMPTS) {
            String oldest = attempts.entrySet().stream()
                    .min(Comparator.<Map.Entry<String, Attempt>>comparingLong(entry -> entry.getValue().expiresAt())
                            .thenComparing(Map.Entry::getKey))
                    .orElseThrow().getKey();
            attempts.remove(oldest);
        }
        attempts.put(key, new Attempt(identity, authorization,
                AuthenticationReturnPathSession.startDestination(request), now + LIFETIME_MILLIS));
        session.setAttribute(ATTRIBUTE, Map.copyOf(attempts));
    }

    Attempt load(String protocol, String identity, HttpServletRequest request) {
        if (identity == null || identity.isBlank() || identity.length() > 512) return null;
        HttpSession session = request.getSession(false);
        if (session == null) return null;
        Attempt attempt = snapshot(session).get(key(protocol, identity));
        if (attempt == null || !identity.equals(attempt.identity())) return null;
        if (attempt.expiresAt() <= clock.millis()) {
            remove(protocol, identity, request);
            return null;
        }
        return attempt;
    }

    void remove(String protocol, String identity, HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) return;
        Map<String, Attempt> attempts = snapshot(session);
        long now = clock.millis();
        attempts.values().removeIf(attempt -> attempt.expiresAt() <= now);
        attempts.remove(key(protocol, identity));
        session.setAttribute(ATTRIBUTE, Map.copyOf(attempts));
    }

    static String parameter(HttpServletRequest request, String name) {
        String[] values = request.getParameterValues(name);
        return values != null && values.length == 1 ? values[0] : null;
    }

    private static String key(String protocol, String identity) {
        return protocol + "." + OneTimeTokenDigest.sha256(identity);
    }

    private static Map<String, Attempt> snapshot(HttpSession session) {
        Map<String, Attempt> attempts = new HashMap<>();
        if (session.getAttribute(ATTRIBUTE) instanceof Map<?, ?> saved) {
            saved.forEach((key, value) -> {
                if (key instanceof String identity && value instanceof Attempt attempt) {
                    attempts.put(identity, attempt);
                }
            });
        }
        return attempts;
    }
}
