package ooo.klae.connex.backend.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * STOMP-over-WebSocket broker configuration for realtime push.
 * Exposes the handshake endpoint at {@code /api/ws} so it inherits the
 * {@code /api/**} authenticated matcher from {@link SecurityConfig}; an
 * unauthenticated upgrade is rejected by the security filter chain before it
 * reaches the broker. Clients receive server pushes on their per-user queue
 * ({@code /user/queue/notifications}) and never send application messages.
 * The in-memory simple broker matches the single-JVM deployment; cross-instance
 * fan-out later replaces the realtime publisher seam, not this config.
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private static final long[] HEARTBEAT_MILLIS = {10_000, 10_000};

    @Value("${connex.cors.allowed-origins}")
    private String[] allowedOrigins;

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/api/ws")
                .setAllowedOrigins(allowedOrigins);
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/queue", "/topic")
                .setHeartbeatValue(HEARTBEAT_MILLIS)
                .setTaskScheduler(webSocketHeartbeatScheduler());
        registry.setUserDestinationPrefix("/user");
        registry.setApplicationDestinationPrefixes("/app");
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
