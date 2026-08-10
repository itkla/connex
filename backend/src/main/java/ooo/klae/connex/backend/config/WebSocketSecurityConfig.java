package ooo.klae.connex.backend.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.config.annotation.web.socket.EnableWebSocketSecurity;
import org.springframework.security.messaging.access.intercept.MessageMatcherDelegatingAuthorizationManager;
import org.springframework.security.messaging.web.csrf.CsrfChannelInterceptor;

/**
 * Message-level security for the STOMP inbound channel. The policy is
 * default-deny: authenticated clients may only SUBSCRIBE to their own
 * notification and assistant queues ({@code /user/queue/notifications} and
 * {@code /user/queue/ai-chat}, which Spring's user-destination resolution scopes to the
 * authenticated principal), frames without a destination (CONNECT, DISCONNECT, UNSUBSCRIBE,
 * heartbeats) require authentication, and everything else — SENDs and subscriptions to raw broker
 * destinations — is denied.
 *
 * <p>The CONNECT frame carries the same session CSRF token the SPA already
 * echoes on mutating HTTP requests. The default {@code XorCsrfChannelInterceptor}
 * expects a masked token, but {@link SecurityConfig} hands the SPA a plain one
 * ({@code HeaderOnlyCsrfTokenRequestHandler}), so the plain
 * {@link CsrfChannelInterceptor} is installed instead; when CSRF is disabled by
 * configuration the frame-level check is a no-op to match the HTTP posture.
 */
@Configuration
@EnableWebSocketSecurity
@ConditionalOnProperty(
    prefix = "connex.maintenance",
    name = "mode",
    havingValue = "off",
    matchIfMissing = true)
public class WebSocketSecurityConfig {

    @Bean
    AuthorizationManager<Message<?>> messageAuthorizationManager(
            MessageMatcherDelegatingAuthorizationManager.Builder messages) {
        messages
                .nullDestMatcher().authenticated()
                .simpSubscribeDestMatchers(
                        "/user/queue/notifications", "/user/queue/ai-chat").authenticated()
                .anyMessage().denyAll();
        return messages.build();
    }

    @Bean("csrfChannelInterceptor")
    ChannelInterceptor csrfChannelInterceptor(
            @Value("${connex.security.csrf-enabled:true}") boolean csrfEnabled) {
        if (csrfEnabled) {
            return new CsrfChannelInterceptor();
        }
        return new ChannelInterceptor() {
        };
    }
}
