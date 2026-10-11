package ooo.klae.connex.backend.services;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.security.core.Authentication;
import org.springframework.security.web.webauthn.api.AuthenticatorAssertionResponse;
import org.springframework.security.web.webauthn.api.PublicKeyCredential;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialRequestOptions;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import lombok.RequiredArgsConstructor;

import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.dto.MfaAttestationRedemptionDto;
import ooo.klae.connex.backend.exceptions.MfaAttestationRefusedException;
import ooo.klae.connex.backend.webauthn.VerifiedPasskey;
import ooo.klae.connex.backend.webauthn.WebAuthnService;

/**
 * Runs a privileged MFA attestation redemption in its separate committed steps (#1534). The
 * passkey assertion is verified first, in its own transaction, because verifying advances the
 * passkey's signature counter and would otherwise take the credential before the account roots the
 * redemption locks. The redemption then runs in its own transaction. A refusal is counted against
 * the grantee's open grants in a third transaction once the redemption has rolled back, then
 * audited at account scope, and answered with one uniform refusal.
 */
@Service
@RequiredArgsConstructor
public class PrivilegedMfaAttestationRedemption {
    private final WebAuthnService webAuthnService;
    private final PrivilegedMfaAttestationService attestationService;
    private final SessionSecurityService sessionSecurityService;
    private final AuditService auditService;

    /**
     * Redeems a code for the passkey that signs the assertion. A refused code fails with
     * {@link MfaAttestationRefusedException} after the refusal has been counted and audited; a
     * failed assertion fails as the ceremony's own error and counts nothing. Success stamps the
     * session's step-up with the attested passkey, after the redemption committed.
     *
     * @param request the current request, whose session is stamped on success
     * @param auth the authenticated principal
     * @param user the authenticated account
     * @param options the assertion options issued for this ceremony
     * @param assertion the client's assertion response
     * @param rawCode the code as entered
     * @return the organization the passkey now covers
     * @throws IllegalStateException when called inside a transaction
     */
    public MfaAttestationRedemptionDto redeem(
            HttpServletRequest request,
            Authentication auth,
            User user,
            PublicKeyCredentialRequestOptions options,
            PublicKeyCredential<AuthenticatorAssertionResponse> assertion,
            String rawCode) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                "Attestation redemption must not run inside a caller's transaction");
        }
        VerifiedPasskey signed = webAuthnService.finishStepUp(auth, options, assertion);
        Integer expectedSessionEpoch = sessionSecurityService.sessionEpoch(request.getSession(false));
        MfaAttestationRedemptionDto redeemed;
        try {
            redeemed = attestationService.redeem(
                user.getId(), expectedSessionEpoch, signed.credentialRowId(), rawCode);
        } catch (PrivilegedMfaAttestationService.AttestationRefusal refusal) {
            attestationService.burn(user.getId());
            auditService.recordStrictFailureIndependentScoped(
                "auth.mfa.attestation.denied",
                "user",
                user.getId(),
                null,
                null,
                user.getDisplayName(),
                "Refused a privileged MFA attestation code",
                refusal.reason());
            throw new MfaAttestationRefusedException();
        }
        sessionSecurityService.markStepUp(request, user.getId(), signed.credentialRowId());
        return redeemed;
    }
}
