package ooo.klae.connex.backend.services;

import java.util.Objects;
import java.util.function.Supplier;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.dto.WorkspaceLifecycleRef;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.tenant.TenantCatalogResolver;
import ooo.klae.connex.backend.tenant.TenantContext;
import ooo.klae.connex.backend.tenant.TenantWorkScope;

/**
 * Captures a trusted control-plane workspace placement and reinstalls it for
 * routed lifecycle work, including resumable cleanup after ordinary resolution
 * has fenced the workspace.
 */
@Component
@RequiredArgsConstructor
public class TenantLifecycleAccess {
    private final TenantWorkScope tenantWorkScope;
    private final TenantCatalogResolver tenantCatalogResolver;
    private final TenantContext tenantContext;

    /** Captures the catalog route for an exact control-plane workspace identity. */
    public Route capture(WorkspaceLifecycleRef workspace, int expectedOrgId) {
        if (workspace == null || workspace.orgId() != expectedOrgId) {
            throw new ResourceNotFoundException("Workspace not found");
        }
        String catalog = tenantWorkScope.unrouted(
            () -> tenantCatalogResolver.resolveCatalog(workspace.orgId()));
        return new Route(workspace.orgId(), workspace.id(), catalog);
    }

    /** Runs work in a previously captured lifecycle route and org-admin scope. */
    public <T> T withRoute(Route route, int actorId, Supplier<T> work) {
        Objects.requireNonNull(route, "route");
        Objects.requireNonNull(work, "work");
        return tenantWorkScope.withCatalog(route.catalog(), () ->
            withTenantContext(route, actorId, work));
    }

    private <T> T withTenantContext(Route route, int actorId, Supplier<T> work) {
        boolean hadTenant = tenantContext.isResolved();
        Integer previousWorkspace = hadTenant ? tenantContext.getWorkspaceId() : null;
        Integer previousOrg = hadTenant ? tenantContext.getOrgId() : null;
        Integer previousUser = hadTenant ? tenantContext.getUserId() : null;
        String previousRole = hadTenant ? tenantContext.getRole() : null;
        String previousCatalog = hadTenant ? tenantContext.getScopeCatalog() : null;
        tenantContext.set(route.workspaceId(), route.orgId(), actorId, "org_admin", route.catalog());
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

    /** Immutable trusted lifecycle catalog route. */
    public record Route(int orgId, int workspaceId, String catalog) {
        public Route {
            if (orgId <= 0 || workspaceId <= 0) {
                throw new IllegalArgumentException("Lifecycle route ids must be positive");
            }
        }
    }
}
