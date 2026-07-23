package ooo.klae.connex.backend.services;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Supplier;

import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.dto.UserDto;
import ooo.klae.connex.backend.dto.UserProfileHydrationRow;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.tenant.TenantContext;
import ooo.klae.connex.backend.tenant.TenantWorkScope;

/** Routes deal-collaborator profile hydration to the control catalog. */
@Component
@RequiredArgsConstructor
public class DealCollaboratorControlAccess {
    private static final int PROFILE_BATCH_SIZE = 500;

    private final UserMapper userMapper;
    private final TenantWorkScope tenantWorkScope;
    private final TenantContext tenantContext;
    private final PlatformTransactionManager transactionManager;

    /**
     * Loads API-safe profiles for collaborator ids in display order.
     *
     * @param workspaceId workspace whose members may be returned
     * @param userIds collaborator user ids from tenant data
     * @return existing control-plane profiles ordered by display name and id
     */
    public List<UserDto> getProfiles(int workspaceId, List<Integer> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return List.of();
        }
        return execute(() -> loadProfiles(workspaceId, userIds));
    }

    private List<UserDto> loadProfiles(int workspaceId, List<Integer> userIds) {
        List<UserProfileHydrationRow> profiles = new ArrayList<>();
        for (int offset = 0; offset < userIds.size(); offset += PROFILE_BATCH_SIZE) {
            profiles.addAll(userMapper.getWorkspaceProfileHydrationRowsByIds(workspaceId,
                userIds.subList(offset, Math.min(userIds.size(), offset + PROFILE_BATCH_SIZE))));
        }
        profiles.sort(Comparator
            .comparing(UserProfileHydrationRow::getDisplaySortKey,
                Comparator.nullsLast(DealCollaboratorControlAccess::compareSortKeys))
            .thenComparing(UserProfileHydrationRow::getId,
                Comparator.nullsLast(Comparator.naturalOrder())));
        return profiles.stream().map(UserProfileHydrationRow::getProfile).toList();
    }

    private static int compareSortKeys(byte[] left, byte[] right) {
        int sharedLength = Math.min(left.length, right.length);
        for (int index = 0; index < sharedLength; index++) {
            int compared = Integer.compare(Byte.toUnsignedInt(left[index]), Byte.toUnsignedInt(right[index]));
            if (compared != 0) {
                return compared;
            }
        }
        return Integer.compare(left.length, right.length);
    }

    private <T> T execute(Supplier<T> work) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || tenantContext.getCatalog() == null) {
            return tenantWorkScope.unrouted(work);
        }
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
        return transaction.execute(status -> tenantWorkScope.unrouted(work));
    }
}
