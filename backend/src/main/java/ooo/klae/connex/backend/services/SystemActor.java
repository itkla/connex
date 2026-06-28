package ooo.klae.connex.backend.services;

import java.util.Set;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.tenant.Permission;

/**
 * Resolves the global system automation user — the principal a {@code system}-mode rule acts as —
 * and the fixed, narrow set of action permissions it is granted. The system user belongs to no
 * workspace and cannot authenticate; the rule engine always scopes its actions to the firing rule's
 * own workspace, so this grant never widens a real member's reach. The catalog is intentionally
 * limited to exactly what the v1 automation actions need.
 */
@Component
@RequiredArgsConstructor
public class SystemActor {

    static final String USERNAME = "__connex_system__";

    private static final Set<Permission> PERMISSIONS = Set.of(
        Permission.TASK_CREATE, Permission.ACTIVITY_CREATE,
        Permission.COMPANY_UPDATE, Permission.PERSON_UPDATE, Permission.DEAL_UPDATE);

    private final UserMapper userMapper;
    private volatile User cached;

    /** The system user principal; throws if it was never provisioned (the V20 seed is missing). */
    public User user() {
        User user = resolve();
        if (user == null) {
            throw new IllegalStateException("System automation user is not provisioned");
        }
        return user;
    }

    /** Whether the given user id is the system automation user. Safe to call before provisioning. */
    public boolean is(int userId) {
        User user = resolve();
        return user != null && userId == user.getId();
    }

    /** The fixed permission set granted to the system actor (only the v1 automation actions). */
    public Set<Permission> permissions() {
        return PERMISSIONS;
    }

    private User resolve() {
        User user = cached;
        if (user == null) {
            user = userMapper.getUserByUsername(USERNAME);
            if (user != null) {
                cached = user;
            }
        }
        return user;
    }
}
