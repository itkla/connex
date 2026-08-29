package ooo.klae.connex.backend.support;

import java.util.UUID;

import org.springframework.mock.web.MockHttpSession;

import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.services.SessionSecurityService;

/**
 * Builds the account and session state a real authenticated request carries.
 *
 * <p>{@code SessionEpochFilter} refuses an authenticated request whose session carries no session
 * epoch, or whose account has none, because in production every authenticated session was stamped
 * by the login ceremony against a row that exists. A test that invents a principal satisfies
 * neither, so its requests are de-authenticated before reaching whatever it meant to assert.
 *
 * <p>Use this rather than a bare {@code new User()}: it inserts the account and reads it back
 * through the credential-bearing mapper read, so the principal has the shape login produces.
 */
public final class AuthenticatedSessions {

    private AuthenticatedSessions() {
    }

    /**
     * Inserts an account and returns it as login would load it.
     *
     * @param userMapper the mapper to insert through
     * @param prefix a short label distinguishing this fixture's rows
     * @return the persisted account, read back with its session epoch
     */
    public static User account(UserMapper userMapper, String prefix) {
        String unique = UUID.randomUUID().toString().substring(0, 8);
        User user = new User();
        user.setUsername(prefix + "-" + unique);
        user.setDisplayName(prefix + " " + unique);
        user.setEmail(prefix + "-" + unique + "@example.com");
        user.setTimezone("UTC");
        userMapper.insert(user);
        return userMapper.getUserById(user.getId());
    }

    /**
     * A session stamped with the account's current epoch, as the login ceremony leaves it.
     *
     * @param account an account loaded by {@link #account}
     * @return the session to attach to a request
     */
    public static MockHttpSession stampedSession(User account) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(SessionSecurityService.SESSION_EPOCH_ATTR, account.getSessionEpoch());
        return session;
    }
}
