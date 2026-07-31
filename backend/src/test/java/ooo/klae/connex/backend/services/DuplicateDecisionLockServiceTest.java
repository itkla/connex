package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ooo.klae.connex.backend.beans.WorkspaceMember;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.OrganizationMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;

@ExtendWith(MockitoExtension.class)
class DuplicateDecisionLockServiceTest {

    @Mock private WorkspaceService workspaceService;
    @Mock private UserMapper userMapper;
    @Mock private WorkspaceMapper workspaceMapper;
    @Mock private OrganizationMapper organizationMapper;

    @Test
    void currentRequestLocksActorWorkspaceMembershipAndOrganizationInOrder() {
        DuplicateDecisionLockService service = service();
        when(workspaceService.getCurrentUserId()).thenReturn(9);
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(5);
        when(workspaceService.getCurrentOrgId()).thenReturn(3);
        when(userMapper.lockByIdForShare(9)).thenReturn(9);
        when(workspaceMapper.lockActiveWorkspaceForShare(5)).thenReturn(3);
        when(workspaceMapper.lockAuthorizationMembership(5, 9))
            .thenReturn(membership("active"));
        when(organizationMapper.lockActiveByIdForShare(3)).thenReturn(3);

        assertEquals(3, service.lockCurrentOrganization());

        InOrder order = inOrder(userMapper, workspaceMapper, organizationMapper);
        order.verify(userMapper).lockByIdForShare(9);
        order.verify(userMapper).isAccountDeletionReserved(9);
        order.verify(workspaceMapper).lockActiveWorkspaceForShare(5);
        order.verify(workspaceMapper).lockAuthorizationMembership(5, 9);
        order.verify(organizationMapper).lockActiveByIdForShare(3);
        order.verify(organizationMapper).lockDuplicateDecision(3);
    }

    @Test
    void currentRequestRejectsInactiveMembershipBeforeOrganizationLock() {
        DuplicateDecisionLockService service = service();
        when(workspaceService.getCurrentUserId()).thenReturn(9);
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(5);
        when(workspaceService.getCurrentOrgId()).thenReturn(3);
        when(userMapper.lockByIdForShare(9)).thenReturn(9);
        when(workspaceMapper.lockActiveWorkspaceForShare(5)).thenReturn(3);
        when(workspaceMapper.lockAuthorizationMembership(5, 9))
            .thenReturn(membership("pending"));

        assertThrows(ForbiddenException.class, service::lockCurrentOrganization);

        verify(organizationMapper, never()).lockActiveByIdForShare(3);
    }

    @Test
    void currentRequestRejectsAccountDeletionReservationBeforeWorkspaceLock() {
        DuplicateDecisionLockService service = service();
        when(workspaceService.getCurrentUserId()).thenReturn(9);
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(5);
        when(workspaceService.getCurrentOrgId()).thenReturn(3);
        when(userMapper.lockByIdForShare(9)).thenReturn(9);
        when(userMapper.isAccountDeletionReserved(9)).thenReturn(true);

        assertThrows(ForbiddenException.class, service::lockCurrentOrganization);

        verify(workspaceMapper, never()).lockActiveWorkspaceForShare(5);
    }

    @Test
    void currentRequestRejectsChangedOrganizationBeforeOrganizationLock() {
        DuplicateDecisionLockService service = service();
        when(workspaceService.getCurrentUserId()).thenReturn(9);
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(5);
        when(workspaceService.getCurrentOrgId()).thenReturn(3);
        when(userMapper.lockByIdForShare(9)).thenReturn(9);
        when(workspaceMapper.lockActiveWorkspaceForShare(5)).thenReturn(4);

        assertThrows(ForbiddenException.class, service::lockCurrentOrganization);

        verify(organizationMapper, never()).lockActiveByIdForShare(3);
        verify(organizationMapper, never()).lockActiveByIdForShare(4);
    }

    @Test
    void currentRequestRejectsOrganizationTeardown() {
        DuplicateDecisionLockService service = service();
        when(workspaceService.getCurrentUserId()).thenReturn(9);
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(5);
        when(workspaceService.getCurrentOrgId()).thenReturn(3);
        when(userMapper.lockByIdForShare(9)).thenReturn(9);
        when(workspaceMapper.lockActiveWorkspaceForShare(5)).thenReturn(3);
        when(workspaceMapper.lockAuthorizationMembership(5, 9))
            .thenReturn(membership("active"));
        when(organizationMapper.lockActiveByIdForShare(3)).thenReturn(null);

        assertThrows(ForbiddenException.class, service::lockCurrentOrganization);
    }

    @Test
    void sharedRecordGrantLocksBothWorkspacesAndMembershipsBeforeMutex() {
        DuplicateDecisionLockService service = service();
        when(workspaceService.getCurrentUserId()).thenReturn(9);
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(7);
        when(workspaceService.getCurrentOrgId()).thenReturn(3);
        when(userMapper.lockByIdForShare(9)).thenReturn(9);
        when(workspaceMapper.lockActiveWorkspaceForShare(5)).thenReturn(3);
        when(workspaceMapper.lockActiveWorkspaceForShare(7)).thenReturn(3);
        when(workspaceMapper.lockAuthorizationMembership(5, 9))
            .thenReturn(membership("active"));
        when(workspaceMapper.lockAuthorizationMembership(7, 9))
            .thenReturn(membership("active"));
        when(organizationMapper.lockActiveByIdForShare(3)).thenReturn(3);

        assertEquals(
            3,
            service.lockCurrentOrganizationWithMemberWorkspace(5));

        InOrder order = inOrder(userMapper, workspaceMapper, organizationMapper);
        order.verify(userMapper).lockByIdForShare(9);
        order.verify(userMapper).isAccountDeletionReserved(9);
        order.verify(workspaceMapper).lockActiveWorkspaceForShare(5);
        order.verify(workspaceMapper).lockActiveWorkspaceForShare(7);
        order.verify(workspaceMapper).lockAuthorizationMembership(5, 9);
        order.verify(workspaceMapper).lockAuthorizationMembership(7, 9);
        order.verify(organizationMapper).lockActiveByIdForShare(3);
        order.verify(organizationMapper).lockDuplicateDecision(3);
    }

    @Test
    void sharedRecordRevocationRetainsTargetWorkspaceWithoutRequiringMembership() {
        DuplicateDecisionLockService service = service();
        when(workspaceService.getCurrentUserId()).thenReturn(9);
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(7);
        when(workspaceService.getCurrentOrgId()).thenReturn(3);
        when(userMapper.lockByIdForShare(9)).thenReturn(9);
        when(workspaceMapper.lockActiveWorkspaceForShare(5)).thenReturn(3);
        when(workspaceMapper.lockActiveWorkspaceForShare(7)).thenReturn(3);
        when(workspaceMapper.lockAuthorizationMembership(7, 9))
            .thenReturn(membership("active"));
        when(organizationMapper.lockActiveByIdForShare(3)).thenReturn(3);

        assertEquals(3, service.lockCurrentOrganizationWithWorkspace(5));

        verify(workspaceMapper, never()).lockAuthorizationMembership(5, 9);
        verify(organizationMapper).lockDuplicateDecision(3);
    }

    @Test
    void backgroundWorkLocksWorkspaceBeforeOrganization() {
        DuplicateDecisionLockService service = service();
        when(workspaceMapper.lockActiveWorkspaceForShare(5)).thenReturn(3);
        when(organizationMapper.lockActiveByIdForShare(3)).thenReturn(3);

        assertEquals(3, service.lockBackgroundOrganization(5));

        InOrder order = inOrder(workspaceMapper, organizationMapper);
        order.verify(workspaceMapper).lockActiveWorkspaceForShare(5);
        order.verify(organizationMapper).lockActiveByIdForShare(3);
        order.verify(organizationMapper).lockDuplicateDecision(3);
    }

    @Test
    void backgroundWorkRejectsInactiveWorkspaceBeforeOrganizationLock() {
        DuplicateDecisionLockService service = service();
        when(workspaceMapper.lockActiveWorkspaceForShare(5)).thenReturn(null);

        assertThrows(
            ResourceNotFoundException.class,
            () -> service.lockBackgroundOrganization(5));

        verify(organizationMapper, never()).lockActiveByIdForShare(3);
    }

    @Test
    void backgroundMemberWorkRejectsAccountDeletionReservationBeforeWorkspaceLock() {
        DuplicateDecisionLockService service = service();
        when(userMapper.lockByIdForShare(9)).thenReturn(9);
        when(userMapper.isAccountDeletionReserved(9)).thenReturn(true);

        assertThrows(
            ForbiddenException.class,
            () -> service.lockBackgroundMemberOrganization(5, 9));

        verify(workspaceMapper, never()).lockActiveWorkspaceForShare(5);
    }

    private DuplicateDecisionLockService service() {
        return new DuplicateDecisionLockService(
            workspaceService,
            userMapper,
            workspaceMapper,
            organizationMapper);
    }

    private static WorkspaceMember membership(String status) {
        WorkspaceMember membership = new WorkspaceMember();
        membership.setStatus(status);
        return membership;
    }
}
