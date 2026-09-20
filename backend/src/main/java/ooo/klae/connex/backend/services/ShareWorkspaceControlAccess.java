package ooo.klae.connex.backend.services;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.dto.ShareDto;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.tenant.TenantContext;
import ooo.klae.connex.backend.tenant.TenantWorkScope;

/**
 * Loads the owning organization's workspace snapshot for record sharing. Share rows are tenant
 * data; the organization a workspace belongs to and the name it displays are control data, so the
 * lookup suspends any routed tenant transaction, reads on the default catalog, and restores the
 * tenant transaction afterwards (#811).
 *
 * <p>The snapshot is taken once per operation and is immutable, so the same workspace set backs
 * the grant ceiling, the listing filter, the workspace-name hydration and the name ordering. A
 * single statement produces it, so no read-only transaction is needed to make it consistent.
 */
@Component
@RequiredArgsConstructor
public class ShareWorkspaceControlAccess {

    private final WorkspaceMapper workspaceMapper;
    private final TenantWorkScope tenantWorkScope;
    private final TenantContext tenantContext;
    private final PlatformTransactionManager transactionManager;

    /**
     * Loads every workspace in the anchor workspace's organization.
     *
     * @param workspaceId workspace whose organization bounds the snapshot
     * @return the organization's workspaces, empty when the anchor workspace does not exist
     */
    public OrganizationWorkspaces getForWorkspace(int workspaceId) {
        return execute(() -> snapshot(workspaceId));
    }

    private OrganizationWorkspaces snapshot(int workspaceId) {
        LinkedHashMap<Integer, String> orderedNamesById = new LinkedHashMap<>();
        for (Workspace row : workspaceMapper.findOrganizationWorkspacesForShare(workspaceId)) {
            orderedNamesById.putIfAbsent(row.getId(), row.getName());
        }
        return new OrganizationWorkspaces(orderedNamesById);
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

    /**
     * One organization's workspaces. {@code workspaceIds} keeps the database's name then id
     * order, which is the deterministic share-listing order the removed {@code ORDER BY w.name}
     * used to produce; {@code namesById} is an unordered lookup.
     */
    public static final class OrganizationWorkspaces {

        private final Map<Integer, String> namesById;
        private final List<Integer> workspaceIds;
        private final String workspaceIdsJson;

        OrganizationWorkspaces(LinkedHashMap<Integer, String> orderedNamesById) {
            this.workspaceIds = List.copyOf(orderedNamesById.keySet());
            this.namesById = Map.copyOf(orderedNamesById);
            this.workspaceIdsJson = this.workspaceIds.stream()
                .map(String::valueOf)
                .collect(Collectors.joining(",", "[", "]"));
        }

        /**
         * The allowlist the tenant grant statements enforce their organization ceiling against.
         *
         * @return a JSON array of this organization's workspace ids
         */
        public String workspaceIdsJson() {
            return workspaceIdsJson;
        }

        /**
         * Names and orders tenant share rows from this snapshot. A share whose target workspace
         * the snapshot does not contain is omitted rather than failing the listing, which is what
         * the removed {@code JOIN workspace} did with a share whose target workspace row was gone.
         *
         * @param shares tenant share rows, without workspace names
         * @return the same rows, named and ordered by workspace name
         */
        public List<ShareDto> hydrate(List<ShareDto> shares) {
            List<ShareDto> hydrated = new ArrayList<>(shares.size());
            for (ShareDto share : shares) {
                String workspaceName = namesById.get(share.getWorkspaceId());
                if (workspaceName == null) {
                    continue;
                }
                share.setWorkspaceName(workspaceName);
                hydrated.add(share);
            }
            hydrated.sort(Comparator.comparingInt(
                (ShareDto share) -> workspaceIds.indexOf(share.getWorkspaceId())));
            return hydrated;
        }
    }
}
