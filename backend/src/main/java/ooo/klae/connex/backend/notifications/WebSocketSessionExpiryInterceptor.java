package ooo.klae.connex.backend.notifications;

import java.time.Clock;
import java.time.Duration;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageHandler;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.support.ExecutorChannelInterceptor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.session.SessionInformation;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.server.support.HttpSessionHandshakeInterceptor;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.config.SessionSecurityProperties;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.services.SessionSecurityService;
import ooo.klae.connex.backend.session.AccountSessionIndex;
import ooo.klae.connex.backend.tenant.TenantWorkScope;

/**
 * Refuses stale HTTP sessions on inbound frames and immediately before outbound delivery.
 * Reads the shared store without refreshing idle time, so receive-only sockets cannot receive after
 * logout, expiry or an epoch bump even when immediate revocation missed their session row.
 * Disconnect frames remain allowed after removal so the broker can release subscription state.
 */
@Component
@RequiredArgsConstructor
public class WebSocketSessionExpiryInterceptor implements ExecutorChannelInterceptor {

    private final SessionRegistry securitySessionRegistry;
    private final WebSocketSessionRegistry webSocketSessionRegistry;
    private final ObjectProvider<SessionRepository<? extends Session>> sessionRepository;
    private final UserMapper userMapper;
    private final SessionSecurityProperties properties;
    private final Clock clock;
    private final TenantWorkScope tenantWorkScope;

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        return validate(message);
    }

    @Override
    public Message<?> beforeHandle(Message<?> message, MessageChannel channel, MessageHandler handler) {
        return validate(message);
    }

    private Message<?> validate(Message<?> message) {
        SimpMessageType type = SimpMessageHeaderAccessor.getMessageType(message.getHeaders());
        if (type == SimpMessageType.DISCONNECT || type == SimpMessageType.DISCONNECT_ACK) {
            return message;
        }
        String sessionId = SimpMessageHeaderAccessor.getSessionId(message.getHeaders());
        WebSocketSession socket = webSocketSessionRegistry.getSession(sessionId);
        if (socket == null) {
            return null;
        }
        Object httpSessionId = socket.getAttributes()
                .get(HttpSessionHandshakeInterceptor.HTTP_SESSION_ID_ATTR_NAME);
        if (httpSessionId instanceof String id) {
            try {
                if (socket.isOpen() && Boolean.TRUE.equals(
                        tenantWorkScope.unrouted(() -> isValid(id, socket)))) {
                    return message;
                }
            } catch (RuntimeException exception) {
                webSocketSessionRegistry.closeByHttpSession(id);
                return null;
            }
            webSocketSessionRegistry.closeByHttpSession(id);
        } else {
            webSocketSessionRegistry.closeByWebSocketSession(sessionId);
        }
        return null;
    }

    private boolean isValid(String httpSessionId, WebSocketSession socket) {
        SessionInformation info = securitySessionRegistry.getSessionInformation(httpSessionId);
        if (info == null || info.isExpired()) {
            return false;
        }
        SessionRepository<? extends Session> repository = sessionRepository.getIfAvailable();
        Session session = repository == null ? null : repository.findById(httpSessionId);
        if (session == null || session.isExpired()
                || !(session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY)
                        instanceof SecurityContext context)
                || !(context.getAuthentication() instanceof Authentication authentication)
                || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof User user)
                || !(socket.getPrincipal() instanceof Authentication socketAuthentication)
                || !(socketAuthentication.getPrincipal() instanceof AccountSessionIndex account)
                || account.userId() != user.getId()) {
            return false;
        }
        Object epoch = session.getAttribute(SessionSecurityService.SESSION_EPOCH_ATTR);
        if (!(epoch instanceof Integer stamped)
                || !stamped.equals(userMapper.currentSessionEpoch(user.getId()))) {
            return false;
        }
        Object authenticatedAt = session.getAttribute(SessionSecurityService.AUTHENTICATED_AT_ATTR);
        Object authenticatedUser = session.getAttribute(SessionSecurityService.AUTHENTICATED_USER_ATTR);
        if (!(authenticatedAt instanceof Long timestamp)
                || !(authenticatedUser instanceof Integer userId) || userId != user.getId()) {
            return false;
        }
        Duration timeout = properties.getAbsoluteTimeout();
        long age = clock.millis() - timestamp;
        return age >= 0 && (timeout == null || timeout.isZero() || timeout.isNegative()
                || age <= timeout.toMillis());
    }
}
