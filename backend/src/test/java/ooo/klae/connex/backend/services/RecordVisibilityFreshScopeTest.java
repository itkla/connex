package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.exceptions.TenantScopeSnapshotMissingException;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.services.OrganizationWorkspaceScopeControlOperations.WorkspaceScope;
import ooo.klae.connex.backend.support.TestTransactionManager;
import ooo.klae.connex.backend.tenant.TenantCatalogResolver;
import ooo.klae.connex.backend.tenant.TenantContext;
import ooo.klae.connex.backend.tenant.TenantWorkScope;
import ooo.klae.connex.backend.tenant.TenantWorkspaceScopeSnapshot;

/** Pins the cache purge's fresh single-database read and memory-only routed exception. */
class RecordVisibilityFreshScopeTest {
    private final TenantContext tenantContext = new TenantContext();

    @AfterEach
    void cleanUp() {
        tenantContext.clear();
        assertTrue(TransactionSynchronizationManager.getResourceMap().isEmpty());
        assertFalse(TransactionSynchronizationManager.isSynchronizationActive());
    }

    @Test
    void freshReadsNeitherReuseNorReplaceTheTransactionMemo() {
        ShareWorkspaceControlAccess delegate = mock(ShareWorkspaceControlAccess.class);
        when(delegate.getForWorkspace(7)).thenReturn(snapshot(7));
        when(delegate.getFreshForWorkspace(7)).thenReturn(snapshot(7, 11), snapshot(7, 11, 13));
        var access = new RecordVisibilityScopeControlAccess(delegate, tenantContext);
        new TransactionTemplate(new TestTransactionManager()).executeWithoutResult(status -> {
            var memo = access.getForWorkspace(7);
            var resources = TransactionSynchronizationManager.getResourceMap();
            int synchronizations = TransactionSynchronizationManager.getSynchronizations().size();
            assertEquals("[7,11]", access.getFreshForWorkspace(7).workspaceIdsJson());
            assertSame(memo, access.getForWorkspace(7));
            assertEquals("[7,11,13]", access.getFreshForWorkspace(7).workspaceIdsJson());
            assertSame(memo, access.getForWorkspace(7));
            assertEquals(resources, TransactionSynchronizationManager.getResourceMap());
            assertEquals(synchronizations, TransactionSynchronizationManager.getSynchronizations().size());
        });
        verify(delegate).getForWorkspace(7);
        verify(delegate, times(2)).getFreshForWorkspace(7);
    }

    @Test
    void singleDatabaseFreshReadBypassesPreparedAndExplicitScopesWithoutReplacingAnySnapshot() {
        var delegate = mock(ShareWorkspaceControlAccess.class);
        when(delegate.getForWorkspace(7)).thenReturn(snapshot(7));
        when(delegate.getFreshForWorkspace(7)).thenReturn(snapshot(7, 19));
        var access = new RecordVisibilityScopeControlAccess(delegate, tenantContext);
        var prepared = new TenantWorkspaceScopeSnapshot(7, 3, null, Optional.of(snapshot(7, 11)),
            new WorkspaceScope(3, List.of(7), "[7]"));
        var explicit = new RecordVisibilityScopeControlAccess.RecordVisibilityScope(3, List.of(7, 13));
        new TransactionTemplate(new TestTransactionManager()).executeWithoutResult(status -> {
            var memo = access.getForWorkspace(7);
            tenantContext.setWithWorkspaceScopes(7, 3, 29, "owner", null, prepared);
            access.withWorkspaceScope(7, explicit, () -> {
                assertEquals("[7,19]", access.getFreshForWorkspace(7).workspaceIdsJson());
                assertSame(explicit, access.getForWorkspace(7));
                assertSame(prepared, tenantContext.getWorkspaceScopes());
                return null;
            });
            assertSame(prepared.requireSharing().recordVisibilityScope(), access.getForWorkspace(7));
            tenantContext.clear();
            assertSame(memo, access.getForWorkspace(7));
        });
        verify(delegate).getForWorkspace(7);
        verify(delegate).getFreshForWorkspace(7);
    }

    @Test
    void freshReadDoesNotCreateAMemoAndRetainsTheDefaultCatalogTransaction() {
        WorkspaceMapper workspaces = mock(WorkspaceMapper.class);
        var manager = new TestTransactionManager();
        Workspace anchor = new Workspace();
        anchor.setId(7);
        anchor.setOrgId(3);
        anchor.setName("Anchor");
        tenantContext.set(7, 3, 29, "owner", null);
        when(workspaces.findOrganizationWorkspacesForCachePurge(7)).thenAnswer(invocation -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            return List.of(anchor);
        });
        var access = new RecordVisibilityScopeControlAccess(new ShareWorkspaceControlAccess(workspaces,
            new TenantWorkScope(tenantContext, mock(TenantCatalogResolver.class), workspaces),
            tenantContext, manager), tenantContext);
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            Object transaction = manager.currentTransaction();
            assertEquals("[7]", access.getFreshForWorkspace(7).workspaceIdsJson());
            assertSame(transaction, manager.currentTransaction());
            assertTrue(TransactionSynchronizationManager.getResourceMap().isEmpty());
            assertTrue(TransactionSynchronizationManager.getSynchronizations().isEmpty());
        });
        assertEquals(0, manager.suspendCount());
        assertEquals(1, manager.beginCount());
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"tenant_catalog"})
    void routedFreshReadsRequireTheExactPreparedSnapshotWithoutTouchingTheDelegate(String catalog) {
        ShareWorkspaceControlAccess delegate = mock(ShareWorkspaceControlAccess.class);
        when(delegate.isCatalogPerPlacement()).thenReturn(true);
        var access = new RecordVisibilityScopeControlAccess(delegate, tenantContext);
        var prepared = new TenantWorkspaceScopeSnapshot(7, 3, catalog, Optional.of(snapshot(7, 11)),
            new WorkspaceScope(3, List.of(7), "[7]"));
        tenantContext.setWithWorkspaceScopes(7, 3, 29, "owner", catalog, prepared);
        var manager = new TestTransactionManager();
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            var first = access.getFreshForWorkspace(7);
            assertEquals("[7,11]", first.workspaceIdsJson());
            assertSame(first, access.getFreshForWorkspace(7));
            assertThrows(TenantScopeSnapshotMissingException.class, () -> access.getFreshForWorkspace(11));
            tenantContext.set(7, 99, 29, "owner", catalog);
            assertThrows(TenantScopeSnapshotMissingException.class, () -> access.getFreshForWorkspace(7));
            assertTrue(TransactionSynchronizationManager.getResourceMap().isEmpty());
        });
        assertThrows(TenantScopeSnapshotMissingException.class, () -> access.getFreshForWorkspace(7));
        verify(delegate, times(5)).isCatalogPerPlacement();
        org.mockito.Mockito.verify(delegate, org.mockito.Mockito.never()).getForWorkspace(7);
        assertEquals(0, manager.suspendCount());
    }

    @Test
    void freshReadKeepsAnchorAndTenantRefusals() {
        WorkspaceMapper workspaces = mock(WorkspaceMapper.class);
        var access = new RecordVisibilityScopeControlAccess(new ShareWorkspaceControlAccess(workspaces,
            new TenantWorkScope(tenantContext, mock(TenantCatalogResolver.class), workspaces),
            tenantContext, new TestTransactionManager()), tenantContext);
        assertThrows(IllegalStateException.class, () -> access.getFreshForWorkspace(7));
        verify(workspaces).findOrganizationWorkspacesForCachePurge(7);
        Workspace anchor = new Workspace();
        anchor.setId(7);
        anchor.setOrgId(3);
        anchor.setName("Anchor");
        when(workspaces.findOrganizationWorkspacesForCachePurge(7)).thenReturn(List.of(anchor));
        tenantContext.set(7, 99, 29, "owner", null);
        assertThrows(IllegalStateException.class, () -> access.getFreshForWorkspace(7));
        ShareWorkspaceControlAccess routed = mock(ShareWorkspaceControlAccess.class);
        when(routed.isCatalogPerPlacement()).thenReturn(true);
        var routedAccess = new RecordVisibilityScopeControlAccess(routed, tenantContext);
        assertThrows(TenantScopeSnapshotMissingException.class, () -> routedAccess.getFreshForWorkspace(7));
        org.mockito.Mockito.verify(routed, org.mockito.Mockito.never()).getForWorkspace(7);
    }

    private static ShareWorkspaceControlAccess.OrganizationWorkspaces snapshot(int... workspaceIds) {
        LinkedHashMap<Integer, String> names = new LinkedHashMap<>();
        for (int workspaceId : workspaceIds) {
            names.put(workspaceId, "Workspace " + workspaceId);
        }
        return new ShareWorkspaceControlAccess.OrganizationWorkspaces(3, names);
    }
}
