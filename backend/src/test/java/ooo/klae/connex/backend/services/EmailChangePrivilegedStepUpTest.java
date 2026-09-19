package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.config.PrivilegedMfaProperties;
import ooo.klae.connex.backend.config.SessionSecurityProperties;
import ooo.klae.connex.backend.exceptions.PasskeyEnrollmentRequiredException;
import ooo.klae.connex.backend.exceptions.RecentAuthenticationRequiredException;
import ooo.klae.connex.backend.mappers.EmailChangeTokenMapper;
import ooo.klae.connex.backend.mappers.NotificationMapper;
import ooo.klae.connex.backend.mappers.PasswordResetTokenMapper;
import ooo.klae.connex.backend.mappers.SpringSessionMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.notifications.NotificationStateVersionService;
import ooo.klae.connex.backend.util.ClientIpResolver.ResolvedClientIp;
import ooo.klae.connex.backend.webauthn.WebAuthnService;

/**
 * Pins the privileged-account gate on email-change requests (#1506 Part A).
 *
 * <p>The account address receives the first-passkey enrollment confirmation, so a privileged
 * account may only move it with a passkey and a fresh WebAuthn step-up. The gate must hold with
 * confinement on and off: each case runs against a real {@link SessionSecurityService} under both
 * {@code privileged-mfa.enforced} values. Refusal audits are independent appends that take the
 * actor's {@code app_user} row shared, so they may only run before this transaction locks it.
 */
class EmailChangePrivilegedStepUpTest {
    private static final int USER_ID = 7;
    private static final int SESSION_EPOCH = 3;
    private static final String PASSWORD = "current-password";
    private static final String NEW_EMAIL = "moved@example.com";
    private static final ResolvedClientIp CLIENT_IP = new ResolvedClientIp("198.51.100.4", false);
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-18T12:00:00Z"), ZoneOffset.UTC);

    private final UserMapper userMapper = mock(UserMapper.class);
    private final EmailChangeTokenMapper tokenMapper = mock(EmailChangeTokenMapper.class);
    private final EmailChangeEmailService emailService = mock(EmailChangeEmailService.class);
    private final AuthService authService = mock(AuthService.class);
    private final AuditService auditService = mock(AuditService.class);
    private final PrivilegedAccountService privilegedAccountService = mock(PrivilegedAccountService.class);
    private final WebAuthnService webAuthnService = mock(WebAuthnService.class);
    private final MockHttpServletRequest request = new MockHttpServletRequest();

    @AfterEach
    void clearRequest() {
        RequestContextHolder.resetRequestAttributes();
    }

    @ParameterizedTest
    @ValueSource(strings = {"true", "false"})
    void aPrivilegedAccountThatNeverEnrolledIsRefusedAndAuditedBeforeTheAccountLock(String enforced) {
        EmailChangeService service = service(enforced);
        when(privilegedAccountService.isPrivileged(USER_ID)).thenReturn(true);
        when(webAuthnService.hasPasskey(USER_ID)).thenReturn(false);

        assertThrows(PasskeyEnrollmentRequiredException.class,
                () -> service.requestChange(NEW_EMAIL, PASSWORD, CLIENT_IP));

        InOrder order = inOrder(authService, auditService, userMapper);
        order.verify(authService).requireCurrentPassword(USER_ID, PASSWORD, CLIENT_IP);
        order.verify(auditService).recordFailure(eq("auth.email_change.refused"), eq("user"), eq(USER_ID),
                isNull(), anyString(), eq("privileged_mfa_enrollment_required"));
        verify(userMapper, never()).lockById(anyInt());
        verify(auditService, times(1)).recordFailure(any(), any(), any(), any(), any(), any());
        verifyNoTokenIssued();
    }

    @ParameterizedTest
    @ValueSource(strings = {"true", "false"})
    void aPrivilegedAccountWithoutAFreshStepUpIsRefusedAndAuditedBeforeTheAccountLock(String enforced) {
        EmailChangeService service = service(enforced);
        when(privilegedAccountService.isPrivileged(USER_ID)).thenReturn(true);
        when(webAuthnService.hasPasskey(USER_ID)).thenReturn(true);

        assertThrows(RecentAuthenticationRequiredException.class,
                () -> service.requestChange(NEW_EMAIL, PASSWORD, CLIENT_IP));

        verify(auditService).recordFailure(eq("auth.email_change.refused"), eq("user"), eq(USER_ID),
                isNull(), anyString(), eq("recent_authentication_required"));
        verify(userMapper, never()).lockById(anyInt());
        verifyNoTokenIssued();
    }

    @ParameterizedTest
    @ValueSource(strings = {"true", "false"})
    void anExpiredStepUpIsRefused(String enforced) {
        EmailChangeService service = service(enforced);
        when(privilegedAccountService.isPrivileged(USER_ID)).thenReturn(true);
        when(webAuthnService.hasPasskey(USER_ID)).thenReturn(true);
        request.getSession().setAttribute(SessionSecurityService.WEBAUTHN_STEP_UP_AT_ATTR,
                CLOCK.millis() - Duration.ofMinutes(11).toMillis());
        request.getSession().setAttribute(SessionSecurityService.WEBAUTHN_STEP_UP_USER_ATTR, USER_ID);

        assertThrows(RecentAuthenticationRequiredException.class,
                () -> service.requestChange(NEW_EMAIL, PASSWORD, CLIENT_IP));

        verify(userMapper, never()).lockById(anyInt());
        verifyNoTokenIssued();
    }

    @ParameterizedTest
    @ValueSource(strings = {"true", "false"})
    void aPrivilegedAccountWithAFreshStepUpProceedsAndIsRecheckedUnderTheLock(String enforced) {
        EmailChangeService service = service(enforced);
        when(privilegedAccountService.isPrivileged(USER_ID)).thenReturn(true);
        when(webAuthnService.hasPasskey(USER_ID)).thenReturn(true);
        new SessionSecurityService(sessionProperties(), properties(enforced), CLOCK,
                userMapper, mock(SpringSessionMapper.class)).markStepUp(request, USER_ID);

        assertDoesNotThrow(() -> service.requestChange(NEW_EMAIL, PASSWORD, CLIENT_IP));

        InOrder order = inOrder(privilegedAccountService, userMapper, tokenMapper, emailService);
        order.verify(privilegedAccountService).isPrivileged(USER_ID);
        order.verify(userMapper).lockById(USER_ID);
        order.verify(userMapper).lockAssignedCustomRoleIds(USER_ID);
        order.verify(privilegedAccountService).isPrivileged(USER_ID);
        order.verify(tokenMapper).invalidateForUser(USER_ID);
        order.verify(tokenMapper).insert(eq(USER_ID), eq(NEW_EMAIL), anyString(), eq("198.51.100.4"),
                eq(30), eq(SESSION_EPOCH));
        order.verify(emailService).sendVerificationEmail(any(), eq(NEW_EMAIL), anyString());
        verify(auditService, never()).recordFailure(any(), any(), any(), any(), any(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"true", "false"})
    void anUnprivilegedAccountNeedsNoPasskeyOrStepUp(String enforced) {
        EmailChangeService service = service(enforced);
        when(privilegedAccountService.isPrivileged(USER_ID)).thenReturn(false);

        assertDoesNotThrow(() -> service.requestChange(NEW_EMAIL, PASSWORD, CLIENT_IP));

        verify(privilegedAccountService, times(2)).isPrivileged(USER_ID);
        verify(webAuthnService, never()).hasPasskey(anyInt());
        verify(tokenMapper).insert(eq(USER_ID), eq(NEW_EMAIL), anyString(), eq("198.51.100.4"),
                anyInt(), eq(SESSION_EPOCH));
        verify(auditService, never()).recordFailure(any(), any(), any(), any(), any(), any());
    }

    /**
     * A promotion that commits while the request waits for the account lock is observed there and
     * refused without an audit append, which would otherwise wait on this transaction's own lock.
     */
    @ParameterizedTest
    @ValueSource(strings = {"true", "false"})
    void aPromotionObservedOnlyUnderTheLockIsRefusedWithoutAnAudit(String enforced) {
        EmailChangeService service = service(enforced);
        when(privilegedAccountService.isPrivileged(USER_ID)).thenReturn(false, true);
        when(webAuthnService.hasPasskey(USER_ID)).thenReturn(false);

        assertThrows(PasskeyEnrollmentRequiredException.class,
                () -> service.requestChange(NEW_EMAIL, PASSWORD, CLIENT_IP));

        InOrder order = inOrder(userMapper, privilegedAccountService, webAuthnService);
        order.verify(userMapper).lockById(USER_ID);
        order.verify(userMapper).lockAssignedCustomRoleIds(USER_ID);
        order.verify(privilegedAccountService).isPrivileged(USER_ID);
        order.verify(webAuthnService).hasPasskey(USER_ID);
        verify(auditService, never()).recordFailure(any(), any(), any(), any(), any(), any());
        verifyNoTokenIssued();
    }

    /** The same holds when the promoted account is enrolled but has not stepped up. */
    @ParameterizedTest
    @ValueSource(strings = {"true", "false"})
    void aPromotedEnrolledAccountWithoutStepUpIsRefusedUnderTheLockWithoutAnAudit(String enforced) {
        EmailChangeService service = service(enforced);
        when(privilegedAccountService.isPrivileged(USER_ID)).thenReturn(false, true);
        when(webAuthnService.hasPasskey(USER_ID)).thenReturn(true);

        assertThrows(RecentAuthenticationRequiredException.class,
                () -> service.requestChange(NEW_EMAIL, PASSWORD, CLIENT_IP));

        verify(userMapper).lockById(USER_ID);
        verify(auditService, never()).recordFailure(any(), any(), any(), any(), any(), any());
        verifyNoTokenIssued();
    }

    private void verifyNoTokenIssued() {
        verify(tokenMapper, never()).insert(anyInt(), any(), any(), any(), anyInt(), any());
        verify(tokenMapper, never()).invalidateForUser(anyInt());
        verifyNoMoreInteractions(emailService);
    }

    private EmailChangeService service(String enforced) {
        User user = user();
        request.getSession().setAttribute(SessionSecurityService.SESSION_EPOCH_ATTR, SESSION_EPOCH);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        when(authService.getCurrentUser()).thenReturn(user);
        when(userMapper.lockById(USER_ID)).thenReturn(USER_ID);
        when(userMapper.getUserByIdForShare(USER_ID)).thenReturn(user());
        SessionSecurityService sessionSecurityService = new SessionSecurityService(
                sessionProperties(), properties(enforced), CLOCK, userMapper, mock(SpringSessionMapper.class));
        EmailChangeService service = new EmailChangeService(
                userMapper,
                mock(WorkspaceMapper.class),
                mock(NotificationMapper.class),
                mock(NotificationStateVersionService.class),
                tokenMapper,
                mock(PasswordResetTokenMapper.class),
                emailService,
                authService,
                sessionSecurityService,
                auditService,
                mock(LoginRateLimiter.class),
                privilegedAccountService,
                webAuthnService);
        ReflectionTestUtils.setField(service, "maxRequests", 5);
        ReflectionTestUtils.setField(service, "tokenExpiryMinutes", 30);
        ReflectionTestUtils.setField(service, "requestWindowSeconds", 900);
        return service;
    }

    private static PrivilegedMfaProperties properties(String enforced) {
        PrivilegedMfaProperties properties = new PrivilegedMfaProperties();
        properties.setEnforced(enforced);
        properties.setChangeActor("security-change-1506");
        return properties;
    }

    private static SessionSecurityProperties sessionProperties() {
        SessionSecurityProperties properties = new SessionSecurityProperties();
        properties.setRecentAuthenticationWindow(Duration.ofMinutes(10));
        return properties;
    }

    private static User user() {
        User user = new User();
        user.setId(USER_ID);
        user.setDisplayName("Privileged Admin");
        user.setEmail("admin@example.com");
        user.setPasswordHash("stored-password-hash");
        user.setSessionEpoch(SESSION_EPOCH);
        return user;
    }
}
