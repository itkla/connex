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
import ooo.klae.connex.backend.tenant.TenantWorkspaceScopeSnapshot;

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
        var scopes = tenantWorkScope.prepareLifecycleWorkspace(workspace.id(), workspace.orgId());
        if (scopes != null) {
            return new Route(workspace.orgId(), workspace.id(), scopes.catalog(), scopes);
        }
        String catalog = tenantWorkScope.unroutedRead(
            () -> tenantCatalogResolver.resolveCatalog(workspace.orgId()));
        return new Route(workspace.orgId(), workspace.id(), catalog);
    }

    /** Runs work in a previously captured lifecycle route and org-admin scope. */
    public <T> T withRoute(Route route, int actorId, Supplier<T> work) {
        Objects.requireNonNull(route, "route");
        Objects.requireNonNull(work, "work");
        return tenantWorkScope.withPreparedWorkspace(
            route.workspaceId(), route.orgId(), route.catalog(), route.scopes(), () ->
            withTenantContext(route, actorId, work));
    }

    private <T> T withTenantContext(Route route, int actorId, Supplier<T> work) {
        TenantContext.State previousTenant = tenantContext.snapshot();
        tenantContext.set(route.workspaceId(), route.orgId(), actorId, "org_admin", route.catalog());
        try {
            return work.get();
        } finally {
            tenantContext.restore(previousTenant);
        }
    }

    /**
     * Immutable trusted route and prepared data. Grouping remains by route identity so snapshots
     * captured separately cannot split the ownership-offer account cleanup's transaction groups.
     */
    public record Route(int orgId, int workspaceId, String catalog, TenantWorkspaceScopeSnapshot scopes) {
        public Route(int orgId, int workspaceId, String catalog) {
            this(orgId, workspaceId, catalog, null);
        }

        public Route {
            if (orgId <= 0 || workspaceId <= 0) {
                throw new IllegalArgumentException("Lifecycle route ids must be positive");
            }
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Route route && orgId == route.orgId
                && workspaceId == route.workspaceId && Objects.equals(catalog, route.catalog);
        }

        @Override
        public int hashCode() {
            return Objects.hash(orgId, workspaceId, catalog);
        }
    }
}
