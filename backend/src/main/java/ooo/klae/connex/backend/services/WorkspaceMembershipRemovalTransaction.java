package ooo.klae.connex.backend.services;

import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Supplier;

import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.tenant.TenantContext;
import ooo.klae.connex.backend.tenant.TenantWorkScope;

/** Opens membership-removal transactions in the authorized path workspace's tenant scope. */
@Component
@RequiredArgsConstructor
public class WorkspaceMembershipRemovalTransaction {
    private final TenantWorkScope tenantWorkScope;
    private final TenantContext tenantContext;
    private final TransactionTemplate transactionTemplate;

    /**
     * Authorizes against the path workspace before resolving its placement, then runs one complete
     * membership-removal unit under that workspace's tenant identity and routed catalog.
     *
     * @param <T> the removal result type
     * @param workspaceId the workspace losing the member
     * @param actorId the user performing the membership-ending operation
     * @param authorization preliminary path-workspace authorization returning the actor's role
     * @param work the transactional removal unit receiving the target workspace and organization
     * @return the non-null removal result
     */
    public <T> T execute(
            int workspaceId,
            int actorId,
            Supplier<String> authorization,
            BiFunction<Integer, Integer, T> work) {
        String actorRole = Objects.requireNonNull(
            authorization.get(), "workspace-membership removal actor role");
        T result = tenantWorkScope.withWorkspacePlacement(
            workspaceId,
            (orgId, catalog) -> withTenantContext(
                workspaceId,
                orgId,
                actorId,
                actorRole,
                catalog,
                () -> transactionTemplate.execute(
                    status -> work.apply(workspaceId, orgId))));
        return Objects.requireNonNull(result, "workspace-membership removal transaction result");
    }

    private <T> T withTenantContext(
            int workspaceId,
            int orgId,
            int actorId,
            String actorRole,
            String catalog,
            Supplier<T> work) {
        boolean hadTenant = tenantContext.isResolved();
        Integer previousWorkspace = hadTenant ? tenantContext.getWorkspaceId() : null;
        Integer previousOrg = hadTenant ? tenantContext.getOrgId() : null;
        Integer previousUser = hadTenant ? tenantContext.getUserId() : null;
        String previousRole = hadTenant ? tenantContext.getRole() : null;
        String previousCatalog = hadTenant ? tenantContext.getScopeCatalog() : null;
        tenantContext.set(workspaceId, orgId, actorId, actorRole, catalog);
        try {
            return work.get();
        } finally {
            if (hadTenant) {
                tenantContext.set(
                    Objects.requireNonNull(previousWorkspace, "previousWorkspace"),
                    Objects.requireNonNull(previousOrg, "previousOrg"),
                    Objects.requireNonNull(previousUser, "previousUser"),
                    Objects.requireNonNull(previousRole, "previousRole"),
                    previousCatalog);
            } else {
                tenantContext.clear();
            }
        }
    }
}
