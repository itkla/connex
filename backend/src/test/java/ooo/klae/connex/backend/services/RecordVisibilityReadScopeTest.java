package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.dao.DataAccessResourceFailureException;

import ooo.klae.connex.backend.exceptions.TenantScopeSnapshotException;
import ooo.klae.connex.backend.exceptions.TenantScopeSnapshotMissingException;
import ooo.klae.connex.backend.support.RecordVisibilityOperationFixture;
import ooo.klae.connex.backend.tenant.TenantContext;

/** Read callbacks retain operation semantics without entering a mutation callback invocation. */
class RecordVisibilityReadScopeTest {
    @ParameterizedTest
    @EnumSource(Entry.class)
    void nestedReadsReuseOneSnapshotAndDiscardItAfterReturning(Entry entry) {
        try (var fixture = new RecordVisibilityOperationFixture(7)) {
            entry.read(fixture.scopes, 7, () -> {
                var scope = fixture.scopes.getForWorkspace(7);
                for (int item = 0; item < 100; item++) {
                    assertSame(scope, fixture.scopes.withWorkspaceScopeRead(7,
                        () -> fixture.scopes.withDeferredWorkspaceScopeRead(7,
                            () -> fixture.scopes.getForWorkspace(7))));
                }
                return null;
            });
            verify(fixture.control).findOrganizationWorkspacesForShare(7);
            fixture.scopes.getForWorkspace(7);
            verify(fixture.control, times(2)).findOrganizationWorkspacesForShare(7);
        }
    }

    @ParameterizedTest
    @EnumSource(Entry.class)
    void exceptionalNestedReadRestoresTheEnclosingSnapshotAndClearsItOnExit(Entry entry) {
        ShareWorkspaceControlAccess delegate = mock(ShareWorkspaceControlAccess.class);
        TenantContext context = new TenantContext();
        var anchor = snapshot(7);
        when(delegate.getForWorkspace(7)).thenReturn(anchor);
        when(delegate.getForWorkspace(8)).thenReturn(snapshot(8));
        var scopes = new RecordVisibilityScopeControlAccess(delegate, context);
        var failure = new IllegalArgumentException("read failed");
        assertSame(failure, assertThrows(IllegalArgumentException.class, () -> entry.read(scopes, 7, () -> {
            var enclosing = scopes.getForWorkspace(7);
            assertSame(failure, assertThrows(IllegalArgumentException.class, () -> entry.read(scopes, 8, () -> {
                assertEquals("[8]", scopes.getForWorkspace(8).workspaceIdsJson());
                throw failure;
            })));
            assertSame(enclosing, scopes.getForWorkspace(7));
            throw failure;
        })));
        verify(delegate).getForWorkspace(7);
        verify(delegate).getForWorkspace(8);
        scopes.getForWorkspace(7);
        verify(delegate, times(2)).getForWorkspace(7);
    }

    @ParameterizedTest
    @EnumSource(Entry.class)
    void aResolvedIdentityChangeIsRefusedBeforeReusingTheSnapshot(Entry entry) {
        try (var fixture = new RecordVisibilityOperationFixture(7)) {
            fixture.context.set(7, 90, 11, "member", null);
            entry.read(fixture.scopes, 7, () -> {
                fixture.scopes.getForWorkspace(7);
                fixture.context.set(8, 90, 11, "member", null);
                assertThrows(TenantScopeSnapshotException.class, () -> fixture.scopes.getForWorkspace(7));
                fixture.context.set(7, 91, 11, "member", null);
                assertThrows(TenantScopeSnapshotException.class, () -> fixture.scopes.getForWorkspace(7));
                fixture.context.clear();
                assertThrows(TenantScopeSnapshotException.class, () -> fixture.scopes.getForWorkspace(7));
                fixture.context.set(7, 90, 11, "member", "other_catalog");
                assertThrows(TenantScopeSnapshotException.class, () -> fixture.scopes.getForWorkspace(7));
                return null;
            });
            verify(fixture.control).findOrganizationWorkspacesForShare(7);
        }
    }

    @ParameterizedTest
    @EnumSource(Entry.class)
    void routedReadsValidateThePreparedSnapshotOnEveryAccess(Entry entry) {
        ShareWorkspaceControlAccess delegate = mock(ShareWorkspaceControlAccess.class);
        when(delegate.isCatalogPerPlacement()).thenReturn(true);
        when(delegate.getForWorkspace(7)).thenReturn(snapshot(7));
        var scopes = new RecordVisibilityScopeControlAccess(delegate, new TenantContext());
        entry.read(scopes, 7, () -> {
            assertEquals("[7]", scopes.getForWorkspace(7).workspaceIdsJson());
            var refusal = new TenantScopeSnapshotMissingException("workspace 7");
            when(delegate.getForWorkspace(7)).thenThrow(refusal);
            assertSame(refusal, assertThrows(TenantScopeSnapshotMissingException.class,
                () -> scopes.getForWorkspace(7)));
            assertSame(refusal, assertThrows(TenantScopeSnapshotMissingException.class,
                () -> scopes.getForWorkspace(7)));
            return null;
        });
        verify(delegate, times(entry == Entry.EAGER ? 4 : 3)).getForWorkspace(7);
    }

    @Test
    void eagerReadRefusesAnInvalidAnchorBeforeInvokingItsCallback() {
        ShareWorkspaceControlAccess delegate = mock(ShareWorkspaceControlAccess.class);
        when(delegate.getForWorkspace(7)).thenReturn(snapshot(8));
        var scopes = new RecordVisibilityScopeControlAccess(delegate, new TenantContext());
        RecordVisibilityRead<?> read = mock(RecordVisibilityRead.class);
        assertThrows(TenantScopeSnapshotException.class, () -> scopes.withWorkspaceScopeRead(7, read));
        verifyNoInteractions(read);
    }

    @Test
    void eagerReadFailsBeforeInvokingItsCallbackWhenTheControlReadFails() {
        try (var fixture = new RecordVisibilityOperationFixture(7)) {
            var failure = new DataAccessResourceFailureException("control unavailable");
            when(fixture.control.findOrganizationWorkspacesForShare(7)).thenThrow(failure);
            RecordVisibilityRead<?> read = mock(RecordVisibilityRead.class);
            assertSame(failure, assertThrows(DataAccessResourceFailureException.class,
                () -> fixture.scopes.withWorkspaceScopeRead(7, read)));
            verifyNoInteractions(read);
        }
    }

    @Test
    void deferredSkippedReadDoesNotTouchTheControlBoundary() {
        try (var fixture = new RecordVisibilityOperationFixture(7)) {
            assertEquals("skipped", fixture.scopes.withDeferredWorkspaceScopeRead(7, () -> "skipped"));
            verifyNoInteractions(fixture.control);
        }
    }

    @Test
    void deferredReadReplaysAnOrdinaryFailureOncePerItemAndRetriesAfterExit() {
        try (var fixture = new RecordVisibilityOperationFixture(7)) {
            var failure = new DataAccessResourceFailureException("control unavailable");
            when(fixture.control.findOrganizationWorkspacesForShare(7)).thenThrow(failure);
            fixture.scopes.withDeferredWorkspaceScopeRead(7, () -> {
                for (int item = 0; item < 100; item++) {
                    assertSame(failure, assertThrows(DataAccessResourceFailureException.class,
                        () -> fixture.scopes.withDeferredWorkspaceScopeRead(7,
                            () -> fixture.scopes.getForWorkspace(7))));
                }
                return null;
            });
            verify(fixture.control).findOrganizationWorkspacesForShare(7);
            assertSame(failure, assertThrows(DataAccessResourceFailureException.class,
                () -> fixture.scopes.getForWorkspace(7)));
            verify(fixture.control, times(2)).findOrganizationWorkspacesForShare(7);
        }
    }

    private static ShareWorkspaceControlAccess.OrganizationWorkspaces snapshot(int workspaceId) {
        return new ShareWorkspaceControlAccess.OrganizationWorkspaces(
            90, new LinkedHashMap<>(Map.of(workspaceId, "Anchor")));
    }

    /** Both read entries must obey the same scope and restoration contracts. */
    private enum Entry {
        EAGER, DEFERRED;

        <T> T read(RecordVisibilityScopeControlAccess scopes, int workspaceId, RecordVisibilityRead<T> read) {
            return switch (this) {
                case EAGER -> scopes.withWorkspaceScopeRead(workspaceId, read);
                case DEFERRED -> scopes.withDeferredWorkspaceScopeRead(workspaceId, read);
            };
        }
    }
}
