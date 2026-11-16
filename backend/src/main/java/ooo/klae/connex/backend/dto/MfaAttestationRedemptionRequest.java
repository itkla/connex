package ooo.klae.connex.backend.dto;

import org.springframework.security.web.webauthn.api.AuthenticatorAssertionResponse;
import org.springframework.security.web.webauthn.api.PublicKeyCredential;

/**
 * A privileged MFA attestation code entered by its grantee, with the passkey assertion that signs
 * the redemption (#1534).
 *
 * @param code the code as entered
 * @param credential the assertion; the passkey that signs it is the one attested
 */
public record MfaAttestationRedemptionRequest(
        String code, PublicKeyCredential<AuthenticatorAssertionResponse> credential) {
}
