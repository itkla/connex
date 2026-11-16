package ooo.klae.connex.backend.tenant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.dto.WorkspaceLifecycleRef;
import ooo.klae.connex.backend.exceptions.TenantScopeSnapshotMissingException;
import ooo.klae.connex.backend.mappers.TenantLifecycleControlMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.services.AutomationExecutor;
import ooo.klae.connex.backend.services.AutomationScope;
import ooo.klae.connex.backend.services.OrganizationWorkspaceScopeControlOperations.WorkspaceScope;
import ooo.klae.connex.backend.services.TenantLifecycleAccess;
import ooo.klae.connex.backend.services.TenantWorkspaceScopeControlOperations;

/** Exercises route entry, transactional nesting and failure cleanup independently of a database. */
class TenantWorkScopeSnapshotTest {
    private final TenantContext context = new TenantContext();
    private final TenantCatalogResolver catalogs = mock(TenantCatalogResolver.class);
    private final WorkspaceMapper workspaces = mock(WorkspaceMapper.class);
    private final TenantLifecycleControlMapper lifecycle = mock(TenantLifecycleControlMapper.class);
    private final TenantWorkspaceScopeControlOperations operations = mock(TenantWorkspaceScopeControlOperations.class);
    private final TenantWorkScope scope = new TenantWorkScope(context, catalogs, workspaces, lifecycle, operations);

    @AfterEach
    void clear() {
        context.clear();
        SecurityContextHolder.clearContext();
        TransactionSynchronizationManager.clear();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void sameWorkspaceRunAsInsideATransactionReusesTheOuterOperation(boolean routed) {
        var prepared = route(7, 900, "cnx_tenant", routed);
        var executor = new AutomationExecutor(context, new AutomationScope(), scope);
        User actor = new User();
        actor.setId(42);

        scope.inWorkspace(7, () -> {
            assertSame(prepared, context.getWorkspaceScopes());
            TransactionSynchronizationManager.setActualTransactionActive(true);
            try {
                executor.runAs(7, actor, "member", () -> {
                    assertSame(prepared, context.getWorkspaceScopes());
                    assertEquals(7, context.getWorkspaceId());
                    return null;
                });
                assertFalse(context.isResolved());
                assertSame(prepared, context.getWorkspaceScopes());
            } finally {
                TransactionSynchronizationManager.setActualTransactionActive(false);
            }
        });
        assertNull(context.getWorkspaceScopes());
        assertNull(context.getCatalog());
        verifyPreparedRoute(routed, 7, 900, "cnx_tenant");
    }

    @Test
    void missingOrDifferentWorkspaceInsideATransactionRefusesBeforeDiscovery() {
        var prepared = routed(7, 900, "cnx_tenant");
        scope.inWorkspace(7, () -> {
            TransactionSynchronizationManager.setActualTransactionActive(true);
            try {
                assertThrows(TenantScopeSnapshotMissingException.class, () -> scope.inWorkspace(11, () -> null));
                assertSame(prepared, context.getWorkspaceScopes());
            } finally {
                TransactionSynchronizationManager.setActualTransactionActive(false);
            }
        });
        verifyNoInteractions(workspaces, lifecycle);
        verify(operations).forWorkspace(7, false, null);
    }

    @Test
    void resolvedButUnpreparedIdentityCannotTriggerALoadInsideATransaction() {
        when(catalogs.isCatalogPerPlacement()).thenReturn(true);
        context.set(7, 900, 42, "member", "cnx_tenant");
        TransactionSynchronizationManager.setActualTransactionActive(true);
        assertThrows(TenantScopeSnapshotMissingException.class, () -> scope.inWorkspace(7, () -> null));
        verifyNoInteractions(workspaces, operations, lifecycle);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void nestedDifferentWorkspaceRestoresTheOriginalPayloadAfterFailureAndClear(boolean routed) {
        var outer = route(7, 900, "cnx_outer", routed);
        route(11, 901, "cnx_inner", routed);
        scope.inWorkspace(7, () -> {
            assertThrows(IllegalArgumentException.class, () -> scope.inWorkspace(11, () -> {
                assertEquals(11, context.getWorkspaceScopes().workspaceId());
                context.clear();
                throw new IllegalArgumentException("failed work");
            }));
            assertSame(outer, context.getWorkspaceScopes());
            assertEquals("cnx_outer", context.getCatalog());
        });
        assertNull(context.getWorkspaceScopes());
        assertNull(context.getCatalog());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void failedPreparationDiscardsTheRootCacheBeforeTheNextOperation(boolean routed) {
        var prepared = route(7, 900, "cnx_tenant", routed);
        if (routed) {
            when(operations.forWorkspace(7, false, null))
                .thenThrow(new IllegalStateException("unavailable")).thenReturn(prepared);
        } else {
            when(operations.load(7, 900, "cnx_tenant", null))
                .thenThrow(new IllegalStateException("unavailable")).thenReturn(prepared);
        }
        assertThrows(IllegalStateException.class, () -> scope.inWorkspace(7, () -> null));
        assertNull(context.getWorkspaceScopes());
        scope.inWorkspace(7, () -> assertSame(prepared, context.getWorkspaceScopes()));
        if (routed) {
            verify(operations, times(2)).forWorkspace(7, false, null);
        } else {
            verify(operations, times(2)).load(7, 900, "cnx_tenant", null);
        }
        assertNull(context.getWorkspaceScopes());
    }

    @Test
    void capturedLifecyclePayloadSurvivesRootDeletionWithoutAnotherRead() {
        var prepared = new TenantWorkspaceScopeSnapshot(7, 900, "cnx_tenant", Optional.empty(),
            new WorkspaceScope(900, List.of(), "[]"));
        scope.withPreparedWorkspace(7, 900, "cnx_tenant", prepared, () -> {
            context.set(7, 900, 42, "org_admin", "cnx_tenant");
            assertSame(prepared, context.getWorkspaceScopes());
            assertThrows(IllegalStateException.class, prepared::requireSharing);
            assertThrows(IllegalStateException.class, prepared::requireActiveWorkspace);
            return null;
        });
        verifyNoInteractions(workspaces, lifecycle, operations);
    }

    @Test
    void lifecycleRouteGroupingIgnoresPreparedPayloadIdentity() {
        var first = new TenantLifecycleAccess.Route(900, 7, "cnx_tenant", scopes(7, 900, "cnx_tenant"));
        var second = new TenantLifecycleAccess.Route(900, 7, "cnx_tenant", scopes(7, 900, "cnx_tenant"));
        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void lifecycleCapturePreparesPlacementAndMissingAnchorBeforeInstallingTheIdentity(boolean routed) {
        var prepared = scopes(7, 900, "cnx_tenant");
        when(catalogs.isCatalogPerPlacement()).thenReturn(routed);
        when(operations.forLifecycleWorkspace(7, 900)).thenReturn(prepared);
        var access = new TenantLifecycleAccess(scope, catalogs, context);
        var route = access.capture(new WorkspaceLifecycleRef(7, 900, "Deleted", "deleted", "tearing_down"), 900);

        assertSame(prepared, route.scopes());
        assertNull(context.getWorkspaceScopes());
        access.withRoute(route, 42, () -> {
            assertSame(prepared, context.getWorkspaceScopes());
            assertEquals(7, context.getWorkspaceId());
            return null;
        });
        assertFalse(context.isResolved());
        assertNull(context.getWorkspaceScopes());
        verify(operations).forLifecycleWorkspace(7, 900);
        verify(catalogs, org.mockito.Mockito.never()).resolveCatalog(900);
        verifyNoInteractions(workspaces, lifecycle);
    }

    @Test
    void singleDatabaseSkipsPreparationInsideAnExistingTransaction() {
        when(workspaces.getOrgId(7)).thenReturn(900);
        context.set(7, 900, 42, "member", null);
        TransactionSynchronizationManager.setActualTransactionActive(true);
        assertNull(scope.prepareWorkspaceScopes(7, 900, null));
        assertNull(scope.prepareLifecycleWorkspace(7, 900));
        scope.inWorkspace(7, () -> assertNull(context.getWorkspaceScopes()));
        verifyNoInteractions(operations, workspaces, lifecycle);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void diagnosticsSuppliesItsPreviouslyLoadedActiveScope(boolean routed) {
        var active = new WorkspaceScope(900, List.of(7), "[7]");
        var prepared = scopes(7, 900, "cnx_tenant");
        when(catalogs.isCatalogPerPlacement()).thenReturn(routed);
        when(workspaces.getOrgId(7)).thenReturn(900);
        when(catalogs.resolveCatalog(900)).thenReturn("cnx_tenant");
        when(operations.forWorkspace(7, false, active)).thenReturn(prepared);
        when(operations.load(7, 900, "cnx_tenant", active)).thenReturn(prepared);
        scope.withWorkspacePlacement(7, active, (orgId, catalog) -> {
            assertSame(prepared, context.getWorkspaceScopes());
            return null;
        });
        if (routed) {
            verify(operations).forWorkspace(7, false, active);
        } else {
            verify(operations).load(7, 900, "cnx_tenant", active);
        }
    }

    @Test
    void singleDatabasePreparesWithEmptySynchronizationAndKnownIdentityWithoutRouteQueries() {
        var prepared = scopes(7, 900, null);
        when(operations.load(7, 900, null, null)).thenAnswer(call -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            return prepared;
        });
        TransactionSynchronizationManager.initSynchronization();
        assertSame(prepared, scope.prepareWorkspaceScopes(7, 900, null));
        scope.withPreparedWorkspace(7, 900, null, null, () -> {
            assertSame(prepared, context.getWorkspaceScopes());
            return null;
        });
        assertNull(context.getWorkspaceScopes());
        verifyNoInteractions(workspaces, lifecycle);
        verify(catalogs, org.mockito.Mockito.never()).resolveCatalog(900);
        verify(operations, times(2)).load(7, 900, null, null);
    }

    @Test
    void singleDatabaseCapturedLifecycleEntryInsideATransactionSkipsPreparation() {
        when(catalogs.resolveCatalog(900)).thenReturn(null);
        context.set(7, 900, 42, "member", null);
        TransactionSynchronizationManager.setActualTransactionActive(true);
        var access = new TenantLifecycleAccess(scope, catalogs, context);
        var route = access.capture(new WorkspaceLifecycleRef(7, 900, "Fenced", "fenced", "tearing_down"), 900);
        assertNull(route.scopes());
        access.withRoute(route, 42, () -> {
            assertNull(context.getWorkspaceScopes());
            return null;
        });
        verifyNoInteractions(operations, workspaces, lifecycle);
    }

    private TenantWorkspaceScopeSnapshot routed(int workspaceId, int orgId, String catalog) {
        return route(workspaceId, orgId, catalog, true);
    }

    private TenantWorkspaceScopeSnapshot route(int workspaceId, int orgId, String catalog, boolean routed) {
        var prepared = scopes(workspaceId, orgId, catalog);
        when(catalogs.isCatalogPerPlacement()).thenReturn(routed);
        when(operations.forWorkspace(workspaceId, false, null)).thenReturn(prepared);
        when(workspaces.getOrgId(workspaceId)).thenReturn(orgId);
        when(catalogs.resolveCatalog(orgId)).thenReturn(catalog);
        when(operations.load(workspaceId, orgId, catalog, null)).thenReturn(prepared);
        return prepared;
    }

    private void verifyPreparedRoute(boolean routed, int workspaceId, int orgId, String catalog) {
        if (routed) {
            verify(operations).forWorkspace(workspaceId, false, null);
        } else {
            verify(operations).load(workspaceId, orgId, catalog, null);
        }
    }

    private static TenantWorkspaceScopeSnapshot scopes(int workspaceId, int orgId, String catalog) {
        return new TenantWorkspaceScopeSnapshot(workspaceId, orgId, catalog, Optional.empty(),
            new WorkspaceScope(orgId, List.of(workspaceId), "[" + workspaceId + "]"));
    }
}
