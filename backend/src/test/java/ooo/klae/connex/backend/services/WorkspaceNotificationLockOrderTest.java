package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ooo.klae.connex.backend.dto.MemberDto;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.mappers.NotificationMapper;
import ooo.klae.connex.backend.mappers.OrganizationMapper;
import ooo.klae.connex.backend.mappers.RoleMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.notifications.NotificationDelivery;
import ooo.klae.connex.backend.notifications.NotificationStateVersionService;
import ooo.klae.connex.backend.tenant.TenantContext;

@ExtendWith(MockitoExtension.class)
class WorkspaceNotificationLockOrderTest {
    @Mock private WorkspaceMapper workspaceMapper;
    @Mock private OrganizationMapper organizationMapper;
    @Mock private OrgMemberService orgMemberService;
    @Mock private OrgAllowedDomainService orgAllowedDomainService;
    @Mock private RoleMapper roleMapper;
    @Mock private NotificationMapper notificationMapper;
    @Mock private UserOffboardingService userOffboardingService;
    @Mock private NotificationDelivery notificationDelivery;
    @Mock private NotificationStateVersionService stateVersionService;
    @Mock private TenantContext tenantContext;
    @Mock private AuditService auditService;
    @Mock private SystemActor systemActor;
    @Mock private SessionSecurityService sessionSecurityService;

    @InjectMocks private WorkspaceService service;

    @Test
    void ownerReferenceUsesCurrentLockingMembershipValidation() {
        when(workspaceMapper.lockActiveMember(7, 9)).thenReturn(9);

        service.lockAndRequireMember(7, 9);

        verify(workspaceMapper).lockActiveMember(7, 9);
        verifyNoInteractions(notificationMapper);
    }

    @Test
    void ownerReferenceRejectsInactiveMembershipUnderLock() {
        when(workspaceMapper.lockActiveMember(7, 9)).thenReturn(null);

        assertThrows(ForbiddenException.class, () -> service.lockAndRequireMember(7, 9));

        verify(workspaceMapper).lockActiveMember(7, 9);
        verifyNoInteractions(notificationMapper);
    }

    @Test
    void declineLocksMembershipBeforeNotificationDeleteAndStateChange() {
        MemberDto pending = new MemberDto();
        pending.setStatus("pending");
        when(workspaceMapper.getMember(7, 9)).thenReturn(pending);

        service.declineMembership(7, 9);

        InOrder order = inOrder(notificationMapper, workspaceMapper, stateVersionService);
        order.verify(notificationMapper).lockRecipientMemberships(9);
        order.verify(notificationMapper).deleteAllForRecipient(7, 9);
        order.verify(workspaceMapper).removeMember(7, 9);
        order.verify(stateVersionService).markChanged(9);
    }

    @Test
    void ownerLeaveLocksWorkspaceAndMembershipsBeforeOwnerRowsAndNotifications() {
        when(workspaceMapper.getRole(7, 9)).thenReturn("owner");
        when(workspaceMapper.workspaceIdsOwnedBy(9)).thenReturn(List.of(3, 7));
        when(workspaceMapper.lockOwnerIds(7)).thenReturn(List.of(1, 9));

        service.leaveWorkspace(7, 9);

        InOrder order = inOrder(
            workspaceMapper, notificationMapper, userOffboardingService, stateVersionService);
        order.verify(workspaceMapper).lockWorkspace(3);
        order.verify(workspaceMapper).lockWorkspace(7);
        order.verify(notificationMapper).lockRecipientMemberships(9);
        order.verify(workspaceMapper).lockOwnerIds(7);
        order.verify(userOffboardingService).detachMemberContent(7, 9);
        order.verify(workspaceMapper).removeMember(7, 9);
        order.verify(stateVersionService).markChanged(9);
    }

    @Test
    void ownerRemovalLocksWorkspaceAndMembershipsBeforeOwnerRowsAndNotifications() {
        MemberDto target = new MemberDto();
        target.setDisplayName("Target");
        target.setRole("owner");
        when(workspaceMapper.getMember(7, 9)).thenReturn(target);
        when(workspaceMapper.getMemberRoleId(7, 1)).thenReturn(null);
        when(workspaceMapper.getRole(7, 1)).thenReturn("owner");
        when(workspaceMapper.workspaceIdsOwnedBy(9)).thenReturn(List.of(3, 7));
        when(workspaceMapper.lockOwnerIds(7)).thenReturn(List.of(1, 9));

        service.removeMember(7, 1, 9);

        InOrder order = inOrder(
            workspaceMapper, notificationMapper, userOffboardingService, stateVersionService);
        order.verify(workspaceMapper).lockWorkspace(3);
        order.verify(workspaceMapper).lockWorkspace(7);
        order.verify(notificationMapper).lockRecipientMemberships(9);
        order.verify(workspaceMapper).lockOwnerIds(7);
        order.verify(userOffboardingService).detachMemberContent(7, 9);
        order.verify(workspaceMapper).removeMember(7, 9);
        order.verify(stateVersionService).markChanged(9);
    }

    @Test
    void accountGuardLocksOwnedWorkspacesAndMembershipsBeforeOwnerRows() {
        when(workspaceMapper.workspaceIdsOwnedBy(9)).thenReturn(List.of(3, 7));
        when(workspaceMapper.lockOwnerIds(3)).thenReturn(List.of(9, 11));
        when(workspaceMapper.lockOwnerIds(7)).thenReturn(List.of(9, 12));

        service.assertNotSoleOwnerOfAnyWorkspace(9);

        InOrder order = inOrder(workspaceMapper, notificationMapper);
        order.verify(workspaceMapper).workspaceIdsOwnedBy(9);
        order.verify(workspaceMapper).lockWorkspace(3);
        order.verify(workspaceMapper).lockWorkspace(7);
        order.verify(notificationMapper).lockRecipientMemberships(9);
        order.verify(workspaceMapper).lockOwnerIds(3);
        order.verify(workspaceMapper).lockOwnerIds(7);
    }

    @Test
    void ownerDemotionUsesTheSameWorkspaceMembershipOwnerOrder() {
        MemberDto target = new MemberDto();
        target.setDisplayName("Target");
        target.setRole("owner");
        when(workspaceMapper.getMember(7, 9)).thenReturn(target);
        when(workspaceMapper.getMemberRoleId(7, 1)).thenReturn(null);
        when(workspaceMapper.getRole(7, 1)).thenReturn("owner");
        when(workspaceMapper.workspaceIdsOwnedBy(9)).thenReturn(List.of(3, 7));
        when(workspaceMapper.lockOwnerIds(7)).thenReturn(List.of(1, 9));

        service.changeMemberRole(7, 1, 9, "member");

        InOrder order = inOrder(workspaceMapper, notificationMapper);
        order.verify(workspaceMapper).lockWorkspace(3);
        order.verify(workspaceMapper).lockWorkspace(7);
        order.verify(notificationMapper).lockRecipientMemberships(9);
        order.verify(workspaceMapper).lockOwnerIds(7);
        order.verify(workspaceMapper).updateMemberRole(7, 9, "member");
    }
}
