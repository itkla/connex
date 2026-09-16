package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ooo.klae.connex.backend.beans.WorkspaceMember;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.mappers.RoleMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WorkflowMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.tenant.Permission;

@ExtendWith(MockitoExtension.class)
class WorkflowPrincipalLockServiceTest {

    @Mock private UserMapper userMapper;
    @Mock private WorkspaceMapper workspaceMapper;
    @Mock private RoleMapper roleMapper;
    @Mock private WorkflowMapper workflowMapper;

    @Test
    void userMutationLocksSortedRootsMembershipsAndCurrentCustomPermission() {
        WorkflowPrincipalLockService service = service();
        when(userMapper.lockById(3)).thenReturn(3);
        when(userMapper.lockById(7)).thenReturn(7);
        when(userMapper.lockById(9)).thenReturn(9);
        when(workspaceMapper.lockWorkspaceForShare(5)).thenReturn(5);
        when(workspaceMapper.lockAuthorizationMembership(5, 3))
            .thenReturn(membership(5, 3, "member", null, "active"));
        when(workspaceMapper.lockAuthorizationMembership(5, 7))
            .thenReturn(membership(5, 7, "member", 11, "active"));
        when(roleMapper.lockRole(5, 11)).thenReturn(11);
        when(roleMapper.lockPermissions(5, 11))
            .thenReturn(List.of(Permission.RULE_MANAGE.name(), Permission.TASK_CREATE.name()));

        service.lockUserMutation(5, 7, List.of(9, 3), Set.of(3), true);

        InOrder order = inOrder(userMapper, workspaceMapper, workflowMapper, roleMapper);
        order.verify(userMapper).lockById(3);
        order.verify(userMapper).lockById(7);
        order.verify(userMapper).lockById(9);
        order.verify(workspaceMapper).lockWorkspaceForShare(5);
        order.verify(workflowMapper).acquireTriggerAdmissionMutex(5);
        order.verify(workspaceMapper).lockAuthorizationMembership(5, 3);
        order.verify(workspaceMapper).lockAuthorizationMembership(5, 7);
        order.verify(roleMapper).lockRole(5, 11);
        order.verify(roleMapper).lockPermissions(5, 11);
    }

    @Test
    void builtInAdminPassesSystemAuthorizationWithoutCustomRoleReads() {
        WorkflowPrincipalLockService service = service();
        when(userMapper.lockById(7)).thenReturn(7);
        when(workspaceMapper.lockWorkspaceForShare(5)).thenReturn(5);
        when(workspaceMapper.lockAuthorizationMembership(5, 7))
            .thenReturn(membership(5, 7, "admin", null, "active"));

        service.lockSystemMutation(5, 7, Set.of(7), true);

        verifyNoInteractions(roleMapper);
    }

    @Test
    void systemAuthorizationRejectsCustomRolesEvenWhenTheyGrantRuleManage() {
        WorkflowPrincipalLockService service = service();
        when(userMapper.lockById(7)).thenReturn(7);
        when(workspaceMapper.lockWorkspaceForShare(5)).thenReturn(5);
        when(workspaceMapper.lockAuthorizationMembership(5, 7))
            .thenReturn(membership(5, 7, "admin", 11, "active"));

        assertThrows(
            ForbiddenException.class,
            () -> service.lockSystemMutation(5, 7, Set.of(7), true));

        verifyNoInteractions(roleMapper);
    }

    @Test
    void userAuthorizationFailsClosedOnRevokedLockedPermission() {
        WorkflowPrincipalLockService service = service();
        when(userMapper.lockById(7)).thenReturn(7);
        when(workspaceMapper.lockWorkspaceForShare(5)).thenReturn(5);
        when(workspaceMapper.lockAuthorizationMembership(5, 7))
            .thenReturn(membership(5, 7, "member", 11, "active"));
        when(roleMapper.lockRole(5, 11)).thenReturn(11);
        when(roleMapper.lockPermissions(5, 11)).thenReturn(List.of(Permission.TASK_CREATE.name()));

        assertThrows(
            ForbiddenException.class,
            () -> service.lockUserMutation(5, 7, Set.of(7), Set.of(), true));

        verify(roleMapper).lockRole(5, 11);
        verify(roleMapper).lockPermissions(5, 11);
    }

    @Test
    void lockedPermissionsRejectAnActionGrantRevokedBeforeAuthorization() {
        WorkflowPrincipalLockService service = service();
        when(userMapper.lockById(7)).thenReturn(7);
        when(workspaceMapper.lockWorkspaceForShare(5)).thenReturn(5);
        when(workspaceMapper.lockAuthorizationMembership(5, 7))
            .thenReturn(membership(5, 7, "member", 11, "active"));
        when(roleMapper.lockRole(5, 11)).thenReturn(11);
        when(roleMapper.lockPermissions(5, 11)).thenReturn(List.of(Permission.RULE_MANAGE.name()));

        WorkflowPrincipalLockService.LockedPrincipals principals =
            service.lockUserMutation(5, 7, Set.of(7), Set.of(), true);

        assertThrows(
            ForbiddenException.class,
            () -> principals.requirePermissions(Set.of(Permission.TASK_CREATE)));
    }

    @Test
    void pendingActorMembershipFailsBeforeRoleAuthorization() {
        WorkflowPrincipalLockService service = service();
        when(userMapper.lockById(7)).thenReturn(7);
        when(workspaceMapper.lockWorkspaceForShare(5)).thenReturn(5);
        when(workspaceMapper.lockAuthorizationMembership(5, 7))
            .thenReturn(membership(5, 7, "admin", null, "pending"));

        assertThrows(
            ForbiddenException.class,
            () -> service.lockUserMutation(5, 7, Set.of(7), Set.of(), true));

        verify(roleMapper, never()).lockRole(5, 11);
    }

    @Test
    void capacityAdmittingAuthoringTakesTheMutexUpsertUnderASharedWorkspaceRoot() {
        WorkflowPrincipalLockService service = service();
        when(userMapper.lockById(7)).thenReturn(7);
        when(workspaceMapper.lockWorkspaceForShare(5)).thenReturn(5);
        when(workspaceMapper.lockAuthorizationMembership(5, 7))
            .thenReturn(membership(5, 7, "admin", null, "active"));

        service.lockUserMutation(5, 7, Set.of(7), Set.of(), true);

        verify(workspaceMapper, never()).lockWorkspace(5);
        InOrder order = inOrder(workspaceMapper, workflowMapper);
        order.verify(workspaceMapper).lockWorkspaceForShare(5);
        order.verify(workflowMapper).acquireTriggerAdmissionMutex(5);
        order.verify(workspaceMapper).lockAuthorizationMembership(5, 7);
    }

    @Test
    void remediationMutationsNeverTouchTheAdmissionMutex() {
        WorkflowPrincipalLockService service = service();
        when(userMapper.lockById(7)).thenReturn(7);
        when(workspaceMapper.lockWorkspaceForShare(5)).thenReturn(5);
        when(workspaceMapper.lockAuthorizationMembership(5, 7))
            .thenReturn(membership(5, 7, "admin", null, "active"));

        service.lockUserMutation(5, 7, Set.of(7), Set.of(), false);
        service.lockSystemMutation(5, 7, Set.of(7), false);

        verifyNoInteractions(workflowMapper);
        verify(workspaceMapper, times(2)).lockWorkspaceForShare(5);
    }

    private WorkflowPrincipalLockService service() {
        return new WorkflowPrincipalLockService(
            userMapper, workspaceMapper, roleMapper, workflowMapper);
    }

    private static WorkspaceMember membership(
            int workspaceId, int userId, String role, Integer roleId, String status) {
        WorkspaceMember member = new WorkspaceMember();
        member.setWorkspaceId(workspaceId);
        member.setUserId(userId);
        member.setRole(role);
        member.setRoleId(roleId);
        member.setStatus(status);
        return member;
    }
}
