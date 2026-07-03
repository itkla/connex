package ooo.klae.connex.backend.notifications;

import java.security.Principal;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

/**
 * Caps the number of concurrent realtime WebSocket connections a single
 * authenticated principal may hold on {@code /api/ws}. The in-memory broker is
 * single-JVM and shared across tenants, so one authenticated account opening an
 * unbounded number of sockets could exhaust broker and heap and degrade realtime
 * for every workspace; this bounds the live count per principal while leaving
 * normal multi-tab / multi-device use unaffected. The cap keys on the principal
 * established by the authenticated {@code /api/ws} handshake; a principal-less
 * session is left uncapped, so the guarantee relies on that endpoint requiring
 * authentication. In-memory and single-JVM, matching the broker and session model.
 */
@Component
public class WebSocketConnectionLimiter {

    private final int maxSessionsPerUser;
    private final Map<String, Set<WebSocketSession>> sessionsByPrincipal = new ConcurrentHashMap<>();

    /**
     * @param maxSessionsPerUser the maximum concurrent sessions allowed per principal
     * @throws IllegalArgumentException if {@code maxSessionsPerUser} is less than 1,
     *     which would reject every connection and disable realtime instance-wide
     */
    WebSocketConnectionLimiter(@Value("${connex.websocket.max-sessions-per-user}") int maxSessionsPerUser) {
        if (maxSessionsPerUser < 1) {
            throw new IllegalArgumentException(
                    "connex.websocket.max-sessions-per-user must be >= 1, was " + maxSessionsPerUser);
        }
        this.maxSessionsPerUser = maxSessionsPerUser;
    }

    /**
     * Admits a newly established session for its principal, unless that principal
     * already holds the maximum number of live sessions. Unauthenticated sessions
     * (no principal) are never capped.
     * @param session the established WebSocket session
     * @return {@code true} if admitted; {@code false} if the principal is at the
     *     limit and the caller should close the session
     */
    public boolean tryRegister(WebSocketSession session) {
        Principal principal = session.getPrincipal();
        if (principal == null) {
            return true;
        }
        boolean[] admitted = {false};
        sessionsByPrincipal.compute(principal.getName(), (key, sessions) -> {
            Set<WebSocketSession> current = sessions == null ? ConcurrentHashMap.newKeySet() : sessions;
            if (current.size() < maxSessionsPerUser) {
                current.add(session);
                admitted[0] = true;
            }
            return current.isEmpty() ? null : current;
        });
        return admitted[0];
    }

    /**
     * Releases a closed session's slot for its principal. A no-op for a session
     * that was never admitted.
     * @param session the closed WebSocket session
     */
    public void remove(WebSocketSession session) {
        Principal principal = session.getPrincipal();
        if (principal == null) {
            return;
        }
        sessionsByPrincipal.computeIfPresent(principal.getName(), (key, sessions) -> {
            sessions.remove(session);
            return sessions.isEmpty() ? null : sessions;
        });
    }
}
