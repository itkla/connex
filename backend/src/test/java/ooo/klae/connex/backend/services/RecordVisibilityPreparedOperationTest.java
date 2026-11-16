package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.support.TransactionTemplate;

import ooo.klae.connex.backend.exceptions.TenantScopeSnapshotException;
import ooo.klae.connex.backend.exceptions.TenantScopeSnapshotMissingException;
import ooo.klae.connex.backend.services.OrganizationWorkspaceScopeControlOperations.WorkspaceScope;
import ooo.klae.connex.backend.services.RecordVisibilityScopeControlAccess.RecordVisibilityScope;
import ooo.klae.connex.backend.support.RecordVisibilityOperationFixture;
import ooo.klae.connex.backend.tenant.TenantWorkspaceScopeSnapshot;

/** Composes deferred/read operation ownership with entry-prepared scopes and transaction memos. */
class RecordVisibilityPreparedOperationTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void deferredSupplierUsesThePreparedPayloadBeforeAnyTransactionLookup(boolean routed) {
        try (var fixture = new RecordVisibilityOperationFixture(7)) {
            var delegate = mock(ShareWorkspaceControlAccess.class);
            when(delegate.isCatalogPerPlacement()).thenReturn(routed);
            var scopes = new RecordVisibilityScopeControlAccess(delegate, fixture.context);
            var sharing = sharing();
            fixture.context.setWithWorkspaceScopes(7, 90, 11, "member", null, prepared(sharing));
            scopes.withDeferredWorkspaceScope(7, () -> {
                for (int item = 0; item < 10; item++) {
                    new TransactionTemplate(fixture.transactions).executeWithoutResult(status ->
                        assertSame(sharing.recordVisibilityScope(), scopes.getForWorkspace(7)));
                }
                return null;
            });
            assertEquals(10, fixture.transactions.beginCount());
            verify(delegate, never()).getForWorkspace(7);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void eagerDeferredAndSuppliedReadsUsePreparedDataWithoutTheLazyBoundary(boolean routed) {
        try (var fixture = new RecordVisibilityOperationFixture(7)) {
            var delegate = mock(ShareWorkspaceControlAccess.class);
            when(delegate.isCatalogPerPlacement()).thenReturn(routed);
            var scopes = new RecordVisibilityScopeControlAccess(delegate, fixture.context);
            var sharing = sharing();
            fixture.context.setWithWorkspaceScopes(7, 90, 11, "member", null, prepared(sharing));
            new TransactionTemplate(fixture.transactions).executeWithoutResult(status -> {
                assertSame(sharing.recordVisibilityScope(), scopes.withDeferredWorkspaceScopeRead(7,
                    () -> scopes.getForWorkspace(7)));
                assertSame(sharing.recordVisibilityScope(), scopes.withWorkspaceScopeRead(7,
                    () -> scopes.withDeferredWorkspaceScopeRead(7, () -> scopes.getForWorkspace(7))));
                assertSame(sharing.recordVisibilityScope(), scopes.withWorkspaceScopeRead(
                    7, sharing.recordVisibilityScope(), () -> scopes.getForWorkspace(7)));
            });
            verify(delegate, never()).getForWorkspace(7);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void deferredFirstUsePrefersPreparedDataOverAnExistingTransactionMemo(boolean readOnly) {
        try (var fixture = new RecordVisibilityOperationFixture(7)) {
            var delegate = mock(ShareWorkspaceControlAccess.class);
            when(delegate.getForWorkspace(7)).thenReturn(sharing());
            var scopes = new RecordVisibilityScopeControlAccess(delegate, fixture.context);
            var newer = new ShareWorkspaceControlAccess.OrganizationWorkspaces(90,
                new LinkedHashMap<>(Map.of(7, "Anchor", 19, "New sibling")));
            new TransactionTemplate(fixture.transactions).executeWithoutResult(status -> {
                var memo = scopes.getForWorkspace(7);
                fixture.context.setWithWorkspaceScopes(7, 90, 11, "member", null, prepared(newer));
                if (readOnly) {
                    scopes.withDeferredWorkspaceScopeRead(7, () -> {
                        assertSame(newer.recordVisibilityScope(), scopes.getForWorkspace(7));
                        return null;
                    });
                } else {
                    scopes.withDeferredWorkspaceScope(7, () -> {
                        assertSame(newer.recordVisibilityScope(), scopes.getForWorkspace(7));
                        return null;
                    });
                }
                fixture.context.clear();
                assertSame(memo, scopes.getForWorkspace(7));
            });
            verify(delegate).getForWorkspace(7);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void suppliedReadRestoresTheEnclosingScopeAfterFailureAndEnforcesRoutedEquality(boolean routed) {
        try (var fixture = new RecordVisibilityOperationFixture(7)) {
            var delegate = mock(ShareWorkspaceControlAccess.class);
            when(delegate.isCatalogPerPlacement()).thenReturn(routed);
            var scopes = new RecordVisibilityScopeControlAccess(delegate, fixture.context);
            var sharing = sharing();
            fixture.context.setWithWorkspaceScopes(7, 90, 11, "member", null, prepared(sharing));
            var failure = new IllegalArgumentException("read failed");
            scopes.withWorkspaceScopeRead(7, () -> {
                assertSame(failure, assertThrows(IllegalArgumentException.class,
                    () -> scopes.withWorkspaceScopeRead(7, sharing.recordVisibilityScope(), () -> {
                        throw failure;
                    })));
                assertSame(sharing.recordVisibilityScope(), scopes.getForWorkspace(7));
                return null;
            });
            assertThrows(TenantScopeSnapshotException.class, () -> scopes.withWorkspaceScopeRead(
                7, new RecordVisibilityScope(91, List.of(7)), () -> {
                    throw new AssertionError("Wrong-organization read was invoked");
                }));
            assertThrows(TenantScopeSnapshotException.class, () -> scopes.withWorkspaceScopeRead(
                7, new RecordVisibilityScope(90, List.of(17)), () -> {
                    throw new AssertionError("Missing-anchor read was invoked");
                }));
            var explicit = new RecordVisibilityScope(90, List.of(7, 13));
            if (routed) {
                assertThrows(TenantScopeSnapshotException.class,
                    () -> scopes.withWorkspaceScopeRead(7, explicit, () -> null));
                fixture.context.clear();
                assertThrows(TenantScopeSnapshotMissingException.class,
                    () -> scopes.withWorkspaceScopeRead(7, sharing.recordVisibilityScope(), () -> null));
            } else {
                assertSame(explicit, scopes.withWorkspaceScopeRead(7, explicit, () -> scopes.getForWorkspace(7)));
            }
            verify(delegate, never()).getForWorkspace(7);
        }
    }

    private static ShareWorkspaceControlAccess.OrganizationWorkspaces sharing() {
        return new ShareWorkspaceControlAccess.OrganizationWorkspaces(90,
            new LinkedHashMap<>(Map.of(7, "Anchor", 17, "Sibling")));
    }

    private static TenantWorkspaceScopeSnapshot prepared(ShareWorkspaceControlAccess.OrganizationWorkspaces sharing) {
        return new TenantWorkspaceScopeSnapshot(7, 90, null, Optional.of(sharing),
            new WorkspaceScope(90, List.of(7), "[7]"));
    }
}
