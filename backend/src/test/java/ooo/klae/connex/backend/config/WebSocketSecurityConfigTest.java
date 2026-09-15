package ooo.klae.connex.backend.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.function.Supplier;
import java.util.HashMap;
import java.util.List;

import org.springframework.http.server.ServerHttpRequest;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeFailureException;

import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.notifications.RealtimeRoutingIdentity;
import ooo.klae.connex.backend.notifications.RealtimeRoutingIdentityResolver;

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
    void handshakeUsesImmutableAccountIdentityAndKeepsAuthentication() {
        User account = new User();
        account.setId(17);
        account.setUsername("recycled");
        ServerHttpRequest request = mock(ServerHttpRequest.class);
        when(request.getPrincipal()).thenReturn(
                UsernamePasswordAuthenticationToken.authenticated(account, null, List.of()));
        RealtimeRoutingIdentityResolver identities = new RealtimeRoutingIdentityResolver();

        var principal = new WebSocketAccountHandshakeHandler(identities).determineUser(
                request, mock(WebSocketHandler.class), new HashMap<>());
        account.setUsername("renamed");

        assertTrue(principal instanceof Authentication authentication && authentication.isAuthenticated());
        assertEquals(new RealtimeRoutingIdentity(17, identities.destinationFor(17)),
                ((Authentication) principal).getPrincipal());
        assertEquals(identities.destinationFor(17), principal.getName());
    }

    /**
     * The destination name reaches the browser in the {@code CONNECTED} frame's {@code user-name}
     * header, so it must not be the account id or anything derived from it without the key.
     */
    @Test
    void handshakeRoutesByAnOpaqueNameThatDoesNotDiscloseTheAccountId() {
        User account = new User();
        account.setId(17);
        account.setUsername("recycled");
        ServerHttpRequest request = mock(ServerHttpRequest.class);
        when(request.getPrincipal()).thenReturn(
                UsernamePasswordAuthenticationToken.authenticated(account, null, List.of()));

        var principal = new WebSocketAccountHandshakeHandler(new RealtimeRoutingIdentityResolver())
                .determineUser(request, mock(WebSocketHandler.class), new HashMap<>());

        assertNotEquals("uid:17", principal.getName());
        assertNotEquals("17", principal.getName());
        assertNotEquals("recycled", principal.getName());
        assertNotEquals(new RealtimeRoutingIdentityResolver().destinationFor(17), principal.getName());
    }

    @Test
    void handshakeRefusesNonAccountAuthentication() {
        ServerHttpRequest request = mock(ServerHttpRequest.class);
        when(request.getPrincipal()).thenReturn(
                UsernamePasswordAuthenticationToken.authenticated("subject", null, List.of()));

        assertThrows(HandshakeFailureException.class, () ->
                new WebSocketAccountHandshakeHandler(new RealtimeRoutingIdentityResolver())
                        .determineUser(request, mock(WebSocketHandler.class), new HashMap<>()));
    }

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
