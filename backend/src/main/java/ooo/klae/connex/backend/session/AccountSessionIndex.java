package ooo.klae.connex.backend.session;

import org.springframework.security.core.AuthenticatedPrincipal;

/**
 * The immutable value every session of an account is filed under in
 * {@code SPRING_SESSION.PRINCIPAL_NAME}.
 *
 * <p>This is the one place the key format exists. {@link AccountSessionIndexResolver} writes it when
 * Spring Session saves a session; {@code AccountSessionRevocationService} reads it back to enumerate
 * an account's sessions. The account id is immutable, the username is not, which is the whole point:
 * a login identifier that changes must not be able to strand a live session under a name nothing
 * looks up again.
 *
 * <p>Implementing {@link AuthenticatedPrincipal} rather than passing a bare string is deliberate.
 * {@code SpringSessionBackedSessionRegistry} wraps whatever it is handed in an
 * {@code AbstractAuthenticationToken} and calls {@code getName()}, which consults
 * {@code AuthenticatedPrincipal} explicitly and falls through to {@code toString()} only for types it
 * does not recognise. Relying on a record's generated {@code toString()} would make the index format
 * a function of the compiler.
 *
 * @param userId the immutable {@code app_user.id} the session belongs to
 */
public record AccountSessionIndex(int userId) implements AuthenticatedPrincipal {
    private static final String PREFIX = "uid:";

    @Override
    public String getName() {
        return PREFIX + userId;
    }
}
