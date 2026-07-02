package ooo.klae.connex.backend.notifications;

import java.util.Map;

import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.security.core.session.SessionInformation;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.server.support.HttpSessionHandshakeInterceptor;

import lombok.RequiredArgsConstructor;

/**
 * Drops inbound STOMP frames — and closes the socket — once the HTTP session
 * that authenticated the handshake has been expired via
 * {@code SessionInformation.expireNow()} (the password-reset kill path). That
 * path only invalidates the servlet session on the user's next HTTP request,
 * which a WebSocket-only client never makes, so expiry is enforced here on
 * every inbound frame including heartbeats. Sessions absent from the registry
 * (e.g. WebAuthn logins, which are never registered) are left alone; the
 * authorization interceptor remains the authentication gate.
 */
@Component
@RequiredArgsConstructor
public class WebSocketSessionExpiryInterceptor implements ChannelInterceptor {

    private final SessionRegistry securitySessionRegistry;
    private final WebSocketSessionRegistry webSocketSessionRegistry;

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        Map<String, Object> attributes = SimpMessageHeaderAccessor.getSessionAttributes(message.getHeaders());
        Object httpSessionId = attributes == null
                ? null
                : attributes.get(HttpSessionHandshakeInterceptor.HTTP_SESSION_ID_ATTR_NAME);
        if (httpSessionId instanceof String id) {
            SessionInformation info = securitySessionRegistry.getSessionInformation(id);
            if (info != null && info.isExpired()) {
                webSocketSessionRegistry.closeByHttpSession(id);
                return null;
            }
        }
        return message;
    }
}
