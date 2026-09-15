package ooo.klae.connex.backend.notifications;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageHandler;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.server.support.HttpSessionHandshakeInterceptor;

import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.config.SessionSecurityProperties;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.services.SessionSecurityService;
import ooo.klae.connex.backend.tenant.TenantCatalogResolver;
import ooo.klae.connex.backend.tenant.TenantContext;
import ooo.klae.connex.backend.tenant.TenantWorkScope;

class WebSocketSessionExpiryInterceptorTest {

    private static final String HTTP_SESSION_ID = "backing-http-session";
    private static final String SOCKET_ID = "registered-websocket";
    private static final int USER_ID = 17;
    private static final int EPOCH = 3;
    private static final Instant NOW = Instant.parse("2026-09-13T12:00:00Z");

    private final RealtimeRoutingIdentityResolver routingIdentities = new RealtimeRoutingIdentityResolver();
    private final WebSocketSessionRegistry webSocketSessions = new WebSocketSessionRegistry();
    private final ObjectProvider<SessionRepository<? extends Session>> repositoryProvider = mock();
    private final SessionRepository<Session> repository = mock();
    private final Session backingSession = mock(Session.class);
    private final WebSocketSession socket = mock(WebSocketSession.class);
    private final UserMapper userMapper = mock(UserMapper.class);
    private final MessageChannel channel = mock(MessageChannel.class);
    private final MessageHandler handler = mock(MessageHandler.class);
    private final Map<String, Object> socketAttributes = new HashMap<>();
    private final Map<String, Object> sessionAttributes = new HashMap<>();

    private WebSocketSessionExpiryInterceptor interceptor;

    @BeforeEach
    void setUp() {
        User user = new User();
        user.setId(USER_ID);
        user.setUsername("realtime-account");
        sessionAttributes.put(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                new SecurityContextImpl(UsernamePasswordAuthenticationToken.authenticated(
                        user, null, List.of())));
        sessionAttributes.put(SessionSecurityService.SESSION_EPOCH_ATTR, EPOCH);
        sessionAttributes.put(SessionSecurityService.AUTHENTICATED_AT_ATTR, NOW.minusSeconds(60).toEpochMilli());
        sessionAttributes.put(SessionSecurityService.AUTHENTICATED_USER_ATTR, USER_ID);
        socketAttributes.put(HttpSessionHandshakeInterceptor.HTTP_SESSION_ID_ATTR_NAME, HTTP_SESSION_ID);
        when(socket.getId()).thenReturn(SOCKET_ID);
        when(socket.getAttributes()).thenReturn(socketAttributes);
        when(socket.isOpen()).thenReturn(true);
        when(socket.getPrincipal()).thenReturn(UsernamePasswordAuthenticationToken.authenticated(
                routingIdentities.forAccount(USER_ID), null, List.of()));
        Mockito.<SessionRepository<? extends Session>>when(repositoryProvider.getIfAvailable())
                .thenReturn(repository);
        when(repository.findById(HTTP_SESSION_ID)).thenReturn(backingSession);
        when(backingSession.getAttribute(anyString())).thenAnswer(invocation ->
                sessionAttributes.get(invocation.getArgument(0, String.class)));
        when(userMapper.currentSessionEpoch(USER_ID)).thenReturn(EPOCH);
        webSocketSessions.register(HTTP_SESSION_ID, socket);
        TenantWorkScope tenantWorkScope = new TenantWorkScope(
                new TenantContext(), mock(TenantCatalogResolver.class), mock(WorkspaceMapper.class));
        interceptor = new WebSocketSessionExpiryInterceptor(
                webSocketSessions, repositoryProvider, userMapper,
                new SessionSecurityProperties(), Clock.fixed(NOW, ZoneOffset.UTC), tenantWorkScope);
    }

    @Test
    void renameClosesHistoricalUsernamePrincipalByAccountId() throws IOException {
        User historical = new User();
        historical.setId(USER_ID);
        historical.setUsername("recycled");
        when(socket.getPrincipal()).thenReturn(UsernamePasswordAuthenticationToken.authenticated(
                historical, null, List.of()));

        webSocketSessions.closeByUser(USER_ID);

        verify(socket).close(CloseStatus.POLICY_VIOLATION);
    }

    @Test
    void renamingAnotherAccountKeepsThisSocketOpen() throws IOException {
        webSocketSessions.closeByUser(USER_ID + 1);

        verify(socket, never()).close(any());
    }

    @Test
    void missingPersistedSessionRejectsInboundFramesAndClosesSocket() throws IOException {
        when(repository.findById(HTTP_SESSION_ID)).thenReturn(null);

        assertNull(interceptor.preSend(message(SimpMessageType.SUBSCRIBE), channel));

        verify(socket).close(CloseStatus.POLICY_VIOLATION);
    }

    @Test
    void missingHandshakeSessionIdRejectsDeliveryAndClosesTransport() throws IOException {
        socketAttributes.clear();

        assertOutboundRejected();
    }

    @Test
    void registryExpiryMarkerRejectsDeliveryWithoutAnotherClientFrame() throws IOException {
        sessionAttributes.put(
                WebSocketSessionExpiryInterceptor.SPRING_SESSION_EXPIRED_ATTR, Boolean.TRUE);

        assertOutboundRejected();
    }

    @Test
    void jdbcIdleExpiryRejectsDeliveryWithoutAnotherClientFrame() throws IOException {
        when(backingSession.isExpired()).thenReturn(true);

        assertOutboundRejected();
    }

    @Test
    void missingEpochRejectsDelivery() throws IOException {
        sessionAttributes.remove(SessionSecurityService.SESSION_EPOCH_ATTR);

        assertOutboundRejected();
    }

    @Test
    void queuedMessageIsRecheckedAfterAnEpochBump() throws IOException {
        Message<byte[]> message = message(SimpMessageType.MESSAGE);
        assertSame(message, interceptor.preSend(message, channel));

        when(userMapper.currentSessionEpoch(USER_ID)).thenReturn(EPOCH + 1);

        assertNull(interceptor.beforeHandle(message, channel, handler));
        verify(socket).close(CloseStatus.POLICY_VIOLATION);
    }

    @Test
    void deletedAccountRejectsDelivery() throws IOException {
        when(userMapper.currentSessionEpoch(USER_ID)).thenReturn(null);

        assertOutboundRejected();
    }

    @Test
    void replacedBackingAccountCannotReceiveThroughTheOldSocket() throws IOException {
        User replacement = new User();
        replacement.setId(USER_ID + 1);
        sessionAttributes.put(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                new SecurityContextImpl(UsernamePasswordAuthenticationToken.authenticated(
                        replacement, null, List.of())));

        assertOutboundRejected();
    }

    @Test
    void untrustedPersistedPrincipalRejectsDelivery() throws IOException {
        sessionAttributes.put(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                new SecurityContextImpl(UsernamePasswordAuthenticationToken.authenticated(
                        "identity-provider-subject", null, List.of())));

        assertOutboundRejected();
    }

    @Test
    void unauthenticatedPersistedContextRejectsDelivery() throws IOException {
        User user = new User();
        user.setId(USER_ID);
        sessionAttributes.put(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                new SecurityContextImpl(UsernamePasswordAuthenticationToken.unauthenticated(user, null)));

        assertOutboundRejected();
    }

    @Test
    void socketWithoutAnImmutableAccountPrincipalRejectsDelivery() throws IOException {
        when(socket.getPrincipal()).thenReturn(UsernamePasswordAuthenticationToken.authenticated(
                "recycled", null, List.of()));

        assertOutboundRejected();
    }

    @Test
    void absoluteLifetimeRejectsDeliveryWithoutAnotherClientFrame() throws IOException {
        sessionAttributes.put(SessionSecurityService.AUTHENTICATED_AT_ATTR,
                NOW.minus(Duration.ofHours(12)).minusMillis(1).toEpochMilli());

        assertOutboundRejected();
    }

    @Test
    void missingAuthenticationTimestampRejectsDelivery() throws IOException {
        sessionAttributes.remove(SessionSecurityService.AUTHENTICATED_AT_ATTR);

        assertOutboundRejected();
    }

    @Test
    void futureAuthenticationTimestampRejectsDelivery() throws IOException {
        sessionAttributes.put(SessionSecurityService.AUTHENTICATED_AT_ATTR, NOW.plusMillis(1).toEpochMilli());

        assertOutboundRejected();
    }

    @Test
    void authenticationStampForAnotherAccountRejectsDelivery() throws IOException {
        sessionAttributes.put(SessionSecurityService.AUTHENTICATED_USER_ATTR, USER_ID + 1);

        assertOutboundRejected();
    }

    @Test
    void missingRepositoryRejectsDelivery() throws IOException {
        when(repositoryProvider.getIfAvailable()).thenReturn(null);

        assertOutboundRejected();
    }

    @Test
    void sessionStoreReadFailureClosesSocketAndDropsDelivery() throws IOException {
        when(repository.findById(HTTP_SESSION_ID)).thenThrow(new IllegalStateException("store unavailable"));

        assertOutboundRejected();
    }

    @Test
    void epochReadFailureClosesSocketAndDropsDelivery() throws IOException {
        when(userMapper.currentSessionEpoch(USER_ID)).thenThrow(new IllegalStateException("account unavailable"));

        assertOutboundRejected();
    }

    @Test
    void validOutboundWithoutAttributesUsesRegisteredHandshakeAndNeverRefreshesIdleTime() throws IOException {
        Message<byte[]> message = message(SimpMessageType.MESSAGE);
        assertNull(SimpMessageHeaderAccessor.getSessionAttributes(message.getHeaders()));

        assertSame(message, interceptor.preSend(message, channel));
        assertSame(message, interceptor.beforeHandle(message, channel, handler));

        verify(repository, times(2)).findById(HTTP_SESSION_ID);
        verifyNoMoreInteractions(repository);
        verify(backingSession, never()).setLastAccessedTime(any());
        verify(socket, never()).close(any());
    }

    /**
     * The broker negotiates a ten-second heartbeat with every client, so the cheap path must not
     * add the account-epoch database read to that timer.
     */
    @Test
    void heartbeatsAreCheckedAgainstTheSessionOnlyAndNeverLookUpTheAccountEpoch() throws IOException {
        Message<byte[]> heartbeat = message(SimpMessageType.HEARTBEAT);

        assertSame(heartbeat, interceptor.preSend(heartbeat, channel));
        assertSame(heartbeat, interceptor.beforeHandle(heartbeat, channel, handler));

        verify(repository, times(2)).findById(HTTP_SESSION_ID);
        verifyNoMoreInteractions(repository);
        verify(userMapper, never()).currentSessionEpoch(anyInt());
        verify(backingSession, never()).setLastAccessedTime(any());
        verify(socket, never()).close(any());
    }

    @Test
    void heartbeatsOnARevokedSessionAreRefusedAndCloseTheSocket() throws IOException {
        sessionAttributes.put(
                WebSocketSessionExpiryInterceptor.SPRING_SESSION_EXPIRED_ATTR, Boolean.TRUE);

        assertNull(interceptor.preSend(message(SimpMessageType.HEARTBEAT), channel));

        verify(socket).close(CloseStatus.POLICY_VIOLATION);
    }

    @Test
    void heartbeatsOnAnExpiredOrDeletedSessionAreRefusedAndCloseTheSocket() throws IOException {
        when(repository.findById(HTTP_SESSION_ID)).thenReturn(null);

        assertNull(interceptor.preSend(message(SimpMessageType.HEARTBEAT), channel));

        verify(socket).close(CloseStatus.POLICY_VIOLATION);
    }

    @Test
    void heartbeatsPastTheAbsoluteLifetimeAreRefusedAndCloseTheSocket() throws IOException {
        sessionAttributes.put(SessionSecurityService.AUTHENTICATED_AT_ATTR,
                NOW.minus(Duration.ofHours(12)).minusMillis(1).toEpochMilli());

        assertNull(interceptor.preSend(message(SimpMessageType.HEARTBEAT), channel));

        verify(socket).close(CloseStatus.POLICY_VIOLATION);
    }

    private void assertOutboundRejected() throws IOException {
        assertNull(interceptor.beforeHandle(message(SimpMessageType.MESSAGE), channel, handler));
        verify(socket).close(CloseStatus.POLICY_VIOLATION);
    }

    private static Message<byte[]> message(SimpMessageType type) {
        return MessageBuilder.withPayload(new byte[0])
                .setHeader(SimpMessageHeaderAccessor.MESSAGE_TYPE_HEADER, type)
                .setHeader(SimpMessageHeaderAccessor.SESSION_ID_HEADER, SOCKET_ID)
                .build();
    }
}
