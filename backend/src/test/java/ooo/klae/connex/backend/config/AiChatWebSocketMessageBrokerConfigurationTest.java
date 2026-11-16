package ooo.klae.connex.backend.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.support.ExecutorSubscribableChannel;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockServletContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.messaging.SubProtocolWebSocketHandler;

import ooo.klae.connex.backend.ai.assistant.AiAssistantPrivacyPublicationGuard;
import ooo.klae.connex.backend.ai.assistant.AiAssistantPrivacyService;
import ooo.klae.connex.backend.notifications.AiChatPrivacyOutboundInterceptor;
import ooo.klae.connex.backend.notifications.RealtimeRoutingIdentityResolver;
import ooo.klae.connex.backend.notifications.WebSocketConnectionLimiter;
import ooo.klae.connex.backend.notifications.WebSocketSessionExpiryInterceptor;
import ooo.klae.connex.backend.notifications.WebSocketSessionRegistry;

class AiChatWebSocketMessageBrokerConfigurationTest {
    @Test
    void registeredBrokerChecksAuthorityAgainAtItsSocketDelegate() throws Exception {
        try (var context = context("off")) {
            var handler = context.getBean("subProtocolWebSocketHandler", SubProtocolWebSocketHandler.class);
            assertFalse(handler.getProtocolHandlers().isEmpty());
            var socket = mock(WebSocketSession.class);
            when(socket.isOpen()).thenReturn(true);
            when(socket.getId()).thenReturn("socket");
            when(socket.getAcceptedProtocol()).thenReturn("v12.stomp");
            when(socket.getAttributes()).thenReturn(new ConcurrentHashMap<>());
            when(socket.getTextMessageSizeLimit()).thenReturn(64_000);
            handler.afterConnectionEstablished(socket);
            var headers = SimpMessageHeaderAccessor.wrap(MessageBuilder.createMessage(
                    new byte[0], AiChatPrivacyOutboundInterceptor.headers(7, 1)));
            headers.setSessionId("socket");
            headers.setSubscriptionId("subscription");
            headers.setDestination("/queue/ai-chat");
            var outbound = context.getBean("clientOutboundChannel", ExecutorSubscribableChannel.class);
            assertTrue(outbound.send(MessageBuilder.createMessage("delta".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    headers.getMessageHeaders())));
            verify(socket, timeout(10_000)).sendMessage(any());
            verify(context.getBean(AiAssistantPrivacyService.class), timeout(10_000).times(2)).current(7);
            assertEquals(10_000, handler.getSendTimeLimit());
            assertEquals(512 * 1024, handler.getSendBufferSizeLimit());
        }
    }

    @Test
    void maintenanceModeDoesNotImportTheBrokerInfrastructure() {
        try (var context = context("legacy-upload-migration")) {
            assertFalse(context.containsBean("subProtocolWebSocketHandler"));
        }
    }

    private AnnotationConfigWebApplicationContext context(String maintenance) {
        var context = new AnnotationConfigWebApplicationContext();
        context.setEnvironment(new MockEnvironment()
                .withProperty("connex.cors.allowed-origins", "https://connex.test")
                .withProperty("connex.maintenance.mode", maintenance));
        context.setServletContext(new MockServletContext());
        context.register(BrokerTestConfig.class);
        context.refresh();
        return context;
    }

    @Configuration(proxyBeanMethods = false)
    @Import(WebSocketConfig.class)
    static class BrokerTestConfig {
        @Bean
        AiAssistantPrivacyService authority() {
            var authority = mock(AiAssistantPrivacyService.class);
            when(authority.current(7)).thenReturn(1L);
            return authority;
        }

        @Bean
        AiAssistantPrivacyPublicationGuard publication(AiAssistantPrivacyService authority) {
            var transactions = mock(PlatformTransactionManager.class);
            when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
            return new AiAssistantPrivacyPublicationGuard(authority, transactions);
        }

        @Bean
        AiChatPrivacyOutboundInterceptor privacy(AiAssistantPrivacyPublicationGuard publication) {
            return new AiChatPrivacyOutboundInterceptor(publication);
        }

        @Bean
        WebSocketSessionRegistry sessions() {
            return mock(WebSocketSessionRegistry.class);
        }

        @Bean
        WebSocketSessionExpiryInterceptor expiry() {
            var interceptor = mock(WebSocketSessionExpiryInterceptor.class);
            when(interceptor.beforeHandle(any(), any(), any())).thenAnswer(call -> call.getArgument(0));
            return interceptor;
        }

        @Bean
        WebSocketConnectionLimiter limiter() {
            return mock(WebSocketConnectionLimiter.class);
        }

        @Bean
        RealtimeRoutingIdentityResolver routing() {
            return new RealtimeRoutingIdentityResolver();
        }
    }
}
