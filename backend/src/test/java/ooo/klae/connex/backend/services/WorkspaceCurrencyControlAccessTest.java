package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.exceptions.TenantScopeSnapshotException;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.tenant.ControlPlaneRead;
import ooo.klae.connex.backend.tenant.TenantContext;
import ooo.klae.connex.backend.tenant.TenantWorkScope;

class WorkspaceCurrencyControlAccessTest {
    private final WorkspaceMapper mapper = mock(WorkspaceMapper.class);
    private final TenantWorkScope scopes = mock(TenantWorkScope.class);
    private final TenantContext context = new TenantContext();
    private final WorkspaceCurrencyControlAccess access = new WorkspaceCurrencyControlAccess(mapper, scopes, context);

    @AfterEach
    void clearContext() {
        context.clear();
        TransactionSynchronizationManager.clear();
    }

    @Test
    void activeTenantTransactionNeverAttemptsAnUnroutedOverride() {
        Workspace workspace = new Workspace();
        workspace.setId(7);
        workspace.setOrgId(90);
        workspace.setDefaultCurrency("EUR");
        context.set(7, 90, 11, "member", "cnx_tenant");
        when(mapper.getActiveById(7)).thenReturn(workspace);
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertEquals("EUR", access.getDefaultCurrency(7));
            workspace.setDefaultCurrency(null);
            assertEquals("USD", access.getDefaultCurrency(7));
            when(mapper.getActiveById(7)).thenReturn(null);
            assertThrows(ForbiddenException.class, () -> access.getDefaultCurrency(7));
            verifyNoInteractions(scopes);
        } finally {
            TransactionSynchronizationManager.clear();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void anotherOrganizationsWorkspaceIsRefusedBeforeControlPlaneAccess(boolean transactionActive) {
        context.set(7, 90, 11, "member", "cnx_tenant");
        Workspace foreign = new Workspace();
        foreign.setId(8);
        foreign.setOrgId(91);
        foreign.setDefaultCurrency("GBP");
        when(mapper.getActiveById(8)).thenReturn(foreign);
        when(scopes.<Workspace>unroutedRead(any())).thenAnswer(invocation ->
            invocation.<ControlPlaneRead<Workspace>>getArgument(0).read());
        TransactionSynchronizationManager.setActualTransactionActive(transactionActive);

        assertThrows(TenantScopeSnapshotException.class, () -> access.getDefaultCurrency(8));

        verifyNoInteractions(mapper, scopes);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void sameWorkspaceIdWithForeignOrganizationDataFailsClosed(boolean transactionActive) {
        context.set(7, 90, 11, "member", "cnx_tenant");
        Workspace foreign = new Workspace();
        foreign.setId(7);
        foreign.setOrgId(91);
        foreign.setDefaultCurrency("GBP");
        when(mapper.getActiveById(7)).thenReturn(foreign);
        when(scopes.<Workspace>unroutedRead(any())).thenAnswer(invocation ->
            invocation.<ControlPlaneRead<Workspace>>getArgument(0).read());
        TransactionSynchronizationManager.setActualTransactionActive(transactionActive);

        assertThrows(TenantScopeSnapshotException.class, () -> access.getDefaultCurrency(7));
    }

    @Test
    void unresolvedContextCannotReadWorkspaceSettings() {
        assertThrows(TenantScopeSnapshotException.class, () -> access.getDefaultCurrency(7));
        verifyNoInteractions(mapper, scopes);
    }

    @Test
    void matchingContextUsesTheScopedControlReadOutsideTransactions() {
        context.set(7, 90, 11, "member", null);
        Workspace workspace = new Workspace();
        workspace.setId(7);
        workspace.setOrgId(90);
        workspace.setDefaultCurrency("EUR");
        when(mapper.getActiveById(7)).thenReturn(workspace);
        when(scopes.<Workspace>unroutedRead(any())).thenAnswer(invocation ->
            invocation.<ControlPlaneRead<Workspace>>getArgument(0).read());

        assertEquals("EUR", access.getDefaultCurrency(7));
    }
}
