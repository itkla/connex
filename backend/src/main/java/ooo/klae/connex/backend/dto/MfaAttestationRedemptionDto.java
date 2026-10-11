package ooo.klae.connex.backend.dto;

/**
 * The outcome of a redeemed privileged MFA attestation code (#1534).
 *
 * @param orgId the organization whose privileged MFA the signing passkey now satisfies
 */
public record MfaAttestationRedemptionDto(int orgId) {
}
