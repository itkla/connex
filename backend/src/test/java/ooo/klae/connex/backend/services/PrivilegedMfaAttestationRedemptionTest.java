package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.webauthn.api.AuthenticatorAssertionResponse;
import org.springframework.security.web.webauthn.api.PublicKeyCredential;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialRequestOptions;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.dto.MfaAttestationRedemptionDto;
import ooo.klae.connex.backend.exceptions.MfaAttestationRefusedException;
import ooo.klae.connex.backend.webauthn.VerifiedPasskey;
import ooo.klae.connex.backend.webauthn.WebAuthnService;

@ExtendWith(MockitoExtension.class)
class PrivilegedMfaAttestationRedemptionTest {
    private static final int GRANTEE_ID = 9;
    private static final int PASSKEY_ROW_ID = 55;
    private static final int SESSION_EPOCH = 4;
    private static final String CODE = "0123-4567-89AB-CDEF";

    @Mock private WebAuthnService webAuthnService;
    @Mock private PrivilegedMfaAttestationService attestationService;
    @Mock private SessionSecurityService sessionSecurityService;
    @Mock private AuditService auditService;

    @InjectMocks private PrivilegedMfaAttestationRedemption redemption;

    private final MockHttpServletRequest request = new MockHttpServletRequest();
    private final Authentication auth = mock(Authentication.class);
    private final PublicKeyCredentialRequestOptions options = mock(PublicKeyCredentialRequestOptions.class);
    private final PublicKeyCredential<AuthenticatorAssertionResponse> assertion = mock();
    private final User grantee = grantee();

    @BeforeEach
    void setUp() {
        request.getSession();
    }

    @AfterEach
    void tearDown() {
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Test
    void aRedeemedCodeStampsTheSessionWithTheAttestedPasskeyOnlyAfterTheRedemption() {
        stubVerifiedAssertion();
        when(attestationService.redeem(GRANTEE_ID, SESSION_EPOCH, PASSKEY_ROW_ID, CODE))
            .thenReturn(new MfaAttestationRedemptionDto(3));

        assertEquals(3, redemption.redeem(request, auth, grantee, options, assertion, CODE).orgId());

        InOrder order = inOrder(webAuthnService, attestationService, sessionSecurityService);
        order.verify(webAuthnService).finishStepUp(auth, options, assertion);
        order.verify(attestationService).redeem(GRANTEE_ID, SESSION_EPOCH, PASSKEY_ROW_ID, CODE);
        order.verify(sessionSecurityService).markStepUp(request, GRANTEE_ID, PASSKEY_ROW_ID);
        verify(attestationService, never()).burn(anyInt());
        verifyNoInteractions(auditService);
    }

    @Test
    void aRefusalIsCountedThenAuditedAtAccountScopeAndAnsweredUniformlyWithoutAStamp() {
        stubVerifiedAssertion();
        when(attestationService.redeem(GRANTEE_ID, SESSION_EPOCH, PASSKEY_ROW_ID, CODE))
            .thenThrow(new PrivilegedMfaAttestationService.AttestationRefusal("code_mismatch"));

        MfaAttestationRefusedException refused = assertThrows(MfaAttestationRefusedException.class,
            () -> redemption.redeem(request, auth, grantee, options, assertion, CODE));

        assertEquals(MfaAttestationRefusedException.CODE, refused.getCode());
        InOrder order = inOrder(attestationService, auditService);
        order.verify(attestationService).redeem(GRANTEE_ID, SESSION_EPOCH, PASSKEY_ROW_ID, CODE);
        order.verify(attestationService).burn(GRANTEE_ID);
        order.verify(auditService).recordStrictFailureIndependentScoped(
            "auth.mfa.attestation.denied", "user", GRANTEE_ID, null, null, "Grantee",
            "Refused a privileged MFA attestation code", "code_mismatch");
        verify(sessionSecurityService, never()).markStepUp(any(), anyInt(), anyInt());
    }

    @Test
    void aFailedAssertionCountsAndAuditsNothing() {
        when(webAuthnService.finishStepUp(auth, options, assertion))
            .thenThrow(new BadCredentialsException("Passkey authentication failed"));

        assertThrows(BadCredentialsException.class,
            () -> redemption.redeem(request, auth, grantee, options, assertion, CODE));

        verifyNoInteractions(attestationService, auditService);
        verify(sessionSecurityService, never()).markStepUp(any(), anyInt(), anyInt());
    }

    @Test
    void aDenialThatCannotBeAuditedIsAnInfrastructureErrorAfterTheCountCommitted() {
        stubVerifiedAssertion();
        when(attestationService.redeem(GRANTEE_ID, SESSION_EPOCH, PASSKEY_ROW_ID, CODE))
            .thenThrow(new PrivilegedMfaAttestationService.AttestationRefusal("grant_unusable"));
        doThrow(new IllegalStateException("audit unavailable")).when(auditService)
            .recordStrictFailureIndependentScoped(
                anyString(), anyString(), any(), isNull(), isNull(), any(), anyString(), anyString());

        assertThrows(IllegalStateException.class,
            () -> redemption.redeem(request, auth, grantee, options, assertion, CODE));

        verify(attestationService).burn(GRANTEE_ID);
    }

    @Test
    void itRefusesToRunInsideACallersTransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(true);

        assertThrows(IllegalStateException.class,
            () -> redemption.redeem(request, auth, grantee, options, assertion, CODE));

        verifyNoInteractions(webAuthnService, attestationService, auditService);
    }

    private void stubVerifiedAssertion() {
        when(webAuthnService.finishStepUp(auth, options, assertion))
            .thenReturn(new VerifiedPasskey(grantee, PASSKEY_ROW_ID));
        when(sessionSecurityService.sessionEpoch(request.getSession(false))).thenReturn(SESSION_EPOCH);
    }

    private static User grantee() {
        User user = new User();
        user.setId(GRANTEE_ID);
        user.setDisplayName("Grantee");
        return user;
    }
}
