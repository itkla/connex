package ooo.klae.connex.backend.config;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.function.Supplier;

import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.messaging.access.intercept.MessageMatcherDelegatingAuthorizationManager;

class WebSocketSecurityConfigTest {
    @Test
    void authenticatedClientsMaySubscribeOnlyToTheirUserAssistantQueue() {
        var manager = new WebSocketSecurityConfig().messageAuthorizationManager(
                MessageMatcherDelegatingAuthorizationManager.builder());
        Supplier<Authentication> authentication = () ->
                UsernamePasswordAuthenticationToken.authenticated(
                        "member", "credentials", java.util.List.of());

        assertTrue(manager.authorize(
                authentication, subscribe("/user/queue/ai-chat")).isGranted());
        assertFalse(manager.authorize(
                authentication, subscribe("/topic/ai-chat/13")).isGranted());
        assertFalse(manager.authorize(
                authentication, subscribe("/queue/ai-chat-user17")).isGranted());
    }

    private Message<byte[]> subscribe(String destination) {
        return MessageBuilder.withPayload(new byte[0])
                .setHeader(
                        SimpMessageHeaderAccessor.MESSAGE_TYPE_HEADER,
                        SimpMessageType.SUBSCRIBE)
                .setHeader(SimpMessageHeaderAccessor.DESTINATION_HEADER, destination)
                .build();
    }
}
