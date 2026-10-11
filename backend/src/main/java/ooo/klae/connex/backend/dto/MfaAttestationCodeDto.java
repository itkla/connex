package ooo.klae.connex.backend.dto;

/**
 * A freshly issued privileged MFA attestation code, shown to its grantor once (#1534).
 *
 * @param code the code in four hyphenated groups, for out-of-band delivery to the grantee
 * @param expiresAt when the code stops being redeemable, as an ISO-8601 UTC instant
 */
public record MfaAttestationCodeDto(String code, String expiresAt) {
}
