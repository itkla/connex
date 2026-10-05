package ooo.klae.connex.backend.webauthn;

import org.springframework.security.web.webauthn.api.CredentialRecord;

/**
 * A passkey registration that committed.
 *
 * @param record the stored credential
 * @param credentialRowId the new credential's {@code webauthn_credential.id}
 */
public record RegisteredPasskey(CredentialRecord record, int credentialRowId) {
}
