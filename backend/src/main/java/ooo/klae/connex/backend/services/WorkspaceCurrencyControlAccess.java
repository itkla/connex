package ooo.klae.connex.backend.services;

import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.tenant.TenantWorkScope;

/** Reads workspace currency without overriding the catalog of an active tenant transaction. */
@Component
@RequiredArgsConstructor
public class WorkspaceCurrencyControlAccess {
    private final WorkspaceMapper workspaceMapper;
    private final TenantWorkScope tenantWorkScope;

    /**
     * Active transactions use the mapper's same-connection control-catalog routing so their
     * uncommitted settings remain visible. Outside transactions, the explicit control read scope applies.
     */
    public String getDefaultCurrency(int workspaceId) {
        Workspace workspace = TransactionSynchronizationManager.isActualTransactionActive()
            ? workspaceMapper.getActiveById(workspaceId)
            : tenantWorkScope.unroutedRead(() -> workspaceMapper.getActiveById(workspaceId));
        if (workspace == null) throw new ForbiddenException("Active workspace is not accessible");
        return workspace.getDefaultCurrency() == null ? "USD" : workspace.getDefaultCurrency();
    }
}
