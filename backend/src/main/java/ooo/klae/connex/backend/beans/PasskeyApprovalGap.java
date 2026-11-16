package ooo.klae.connex.backend.beans;

/**
 * One organization in which one of an account's passkeys still needs approval (#1534).
 *
 * @param credentialRowId the passkey's {@code webauthn_credential.id}
 * @param credentialId the passkey's WebAuthn credential id, as the browser knows it
 * @param orgId the organization where the account is privileged and the passkey is not covered
 * @param orgName that organization's name
 */
public record PasskeyApprovalGap(int credentialRowId, byte[] credentialId, int orgId, String orgName) {
}
