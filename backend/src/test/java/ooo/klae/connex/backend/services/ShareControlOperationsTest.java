package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.services.ShareControlOperations.WorkspaceSnapshot;
import ooo.klae.connex.backend.tenant.Permission;

@ExtendWith(MockitoExtension.class)
class ShareControlOperationsTest {
    @Mock private WorkspaceService workspaceService;
    @Mock private WorkspaceMapper workspaceMapper;
    @Mock private AuditService auditService;

    private ShareControlOperations operations;

    @BeforeEach
    void setUp() {
        operations = new ShareControlOperations(workspaceService, workspaceMapper, auditService);
    }

    @Test
    void prepareListAuthorizesAndReturnsAnImmutableOrganizationSnapshot() {
        Workspace owner = workspace(7, "Owner");
        Workspace target = workspace(8, "Target");
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(7);
        when(workspaceService.getCurrentUserId()).thenReturn(42);
        when(workspaceService.getOrgId(7)).thenReturn(9);
        when(workspaceMapper.findByOrgId(9)).thenReturn(List.of(owner, target));

        WorkspaceSnapshot snapshot = operations.prepareList().snapshot();

        assertEquals(List.of(7, 8), snapshot.ids());
        assertEquals("Target", snapshot.names().get(8));
        assertThrows(UnsupportedOperationException.class, () -> snapshot.ids().add(10));
        assertThrows(UnsupportedOperationException.class, () -> snapshot.names().put(10, "Other"));
        verify(workspaceService).requirePermission(7, 42, Permission.SHARE_MANAGE);
    }

    @Test
    void prepareTargetRejectsAWorkspaceOutsideTheOwnerOrganizationSnapshot() {
        when(workspaceMapper.findByOrgId(9)).thenReturn(List.of(workspace(7, "Owner")));

        assertThrows(ForbiddenException.class, () -> operations.prepareTarget(7, 9, 12, 42));

        verify(workspaceService).requireMember(12, 42);
    }

    @Test
    void auditsUseExplicitWorkspaceAndOrganizationScope() {
        operations.recordShare("company", 101, 7, 9, 8);
        operations.recordUnshare("person", 202, 7, 9, 8);

        verify(auditService).recordScoped("workspace.share", "company", 101, 7, 9, null,
            "Shared with workspace 8", null);
        verify(auditService).recordScoped("workspace.unshare", "person", 202, 7, 9, null,
            "Stopped sharing with workspace 8", null);
    }

    private static Workspace workspace(int id, String name) {
        Workspace workspace = new Workspace();
        workspace.setId(id);
        workspace.setName(name);
        workspace.setOrgId(9);
        return workspace;
    }
}
