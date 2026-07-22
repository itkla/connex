package ooo.klae.connex.backend.services;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ooo.klae.connex.backend.beans.WorkspaceRole;
import ooo.klae.connex.backend.mappers.RoleMapper;
import ooo.klae.connex.backend.tenant.Permission;

@ExtendWith(MockitoExtension.class)
class RoleServiceLockOrderTest {
    @Mock private RoleMapper roleMapper;
    @Mock private WorkspaceService workspaceService;
    @Mock private AuditService auditService;
    @Mock private SessionSecurityService sessionSecurityService;

    @InjectMocks private RoleService service;

    @Test
    void customRoleCreationLocksCurrentAuthorizationBeforeInsert() {
        service.createRole(7, 1, "Analyst", List.of(Permission.PERSON_CREATE.name()));

        InOrder order = inOrder(workspaceService, sessionSecurityService, roleMapper);
        order.verify(workspaceService).requirePermission(7, 1, Permission.ROLE_MANAGE);
        order.verify(sessionSecurityService).requireRecentAuthentication(1);
        order.verify(workspaceService).lockRoleMutationAuthorization(
            7, 1, null, Set.of(Permission.PERSON_CREATE));
        order.verify(roleMapper).insertRole(any(WorkspaceRole.class));
    }

    @Test
    void customRoleUpdateLocksCurrentAuthorizationAndRoleBeforeMutation() {
        when(roleMapper.updateRoleName(7, 5, "Analyst")).thenReturn(1);

        service.updateRole(7, 1, 5, "Analyst", List.of(Permission.PERSON_CREATE.name()));

        InOrder order = inOrder(workspaceService, sessionSecurityService, roleMapper);
        order.verify(workspaceService).requirePermission(7, 1, Permission.ROLE_MANAGE);
        order.verify(sessionSecurityService).requireRecentAuthentication(1);
        order.verify(workspaceService).lockRoleMutationAuthorization(
            7, 1, 5, Set.of(Permission.PERSON_CREATE));
        order.verify(roleMapper).updateRoleName(7, 5, "Analyst");
    }

    @Test
    void customRoleDeletionLocksWorkspaceRootBeforeCascadeDelete() {
        when(roleMapper.deleteRole(7, 5)).thenReturn(1);

        service.deleteRole(7, 1, 5);

        InOrder order = inOrder(workspaceService, sessionSecurityService, roleMapper, auditService);
        order.verify(workspaceService).requirePermission(7, 1, Permission.ROLE_MANAGE);
        order.verify(sessionSecurityService).requireRecentAuthentication(1);
        order.verify(workspaceService).lockRoleDeletionAuthorization(7, 1, 5);
        order.verify(roleMapper).deleteRole(7, 5);
        order.verify(auditService).record(
            "workspace.role.delete", "workspace", 7, null, "Deleted role 5", null);
    }
}
