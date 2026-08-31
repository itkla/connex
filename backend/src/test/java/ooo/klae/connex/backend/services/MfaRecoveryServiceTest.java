package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.InOrder;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;

import org.springframework.security.core.session.SessionInformation;
import org.springframework.security.core.session.SessionRegistry;

import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.config.PrivilegedMfaProperties;
import ooo.klae.connex.backend.dto.PasskeyRecoveryRequest;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.mappers.SpringSessionMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.session.AccountSessionIndex;
import ooo.klae.connex.backend.webauthn.WebAuthnService;

class MfaRecoveryServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-13T12:00:00Z");
    private final AuthService authService = mock(AuthService.class);
    private final UserMapper userMapper = mock(UserMapper.class);
    private final SpringSessionMapper springSessionMapper = mock(SpringSessionMapper.class);
    private final WebAuthnService webAuthnService = mock(WebAuthnService.class);
    private final SessionSecurityService sessionSecurityService = mock(SessionSecurityService.class);
    private final AuditService auditService = mock(AuditService.class);
    private final SessionRegistry sessionRegistry = mock(SessionRegistry.class);
    private final AccountSessionRevocationService accountSessionRevocationService =
            new AccountSessionRevocationService(sessionRegistry, springSessionMapper);
    private final PrivilegedMfaProperties properties = properties();
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final MfaRecoveryService service = new MfaRecoveryService(
            authService,
            userMapper,
            springSessionMapper,
            webAuthnService,
            sessionSecurityService,
            properties,
            auditService,
            accountSessionRevocationService,
            clock);

    @BeforeEach
    void allowUserLock() {
        when(userMapper.lockById(7)).thenReturn(7);
        when(userMapper.bumpSessionEpoch(7)).thenReturn(1);
        when(userMapper.currentSessionEpoch(7)).thenReturn(4);
        when(userMapper.grantEpochRestamp(eq(7), any(), eq(4))).thenReturn(1);
        when(springSessionMapper.primaryIdBySessionId(any()))
                .thenReturn("ceremony-session-primary-id");
    }

    @Test
    void recoveryExpiresEverySessionExceptTheOneCompletingTheCeremony() {
        User user = user();
        MockHttpServletRequest httpRequest = new MockHttpServletRequest();
        String currentId = httpRequest.getSession().getId();
        SessionInformation current = new SessionInformation(user, currentId, java.util.Date.from(NOW));
        SessionInformation other = new SessionInformation(user, "another-session", java.util.Date.from(NOW));
        when(authService.getCurrentUser()).thenReturn(user);
        when(webAuthnService.recover(7)).thenReturn(1);
        when(springSessionMapper.primaryIdBySessionId(currentId)).thenReturn("ceremony-primary");
        when(springSessionMapper.primaryIdBySessionId("another-session")).thenReturn("other-primary");
        when(sessionRegistry.getAllSessions(new AccountSessionIndex(7), false)).thenReturn(java.util.List.of(current, other));

        int epoch = service.recover(request("operator-proof"), httpRequest);

        assertEquals(4, epoch);
        org.junit.jupiter.api.Assertions.assertTrue(other.isExpired());
        org.junit.jupiter.api.Assertions.assertFalse(current.isExpired());
    }

    @Test
    void recoveryRequiresBothAccountAndOperatorProofsAndAuditsWithoutTokenMaterial() {
        User user = user();
        MockHttpServletRequest httpRequest = new MockHttpServletRequest();
        httpRequest.getSession();
        PasskeyRecoveryRequest request = request("operator-proof");
        when(authService.getCurrentUser()).thenReturn(user);
        when(webAuthnService.recover(7)).thenReturn(2);

        int epoch = service.recover(request, httpRequest);

        assertEquals(4, epoch);
        InOrder proofOrder = inOrder(
                authService,
                userMapper,
                springSessionMapper,
                webAuthnService,
                auditService,
                sessionSecurityService,
                sessionRegistry);
        proofOrder.verify(authService).getCurrentUser();
        proofOrder.verify(userMapper).lockById(7);
        proofOrder.verify(springSessionMapper)
                .primaryIdBySessionId(httpRequest.getSession(false).getId());
        proofOrder.verify(authService).requireFirstPasskeyBootstrapAuthentication(
                7, "current-password", httpRequest);
        proofOrder.verify(webAuthnService).recover(7);
        proofOrder.verify(auditService).recordStrictScoped(
                eq("auth.mfa.recovery.used"),
                eq("user"),
                eq(7),
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.isNull(),
                eq("Admin"),
                eq("Operator-authorized passkey recovery used"),
                any());
        proofOrder.verify(userMapper).bumpSessionEpoch(7);
        proofOrder.verify(userMapper).currentSessionEpoch(7);
        proofOrder.verify(userMapper).grantEpochRestamp(
                7, "ceremony-session-primary-id", 4);
        proofOrder.verify(sessionSecurityService).clearRecentAuthentication(httpRequest);
        proofOrder.verify(sessionRegistry).getAllSessions(new AccountSessionIndex(7), false);
    }

    @Test
    void invalidOperatorProofCannotRemoveCredentials() {
        when(authService.getCurrentUser()).thenReturn(user());
        PasskeyRecoveryRequest request = request("wrong-proof");
        MockHttpServletRequest httpRequest = new MockHttpServletRequest();
        httpRequest.getSession();

        assertThrows(ForbiddenException.class,
                () -> service.recover(request, httpRequest));

        verify(webAuthnService, never()).recover(7);
        verify(auditService, never()).recordStrictScoped(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void recoveryWithoutACeremonySessionRefusesBeforeReadingOrDeletingTheAccount() {
        assertThrows(ForbiddenException.class,
                () -> service.recover(request("operator-proof"), new MockHttpServletRequest()));

        verify(authService, never()).getCurrentUser();
        verify(userMapper, never()).lockById(anyInt());
        verify(webAuthnService, never()).recover(anyInt());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 2})
    void recoveryRequiresExactlyOneEpochAdvance(int updated) {
        MockHttpServletRequest httpRequest = preparedRecoveryRequest();
        when(userMapper.bumpSessionEpoch(7)).thenReturn(updated);

        assertThrows(IllegalStateException.class,
                () -> service.recover(request("operator-proof"), httpRequest));

        verify(userMapper, never()).currentSessionEpoch(7);
        verify(userMapper, never()).grantEpochRestamp(anyInt(), any(), anyInt());
        verify(sessionSecurityService, never()).clearRecentAuthentication(httpRequest);
    }

    @Test
    void recoveryRequiresTheAdvancedEpochToBeReadable() {
        MockHttpServletRequest httpRequest = preparedRecoveryRequest();
        when(userMapper.currentSessionEpoch(7)).thenReturn(null);

        assertThrows(IllegalStateException.class,
                () -> service.recover(request("operator-proof"), httpRequest));

        verify(userMapper, never()).grantEpochRestamp(anyInt(), any(), anyInt());
        verify(sessionSecurityService, never()).clearRecentAuthentication(httpRequest);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 2})
    void recoveryRequiresExactlyOneRestampGrantUpdate(int updated) {
        MockHttpServletRequest httpRequest = preparedRecoveryRequest();
        when(userMapper.grantEpochRestamp(eq(7), any(), eq(4))).thenReturn(updated);

        assertThrows(IllegalStateException.class,
                () -> service.recover(request("operator-proof"), httpRequest));

        verify(sessionSecurityService, never()).clearRecentAuthentication(httpRequest);
        verify(sessionRegistry, never()).getAllSessions(any(), eq(false));
    }

    private MockHttpServletRequest preparedRecoveryRequest() {
        MockHttpServletRequest httpRequest = new MockHttpServletRequest();
        httpRequest.getSession();
        when(authService.getCurrentUser()).thenReturn(user());
        when(webAuthnService.recover(7)).thenReturn(1);
        return httpRequest;
    }

    private static PrivilegedMfaProperties properties() {
        PrivilegedMfaProperties properties = new PrivilegedMfaProperties();
        properties.setRecoveryTokenSha256(sha256Hex("operator-proof"));
        properties.setRecoveryExpiresAt(NOW.plusSeconds(1800).toString());
        properties.setRecoveryActor("security-operator");
        return properties;
    }

    private static PasskeyRecoveryRequest request(String token) {
        PasskeyRecoveryRequest request = new PasskeyRecoveryRequest();
        request.setCurrentPassword("current-password");
        request.setRecoveryToken(token);
        return request;
    }

    private static User user() {
        User user = new User();
        user.setId(7);
        user.setDisplayName("Admin");
        return user;
    }

    private static String sha256Hex(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
