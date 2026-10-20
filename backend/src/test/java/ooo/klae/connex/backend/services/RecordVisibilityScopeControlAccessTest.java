package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.services.RecordVisibilityScopeControlAccess.RecordVisibilityScope;

/** Pins the unfiltered delegation contract and immutable scope representation shared by #485 lanes. */
class RecordVisibilityScopeControlAccessTest {
    @Test
    void recordScopeDelegatesToTheUnfilteredSnapshotOnce() {
        ShareWorkspaceControlAccess delegate = mock(ShareWorkspaceControlAccess.class);
        LinkedHashMap<Integer, String> workspaces = new LinkedHashMap<>();
        workspaces.put(11, "Alpha");
        workspaces.put(7, "Zulu");
        when(delegate.getForWorkspace(7)).thenReturn(new ShareWorkspaceControlAccess.OrganizationWorkspaces(900, workspaces));

        RecordVisibilityScope scope = new RecordVisibilityScopeControlAccess(delegate).getForWorkspace(7);

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
            () -> new RecordVisibilityScopeControlAccess(delegate).getForWorkspace(7));
    }

    @Test
    void scopeCannotBeChangedThroughItsInputOrAccessor() {
        List<Integer> workspaceIds = new ArrayList<>(List.of(7, 11));
        RecordVisibilityScope scope = new RecordVisibilityScope(900, workspaceIds);
        workspaceIds.clear();
        assertEquals("[7,11]", scope.workspaceIdsJson());
        assertThrows(UnsupportedOperationException.class, () -> scope.workspaceIds().add(13));
    }
}
