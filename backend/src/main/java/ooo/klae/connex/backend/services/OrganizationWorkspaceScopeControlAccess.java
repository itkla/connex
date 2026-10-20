package ooo.klae.connex.backend.services;

import ooo.klae.connex.backend.exceptions.TenantScopeSnapshotException;


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
 * Resolves active-only organization scopes. Routed operations reuse prepared workspace data and
 * refuse missing snapshots inside transactions; single-database operations retain lazy reads and
 * the existing independent control transaction, including on the default catalog.
 */
@Component
@RequiredArgsConstructor
public class OrganizationWorkspaceScopeControlAccess {
    private final OrganizationWorkspaceScopeControlOperations controlOperations;
    private final TenantWorkScope tenantWorkScope;
    private final TenantContext tenantContext;
    private final PlatformTransactionManager transactionManager;

    /**
     * Resolves the complete organization workspace scope for one workspace.
     *
     * @param workspaceId workspace anchoring the organization
     * @return sorted workspace IDs and their JSON representation
     */
    public WorkspaceScope getForWorkspace(int workspaceId) {
        if (tenantWorkScope.isCatalogPerPlacement()) {
            TenantWorkspaceScopeSnapshot prepared = tenantContext.getWorkspaceScopes();
            if (prepared != null && prepared.workspaceId() == workspaceId) {
                return prepared.requireActiveWorkspace();
            }
            tenantWorkScope.requireScopePreparationAllowed("workspace " + workspaceId);
        }
        WorkspaceScope scope = execute(() -> controlOperations.getForWorkspace(workspaceId));
        if (tenantContext.isResolved() && (tenantContext.getWorkspaceId().intValue() != workspaceId
                || tenantContext.getOrgId().intValue() != scope.orgId())) {
            throw new TenantScopeSnapshotException("Workspace scope does not match the resolved tenant context");
        }
        return scope;
    }

    /**
     * Resolves the complete workspace allowlist for one organization.
     *
     * @param orgId organization to resolve
     * @return sorted workspace IDs and their JSON representation
     */
    public WorkspaceScope getForOrg(int orgId) {
        if (tenantWorkScope.isCatalogPerPlacement()) {
            TenantWorkspaceScopeSnapshot prepared = tenantContext.getWorkspaceScopes();
            if (prepared != null && prepared.orgId() == orgId) {
                return prepared.active();
            }
            tenantWorkScope.requireScopePreparationAllowed("organization " + orgId);
        }
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
