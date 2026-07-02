package ooo.klae.connex.backend.notifications;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.security.web.session.HttpSessionDestroyedEvent;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

/**
 * Tracks live WebSocket sessions by the HTTP session that authenticated their
 * handshake, so realtime connections can be force-closed when that HTTP
 * session ends. Logout and expiry publish {@link HttpSessionDestroyedEvent}
 * (via the {@code HttpSessionEventPublisher} bean), which closes the sockets
 * here; password-reset kills use the lazy {@code SessionInformation.expireNow()}
 * path instead and are enforced per-frame by the expiry channel interceptor.
 * In-memory and single-JVM, matching the session model.
 */
@Component
public class WebSocketSessionRegistry {

    private static final Logger log = LoggerFactory.getLogger(WebSocketSessionRegistry.class);

    private final Map<String, Set<WebSocketSession>> sessionsByHttpSession = new ConcurrentHashMap<>();

    /**
     * Registers an established WebSocket session under its HTTP session id.
     * @param httpSessionId the id of the HTTP session that authenticated the handshake
     * @param session the established WebSocket session
     */
    public void register(String httpSessionId, WebSocketSession session) {
        sessionsByHttpSession
                .computeIfAbsent(httpSessionId, key -> ConcurrentHashMap.newKeySet())
                .add(session);
    }

    /**
     * Removes a closed WebSocket session from the registry.
     * @param httpSessionId the id of the HTTP session that authenticated the handshake
     * @param session the closed WebSocket session
     */
    public void remove(String httpSessionId, WebSocketSession session) {
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
        for (WebSocketSession session : sessions) {
            try {
                session.close(CloseStatus.POLICY_VIOLATION);
            } catch (IOException e) {
                log.warn("Failed to close websocket session after HTTP session end: {}", e.getMessage());
            }
        }
    }

    @EventListener
    void onHttpSessionDestroyed(HttpSessionDestroyedEvent event) {
        closeByHttpSession(event.getId());
    }
}
