package ooo.klae.connex.backend.webauthn;

import ooo.klae.connex.backend.beans.User;

/**
 * A verified WebAuthn assertion: the account it authenticated and the passkey that signed it.
 *
 * @param user the authenticated account
 * @param credentialRowId the signing credential's {@code webauthn_credential.id}
 */
public record VerifiedPasskey(User user, int credentialRowId) {
}
