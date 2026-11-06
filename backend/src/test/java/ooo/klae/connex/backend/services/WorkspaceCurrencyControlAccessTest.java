package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.tenant.TenantWorkScope;

class WorkspaceCurrencyControlAccessTest {
    private final WorkspaceMapper mapper = mock(WorkspaceMapper.class);
    private final TenantWorkScope scopes = mock(TenantWorkScope.class);
    private final WorkspaceCurrencyControlAccess access = new WorkspaceCurrencyControlAccess(mapper, scopes);

    @Test
    void activeTenantTransactionNeverAttemptsAnUnroutedOverride() {
        Workspace workspace = new Workspace();
        workspace.setDefaultCurrency("EUR");
        when(mapper.getActiveById(7)).thenReturn(workspace);
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertEquals("EUR", access.getDefaultCurrency(7));
            workspace.setDefaultCurrency(null);
            assertEquals("USD", access.getDefaultCurrency(7));
            assertThrows(ForbiddenException.class, () -> access.getDefaultCurrency(8));
            verifyNoInteractions(scopes);
        } finally {
            TransactionSynchronizationManager.clear();
        }
    }
}
