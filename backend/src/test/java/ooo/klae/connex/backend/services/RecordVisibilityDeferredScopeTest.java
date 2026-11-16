package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.support.TransactionTemplate;

import ooo.klae.connex.backend.exceptions.TenantScopeSnapshotException;
import ooo.klae.connex.backend.exceptions.TenantScopeSnapshotMissingException;
import ooo.klae.connex.backend.support.RecordVisibilityOperationFixture;
import ooo.klae.connex.backend.tenant.TenantContext;

/** Covers deferred operation ownership without changing eager writes or routed snapshot validation. */
class RecordVisibilityDeferredScopeTest {
    @Test
    void skippedWorkDoesNotReadAndNestedReadsShareOneSnapshotAcrossIndependentTransactions() {
        try (var fixture = new RecordVisibilityOperationFixture(7)) {
            assertEquals("skipped", fixture.scopes.withDeferredWorkspaceScope(7, () -> "skipped"));
            verifyNoInteractions(fixture.control);
            fixture.scopes.withDeferredWorkspaceScope(7, () -> {
                for (int item = 0; item < 100; item++) {
                    fixture.scopes.withDeferredWorkspaceScope(7, () ->
                        new TransactionTemplate(fixture.transactions).execute(status -> {
                            assertEquals("[7,17]", fixture.scopes.getForWorkspace(7).workspaceIdsJson());
                            return null;
                        }));
                }
                return null;
            });
            verify(fixture.control).findOrganizationWorkspacesForShare(7);
            assertEquals(100, fixture.transactions.beginCount());
            fixture.scopes.getForWorkspace(7);
            verify(fixture.control, times(2)).findOrganizationWorkspacesForShare(7);
        }
    }

    @Test
    void anOrdinaryFailedReadIsReplayedOncePerItemWithoutRetryingAndIsDiscardedAfterward() {
        try (var fixture = new RecordVisibilityOperationFixture(7)) {
            var failure = new DataAccessResourceFailureException("control unavailable");
            when(fixture.control.findOrganizationWorkspacesForShare(7)).thenThrow(failure);
            fixture.scopes.withDeferredWorkspaceScope(7, () -> {
                for (int item = 0; item < 100; item++) {
                    assertSame(failure, assertThrows(DataAccessResourceFailureException.class,
                        () -> fixture.scopes.getForWorkspace(7)));
                }
                fixture.context.set(7, 90, 11, "member", "other_catalog");
                assertThrows(TenantScopeSnapshotException.class, () -> fixture.scopes.getForWorkspace(7));
                return null;
            });
            verify(fixture.control).findOrganizationWorkspacesForShare(7);
            fixture.context.clear();
            assertSame(failure, assertThrows(DataAccessResourceFailureException.class,
                () -> fixture.scopes.getForWorkspace(7)));
            verify(fixture.control, times(2)).findOrganizationWorkspacesForShare(7);
        }
    }

    @Test
    void routedLookupsAlwaysValidateThePreparedBoundaryRatherThanCachingSuccessOrRefusal() {
        ShareWorkspaceControlAccess delegate = mock(ShareWorkspaceControlAccess.class);
        TenantContext context = new TenantContext();
        when(delegate.isCatalogPerPlacement()).thenReturn(true);
        var snapshot = new ShareWorkspaceControlAccess.OrganizationWorkspaces(90, new java.util.LinkedHashMap<>(Map.of(7, "Anchor")));
        var refusal = new TenantScopeSnapshotMissingException("workspace 7");
        when(delegate.getForWorkspace(7)).thenReturn(snapshot).thenThrow(refusal);
        var scopes = new RecordVisibilityScopeControlAccess(delegate, context);
        scopes.withDeferredWorkspaceScope(7, () -> {
            assertEquals("[7]", scopes.getForWorkspace(7).workspaceIdsJson());
            assertSame(refusal, assertThrows(TenantScopeSnapshotMissingException.class,
                () -> scopes.getForWorkspace(7)));
            assertSame(refusal, assertThrows(TenantScopeSnapshotMissingException.class,
                () -> scopes.getForWorkspace(7)));
            return null;
        });
        verify(delegate, times(3)).getForWorkspace(7);
    }

    @Test
    void eagerBoundaryStillFailsBeforeCallingAnOperationAndNeverUsesADeferredFailureAfterward() {
        try (var fixture = new RecordVisibilityOperationFixture(7)) {
            var failure = new DataAccessResourceFailureException("control unavailable");
            when(fixture.control.findOrganizationWorkspacesForShare(7)).thenThrow(failure);
            Runnable operation = mock(Runnable.class);
            assertSame(failure, assertThrows(DataAccessResourceFailureException.class, () ->
                fixture.scopes.withWorkspaceScope(7, () -> { operation.run(); return null; })));
            verifyNoInteractions(operation);
        }
    }
}
