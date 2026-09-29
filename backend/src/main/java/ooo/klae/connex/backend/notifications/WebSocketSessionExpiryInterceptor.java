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
import ooo.klae.connex.backend.tenant.TenantWorkScope;

/**
 * Refuses stale HTTP sessions on inbound frames and immediately before outbound delivery.
 * Reads the shared store without refreshing idle time, so receive-only sockets cannot receive after
 * logout, expiry or an epoch bump even when immediate revocation missed their session row.
 * Disconnect frames remain allowed after removal so the broker can release subscription state.
 *
 * <p>Heartbeats take a cheaper path. They are empty liveness frames that neither carry nor trigger
 * application data, so they check the persisted session only: the single store read this class
 * already performs answers logout, registry expiry, JDBC idle expiry, session deletion, a swapped
 * backing account and absolute lifetime, and the account epoch lookup is skipped. The broker
 * negotiates a ten-second heartbeat with every client, so keeping that second per-socket database
 * read on the heartbeat timer would cost load proportional to the connected population while
 * adding nothing a delivery does not already refuse. Frames that carry or trigger application
 * data, including every outbound delivery, are still checked in full.
 */
@Component
@RequiredArgsConstructor
public class WebSocketSessionExpiryInterceptor implements ExecutorChannelInterceptor {

    static final String SPRING_SESSION_EXPIRED_ATTR =
            "org.springframework.session.security.SpringSessionBackedSessionInformation.EXPIRED";

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
        boolean carriesApplicationData = type != SimpMessageType.HEARTBEAT;
        Object httpSessionId = socket.getAttributes()
                .get(HttpSessionHandshakeInterceptor.HTTP_SESSION_ID_ATTR_NAME);
        if (httpSessionId instanceof String id) {
            try {
                if (socket.isOpen() && Boolean.TRUE.equals(
                        tenantWorkScope.unrouted(() -> isValid(id, socket, carriesApplicationData)))) {
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

    /**
     * Reports whether the socket may still be used, reading the shared store without refreshing
     * idle time.
     *
     * @param httpSessionId the backing HTTP session recorded at handshake
     * @param socket the transport the frame belongs to
     * @param checkAccountEpoch whether to also compare the session's stamped epoch against the
     *     account's current one, which costs a second database read and is skipped for heartbeats
     * @return whether the frame may proceed
     */
    private boolean isValid(String httpSessionId, WebSocketSession socket, boolean checkAccountEpoch) {
        SessionRepository<? extends Session> repository = sessionRepository.getIfAvailable();
        Session session = repository == null ? null : repository.findById(httpSessionId);
        if (session == null || session.isExpired() || isRevoked(session)
                || !(session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY)
                        instanceof SecurityContext context)
                || !(context.getAuthentication() instanceof Authentication authentication)
                || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof User user)
                || !(socket.getPrincipal() instanceof Authentication socketAuthentication)
                || !(socketAuthentication.getPrincipal() instanceof RealtimeRoutingIdentity identity)
                || identity.userId() != user.getId()) {
            return false;
        }
        Object epoch = session.getAttribute(SessionSecurityService.SESSION_EPOCH_ATTR);
        if (!(epoch instanceof Integer stamped)) {
            return false;
        }
        if (checkAccountEpoch && !stamped.equals(userMapper.currentSessionEpoch(user.getId()))) {
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

    /**
     * Reports the marker {@code SessionInformation.expireNow()} writes through
     * {@code SpringSessionBackedSessionRegistry}, which is how
     * {@code AccountSessionRevocationService} expires a session it has enumerated.
     *
     * <p>The registry answers the same question, but only by loading the row a second time — once
     * more per delivered assistant delta. The marker is read off the row this validation has
     * already loaded instead. Spring Session declares the key package-privately, so it is spelled
     * out here and pinned behaviourally by
     * {@code WebSocketSessionSecurityIntegrationTest#registryExpiryClosesAReceiveOnlySocketAtDelivery},
     * which revokes through the real registry and asserts the next delivery is refused.
     *
     * @param session the persisted session backing the socket
     * @return whether the session has been expired through the security session registry
     */
    private static boolean isRevoked(Session session) {
        return Boolean.TRUE.equals(session.getAttribute(SPRING_SESSION_EXPIRED_ATTR));
    }
}
