package ooo.klae.connex.backend.notifications;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.security.web.session.HttpSessionDestroyedEvent;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.session.AccountSessionIndex;

/**
 * Tracks local sockets by their backing HTTP session and transport id. Revocation and logout
 * close them explicitly; delivery checks also catch JDBC expiry and missed session enumeration.
 */
@Component
public class WebSocketSessionRegistry {

    private static final Logger log = LoggerFactory.getLogger(WebSocketSessionRegistry.class);

    private final Map<String, WebSocketSession> sessionsById = new ConcurrentHashMap<>();
    private final Map<String, Set<WebSocketSession>> sessionsByHttpSession = new ConcurrentHashMap<>();

    /**
     * Registers an established WebSocket session under its HTTP session id.
     * @param httpSessionId the id of the HTTP session that authenticated the handshake
     * @param session the established WebSocket session
     */
    public void register(String httpSessionId, WebSocketSession session) {
        sessionsById.put(session.getId(), session);
        sessionsByHttpSession.compute(httpSessionId, (key, sessions) -> {
            Set<WebSocketSession> current = sessions == null ? ConcurrentHashMap.newKeySet() : sessions;
            current.add(session);
            return current;
        });
    }

    /**
     * Removes a closed WebSocket session from the registry.
     * @param httpSessionId the id of the HTTP session that authenticated the handshake
     * @param session the closed WebSocket session
     */
    public void remove(String httpSessionId, WebSocketSession session) {
        sessionsById.remove(session.getId(), session);
        sessionsByHttpSession.computeIfPresent(httpSessionId, (key, sessions) -> {
            sessions.remove(session);
            return sessions.isEmpty() ? null : sessions;
        });
    }

    /**
     * Closes every WebSocket session that was authenticated by the given HTTP session.
     * @param httpSessionId the id of the destroyed or expired HTTP session
     */
    public void closeByHttpSession(String httpSessionId) {
        Set<WebSocketSession> sessions = sessionsByHttpSession.remove(httpSessionId);
        if (sessions == null) {
            return;
        }
        sessions.forEach(this::close);
    }

    /** Returns a live transport's server-held handshake state, or null after removal. */
    public WebSocketSession getSession(String sessionId) {
        return sessionId == null ? null : sessionsById.get(sessionId);
    }

    /** Closes a transport whose handshake state or backing HTTP session is no longer valid. */
    public void closeByWebSocketSession(String sessionId) {
        WebSocketSession session = getSession(sessionId);
        if (session != null) {
            close(session);
        }
    }

    /** Closes both current account-id sockets and historical username sockets after a rename. */
    public void closeByUser(int userId) {
        for (WebSocketSession session : sessionsById.values()) {
            if (session.getPrincipal() instanceof Authentication authentication
                    && ((authentication.getPrincipal() instanceof AccountSessionIndex account
                            && account.userId() == userId)
                        || (authentication.getPrincipal() instanceof User user
                            && user.getId() == userId))) {
                close(session);
            }
        }
    }

    private void close(WebSocketSession session) {
        try {
            session.close(CloseStatus.POLICY_VIOLATION);
        } catch (IOException exception) {
            log.warn("Failed to close websocket session after HTTP session end");
        }
    }

    @EventListener
    void onHttpSessionDestroyed(HttpSessionDestroyedEvent event) {
        closeByHttpSession(event.getId());
    }
}
