package ooo.klae.connex.backend.services;

import java.util.function.Supplier;

import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.services.OrganizationWorkspaceScopeControlOperations.WorkspaceScope;
import ooo.klae.connex.backend.tenant.TenantContext;
import ooo.klae.connex.backend.tenant.TenantWorkScope;

/** Resolves immutable organization workspace scopes from the control catalog. */
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
        WorkspaceScope scope = execute(() -> controlOperations.getForWorkspace(workspaceId));
        if (tenantContext.isResolved() && (tenantContext.getWorkspaceId().intValue() != workspaceId
                || tenantContext.getOrgId().intValue() != scope.orgId())) {
            throw new IllegalStateException("Workspace scope does not match the resolved tenant context");
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
        WorkspaceScope scope = execute(() -> controlOperations.getForOrg(orgId));
        if (tenantContext.isResolved() && tenantContext.getOrgId().intValue() != orgId) {
            throw new ResourceNotFoundException("Organization not found: " + orgId);
        }
        return scope;
    }

    private <T> T execute(Supplier<T> work) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            return tenantWorkScope.unrouted(work);
        }
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
        return transaction.execute(status -> tenantWorkScope.unrouted(work));
    }

}
