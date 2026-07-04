package ooo.klae.connex.backend.config;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.security.messaging.web.socket.server.CsrfTokenHandshakeInterceptor;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;
import org.springframework.web.socket.handler.WebSocketHandlerDecorator;
import org.springframework.web.socket.server.support.HttpSessionHandshakeInterceptor;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.notifications.WebSocketConnectionLimiter;
import ooo.klae.connex.backend.notifications.WebSocketSessionExpiryInterceptor;
import ooo.klae.connex.backend.notifications.WebSocketSessionRegistry;

/**
 * STOMP-over-WebSocket broker configuration for realtime push.
 * Exposes the handshake endpoint at {@code /api/ws} so it inherits the
 * {@code /api/**} authenticated matcher from {@link SecurityConfig}; an
 * unauthenticated upgrade is rejected by the security filter chain before it
 * reaches the broker. Clients receive server pushes on their per-user queue
 * ({@code /user/queue/notifications}) and never send application messages.
 * The in-memory simple broker matches the single-JVM deployment; cross-instance
 * fan-out later replaces the realtime publisher seam, not this config.
 *
 * <p>The handshake records the authenticating HTTP session id so
 * {@link WebSocketSessionRegistry} can force-close sockets when that session
 * ends, and {@link WebSocketSessionExpiryInterceptor} enforces lazy
 * ({@code expireNow()}) session kills per inbound frame. {@link WebSocketConnectionLimiter}
 * caps concurrent sockets per principal so one account cannot flood the shared broker.
 *
 * <p>The handshake also seeds the expected {@code CsrfToken} into the WebSocket
 * session attributes via {@code CsrfTokenHandshakeInterceptor}, which the secured
 * inbound channel's {@code CsrfChannelInterceptor} reads to validate the STOMP
 * {@code CONNECT} frame. Without it every {@code CONNECT} fails
 * {@code MissingCsrfTokenException} and no client can subscribe — do not remove it.
 */
@Configuration
@EnableWebSocketMessageBroker
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private static final long[] HEARTBEAT_MILLIS = {10_000, 10_000};
    private static final CloseStatus CONNECTION_LIMIT_CLOSE_STATUS =
            new CloseStatus(4029, "connection limit exceeded");

    @Value("${connex.cors.allowed-origins}")
    private String[] allowedOrigins;

    private final WebSocketSessionRegistry sessionRegistry;
    private final WebSocketSessionExpiryInterceptor sessionExpiryInterceptor;
    private final WebSocketConnectionLimiter connectionLimiter;

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/api/ws")
                .setAllowedOrigins(allowedOrigins)
                .addInterceptors(
                        new HttpSessionHandshakeInterceptor(List.of()),
                        new CsrfTokenHandshakeInterceptor());
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/queue", "/topic")
                .setHeartbeatValue(HEARTBEAT_MILLIS)
                .setTaskScheduler(webSocketHeartbeatScheduler());
        registry.setUserDestinationPrefix("/user");
        registry.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(sessionExpiryInterceptor);
    }

    @Override
    public void configureWebSocketTransport(WebSocketTransportRegistration registry) {
        registry.addDecoratorFactory(handler -> new WebSocketHandlerDecorator(handler) {
            @Override
            public void afterConnectionEstablished(WebSocketSession session) throws Exception {
                boolean admitted = connectionLimiter.tryRegister(session);
                if (admitted && session.getAttributes()
                        .get(HttpSessionHandshakeInterceptor.HTTP_SESSION_ID_ATTR_NAME)
                        instanceof String httpSessionId) {
                    sessionRegistry.register(httpSessionId, session);
                }
                super.afterConnectionEstablished(session);
                if (!admitted) {
                    session.close(CONNECTION_LIMIT_CLOSE_STATUS);
                }
            }

            @Override
            public void afterConnectionClosed(WebSocketSession session, CloseStatus closeStatus)
                    throws Exception {
                connectionLimiter.remove(session);
                if (session.getAttributes()
                        .get(HttpSessionHandshakeInterceptor.HTTP_SESSION_ID_ATTR_NAME)
                        instanceof String httpSessionId) {
                    sessionRegistry.remove(httpSessionId, session);
                }
                super.afterConnectionClosed(session, closeStatus);
            }
        });
    }

    /**
     * Dedicated scheduler for simple-broker heartbeats, which detect half-open
     * connections through proxies and tunnels.
     * @return the heartbeat task scheduler
     */
    @Bean
    public ThreadPoolTaskScheduler webSocketHeartbeatScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("ws-heartbeat-");
        return scheduler;
    }
}
