package ooo.klae.connex.backend.beans;

/**
 * The facts an account's privileged-MFA standing is derived from (#1534), read in one statement.
 *
 * @param privileged whether the account currently holds privilege in any organization
 * @param hasPasskey whether the account holds any passkey
 * @param hasSufficientPasskey whether one of its passkeys carries operator assurance or is covered
 *     in every organization where the account is privileged
 */
public record PrivilegedMfaStandingRow(boolean privileged, boolean hasPasskey, boolean hasSufficientPasskey) {
}
