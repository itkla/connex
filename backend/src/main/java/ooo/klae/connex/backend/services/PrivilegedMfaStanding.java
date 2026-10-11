package ooo.klae.connex.backend.services;

import ooo.klae.connex.backend.beans.PrivilegedMfaStandingRow;

/**
 * Where an account stands against privileged MFA (#1534).
 */
public enum PrivilegedMfaStanding {
    /** The account holds no privilege, so privileged MFA asks nothing of it. */
    UNPRIVILEGED,
    /** The account is privileged and holds a passkey that is sufficient for that privilege. */
    SATISFIED,
    /** The account is privileged and holds no passkey at all; it must enroll one. */
    ENROLLMENT_REQUIRED,
    /**
     * The account is privileged and holds passkeys, but none is approved in every organization
     * where it is privileged; a grantor's approval code is required.
     */
    ATTESTATION_REQUIRED;

    /**
     * Derives standing from the facts read in one statement.
     *
     * @param row the account's privilege and passkey facts
     * @return the standing those facts imply
     */
    public static PrivilegedMfaStanding of(PrivilegedMfaStandingRow row) {
        if (!row.privileged()) {
            return UNPRIVILEGED;
        }
        if (!row.hasPasskey()) {
            return ENROLLMENT_REQUIRED;
        }
        return row.hasSufficientPasskey() ? SATISFIED : ATTESTATION_REQUIRED;
    }

    /**
     * Whether this standing confines the account until it is resolved.
     *
     * @return true for {@link #ENROLLMENT_REQUIRED} and {@link #ATTESTATION_REQUIRED}
     */
    public boolean confines() {
        return this == ENROLLMENT_REQUIRED || this == ATTESTATION_REQUIRED;
    }
}
