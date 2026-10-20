package ooo.klae.connex.backend.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.messaging.support.AbstractSubscribableChannel;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.config.annotation.DelegatingWebSocketMessageBrokerConfiguration;
import org.springframework.web.socket.messaging.SubProtocolWebSocketHandler;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.ai.assistant.AiAssistantPrivacyPublicationGuard;
import ooo.klae.connex.backend.notifications.AiChatPrivacyOutboundInterceptor;
import ooo.klae.connex.backend.notifications.AiChatPrivacyWebSocketSession;

/** Installs the final-write privacy boundary while preserving Spring's broker configuration. */
@RequiredArgsConstructor
public class AiChatWebSocketMessageBrokerConfiguration extends DelegatingWebSocketMessageBrokerConfiguration {
    private final AiChatPrivacyOutboundInterceptor interceptor;
    private final AiAssistantPrivacyPublicationGuard guard;

    @Override
    @Bean
    public WebSocketHandler subProtocolWebSocketHandler(
            @Qualifier("clientInboundChannel") AbstractSubscribableChannel clientInboundChannel,
            @Qualifier("clientOutboundChannel") AbstractSubscribableChannel clientOutboundChannel) {
        var handler = new SubProtocolWebSocketHandler(clientInboundChannel, clientOutboundChannel) {
            @Override
            protected WebSocketSession decorateSession(WebSocketSession session) {
                return new AiChatPrivacyWebSocketSession(session, getSendTimeLimit(), getSendBufferSizeLimit(), interceptor, guard);
            }
        };
        handler.setPhase(getPhase());
        return handler;
    }
}
