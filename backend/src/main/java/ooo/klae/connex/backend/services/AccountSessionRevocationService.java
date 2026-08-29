package ooo.klae.connex.backend.services;

import org.springframework.security.core.session.SessionInformation;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.session.AccountSessionIndex;

/**
 * Single owner of "expire the sessions this account holds", across the shared session store.
 *
 * <p>The enumeration key is the account id, never the username.
 * {@code AccountSessionIndexResolver} writes it into {@code SPRING_SESSION.PRINCIPAL_NAME} on every
 * save, and Spring Session re-derives that column on every save from the session's own serialized
 * security context — so the key cannot drift from the account, and a login identifier that changes
 * cannot strand a session under a name nothing looks up again. Taking an {@code int} rather than a
 * {@code User} is part of that: a caller cannot pass a stale-or-fresh principal and get a silently
 * different answer.
 *
 * <p><strong>This does not expire every session an account holds.</strong> Two windows survive and
 * are tracked separately:
 *
 * <ul>
 *   <li>Enumerate-and-expire fails open. A login whose session row is written after this method has
 *       already enumerated is not seen, and is left live. The durable key means every later
 *       revocation can still find it, but this one misses it.</li>
 *   <li>A session whose persisted principal is not a Connex user — the SSO handler's error branches
 *       produce these — is unindexed and reachable by no key at all.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class AccountSessionRevocationService {
    private final SessionRegistry sessionRegistry;

    /**
     * Expires every session the account holds, forcing re-authentication everywhere.
     *
     * @param userId the immutable account id the sessions are filed under
     */
    public void expireAll(int userId) {
        expireAllExcept(userId, null);
    }

    /**
     * Expires every session the account holds apart from one that must survive.
     *
     * @param userId the immutable account id the sessions are filed under
     * @param retainedSessionId the session to leave alive, or null to expire all of them
     */
    public void expireAllExcept(int userId, String retainedSessionId) {
        for (SessionInformation session
                : sessionRegistry.getAllSessions(new AccountSessionIndex(userId), false)) {
            if (!session.getSessionId().equals(retainedSessionId)) {
                session.expireNow();
            }
        }
    }
}
