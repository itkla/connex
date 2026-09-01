package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.config.PrivilegedMfaProperties;
import ooo.klae.connex.backend.exceptions.PrivilegedBootstrapForbiddenException;

/**
 * Pins the decision matrix for first-passkey enrollment authorization. The refusal exists so a
 * stolen password cannot mint the second factor for an account that administers other people.
 */
class PasskeyBootstrapAuthorizationServiceTest {
    private static final int USER_ID = 7;

    private final PrivilegedMfaProperties properties = mock(PrivilegedMfaProperties.class);
    private final PrivilegedAccountService privilegedAccountService =
        mock(PrivilegedAccountService.class);
    private final AuthService authService = mock(AuthService.class);
    private final MfaRecoveryService mfaRecoveryService = mock(MfaRecoveryService.class);
    private final AuditService auditService = mock(AuditService.class);
    private final PasskeyBootstrapAuthorizationService service =
        new PasskeyBootstrapAuthorizationService(
            properties, privilegedAccountService, authService, mfaRecoveryService, auditService);

    private final MockHttpServletRequest request = new MockHttpServletRequest();

    private static User user() {
        User user = new User();
        user.setId(USER_ID);
        user.setDisplayName("Priv Admin");
        return user;
    }

    private void privileged(boolean overOthers) {
        when(properties.isEnforced()).thenReturn(true);
        when(authService.hasPasswordCredential(USER_ID)).thenReturn(true);
        when(privilegedAccountService.isPrivileged(USER_ID)).thenReturn(true);
        when(privilegedAccountService.hasPrivilegeOverOtherAccounts(USER_ID)).thenReturn(overOthers);
    }

    @Test
    void administeringOtherPrincipalsRefusesPasswordOnlyBootstrap() {
        privileged(true);
        when(mfaRecoveryService.hasOutstandingRecoveryGrant(USER_ID, request)).thenReturn(false);

        assertThrows(PrivilegedBootstrapForbiddenException.class,
            () -> service.requireFirstPasskeyBootstrapAuthorization(user(), request));

        verify(auditService).recordStrictFailureIndependentScoped(
            eq("auth.passkey.bootstrap.denied"), eq("user"), eq(USER_ID), any(), any(),
            anyString(), anyString(), eq("privileged_bootstrap_unauthorized"));
    }

    @Test
    void refusalCarriesItsOwnApiCode() {
        assertEquals("PRIVILEGED_PASSKEY_BOOTSTRAP_FORBIDDEN",
            new PrivilegedBootstrapForbiddenException("refused").getCode());
    }

    @Test
    void administeringNobodyElseStillEnrollsWithAPassword() {
        privileged(false);

        service.requireFirstPasskeyBootstrapAuthorization(user(), request);

        verify(mfaRecoveryService, never()).hasOutstandingRecoveryGrant(anyInt(), any());
    }

    @Test
    void anOutstandingOperatorRecoveryGrantAuthorizesEnrollment() {
        privileged(true);
        when(mfaRecoveryService.hasOutstandingRecoveryGrant(USER_ID, request)).thenReturn(true);

        service.requireFirstPasskeyBootstrapAuthorization(user(), request);
    }

    @Test
    void aPasswordlessAccountIsUntouched() {
        when(properties.isEnforced()).thenReturn(true);
        when(authService.hasPasswordCredential(USER_ID)).thenReturn(false);

        service.requireFirstPasskeyBootstrapAuthorization(user(), request);

        verify(privilegedAccountService, never()).isPrivileged(anyInt());
    }

    @Test
    void anUnprivilegedAccountIsUntouched() {
        when(properties.isEnforced()).thenReturn(true);
        when(authService.hasPasswordCredential(USER_ID)).thenReturn(true);
        when(privilegedAccountService.isPrivileged(USER_ID)).thenReturn(false);

        service.requireFirstPasskeyBootstrapAuthorization(user(), request);

        verify(privilegedAccountService, never()).hasPrivilegeOverOtherAccounts(anyInt());
    }

    @Test
    void anUnenforcedDeploymentIsUntouched() {
        when(properties.isEnforced()).thenReturn(false);

        service.requireFirstPasskeyBootstrapAuthorization(user(), request);

        verify(authService, never()).hasPasswordCredential(anyInt());
    }

    @Test
    void theRequirementsFlagMirrorsTheRefusal() {
        privileged(true);
        when(mfaRecoveryService.hasOutstandingRecoveryGrant(USER_ID, request)).thenReturn(false);
        assertTrue(service.operatorAuthorizationRequired(user(), request));

        when(privilegedAccountService.hasPrivilegeOverOtherAccounts(USER_ID)).thenReturn(false);
        assertFalse(service.operatorAuthorizationRequired(user(), request));
    }
}
