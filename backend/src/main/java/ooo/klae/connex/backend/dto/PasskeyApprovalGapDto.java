package ooo.klae.connex.backend.dto;

/**
 * An organization in which one of the caller's own passkeys still needs approval (#1534).
 *
 * @param credentialId the passkey's base64url credential id, as {@link PasskeyDto} lists it
 * @param orgId the organization whose approval the passkey lacks
 * @param orgName that organization's name, so the account knows whom to ask
 */
public record PasskeyApprovalGapDto(String credentialId, int orgId, String orgName) {}
