package ooo.klae.connex.backend.services;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.function.Supplier;

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
 *
 * <p><strong>Why this is not {@link OrganizationWorkspaceScopeControlAccess}.</strong> That
 * component answers the same question for the read-path ceilings (IdentityMapper,
 * PersonEdgeMapper, AiAssistantIdentifierMapper) and for tenant diagnostics, and it answers it
 * from {@code WorkspaceMapper.findByOrgId}, which admits only workspaces whose own and whose
 * organization's {@code lifecycle_state} is {@code active}. Sharing must not apply that filter:
 * the {@code JOIN workspace} it replaces applied none, so filtering here would silently stop
 * listing — and stop granting to — a target workspace whose organization is winding down. Sharing
 * also needs workspace names, which no read-path ceiling consumes. Merging the two would either
 * change what the read-path ceilings admit or put two differently-filtered workspace sets behind
 * one type, where a future read-path caller could pick the wider one and widen a ceiling. The two
 * components therefore stay separate and each carries the same defensive checks: the anchor
 * workspace must exist and appear in its own organization, and the resolved tenant context must
 * agree with the scope that was loaded.
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
     * @return the organization's workspaces, always including the anchor
     * @throws IllegalStateException when the anchor workspace does not exist, when it is absent
     *     from its own organization, or when the snapshot disagrees with the resolved tenant
     *     context
     */
    public OrganizationWorkspaces getForWorkspace(int workspaceId) {
        OrganizationWorkspaces organizationWorkspaces = execute(() -> snapshot(workspaceId));
        if (tenantContext.isResolved()
                && (tenantContext.getWorkspaceId().intValue() != workspaceId
                    || tenantContext.getOrgId().intValue() != organizationWorkspaces.orgId())) {
            throw new IllegalStateException(
                "Workspace scope does not match the resolved tenant context");
        }
        return organizationWorkspaces;
    }

    private OrganizationWorkspaces snapshot(int workspaceId) {
        LinkedHashMap<Integer, String> orderedNamesById = new LinkedHashMap<>();
        Integer orgId = null;
        for (Workspace row : workspaceMapper.findOrganizationWorkspacesForShare(workspaceId)) {
            orderedNamesById.putIfAbsent(row.getId(), row.getName());
            orgId = row.getOrgId();
        }
        if (orgId == null) {
            throw new IllegalStateException("Workspace " + workspaceId + " does not exist");
        }
        if (!orderedNamesById.containsKey(workspaceId)) {
            throw new IllegalStateException(
                "Workspace " + workspaceId + " is missing from organization " + orgId);
        }
        return new OrganizationWorkspaces(orgId, orderedNamesById);
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
     * One organization's workspaces. {@code ranksById} records the database's name then id
     * order, which is the deterministic share-listing order the removed {@code ORDER BY w.name}
     * used to produce, as one rank per workspace; {@code namesById} is an unordered lookup.
     * Both are built once per snapshot so hydrating a listing never rescans the organization.
     */
    public static final class OrganizationWorkspaces {

        private final int orgId;
        private final Map<Integer, String> namesById;
        private final Map<Integer, Integer> ranksById;
        private final String workspaceIdsJson;

        OrganizationWorkspaces(int orgId, LinkedHashMap<Integer, String> orderedNamesById) {
            this.orgId = orgId;
            LinkedHashMap<Integer, Integer> ranks = new LinkedHashMap<>();
            StringJoiner ids = new StringJoiner(",", "[", "]");
            for (Integer workspaceId : orderedNamesById.keySet()) {
                ranks.put(workspaceId, ranks.size());
                ids.add(String.valueOf(workspaceId));
            }
            this.namesById = Map.copyOf(orderedNamesById);
            this.ranksById = Map.copyOf(ranks);
            this.workspaceIdsJson = ids.toString();
        }

        /**
         * The organization the snapshot was taken for, used to cross-check the resolved tenant
         * context.
         *
         * @return the owning organization id
         */
        int orgId() {
            return orgId;
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
         * the snapshot does not contain is omitted rather than failing the listing. Two kinds of
         * row are dropped: one whose target workspace row no longer exists, which the removed
         * {@code JOIN workspace} also dropped, and one whose target workspace belongs to another
         * organization, which that join listed under the foreign workspace's name. Only the
         * grant path ever refused the second kind, so such a row can predate the ceiling or be
         * written around it; it is now invisible, and therefore not revocable, through the UI.
         *
         * <p>Ordering is the snapshot's own, reproduced from the precomputed ranks rather than
         * recomputed in Java, so the response keeps the database collation the removed
         * {@code ORDER BY w.name} ordered by and costs one map lookup per comparison.
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
                (ShareDto share) -> ranksById.get(share.getWorkspaceId())));
            return hydrated;
        }
    }
}
