package ooo.klae.connex.backend.services;

import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.mappers.UserMapper;

/** Reads committed reservation flags without taking user locks after workspace locks. */
@Component
@RequiredArgsConstructor
public class AccountDeletionReservationRead {
    private final UserMapper userMapper;

    /**
     * The caller must hold the workspace exclusively and derive candidates from locked owner
     * memberships. That workspace lock fences new reservations and renewals through commit;
     * expiry and release only restore availability. A separate read avoids inheriting the
     * caller's repeatable-read snapshot.
     */
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public Set<Integer> reservedUserIds(List<Integer> userIds) {
        return userIds.isEmpty() ? Set.of() : Set.copyOf(userMapper.findAccountDeletionReservedIds(userIds));
    }
}
