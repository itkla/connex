package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.mappers.TenantLifecycleControlMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.services.OrganizationWorkspaceScopeControlOperations.WorkspaceScope;
import ooo.klae.connex.backend.tenant.TenantCatalogResolver;

/** Pins the distinct lifecycle ceilings and the one-read-per-kind preparation budget. */
class TenantWorkspaceScopeControlOperationsTest {
    private final WorkspaceMapper workspaces = mock(WorkspaceMapper.class);
    private final TenantLifecycleControlMapper lifecycle = mock(TenantLifecycleControlMapper.class);
    private final TenantCatalogResolver catalogs = mock(TenantCatalogResolver.class);
    private final TenantWorkspaceScopeControlOperations operations =
        new TenantWorkspaceScopeControlOperations(workspaces, lifecycle, catalogs);

    @Test
    void oneReadPerKindPreservesUnfilteredNameOrderingAndActiveOnlyIds() {
        when(workspaces.findOrganizationWorkspacesForShare(7)).thenReturn(List.of(
            workspace(11, 900, "Alpha"), workspace(7, 900, "Zulu"), workspace(13, 900, "廃止")));
        when(workspaces.findByOrgId(900)).thenReturn(List.of(workspace(11, 900, "Alpha"), workspace(7, 900, "Zulu")));

        var prepared = operations.load(7, 900, "cnx_tenant", null);

        assertEquals("[11,7,13]", prepared.requireSharing().workspaceIdsJson());
        assertEquals(List.of(7, 11, 13), prepared.requireSharing().recordVisibilityScope().workspaceIds());
        assertSame(prepared.requireSharing().recordVisibilityScope(), prepared.requireSharing().recordVisibilityScope());
        assertEquals("[7,11]", prepared.requireActiveWorkspace().workspaceIdsJson());
        verify(workspaces).findOrganizationWorkspacesForShare(7);
        verify(workspaces).findByOrgId(900);
        verify(workspaces, never()).getOrgId(7);
    }

    @Test
    void inactiveAnchorDoesNotPreventPreparationOrWidenActiveVisibility() {
        when(workspaces.findOrganizationWorkspacesForShare(7)).thenReturn(List.of(workspace(7, 900, "Suspended")));
        when(workspaces.findByOrgId(900)).thenReturn(List.of());

        var prepared = operations.load(7, 900, "cnx_tenant", null);

        assertEquals("[7]", prepared.requireSharing().workspaceIdsJson());
        assertEquals(List.of(), prepared.active().workspaceIds());
        assertThrows(IllegalStateException.class, prepared::requireActiveWorkspace);
    }

    @Test
    void missingAnchorIsStoredWithoutBlockingTombstoneCleanup() {
        when(workspaces.findOrganizationWorkspacesForShare(7)).thenReturn(List.of());
        when(workspaces.findByOrgId(900)).thenReturn(List.of());

        var prepared = operations.load(7, 900, "cnx_tenant", null);

        assertFalse(prepared.sharing().isPresent());
        assertThrows(IllegalStateException.class, prepared::requireSharing);
        assertThrows(IllegalStateException.class, prepared::requireActiveWorkspace);
        verify(workspaces).findOrganizationWorkspacesForShare(7);
        verify(workspaces).findByOrgId(900);
    }

    @Test
    void knownDiagnosticsScopeDoesNotRepeatTheActiveRead() {
        when(workspaces.findOrganizationWorkspacesForShare(7)).thenReturn(List.of(workspace(7, 900, "Owner")));
        var active = new WorkspaceScope(900, List.of(7), "[7]");

        var prepared = operations.load(7, 900, null, active);

        assertSame(active, prepared.active());
        verify(workspaces, never()).findByOrgId(900);
    }

    @Test
    void mismatchedOrganizationCannotCreateAPreparedPayload() {
        when(workspaces.findOrganizationWorkspacesForShare(7)).thenReturn(List.of(workspace(7, 901, "Foreign")));
        assertThrows(IllegalStateException.class, () -> operations.load(7, 900, null, null));
    }

    @Test
    void mismatchedKnownActiveScopeIsRefused() {
        when(workspaces.findOrganizationWorkspacesForShare(7)).thenReturn(List.of(workspace(7, 900, "Owner")));
        assertThrows(IllegalArgumentException.class,
            () -> operations.load(7, 900, null, new WorkspaceScope(901, List.of(7), "[7]")));
    }

    @Test
    void ordinaryAndLifecycleRoutesUseTheirOwnAnchorPolicies() {
        when(workspaces.getOrgId(7)).thenReturn(900);
        when(lifecycle.findWorkspaceOrgIdForLifecycle(8)).thenReturn(900);
        when(catalogs.resolveCatalog(900)).thenReturn("cnx_tenant");
        when(workspaces.findOrganizationWorkspacesForShare(7)).thenReturn(List.of(workspace(7, 900, "Owner")));
        when(workspaces.findOrganizationWorkspacesForShare(8)).thenReturn(List.of(workspace(8, 900, "Fenced")));
        when(workspaces.findByOrgId(900)).thenReturn(List.of(workspace(7, 900, "Owner")));

        assertEquals("cnx_tenant", operations.forWorkspace(7, false, null).catalog());
        assertEquals("[8]", operations.forWorkspace(8, true, null).requireSharing().workspaceIdsJson());
        verify(workspaces, never()).getOrgId(8);
        verify(lifecycle, never()).findWorkspaceOrgIdForLifecycle(7);
    }

    @Test
    void trustedLifecycleCaptureResolvesPlacementWithoutRequiringTheDeletedRoot() {
        when(catalogs.resolveCatalog(900)).thenReturn("cnx_tenant");
        when(workspaces.findOrganizationWorkspacesForShare(7)).thenReturn(List.of());
        when(workspaces.findByOrgId(900)).thenReturn(List.of(workspace(11, 900, "Sibling")));

        var prepared = operations.forLifecycleWorkspace(7, 900);

        assertEquals("cnx_tenant", prepared.catalog());
        assertFalse(prepared.sharing().isPresent());
        assertEquals(List.of(11), prepared.active().workspaceIds());
        verify(workspaces, never()).getOrgId(7);
        verify(lifecycle, never()).findWorkspaceOrgIdForLifecycle(7);
    }

    private static Workspace workspace(int id, int orgId, String name) {
        Workspace workspace = new Workspace();
        workspace.setId(id);
        workspace.setOrgId(orgId);
        workspace.setName(name);
        return workspace;
    }
}
