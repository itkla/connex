package ooo.klae.connex.backend.services;


import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.services.OrganizationWorkspaceScopeControlOperations.WorkspaceScope;
import ooo.klae.connex.backend.tenant.ControlPlaneRead;
import ooo.klae.connex.backend.tenant.TenantContext;
import ooo.klae.connex.backend.tenant.TenantWorkScope;
import ooo.klae.connex.backend.tenant.TenantWorkspaceScopeSnapshot;

/**
 * Resolves active-only organization scopes from matching prepared data in both modes. Routed
 * operations refuse missing snapshots inside transactions; single-database misses retain the
 * existing independent control transaction, including on the default catalog. That fallback
 * does not establish the caller transaction's consistent-read view.
 */
@Component
@RequiredArgsConstructor
public class OrganizationWorkspaceScopeControlAccess {
    private final OrganizationWorkspaceScopeControlOperations controlOperations;
    private final TenantWorkScope tenantWorkScope;
    private final TenantContext tenantContext;
    private final PlatformTransactionManager transactionManager;

    /**
     * Resolves the active organization workspace scope for an exact prepared workspace/org/catalog
     * identity, falling back to an independent control read when the mode permits it.
     *
     * @param workspaceId workspace anchoring the organization
     * @return sorted workspace IDs and their JSON representation
     */
    public WorkspaceScope getForWorkspace(int workspaceId) {
        TenantWorkspaceScopeSnapshot prepared = tenantContext.getWorkspaceScopes();
        if (prepared != null && prepared.workspaceId() == workspaceId) {
            return prepared.requireActiveWorkspace();
        }
        tenantWorkScope.requireScopePreparationAllowed("workspace " + workspaceId);
        WorkspaceScope scope = execute(() -> controlOperations.getForWorkspace(workspaceId));
        if (tenantContext.isResolved() && (tenantContext.getWorkspaceId().intValue() != workspaceId
                || tenantContext.getOrgId().intValue() != scope.orgId())) {
            throw new IllegalStateException("Workspace scope does not match the resolved tenant context");
        }
        return scope;
    }

    /**
     * Resolves the active workspace allowlist for the prepared identity's organization and catalog,
     * or uses the existing independent control read when no matching payload is available.
     *
     * @param orgId organization to resolve
     * @return sorted workspace IDs and their JSON representation
     */
    public WorkspaceScope getForOrg(int orgId) {
        TenantWorkspaceScopeSnapshot prepared = tenantContext.getWorkspaceScopes();
        if (prepared != null && prepared.orgId() == orgId) {
            return prepared.active();
        }
        tenantWorkScope.requireScopePreparationAllowed("organization " + orgId);
        WorkspaceScope scope = execute(() -> controlOperations.getForOrg(orgId));
        if (tenantContext.isResolved() && tenantContext.getOrgId().intValue() != orgId) {
            throw new ResourceNotFoundException("Organization not found: " + orgId);
        }
        return scope;
    }

    private <T> T execute(ControlPlaneRead<T> read) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            return tenantWorkScope.unroutedRead(read);
        }
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
        return transaction.execute(status -> tenantWorkScope.unroutedRead(read));
    }

}
