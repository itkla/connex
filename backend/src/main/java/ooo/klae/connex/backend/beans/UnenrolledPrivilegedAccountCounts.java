package ooo.klae.connex.backend.beans;

/**
 * Population-wide counts of privileged accounts that have no enrolled passkey.
 *
 * @param total every unenrolled privileged account
 * @param passwordBacked the subset holding a password credential, which the emailed first-passkey
 *     confirmation covers
 * @param passwordBackedWithoutEmail the password-backed subset with no address to confirm through
 */
public record UnenrolledPrivilegedAccountCounts(
        long total,
        long passwordBacked,
        long passwordBackedWithoutEmail) {
}
