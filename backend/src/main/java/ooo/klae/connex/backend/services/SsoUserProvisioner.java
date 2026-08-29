package ooo.klae.connex.backend.services;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.mappers.UserMapper;

/**
 * Provisions a passwordless Connex account for a federated login (enterprise SSO or consumer
 * social login) whose email has no existing account. The username is derived from the email's
 * local part, sanitized and de-duplicated against a numeric suffix; the account carries no
 * password (it authenticates only through its IdP / social provider).
 */
@Component
@RequiredArgsConstructor
public class SsoUserProvisioner {

    private static final String FALLBACK_USERNAME = "user";
    private static final int MAX_USERNAME_LENGTH = 64;

    private final UserMapper userMapper;

    /**
     * Creates and persists a new passwordless user for a federated email.
     * @param email the verified IdP/provider email
     * @param displayName the asserted display name, falling back to the email when blank
     * @param emailVerified whether the email is verified
     * @return the persisted user
     */
    public User provision(String email, String displayName, boolean emailVerified) {
        User user = new User();
        user.setUsername(deriveUsername(email));
        user.setDisplayName(displayName != null && !displayName.isBlank() ? displayName.trim() : email);
        user.setEmail(email);
        user.setEmailVerified(emailVerified);
        user.setTimezone("UTC");
        user.setPasswordHash(null);
        userMapper.insert(user);
        return userMapper.getUserById(user.getId());
    }

    private String deriveUsername(String email) {
        int at = email.indexOf('@');
        String local = at > 0 ? email.substring(0, at) : email;
        String base = local.toLowerCase().replaceAll("[^a-z0-9._-]", "");
        if (base.isEmpty()) {
            base = FALLBACK_USERNAME;
        }
        if (base.length() > MAX_USERNAME_LENGTH) {
            base = base.substring(0, MAX_USERNAME_LENGTH);
        }
        String candidate = base;
        int suffix = 1;
        while (userMapper.getUserByUsername(candidate) != null) {
            String tag = Integer.toString(suffix++);
            int keep = MAX_USERNAME_LENGTH - tag.length();
            candidate = (base.length() > keep ? base.substring(0, keep) : base) + tag;
        }
        return candidate;
    }
}
