package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.WorkspaceInvite;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.mappers.InviteMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.notifications.NotificationStateVersionService;

@ExtendWith(MockitoExtension.class)
class InviteServiceClaimTest {

    @Mock private InviteMapper inviteMapper;
    @Mock private WorkspaceMapper workspaceMapper;
    @Mock private UserOffboardingService userOffboardingService;
    @Mock private UserMapper userMapper;
    @Mock private WorkspaceService workspaceService;
    @Mock private OrgAllowedDomainService orgAllowedDomainService;
    @Mock private AuditService auditService;
    @Mock private InviteEmailService inviteEmailService;
    @Mock private SessionSecurityService sessionSecurityService;
    @Mock private NotificationStateVersionService notificationStateVersionService;

    @InjectMocks private InviteService inviteService;

    @Test
    void lostClaimStopsBeforeEveryMembershipSideEffect() {
        WorkspaceInvite invite = pendingInvite();
        User user = user("recipient@example.com");
        stubPreliminaryValidation(invite, user);
        when(userMapper.getUserByIdForShare(user.getId())).thenReturn(user);
        when(workspaceMapper.lockWorkspaceForShare(invite.getWorkspaceId()))
            .thenReturn(invite.getWorkspaceId());
        when(inviteMapper.claimAcceptance(
                invite.getId(), invite.getToken(), invite.getWorkspaceId(), user.getId()))
            .thenReturn(0);

        BadRequestException exception = assertThrows(
            BadRequestException.class,
            () -> inviteService.acceptInvite(invite.getToken(), user));

        assertEquals("This invite is no longer available", exception.getMessage());
        InOrder order = inOrder(userMapper, workspaceMapper, inviteMapper);
        order.verify(userMapper).getUserByIdForShare(user.getId());
        order.verify(workspaceMapper).lockWorkspaceForShare(invite.getWorkspaceId());
        order.verify(inviteMapper).claimAcceptance(
            invite.getId(), invite.getToken(), invite.getWorkspaceId(), user.getId());
        verify(workspaceMapper, never()).isMember(invite.getWorkspaceId(), user.getId());
        verify(workspaceMapper, never()).addMember(invite.getWorkspaceId(), user.getId(), invite.getRole());
        verify(workspaceMapper, never()).getMembershipsForUser(user.getId());
        verifyNoInteractions(userOffboardingService, notificationStateVersionService, auditService);
    }

    @Test
    void lockedUserEmailMustStillMatchBeforeClaim() {
        WorkspaceInvite invite = pendingInvite();
        User initialUser = user("recipient@example.com");
        User lockedUser = user("changed@example.com");
        stubPreliminaryValidation(invite, initialUser);
        when(userMapper.getUserByIdForShare(initialUser.getId())).thenReturn(lockedUser);

        assertThrows(
            ForbiddenException.class,
            () -> inviteService.acceptInvite(invite.getToken(), initialUser));

        verify(workspaceMapper, never()).lockWorkspaceForShare(invite.getWorkspaceId());
        verify(inviteMapper, never()).claimAcceptance(
            invite.getId(), invite.getToken(), invite.getWorkspaceId(), initialUser.getId());
        verifyNoInteractions(userOffboardingService, notificationStateVersionService, auditService);
    }

    private void stubPreliminaryValidation(WorkspaceInvite invite, User user) {
        when(inviteMapper.findByToken(invite.getToken())).thenReturn(invite);
        when(inviteMapper.isRedeemable(invite.getToken())).thenReturn(true);
        when(workspaceService.getOrgId(invite.getWorkspaceId())).thenReturn(3);
        when(orgAllowedDomainService.isJoinAllowed(3, user.getEmail())).thenReturn(true);
    }

    private static WorkspaceInvite pendingInvite() {
        WorkspaceInvite invite = new WorkspaceInvite();
        invite.setId(12);
        invite.setWorkspaceId(7);
        invite.setEmail("recipient@example.com");
        invite.setRole("member");
        invite.setToken("invite-token");
        invite.setStatus("pending");
        return invite;
    }

    private static User user(String email) {
        User user = new User();
        user.setId(9);
        user.setEmail(email);
        user.setDisplayName("Recipient");
        return user;
    }
}
