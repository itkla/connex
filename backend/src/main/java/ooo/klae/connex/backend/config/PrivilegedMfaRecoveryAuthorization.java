package ooo.klae.connex.backend.config;

/**
 * A recovery token accepted for one account, before it is spent.
 *
 * @param operator the configured actor who issued the token
 * @param redemptionKey hex SHA-256 of the configured token digest; identifies the token in the
 *     single-use ledger without storing the raw token or the configured digest
 */
public record PrivilegedMfaRecoveryAuthorization(String operator, String redemptionKey) {
}
