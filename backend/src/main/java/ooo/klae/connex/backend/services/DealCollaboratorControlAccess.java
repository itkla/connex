package ooo.klae.connex.backend.services;

import java.util.ArrayList;
import java.util.Arrays;
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

/**
 * Hydrates deal-collaborator profiles from the control catalog. Collaborator ids are tenant data;
 * the matching account profiles are control data, so the lookup suspends any routed tenant
 * transaction, reads on the default catalog, and restores the tenant transaction afterwards.
 */
@Component
@RequiredArgsConstructor
public class DealCollaboratorControlAccess {
    static final int PROFILE_BATCH_SIZE = 500;

    private static final Comparator<UserProfileHydrationRow> DISPLAY_ORDER = Comparator
        .comparing(UserProfileHydrationRow::getDisplaySortKey,
            Comparator.nullsLast(Arrays::compareUnsigned))
        .thenComparing(UserProfileHydrationRow::getId, Comparator.nullsLast(Comparator.naturalOrder()));

    private final UserMapper userMapper;
    private final TenantWorkScope tenantWorkScope;
    private final TenantContext tenantContext;
    private final PlatformTransactionManager transactionManager;

    /**
     * Loads display-safe profiles for the collaborator ids recorded on a deal. Ids without an
     * account, or whose account is not an active member of the workspace, are omitted.
     *
     * @param workspaceId workspace whose active members may be returned
     * @param userIds collaborator user ids read from tenant data
     * @return profiles ordered by display name in database collation order, then by id
     */
    public List<UserDto> getProfiles(int workspaceId, List<Integer> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return List.of();
        }
        return execute(() -> loadProfiles(workspaceId, userIds));
    }

    private List<UserDto> loadProfiles(int workspaceId, List<Integer> userIds) {
        List<UserProfileHydrationRow> rows = new ArrayList<>();
        for (int from = 0; from < userIds.size(); from += PROFILE_BATCH_SIZE) {
            int to = Math.min(userIds.size(), from + PROFILE_BATCH_SIZE);
            rows.addAll(userMapper.getActiveWorkspaceMemberProfilesByIds(workspaceId, userIds.subList(from, to)));
        }
        rows.sort(DISPLAY_ORDER);
        return rows.stream().map(UserProfileHydrationRow::getProfile).toList();
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
