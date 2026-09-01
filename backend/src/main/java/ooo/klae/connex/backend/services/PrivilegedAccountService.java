package ooo.klae.connex.backend.services;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
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
     * Returns whether the account holds privilege in a scope containing at least one other
     * principal, distinguishing an administrator of other people from an account that only
     * administers itself.
     *
     * @param userId account to evaluate
     * @return whether the account currently administers another principal
     */
    public boolean hasPrivilegeOverOtherAccounts(int userId) {
        return userMapper.holdsPrivilegeOverOtherAccounts(userId);
    }
}
