package ooo.klae.connex.backend.session;

import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.session.SingleIndexResolver;
import org.springframework.stereotype.Component;

import ooo.klae.connex.backend.beans.User;

/**
 * Files every saved session under its account id instead of its login username.
 *
 * <p>Replaces Spring Session's default {@code PrincipalNameIndexResolver}, which indexes by
 * {@code Authentication.getName()} — the username. Because a username is self-service mutable, a
 * session outliving a rename stayed filed under a name no later lookup could reconstruct, and
 * password reset and MFA recovery silently revoked nothing for that account.
 *
 * <p>Spring Session re-derives this value on <em>every</em> save
 * ({@code JdbcIndexedSessionRepository.UPDATE_SESSION_QUERY} writes {@code PRINCIPAL_NAME}), so a
 * session saved by an earlier build is re-filed under its account id the next time it is written.
 * That self-healing is not sufficient on its own — a session that stays idle across the upgrade is
 * still filed under its old username at the moment a revocation enumerates — which is why the
 * cutover migration clears the authenticated rows once.
 *
 * <p>A session whose persisted principal is not a Connex {@link User} is left unindexed. Filing it
 * under an identity provider's subject identifier would put it under a key nothing looks up, which
 * reads as "revoked" while the session is still live; leaving it null at least makes the gap
 * visible.
 */
@Component
public class AccountSessionIndexResolver extends SingleIndexResolver<Session> {

    public AccountSessionIndexResolver() {
        super(FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME);
    }

    @Override
    public String resolveIndexValueFor(Session session) {
        Object context = session.getAttribute(
            HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        if (context instanceof SecurityContext securityContext
                && securityContext.getAuthentication() != null
                && securityContext.getAuthentication().getPrincipal() instanceof User account) {
            return new AccountSessionIndex(account.getId()).getName();
        }
        return null;
    }
}
