package ooo.klae.connex.backend.session;

/**
 * The passkey that signed a session's current WebAuthn step-up, and when (#1534).
 *
 * @param credentialRowId the signing credential's {@code webauthn_credential.id}
 * @param stampedAtMillis the step-up stamp's epoch-millisecond time
 */
public record StepUpProof(int credentialRowId, long stampedAtMillis) {
}
