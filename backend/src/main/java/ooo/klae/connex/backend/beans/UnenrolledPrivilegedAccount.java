package ooo.klae.connex.backend.beans;

/**
 * One account that currently holds administrative privilege but has no enrolled passkey.
 *
 * <p>Carries only whether each self-service enrollment prerequisite exists, never the address or
 * the credential itself, so the row is safe to count and to identify by id in operator output.
 *
 * @param id the account id
 * @param hasPassword whether the account has a password credential
 * @param hasEmail whether the account has a non-blank email address
 */
public record UnenrolledPrivilegedAccount(int id, boolean hasPassword, boolean hasEmail) {
}
