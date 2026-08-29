package ooo.klae.connex.backend.services;

import org.springframework.security.core.session.SessionInformation;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.beans.User;

/**
 * Single owner of "expire the sessions this account holds", across the shared session store.
 *
 * <p><strong>The principal must carry the name the sessions were indexed under.</strong>
 * {@code SpringSessionBackedSessionRegistry} enumerates by principal <em>name</em>: it resolves the
 * {@code UserDetails} to its username and calls
 * {@code FindByIndexNameSessionRepository.findByPrincipalName}. Spring Session writes that index
 * when a session's security context is saved and never rewrites it, so a session established before
 * a rename stays filed under the old username. Passing a freshly reloaded account after a rename
 * therefore matches nothing and revokes nothing — silently, because an empty result is
 * indistinguishable from "no sessions".
 *
 * <p>{@link #expireAll} is called with the pre-update principal on the rename path for exactly that
 * reason, which keeps the index from ever going stale: every live session belongs to an account
 * whose current username is the one it was filed under.
 */
@Service
@RequiredArgsConstructor
public class AccountSessionRevocationService {
    private final SessionRegistry sessionRegistry;

    /**
     * Expires every session the account holds, forcing re-authentication everywhere.
     *
     * @param account the account whose username the sessions are indexed under
     */
    public void expireAll(User account) {
        expireAllExcept(account, null);
    }

    /**
     * Expires every session the account holds apart from one that must survive.
     *
     * @param account the account whose username the sessions are indexed under
     * @param retainedSessionId the session to leave alive, or null to expire all of them
     */
    public void expireAllExcept(User account, String retainedSessionId) {
        for (SessionInformation session : sessionRegistry.getAllSessions(account, false)) {
            if (!session.getSessionId().equals(retainedSessionId)) {
                session.expireNow();
            }
        }
    }
}
