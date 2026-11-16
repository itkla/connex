package ooo.klae.connex.backend.services;

import java.util.Objects;

import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.exceptions.TenantScopeSnapshotException;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.tenant.TenantContext;
import ooo.klae.connex.backend.tenant.TenantWorkScope;

/** Reads workspace currency without overriding the catalog of an active tenant transaction. */
@Component
@RequiredArgsConstructor
public class WorkspaceCurrencyControlAccess {
    private final WorkspaceMapper workspaceMapper;
    private final TenantWorkScope tenantWorkScope;
    private final TenantContext tenantContext;

    /**
     * Active transactions use the mapper's same-connection control-catalog routing so their
     * uncommitted settings remain visible. Outside transactions, the explicit control read scope applies.
     */
    public String getDefaultCurrency(int workspaceId) {
        Integer orgId = tenantContext.getOrgId();
        if (!tenantContext.isResolved() || !Objects.equals(tenantContext.getWorkspaceId(), workspaceId)
                || orgId == null) {
            throw new TenantScopeSnapshotException("Workspace scope does not match the resolved tenant context");
        }
        Workspace workspace = TransactionSynchronizationManager.isActualTransactionActive()
            ? workspaceMapper.getActiveById(workspaceId)
            : tenantWorkScope.unroutedRead(() -> workspaceMapper.getActiveById(workspaceId));
        if (workspace == null) throw new ForbiddenException("Active workspace is not accessible");
        if (workspace.getId() != workspaceId || workspace.getOrgId() != orgId) {
            throw new TenantScopeSnapshotException("Workspace scope does not match the resolved tenant context");
        }
        return workspace.getDefaultCurrency() == null ? "USD" : workspace.getDefaultCurrency();
    }
}
