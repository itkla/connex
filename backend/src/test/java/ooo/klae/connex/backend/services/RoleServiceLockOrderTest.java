package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Set;
import java.util.function.IntSupplier;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ooo.klae.connex.backend.beans.WorkspaceRole;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.PrivilegedMfaConfinementAcknowledgementRequiredException;
import ooo.klae.connex.backend.mappers.RoleMapper;
import ooo.klae.connex.backend.tenant.Permission;

@ExtendWith(MockitoExtension.class)
class RoleServiceLockOrderTest {
    @Mock private RoleMapper roleMapper;
    @Mock private WorkspaceService workspaceService;
    @Mock private AuditService auditService;
    @Mock private SessionSecurityService sessionSecurityService;
    @Mock private PrivilegedAccountService privilegedAccountService;

    @InjectMocks private RoleService service;

    @Test
    void customRoleCreationLocksCurrentAuthorizationBeforeInsert() {
        service.createRole(7, 1, "Analyst", List.of(Permission.PERSON_CREATE.name()));

        InOrder order = inOrder(workspaceService, sessionSecurityService, roleMapper);
        order.verify(workspaceService).requirePermission(7, 1, Permission.ROLE_MANAGE);
        order.verify(sessionSecurityService).requireRecentAuthentication(1);
        order.verify(workspaceService).lockRoleCreationAuthorization(
            7, 1, Set.of(Permission.PERSON_CREATE));
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

    /**
     * A role edit reads the confinement baseline over the assignees its locks hold, before the
     * write, and checks it after the permissions are replaced and before the success audit (#1534).
     */
    @Test
    void customRoleUpdateChecksConfinementOverTheLockedAssigneesBeforeTheSuccessAudit() {
        when(workspaceService.lockRoleMutationAuthorization(7, 1, 5, Set.of(Permission.PERSON_CREATE)))
            .thenReturn(List.of(3, 4));
        when(workspaceService.getOrgId(7)).thenReturn(2);
        ConfinementBaseline baseline = new ConfinementBaseline(2, List.of(3, 4), Set.of());
        when(privilegedAccountService.confinementBaseline(any(), eq(List.of(3, 4)))).thenReturn(baseline);
        when(roleMapper.updateRoleName(7, 5, "Analyst")).thenReturn(1);

        service.updateRole(7, 1, 5, "Analyst", List.of(Permission.PERSON_CREATE.name()), true);

        ArgumentCaptor<IntSupplier> organization = ArgumentCaptor.forClass(IntSupplier.class);
        InOrder order = inOrder(workspaceService, privilegedAccountService, roleMapper, auditService);
        order.verify(workspaceService).lockRoleMutationAuthorization(7, 1, 5, Set.of(Permission.PERSON_CREATE));
        order.verify(privilegedAccountService).confinementBaseline(organization.capture(), eq(List.of(3, 4)));
        order.verify(roleMapper).updateRoleName(7, 5, "Analyst");
        order.verify(roleMapper).insertPermissions(7, 5, List.of(Permission.PERSON_CREATE.name()));
        order.verify(privilegedAccountService).requireConfinementAcknowledged(baseline, true);
        order.verify(auditService).record(
            "workspace.role.update", "workspace", 7, "Analyst", "Updated role Analyst", null);
        assertEquals(2, organization.getValue().getAsInt());
    }

    @Test
    void aRefusedRoleUpdateWritesNoSuccessAudit() {
        when(roleMapper.updateRoleName(7, 5, "Analyst")).thenReturn(1);
        doThrow(new PrivilegedMfaConfinementAcknowledgementRequiredException(2))
            .when(privilegedAccountService).requireConfinementAcknowledged(any(), eq(false));

        assertThrows(PrivilegedMfaConfinementAcknowledgementRequiredException.class,
            () -> service.updateRole(7, 1, 5, "Analyst", List.of(Permission.PERSON_CREATE.name())));

        verify(roleMapper).insertPermissions(7, 5, List.of(Permission.PERSON_CREATE.name()));
        verifyNoInteractions(auditService);
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

    @Test
    void customRoleDeletionRejectsAnAssignedRole() {
        doThrow(new BadRequestException(
            "Reassign every member using this role before deleting it"))
            .when(workspaceService).lockRoleDeletionAuthorization(7, 1, 5);

        assertThrows(BadRequestException.class, () -> service.deleteRole(7, 1, 5));

        verify(roleMapper, never()).deleteRole(7, 5);
    }
}
