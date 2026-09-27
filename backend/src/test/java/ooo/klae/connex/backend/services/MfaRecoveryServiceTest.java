package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
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

import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.session.SessionInformation;
import org.springframework.security.core.session.SessionRegistry;

import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.config.PrivilegedMfaProperties;
import ooo.klae.connex.backend.dto.PasskeyRecoveryRequest;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.exceptions.SpentRecoveryTokenException;
import ooo.klae.connex.backend.mappers.PrivilegedMfaRecoveryRedemptionMapper;
import ooo.klae.connex.backend.mappers.SpringSessionMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.session.AccountSessionIndex;
import ooo.klae.connex.backend.webauthn.WebAuthnService;

class MfaRecoveryServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-13T12:00:00Z");
    private static final String REDEMPTION_KEY = sha256Hex(HexFormat.of().parseHex(digestFor(7)));
    private final AuthService authService = mock(AuthService.class);
    private final UserMapper userMapper = mock(UserMapper.class);
    private final SpringSessionMapper springSessionMapper = mock(SpringSessionMapper.class);
    private final WebAuthnService webAuthnService = mock(WebAuthnService.class);
    private final SessionSecurityService sessionSecurityService = mock(SessionSecurityService.class);
    private final AuditService auditService = mock(AuditService.class);
    private final SessionRegistry sessionRegistry = mock(SessionRegistry.class);
    private final AccountSessionRevocationService accountSessionRevocationService =
            new AccountSessionRevocationService(sessionRegistry, springSessionMapper,
                    mock(ooo.klae.connex.backend.notifications.WebSocketSessionRegistry.class));
    private final PrivilegedMfaRecoveryRedemptionMapper redemptionMapper =
            mock(PrivilegedMfaRecoveryRedemptionMapper.class);
    private final PrivilegedMfaProperties properties = properties(7);
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
            redemptionMapper,
            clock);

    @BeforeEach
    void allowUserLock() {
        when(userMapper.lockById(7)).thenReturn(7);
        when(userMapper.bumpSessionEpoch(7)).thenReturn(1);
        when(userMapper.currentSessionEpoch(7)).thenReturn(4);
        when(userMapper.grantEpochRestamp(eq(7), any(), eq(4))).thenReturn(1);
        when(springSessionMapper.primaryIdBySessionId(any()))
                .thenReturn("ceremony-session-primary-id");
        when(redemptionMapper.insertIfAbsent(REDEMPTION_KEY, 7, "security-operator")).thenReturn(1);
    }

    /**
     * The ceremony locks the subject row before spending either proof. An account deleted between
     * the session read and that lock is an authentication failure, not a missing resource.
     */
    @Test
    void recoveryForAnAccountDeletedBeforeTheSubjectLockFailsAuthentication() {
        User user = user();
        MockHttpServletRequest httpRequest = new MockHttpServletRequest();
        httpRequest.getSession();
        when(authService.getCurrentUser()).thenReturn(user);
        when(userMapper.lockById(7)).thenReturn(null);

        assertThrows(AuthenticationException.class,
            () -> service.recover(request("operator-proof"), httpRequest));

        verify(webAuthnService, never()).recover(anyInt());
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
                redemptionMapper,
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
        proofOrder.verify(redemptionMapper).insertIfAbsent(REDEMPTION_KEY, 7, "security-operator");
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
        verify(redemptionMapper, never()).insertIfAbsent(any(), anyInt(), any());
        verify(auditService, never()).recordStrictScoped(any(), any(), any(), any(), any(), any(), any(), any());
    }

    /**
     * The recovering account's id is what the token is checked against: the same token is refused
     * for account 7 and accepted for account 8, the account it was issued to.
     */
    @Test
    void aTokenIssuedForAnotherAccountIsRefusedBeforeAnythingIsSpentOrRemoved() {
        MfaRecoveryService boundToAccount8 = new MfaRecoveryService(authService, userMapper,
                springSessionMapper, webAuthnService, sessionSecurityService, properties(8), auditService,
                accountSessionRevocationService, redemptionMapper, clock);
        MockHttpServletRequest httpRequest = preparedRecoveryRequest();

        ForbiddenException refusal = assertThrows(ForbiddenException.class,
                () -> boundToAccount8.recover(request("operator-proof"), httpRequest));

        assertEquals(PrivilegedMfaProperties.INVALID_RECOVERY_AUTHORIZATION, refusal.getMessage());
        assertEquals(ForbiddenException.class, refusal.getClass());
        verify(redemptionMapper, never()).insertIfAbsent(any(), anyInt(), any());
        verify(webAuthnService, never()).recover(anyInt());
        verify(userMapper, never()).bumpSessionEpoch(anyInt());

        User account8 = new User();
        account8.setId(8);
        account8.setDisplayName("Issued");
        when(authService.getCurrentUser()).thenReturn(account8);
        when(userMapper.lockById(8)).thenReturn(8);
        when(userMapper.bumpSessionEpoch(8)).thenReturn(1);
        when(userMapper.currentSessionEpoch(8)).thenReturn(2);
        when(userMapper.grantEpochRestamp(eq(8), any(), eq(2))).thenReturn(1);
        String account8Key = sha256Hex(HexFormat.of().parseHex(digestFor(8)));
        when(redemptionMapper.insertIfAbsent(account8Key, 8, "security-operator")).thenReturn(1);

        assertEquals(2, boundToAccount8.recover(request("operator-proof"), httpRequest));
        verify(redemptionMapper).insertIfAbsent(account8Key, 8, "security-operator");
        verify(webAuthnService).recover(8);
    }

    /**
     * A spent token is refused with the invalid-token message and code and removes nothing. The
     * refusal is the spent-token subtype so the controller can audit the replay distinctly.
     */
    @Test
    void anAlreadyRedeemedTokenIsRefusedBeforeCredentialRemoval() {
        MockHttpServletRequest httpRequest = preparedRecoveryRequest();
        when(redemptionMapper.insertIfAbsent(REDEMPTION_KEY, 7, "security-operator")).thenReturn(0);

        SpentRecoveryTokenException refusal = assertThrows(SpentRecoveryTokenException.class,
                () -> service.recover(request("operator-proof"), httpRequest));

        assertEquals(PrivilegedMfaProperties.INVALID_RECOVERY_AUTHORIZATION, refusal.getMessage());
        assertEquals(ForbiddenException.CODE, refusal.getCode());
        verify(webAuthnService, never()).recover(anyInt());
        verify(auditService, never()).recordStrictScoped(any(), any(), any(), any(), any(), any(), any(), any());
        verify(userMapper, never()).bumpSessionEpoch(anyInt());
        verify(userMapper, never()).grantEpochRestamp(anyInt(), any(), anyInt());
    }

    /**
     * An audit failure after the token is spent propagates, so the surrounding transaction rolls
     * the redemption back together with the removal.
     */
    @Test
    void anAuditFailureAfterRedemptionPropagates() {
        MockHttpServletRequest httpRequest = preparedRecoveryRequest();
        IllegalStateException auditFailure = new IllegalStateException("audit unavailable");
        doThrow(auditFailure).when(auditService).recordStrictScoped(
                eq("auth.mfa.recovery.used"), any(), any(), any(), any(), any(), any(), any());

        assertSame(auditFailure, assertThrows(IllegalStateException.class,
                () -> service.recover(request("operator-proof"), httpRequest)));

        InOrder order = inOrder(redemptionMapper, webAuthnService, auditService);
        order.verify(redemptionMapper).insertIfAbsent(REDEMPTION_KEY, 7, "security-operator");
        order.verify(webAuthnService).recover(7);
        verify(userMapper, never()).bumpSessionEpoch(anyInt());
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

    private static PrivilegedMfaProperties properties(int userId) {
        PrivilegedMfaProperties properties = new PrivilegedMfaProperties();
        properties.setRecoveryTokenSha256(digestFor(userId));
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

    private static String digestFor(int userId) {
        return sha256Hex(("connex-privileged-mfa-recovery:v1:" + userId + ":operator-proof")
                .getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256Hex(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
