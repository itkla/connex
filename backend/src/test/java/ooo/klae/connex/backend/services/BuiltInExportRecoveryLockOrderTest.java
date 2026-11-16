package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ooo.klae.connex.backend.beans.WorkspaceMember;
import ooo.klae.connex.backend.dto.ExportPermissions;
import ooo.klae.connex.backend.exceptions.WorkspaceRecoveryOwnerRequiredException;
import ooo.klae.connex.backend.mappers.RoleMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.tenant.Permission;

/** Pins recovery validation after authorization and before a built-in export-policy write. */
@ExtendWith(MockitoExtension.class)
class BuiltInExportRecoveryLockOrderTest {
    @Mock private WorkspaceMapper workspaces;
    @Mock private RoleMapper roles;
    @Mock private AccountDeletionReservationRead reservations;
    @Mock private UserMapper users;
    @Mock private PrivilegedMfaLockedAuthorization mfa;
    @InjectMocks private WorkspaceService service;

    @Test
    void refusedEnableRetainsAllOwnerOverlaysInAscendingRoleOrderBeforeCheckingRecovery() {
        owners(List.of(owner(11, 7), owner(13, 4)), Set.of());

        assertThrows(WorkspaceRecoveryOwnerRequiredException.class,
            () -> service.lockBuiltInExportPolicyAuthorization(5, 1, "owner", true));

        var order = inOrder(users, workspaces, roles, mfa, reservations);
        order.verify(users).lockById(1);
        order.verify(workspaces).lockWorkspace(5);
        order.verify(workspaces).lockAuthorizationMembership(5, 1);
        order.verify(workspaces).lockActiveOwnerMembers(5);
        order.verify(roles).lockRole(5, 4);
        order.verify(roles).lockRole(5, 7);
        order.verify(roles).lockPermissions(5, 4);
        order.verify(roles).lockPermissions(5, 7);
        order.verify(mfa).requireAllowed(eq(1), eq(5), any(WorkspaceMember.class), anySet());
        order.verify(reservations).reservedUserIds(List.of(11, 13));
        verify(workspaces, never()).updateExportPermission(5, "owner", true);
    }

    @Test
    void anUnreservedBuiltInOwnerPreservesRecoveryBeforeThePolicyWrite() {
        owners(List.of(owner(11, null), owner(13, 4)), Set.of());

        service.lockBuiltInExportPolicyAuthorization(5, 1, "owner", true);
        service.updateBuiltInExportPermission(5, "owner", true);

        var order = inOrder(workspaces, roles, mfa, reservations);
        order.verify(workspaces).lockActiveOwnerMembers(5);
        order.verify(roles).lockRole(5, 4);
        order.verify(roles).lockPermissions(5, 4);
        order.verify(mfa).requireAllowed(eq(1), eq(5), any(WorkspaceMember.class), anySet());
        order.verify(reservations).reservedUserIds(List.of(11, 13));
        order.verify(workspaces).updateExportPermission(5, "owner", true);
    }

    @Test
    void aReservedBuiltInOwnerCannotBeCountedAsTheRecoveryPath() {
        owners(List.of(owner(11, null), owner(13, 4)), Set.of(11));

        assertThrows(WorkspaceRecoveryOwnerRequiredException.class,
            () -> service.lockBuiltInExportPolicyAuthorization(5, 1, "owner", true));

        verify(workspaces, never()).updateExportPermission(5, "owner", true);
    }

    @Test
    void changingMemberExportAccessDoesNotLockUnrelatedOwnerRoles() {
        scope();
        service.lockBuiltInExportPolicyAuthorization(5, 1, "member", true);
        service.updateBuiltInExportPermission(5, "member", true);

        verify(workspaces).updateExportPermission(5, "member", true);
        verifyNoInteractions(roles, reservations);
    }

    private void owners(List<WorkspaceMember> members, Set<Integer> reserved) {
        scope();
        when(workspaces.lockActiveOwnerMembers(5)).thenReturn(members);
        when(reservations.reservedUserIds(List.of(11, 13))).thenReturn(reserved);
        for (int roleId : members.stream().map(WorkspaceMember::getRoleId).filter(java.util.Objects::nonNull).distinct().toList()) {
            when(roles.lockRole(5, roleId)).thenReturn(roleId);
            when(roles.lockPermissions(5, roleId)).thenReturn(Permission.grantableNames().stream()
                .filter(name -> !name.equals("EXPORT_DATA")).toList());
        }
    }

    private void scope() {
        WorkspaceMember actor = owner(1, null);
        actor.setRole("admin");
        when(users.lockById(1)).thenReturn(1);
        when(workspaces.lockWorkspace(5)).thenReturn(5);
        when(workspaces.lockAuthorizationMembership(5, 1)).thenReturn(actor);
        when(workspaces.getExportPermissionsForShare(5)).thenReturn(new ExportPermissions(false, true, true));
        when(mfa.requestActor(Set.of(1))).thenReturn(1);
    }

    private static WorkspaceMember owner(int userId, Integer roleId) {
        WorkspaceMember member = new WorkspaceMember();
        member.setWorkspaceId(5);
        member.setUserId(userId);
        member.setRole("owner");
        member.setStatus("active");
        member.setRoleId(roleId);
        return member;
    }
}
