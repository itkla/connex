package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.services.RecordVisibilityScopeControlAccess.RecordVisibilityScope;
import ooo.klae.connex.backend.support.TestTransactionManager;
import ooo.klae.connex.backend.tenant.TenantCatalogResolver;
import ooo.klae.connex.backend.tenant.TenantContext;
import ooo.klae.connex.backend.tenant.TenantWorkspaceScopeSnapshot;
import ooo.klae.connex.backend.services.OrganizationWorkspaceScopeControlOperations.WorkspaceScope;
import ooo.klae.connex.backend.tenant.TenantWorkScope;

/** Pins the unfiltered delegation contract and immutable scope representation shared by #485 lanes. */
class RecordVisibilityScopeControlAccessTest {
    private final TenantContext tenantContext = new TenantContext();

    @AfterEach
    void tearDown() {
        tenantContext.clear();
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        assertFalse(TransactionSynchronizationManager.isSynchronizationActive());
        assertTrue(TransactionSynchronizationManager.getResourceMap().isEmpty());
    }

    @Test
    void recordScopeDelegatesToTheUnfilteredSnapshotOnce() {
        ShareWorkspaceControlAccess delegate = mock(ShareWorkspaceControlAccess.class);
        LinkedHashMap<Integer, String> workspaces = new LinkedHashMap<>();
        workspaces.put(11, "Alpha");
        workspaces.put(7, "Zulu");
        when(delegate.getForWorkspace(7)).thenReturn(new ShareWorkspaceControlAccess.OrganizationWorkspaces(900, workspaces));

        RecordVisibilityScope scope = new RecordVisibilityScopeControlAccess(delegate, tenantContext).getForWorkspace(7);

        assertEquals(900, scope.orgId());
        assertEquals(List.of(7, 11), scope.workspaceIds());
        assertEquals("[7,11]", scope.workspaceIdsJson());
        verify(delegate).getForWorkspace(7);
    }

    @Test
    void recordScopeRetainsTheDelegatesAnchorAndTenantMismatchRefusals() {
        ShareWorkspaceControlAccess delegate = mock(ShareWorkspaceControlAccess.class);
        when(delegate.getForWorkspace(7)).thenThrow(new IllegalStateException("tenant mismatch"));
        assertThrows(IllegalStateException.class,
            () -> new RecordVisibilityScopeControlAccess(delegate, tenantContext).getForWorkspace(7));
    }

    @Test
    void scopeCannotBeChangedThroughItsInputOrAccessor() {
        List<Integer> workspaceIds = new ArrayList<>(List.of(7, 11));
        RecordVisibilityScope scope = new RecordVisibilityScope(900, workspaceIds);
        workspaceIds.clear();
        assertEquals("[7,11]", scope.workspaceIdsJson());
        assertThrows(UnsupportedOperationException.class, () -> scope.workspaceIds().add(13));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void twoHundredReadsReuseOneScopeUntilCommitOrRollback(boolean rollback) {
        ShareWorkspaceControlAccess delegate = mock(ShareWorkspaceControlAccess.class);
        when(delegate.getForWorkspace(7)).thenReturn(snapshot(900, 7, 11), snapshot(900, 7, 13));
        RecordVisibilityScopeControlAccess access = new RecordVisibilityScopeControlAccess(delegate, tenantContext);
        TransactionTemplate transaction = new TransactionTemplate(new TestTransactionManager());

        transaction.executeWithoutResult(status -> {
            RecordVisibilityScope first = access.getForWorkspace(7);
            for (int record = 0; record < 200; record++) {
                assertSame(first, access.getForWorkspace(7));
            }
            verify(delegate).getForWorkspace(7);
            if (rollback) {
                status.setRollbackOnly();
            }
        });

        assertTrue(TransactionSynchronizationManager.getResourceMap().isEmpty());
        transaction.executeWithoutResult(status ->
            assertEquals("[7,13]", access.getForWorkspace(7).workspaceIdsJson()));
        verify(delegate, times(2)).getForWorkspace(7);
    }

    @Test
    void callsOutsideATransactionAlwaysReadFreshSnapshots() {
        ShareWorkspaceControlAccess delegate = mock(ShareWorkspaceControlAccess.class);
        when(delegate.getForWorkspace(7)).thenReturn(snapshot(900, 7, 11), snapshot(900, 7, 13));
        RecordVisibilityScopeControlAccess access = new RecordVisibilityScopeControlAccess(delegate, tenantContext);

        assertEquals("[7,11]", access.getForWorkspace(7).workspaceIdsJson());
        assertEquals("[7,13]", access.getForWorkspace(7).workspaceIdsJson());

        verify(delegate, times(2)).getForWorkspace(7);
    }

    @Test
    void transactionMemoKeepsDifferentWorkspaceAnchorsSeparate() {
        ShareWorkspaceControlAccess delegate = mock(ShareWorkspaceControlAccess.class);
        when(delegate.getForWorkspace(7)).thenReturn(snapshot(900, 7, 11));
        when(delegate.getForWorkspace(13)).thenReturn(snapshot(901, 13, 17));
        RecordVisibilityScopeControlAccess access = new RecordVisibilityScopeControlAccess(delegate, tenantContext);

        new TransactionTemplate(new TestTransactionManager()).executeWithoutResult(status -> {
            for (int record = 0; record < 200; record++) {
                assertEquals("[7,11]", access.getForWorkspace(7).workspaceIdsJson());
                assertEquals("[13,17]", access.getForWorkspace(13).workspaceIdsJson());
            }
        });

        verify(delegate).getForWorkspace(7);
        verify(delegate).getForWorkspace(13);
    }

    @Test
    void requiresNewUsesItsOwnMemoAndRestoresTheOuterSnapshot() {
        ShareWorkspaceControlAccess delegate = mock(ShareWorkspaceControlAccess.class);
        when(delegate.getForWorkspace(7)).thenReturn(snapshot(900, 7, 11), snapshot(900, 7, 13));
        RecordVisibilityScopeControlAccess access = new RecordVisibilityScopeControlAccess(delegate, tenantContext);
        TestTransactionManager manager = new TestTransactionManager();
        TransactionTemplate outer = new TransactionTemplate(manager);
        TransactionTemplate inner = new TransactionTemplate(manager);
        inner.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        outer.executeWithoutResult(status -> {
            RecordVisibilityScope original = access.getForWorkspace(7);
            inner.executeWithoutResult(nested -> {
                RecordVisibilityScope independent = access.getForWorkspace(7);
                assertNotSame(original, independent);
                assertEquals("[7,13]", independent.workspaceIdsJson());
                assertSame(independent, access.getForWorkspace(7));
                nested.setRollbackOnly();
            });
            assertSame(original, access.getForWorkspace(7));
        });

        assertEquals(2, manager.beginCount());
        assertEquals(1, manager.suspendCount());
        verify(delegate, times(2)).getForWorkspace(7);
    }

    @Test
    void suspendedWorkCannotReuseOrReplaceTheTransactionMemo() {
        ShareWorkspaceControlAccess delegate = mock(ShareWorkspaceControlAccess.class);
        when(delegate.getForWorkspace(7)).thenReturn(snapshot(900, 7, 11), snapshot(900, 7, 13), snapshot(900, 7, 17));
        RecordVisibilityScopeControlAccess access = new RecordVisibilityScopeControlAccess(delegate, tenantContext);
        TestTransactionManager manager = new TestTransactionManager();
        TransactionTemplate suspended = new TransactionTemplate(manager);
        suspended.setPropagationBehavior(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);

        new TransactionTemplate(manager).executeWithoutResult(status -> {
            RecordVisibilityScope original = access.getForWorkspace(7);
            suspended.executeWithoutResult(ignored -> {
                assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
                assertTrue(TransactionSynchronizationManager.getResourceMap().isEmpty());
                assertEquals("[7,13]", access.getForWorkspace(7).workspaceIdsJson());
                assertEquals("[7,17]", access.getForWorkspace(7).workspaceIdsJson());
                assertTrue(TransactionSynchronizationManager.getResourceMap().isEmpty());
            });
            assertSame(original, access.getForWorkspace(7));
        });

        verify(delegate, times(3)).getForWorkspace(7);
    }

    @Test
    void cacheHitsStillRejectAChangedResolvedWorkspaceOrOrganization() {
        ShareWorkspaceControlAccess delegate = mock(ShareWorkspaceControlAccess.class);
        when(delegate.getForWorkspace(7)).thenReturn(snapshot(900, 7, 11));
        RecordVisibilityScopeControlAccess access = new RecordVisibilityScopeControlAccess(delegate, tenantContext);
        tenantContext.set(7, 900, 42, "member", null);

        new TransactionTemplate(new TestTransactionManager()).executeWithoutResult(status -> {
            RecordVisibilityScope original = access.getForWorkspace(7);
            tenantContext.set(11, 900, 42, "member", null);
            assertThrows(IllegalStateException.class, () -> access.getForWorkspace(7));
            tenantContext.set(7, 901, 42, "member", null);
            assertThrows(IllegalStateException.class, () -> access.getForWorkspace(7));
            tenantContext.set(7, 900, 42, "member", null);
            assertSame(original, access.getForWorkspace(7));
        });

        verify(delegate).getForWorkspace(7);
    }

    @Test
    void failedLookupDoesNotPopulateTheTransactionMemo() {
        ShareWorkspaceControlAccess delegate = mock(ShareWorkspaceControlAccess.class);
        when(delegate.getForWorkspace(7)).thenThrow(new IllegalStateException("tenant mismatch"))
            .thenReturn(snapshot(900, 7, 11));
        RecordVisibilityScopeControlAccess access = new RecordVisibilityScopeControlAccess(delegate, tenantContext);

        new TransactionTemplate(new TestTransactionManager()).executeWithoutResult(status -> {
            assertThrows(IllegalStateException.class, () -> access.getForWorkspace(7));
            assertTrue(TransactionSynchronizationManager.getResourceMap().isEmpty());
            assertEquals("[7,11]", access.getForWorkspace(7).workspaceIdsJson());
            assertEquals("[7,11]", access.getForWorkspace(7).workspaceIdsJson());
        });

        verify(delegate, times(2)).getForWorkspace(7);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void singleDatabaseMemoDoesNotDependOnThePinnedCatalog(boolean catalogPinned) {
        WorkspaceMapper workspaces = mock(WorkspaceMapper.class);
        TestTransactionManager manager = new TestTransactionManager();
        tenantContext.set(7, 900, 42, "member", catalogPinned ? "cnx_tenant" : null);
        Workspace anchor = new Workspace();
        anchor.setId(7);
        anchor.setOrgId(900);
        anchor.setName("Anchor");
        when(workspaces.findOrganizationWorkspacesForShare(7)).thenAnswer(invocation -> {
            assertEquals(!catalogPinned, TransactionSynchronizationManager.isActualTransactionActive());
            assertNull(tenantContext.getCatalog());
            return List.of(anchor);
        });
        RecordVisibilityScopeControlAccess access = new RecordVisibilityScopeControlAccess(
            new ShareWorkspaceControlAccess(workspaces,
                new TenantWorkScope(tenantContext, mock(TenantCatalogResolver.class), workspaces), tenantContext, manager),
            tenantContext);

        new TransactionTemplate(manager).executeWithoutResult(status -> {
            Object original = manager.currentTransaction();
            for (int record = 0; record < 200; record++) {
                assertEquals("[7]", access.getForWorkspace(7).workspaceIdsJson());
                assertSame(original, manager.currentTransaction());
            }
        });

        verify(workspaces).findOrganizationWorkspacesForShare(7);
        assertEquals(catalogPinned ? 1 : 0, manager.suspendCount());
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"cnx_tenant"})
    void routedReadsAlwaysReachTheBoundaryWithoutPopulatingTheMemo(String catalog) {
        ShareWorkspaceControlAccess delegate = mock(ShareWorkspaceControlAccess.class);
        when(delegate.isCatalogPerPlacement()).thenReturn(true);
        when(delegate.getForWorkspace(7)).thenReturn(snapshot(900, 7, 11));
        RecordVisibilityScopeControlAccess access = new RecordVisibilityScopeControlAccess(delegate, tenantContext);
        tenantContext.set(7, 900, 42, "member", catalog);

        new TransactionTemplate(new TestTransactionManager()).executeWithoutResult(status -> {
            for (int record = 0; record < 200; record++) {
                assertEquals("[7,11]", access.getForWorkspace(7).workspaceIdsJson());
                assertTrue(TransactionSynchronizationManager.getResourceMap().isEmpty());
                assertTrue(TransactionSynchronizationManager.getSynchronizations().isEmpty());
            }
        });

        verify(delegate, times(200)).getForWorkspace(7);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"cnx_tenant"})
    void routedReadsRetainBoundaryRefusalsAfterReinstallingTheSameIdentity(String catalog) {
        ShareWorkspaceControlAccess delegate = mock(ShareWorkspaceControlAccess.class);
        when(delegate.isCatalogPerPlacement()).thenReturn(true);
        IllegalStateException missingSnapshot = new IllegalStateException("prepared snapshot missing");
        when(delegate.getForWorkspace(7)).thenReturn(snapshot(900, 7, 11)).thenThrow(missingSnapshot);
        RecordVisibilityScopeControlAccess access = new RecordVisibilityScopeControlAccess(delegate, tenantContext);
        tenantContext.set(7, 900, 42, "member", catalog);

        new TransactionTemplate(new TestTransactionManager()).executeWithoutResult(status -> {
            assertEquals("[7,11]", access.getForWorkspace(7).workspaceIdsJson());
            tenantContext.clear();
            tenantContext.set(7, 900, 42, "member", catalog);
            assertSame(missingSnapshot, assertThrows(IllegalStateException.class, () -> access.getForWorkspace(7)));
            assertTrue(TransactionSynchronizationManager.getResourceMap().isEmpty());
            assertTrue(TransactionSynchronizationManager.getSynchronizations().isEmpty());
        });

        verify(delegate, times(2)).getForWorkspace(7);
    }

    @Test
    void routedReadsCannotReuseAnExistingSingleDatabaseMemo() {
        ShareWorkspaceControlAccess delegate = mock(ShareWorkspaceControlAccess.class);
        IllegalStateException missingSnapshot = new IllegalStateException("prepared snapshot missing");
        when(delegate.getForWorkspace(7)).thenReturn(snapshot(900, 7, 11)).thenThrow(missingSnapshot);
        RecordVisibilityScopeControlAccess access = new RecordVisibilityScopeControlAccess(delegate, tenantContext);

        new TransactionTemplate(new TestTransactionManager()).executeWithoutResult(status -> {
            assertEquals("[7,11]", access.getForWorkspace(7).workspaceIdsJson());
            var originalResources = TransactionSynchronizationManager.getResourceMap();
            assertEquals(1, originalResources.size());
            when(delegate.isCatalogPerPlacement()).thenReturn(true);

            assertSame(missingSnapshot, assertThrows(IllegalStateException.class, () -> access.getForWorkspace(7)));
            assertEquals(originalResources, TransactionSynchronizationManager.getResourceMap());
            assertEquals(1, TransactionSynchronizationManager.getSynchronizations().size());
        });

        verify(delegate, times(2)).getForWorkspace(7);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void completionCallbacksCannotLendTheOldMemoToANewTransaction(boolean rollback) {
        ShareWorkspaceControlAccess delegate = mock(ShareWorkspaceControlAccess.class);
        when(delegate.getForWorkspace(7)).thenReturn(snapshot(900, 7, 11), snapshot(900, 7, 13));
        RecordVisibilityScopeControlAccess access = new RecordVisibilityScopeControlAccess(delegate, tenantContext);
        TestTransactionManager manager = new TestTransactionManager();
        TransactionTemplate independent = new TransactionTemplate(manager);
        independent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        List<String> completedScopes = new ArrayList<>();

        new TransactionTemplate(manager).executeWithoutResult(status -> {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int completionStatus) {
                    independent.executeWithoutResult(inner -> {
                        RecordVisibilityScope fresh = access.getForWorkspace(7);
                        completedScopes.add(fresh.workspaceIdsJson());
                        assertSame(fresh, access.getForWorkspace(7));
                    });
                }
            });
            assertEquals("[7,11]", access.getForWorkspace(7).workspaceIdsJson());
            if (rollback) {
                status.setRollbackOnly();
            }
        });

        assertEquals(List.of("[7,13]"), completedScopes);
        assertEquals(2, manager.beginCount());
        verify(delegate, times(2)).getForWorkspace(7);
    }

    @Test
    void anOperationSharesOneScopeAcrossIndependentCommitsAndRestoresFreshReadsAfterward() {
        ShareWorkspaceControlAccess delegate = mock(ShareWorkspaceControlAccess.class);
        when(delegate.getForWorkspace(7)).thenReturn(snapshot(900, 7, 11), snapshot(900, 7, 13));
        RecordVisibilityScopeControlAccess access = new RecordVisibilityScopeControlAccess(delegate, tenantContext);
        TestTransactionManager manager = new TestTransactionManager();
        TransactionTemplate transaction = new TransactionTemplate(manager);
        access.withWorkspaceScope(7, () -> {
            for (int index = 0; index < 10; index++) {
                transaction.executeWithoutResult(status ->
                    assertEquals("[7,11]", access.getForWorkspace(7).workspaceIdsJson()));
            }
            return null;
        });
        assertEquals(10, manager.beginCount());
        verify(delegate, times(1)).getForWorkspace(7);
        assertEquals("[7,13]", access.getForWorkspace(7).workspaceIdsJson());
        verify(delegate, times(2)).getForWorkspace(7);
    }

    @Test
    void aFailedNestedOperationRestoresTheEnclosingScopeAndThenDiscardsIt() {
        ShareWorkspaceControlAccess delegate = mock(ShareWorkspaceControlAccess.class);
        when(delegate.getForWorkspace(7)).thenReturn(snapshot(900, 7, 11), snapshot(900, 7, 17));
        RecordVisibilityScopeControlAccess access = new RecordVisibilityScopeControlAccess(delegate, tenantContext);
        access.withWorkspaceScope(7, () -> {
            assertThrows(IllegalArgumentException.class, () -> access.withWorkspaceScope(
                7, new RecordVisibilityScope(900, List.of(7, 13)), () -> {
                    assertEquals("[7,13]", access.getForWorkspace(7).workspaceIdsJson());
                    throw new IllegalArgumentException("mutation refused");
                }));
            assertEquals("[7,11]", access.getForWorkspace(7).workspaceIdsJson());
            return null;
        });
        assertEquals("[7,17]", access.getForWorkspace(7).workspaceIdsJson());
    }

    @Test
    void operationReuseRejectsWorkspaceOrganizationAndCatalogDrift() {
        ShareWorkspaceControlAccess delegate = mock(ShareWorkspaceControlAccess.class);
        when(delegate.getForWorkspace(7)).thenReturn(snapshot(900, 7, 11));
        RecordVisibilityScopeControlAccess access = new RecordVisibilityScopeControlAccess(delegate, tenantContext);
        tenantContext.set(7, 900, 11, "member", null);
        access.withWorkspaceScope(7, () -> {
            tenantContext.set(7, 901, 11, "member", null);
            assertThrows(IllegalStateException.class, () -> access.getForWorkspace(7));
            tenantContext.set(11, 900, 11, "member", null);
            assertThrows(IllegalStateException.class, () -> access.getForWorkspace(7));
            tenantContext.set(7, 900, 11, "member", "different_catalog");
            assertThrows(IllegalStateException.class, () -> access.getForWorkspace(7));
            tenantContext.clear();
            assertThrows(IllegalStateException.class, () -> access.getForWorkspace(7));
            return null;
        });
    }

    @Test
    void routedOperationsCannotMemoizePastAMissingPreparedSnapshot() {
        ShareWorkspaceControlAccess delegate = mock(ShareWorkspaceControlAccess.class);
        when(delegate.isCatalogPerPlacement()).thenReturn(true);
        IllegalStateException missing = new ooo.klae.connex.backend.exceptions.TenantScopeSnapshotMissingException("workspace 7");
        when(delegate.getForWorkspace(7)).thenReturn(snapshot(900, 7, 11)).thenThrow(missing);
        RecordVisibilityScopeControlAccess access = new RecordVisibilityScopeControlAccess(delegate, tenantContext);
        access.withWorkspaceScope(7, () -> {
            new TransactionTemplate(new TestTransactionManager()).executeWithoutResult(status ->
                assertSame(missing, assertThrows(IllegalStateException.class, () -> access.getForWorkspace(7))));
            return null;
        });
        verify(delegate, times(2)).getForWorkspace(7);
    }

    @Test
    void singleDatabaseOperationThenPreparedPayloadTakePrecedenceOverTheTransactionMemo() {
        var delegate = mock(ShareWorkspaceControlAccess.class);
        when(delegate.getForWorkspace(7)).thenReturn(snapshot(900, 7));
        var access = new RecordVisibilityScopeControlAccess(delegate, tenantContext);
        var prepared = new TenantWorkspaceScopeSnapshot(7, 900, null, Optional.of(snapshot(900, 7, 11)),
            new WorkspaceScope(900, List.of(7), "[7]"));
        var explicit = new RecordVisibilityScope(900, List.of(7, 13));
        new TransactionTemplate(new TestTransactionManager()).executeWithoutResult(status -> {
            var memo = access.getForWorkspace(7);
            tenantContext.setWithWorkspaceScopes(7, 900, 42, "member", null, prepared);
            var installed = access.getForWorkspace(7);
            assertEquals(List.of(7, 11), installed.workspaceIds());
            access.withWorkspaceScope(7, explicit, () -> {
                assertSame(explicit, access.getForWorkspace(7));
                return null;
            });
            assertSame(installed, access.getForWorkspace(7));
            tenantContext.clear();
            assertSame(memo, access.getForWorkspace(7));
        });
        verify(delegate).getForWorkspace(7);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void bothModesServeTheExactPreparedPayloadWithoutUsingTheDelegate(boolean routed) {
        var delegate = mock(ShareWorkspaceControlAccess.class);
        when(delegate.isCatalogPerPlacement()).thenReturn(routed);
        var access = new RecordVisibilityScopeControlAccess(delegate, tenantContext);
        var sharing = snapshot(900, 7, 11);
        var prepared = new TenantWorkspaceScopeSnapshot(7, 900, null, Optional.of(sharing),
            new WorkspaceScope(900, List.of(7), "[7]"));
        tenantContext.setWithWorkspaceScopes(7, 900, 42, "member", null, prepared);
        new TransactionTemplate(new TestTransactionManager()).executeWithoutResult(status -> {
            assertSame(sharing.recordVisibilityScope(), access.getForWorkspace(7));
            access.withWorkspaceScope(7, sharing.recordVisibilityScope(), () -> {
                assertSame(sharing.recordVisibilityScope(), access.getForWorkspace(7));
                return null;
            });
            if (routed) {
                assertThrows(IllegalStateException.class, () -> access.withWorkspaceScope(
                    7, new RecordVisibilityScope(900, List.of(7)), () -> null));
                tenantContext.clear();
                assertThrows(ooo.klae.connex.backend.exceptions.TenantScopeSnapshotMissingException.class,
                    () -> access.withWorkspaceScope(7, sharing.recordVisibilityScope(), () -> null));
            }
        });
        verify(delegate, org.mockito.Mockito.never()).getForWorkspace(7);
    }

    @ParameterizedTest
    @ValueSource(strings = {"workspace", "organization", "catalog"})
    void singleDatabasePreparedIdentityMismatchesRetainTheLazyFallback(String mismatch) {
        var delegate = mock(ShareWorkspaceControlAccess.class);
        when(delegate.getForWorkspace(7)).thenReturn(snapshot(900, 7));
        var access = new RecordVisibilityScopeControlAccess(delegate, tenantContext);
        int preparedWorkspace = "workspace".equals(mismatch) ? 11 : 7;
        var prepared = new TenantWorkspaceScopeSnapshot(preparedWorkspace, 900, null,
            Optional.of(snapshot(900, preparedWorkspace, 13)),
            new WorkspaceScope(900, List.of(preparedWorkspace), "[" + preparedWorkspace + "]"));
        tenantContext.setWithWorkspaceScopes(preparedWorkspace, 900, 42, "member", null, prepared);
        if ("organization".equals(mismatch)) {
            tenantContext.set(7, 901, 42, "member", null);
        } else if ("catalog".equals(mismatch)) {
            tenantContext.set(7, 900, 42, "member", "different_catalog");
        }
        assertEquals(List.of(7), access.getForWorkspace(7).workspaceIds());
        verify(delegate).getForWorkspace(7);
    }

    private static ShareWorkspaceControlAccess.OrganizationWorkspaces snapshot(int orgId, int... workspaceIds) {
        LinkedHashMap<Integer, String> names = new LinkedHashMap<>();
        for (int workspaceId : workspaceIds) {
            names.put(workspaceId, "Workspace " + workspaceId);
        }
        return new ShareWorkspaceControlAccess.OrganizationWorkspaces(orgId, names);
    }
}
