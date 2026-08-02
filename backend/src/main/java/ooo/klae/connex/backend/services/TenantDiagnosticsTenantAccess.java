package ooo.klae.connex.backend.services;

import java.util.Objects;
import java.util.function.Supplier;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.services.OrganizationWorkspaceScopeControlOperations.WorkspaceScope;
import ooo.klae.connex.backend.tenant.TenantContext;
import ooo.klae.connex.backend.tenant.TenantWorkScope;

/**
 * Routes metadata-only tenant diagnostics through a control-derived workspace allowlist.
 */
@Component
@RequiredArgsConstructor
public class TenantDiagnosticsTenantAccess {
    private final TenantWorkScope tenantWorkScope;
    private final TenantContext tenantContext;

    /**
     * Runs diagnostics in the addressed workspace's tenant catalog.
     *
     * @param workspaceId addressed workspace
     * @param expectedOrgId organization proven by the control plane
     * @param actorId authorized actor
     * @param work tenant-plane diagnostics work
     * @return diagnostics work result
     */
    public <T> T inWorkspace(
            int workspaceId,
            int expectedOrgId,
            int actorId,
            Supplier<T> work) {
        return tenantWorkScope.withWorkspacePlacement(workspaceId, (resolvedOrgId, catalog) -> {
            if (resolvedOrgId != expectedOrgId) {
                throw new IllegalStateException("Workspace organization changed during diagnostics");
            }
            String role = tenantContext.isResolved()
                    ? Objects.requireNonNullElse(tenantContext.getRole(), "member")
                    : "member";
            return withTenantContext(
                    workspaceId, resolvedOrgId, actorId, role, catalog, work);
        });
    }

    /**
     * Runs organization diagnostics in the catalog shared by its complete workspace allowlist.
     *
     * @param scope control-derived organization workspace scope
     * @param actorId authorized organization administrator
     * @param work tenant-plane diagnostics work
     * @return diagnostics work result
     */
    public <T> T inOrganization(WorkspaceScope scope, int actorId, Supplier<T> work) {
        if (scope.workspaceIds().isEmpty()) {
            throw new IllegalArgumentException("An empty organization has no tenant catalog");
        }
        int anchorWorkspaceId = scope.workspaceIds().getFirst();
        return tenantWorkScope.withWorkspacePlacement(anchorWorkspaceId, (resolvedOrgId, catalog) -> {
            if (resolvedOrgId != scope.orgId()) {
                throw new IllegalStateException("Organization placement changed during diagnostics");
            }
            return withTenantContext(
                    anchorWorkspaceId, resolvedOrgId, actorId, "org_admin", catalog, work);
        });
    }

    private <T> T withTenantContext(
            int workspaceId,
            int orgId,
            int actorId,
            String role,
            String catalog,
            Supplier<T> work) {
        boolean hadTenant = tenantContext.isResolved();
        int previousWorkspace = hadTenant
                ? Objects.requireNonNull(tenantContext.getWorkspaceId(), "previousWorkspace")
                : 0;
        int previousOrg = hadTenant
                ? Objects.requireNonNull(tenantContext.getOrgId(), "previousOrg")
                : 0;
        int previousUser = hadTenant
                ? Objects.requireNonNull(tenantContext.getUserId(), "previousUser")
                : 0;
        String previousRole = hadTenant
                ? Objects.requireNonNull(tenantContext.getRole(), "previousRole")
                : null;
        String previousCatalog = hadTenant ? tenantContext.getScopeCatalog() : null;
        tenantContext.set(workspaceId, orgId, actorId, role, catalog);
        try {
            return work.get();
        } finally {
            if (hadTenant) {
                tenantContext.set(
                        previousWorkspace,
                        previousOrg,
                        previousUser,
                        previousRole,
                        previousCatalog);
            } else {
                tenantContext.clear();
            }
        }
    }
}
