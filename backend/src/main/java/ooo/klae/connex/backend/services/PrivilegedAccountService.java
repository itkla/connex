package ooo.klae.connex.backend.services;

import java.util.List;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.beans.UnenrolledPrivilegedAccount;
import ooo.klae.connex.backend.beans.UnenrolledPrivilegedAccountCounts;
import ooo.klae.connex.backend.mappers.UserMapper;

/**
 * Resolves account-wide administrative authority from current control-plane membership state.
 */
@Service
@RequiredArgsConstructor
public class PrivilegedAccountService {
    private final UserMapper userMapper;

    /**
     * Returns whether the account currently holds an organization role, a built-in workspace
     * administrator role, or a custom role with administrative permissions.
     *
     * @param userId account to evaluate
     * @return current account-wide privilege state
     */
    public boolean isPrivileged(int userId) {
        return userMapper.isPrivilegedAccount(userId);
    }

    /**
     * Lists accounts that confinement will hold at enrollment: privileged under
     * {@link #isPrivileged(int)} and holding no passkey. Read-only; takes no locks.
     *
     * @param limit maximum number of accounts to return, lowest id first
     * @return the capped sample, carrying only ids and self-service prerequisites
     */
    public List<UnenrolledPrivilegedAccount> unenrolledPrivilegedAccounts(int limit) {
        if (limit <= 0) {
            return List.of();
        }
        return userMapper.listUnenrolledPrivilegedAccounts(limit);
    }

    /**
     * Counts the whole population {@link #unenrolledPrivilegedAccounts(int)} samples.
     *
     * @return uncapped counts
     * @throws IllegalStateException when the aggregate yields no row
     */
    public UnenrolledPrivilegedAccountCounts unenrolledPrivilegedAccountCounts() {
        UnenrolledPrivilegedAccountCounts counts = userMapper.countUnenrolledPrivilegedAccounts();
        if (counts == null) {
            throw new IllegalStateException("Unenrolled privileged account inventory is unavailable");
        }
        return counts;
    }
}
