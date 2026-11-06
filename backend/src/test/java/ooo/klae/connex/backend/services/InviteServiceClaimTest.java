package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.WorkspaceInvite;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.mappers.InviteMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.notifications.NotificationStateVersionService;
import ooo.klae.connex.backend.mail.MailDispatchOutcome;

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
    @Mock private FreshMembershipTransaction freshMembershipTransaction;

    @InjectMocks private InviteService inviteService;
    private boolean insideFreshTransaction;

    @BeforeEach
    void runFreshMembershipTransactionInline() {
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        when(freshMembershipTransaction.execute(anyInt(), any())).thenAnswer(invocation -> {
            insideFreshTransaction = true;
            try {
                return ((Supplier<?>) invocation.getArgument(1)).get();
            } finally {
                insideFreshTransaction = false;
            }
        });
    }

    @AfterEach
    void clearSynchronization() {
        TransactionSynchronizationManager.clear();
    }

    @Test
    void creatingInvitationNeverResolvesRecipientAccounts() {
        User actor = user("owner@example.com");
        String email = "recipient@example.com";
        when(workspaceService.getOrgId(7)).thenReturn(3);
        when(orgAllowedDomainService.isJoinAllowed(3, email)).thenReturn(true);
        WorkspaceInvite saved = pendingInvite();
        saved.setEmail(email);
        when(inviteMapper.findByTokenHash(org.mockito.ArgumentMatchers.anyString())).thenReturn(saved);

        var result = inviteService.createInvite(7, actor, email, "member");

        assertEquals(email, result.getInvite().getEmail());
        assertEquals("pending", result.getInvite().getStatus());
        assertEquals(MailDispatchOutcome.UNCONFIRMED, result.getEmailDelivery());
        verifyNoInteractions(inviteEmailService);
        verifyNoInteractions(userMapper);
        verify(workspaceService).lockInviteGrantAuthorization(7, actor.getId(), null, "member");
    }

    @ParameterizedTest
    @EnumSource(value = MailDispatchOutcome.class, names = {"ACCEPTED", "SKIPPED", "FAILED"})
    void ordinaryMailRunsAfterFreshTransactionReturnsAndReportsTheOutcome(MailDispatchOutcome outcome) {
        TransactionSynchronizationManager.setActualTransactionActive(false);
        User actor = user("owner@example.com");
        String email = "recipient@example.com";
        when(workspaceService.getOrgId(7)).thenReturn(3);
        when(orgAllowedDomainService.isJoinAllowed(3, email)).thenReturn(true);
        WorkspaceInvite saved = pendingInvite();
        saved.setEmail(email);
        when(inviteMapper.findByTokenHash(org.mockito.ArgumentMatchers.anyString())).thenReturn(saved);
        when(inviteEmailService.sendInvite(anyInt(), any(), any(), any(), any(), any()))
            .thenAnswer(invocation -> {
                assertFalse(insideFreshTransaction);
                assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
                return outcome;
            });

        var result = inviteService.createInvite(7, actor, email, "member");

        assertEquals(outcome, result.getEmailDelivery());
        verify(inviteEmailService, never()).sendInviteDeferred(anyInt(), any(), any(), any(), any(), any());
    }

    @Test
    void enclosingTransactionQueuesOnlyAfterCommitAndKeepsOutcomeUnconfirmed() {
        User actor = user("owner@example.com");
        String email = "recipient@example.com";
        when(workspaceService.getOrgId(7)).thenReturn(3);
        when(orgAllowedDomainService.isJoinAllowed(3, email)).thenReturn(true);
        WorkspaceInvite saved = pendingInvite();
        saved.setEmail(email);
        when(inviteMapper.findByTokenHash(org.mockito.ArgumentMatchers.anyString())).thenReturn(saved);

        var result = inviteService.createInvite(7, actor, email, "member");

        verifyNoInteractions(inviteEmailService);
        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);

        verify(inviteEmailService).sendInviteDeferred(anyInt(), any(), any(), any(), any(), any());
        verify(inviteEmailService, never()).sendInvite(anyInt(), any(), any(), any(), any(), any());
        assertEquals(MailDispatchOutcome.UNCONFIRMED, result.getEmailDelivery());
    }

    @Test
    void unexpectedPostCommitMailFailureKeepsTheCreatedInvitationUsable() {
        TransactionSynchronizationManager.setActualTransactionActive(false);
        User actor = user("owner@example.com");
        String email = "recipient@example.com";
        when(workspaceService.getOrgId(7)).thenReturn(3);
        when(orgAllowedDomainService.isJoinAllowed(3, email)).thenReturn(true);
        WorkspaceInvite saved = pendingInvite();
        saved.setEmail(email);
        when(inviteMapper.findByTokenHash(org.mockito.ArgumentMatchers.anyString())).thenReturn(saved);
        when(inviteEmailService.sendInvite(anyInt(), any(), any(), any(), any(), any()))
            .thenThrow(new IllegalStateException("Sender unavailable"));

        var result = inviteService.createInvite(7, actor, email, "member");

        assertEquals(MailDispatchOutcome.FAILED, result.getEmailDelivery());
        assertEquals(email, result.getInvite().getEmail());
        assertEquals("pending", result.getInvite().getStatus());
        verifyNoInteractions(userMapper);
    }

    @Test
    void rolledBackInvitationNeverSendsMail() {
        User actor = user("owner@example.com");
        String email = "recipient@example.com";
        when(workspaceService.getOrgId(7)).thenReturn(3);
        when(orgAllowedDomainService.isJoinAllowed(3, email)).thenReturn(true);
        WorkspaceInvite saved = pendingInvite();
        saved.setEmail(email);
        when(inviteMapper.findByTokenHash(org.mockito.ArgumentMatchers.anyString())).thenReturn(saved);

        inviteService.createInvite(7, actor, email, "member");
        TransactionSynchronizationManager.getSynchronizations()
            .forEach(synchronization -> synchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));

        verifyNoInteractions(inviteEmailService);
    }

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
        verify(workspaceMapper, never()).lockAuthorizationMembership(invite.getWorkspaceId(), user.getId());
        verify(workspaceMapper, never()).addMember(invite.getWorkspaceId(), user.getId(), invite.getRole());
        verify(workspaceMapper, never())
            .getMembershipForUserForShare(invite.getWorkspaceId(), user.getId());
        verifyNoInteractions(userOffboardingService, notificationStateVersionService, auditService);
    }

    @Test
    void revokedStoredGrantStopsBeforeInviteClaim() {
        WorkspaceInvite invite = pendingInvite();
        User user = user("recipient@example.com");
        when(inviteMapper.findByToken(invite.getToken())).thenReturn(invite);
        when(inviteMapper.isRedeemable(invite.getToken())).thenReturn(true);
        doThrow(new ForbiddenException("Grant exceeds the creator's current permissions"))
            .when(workspaceService)
            .lockPersistedInviteGrantAuthorization(
                invite.getWorkspaceId(), invite.getInvitedById(), user.getId(), invite.getRole());

        assertThrows(
            ForbiddenException.class,
            () -> inviteService.acceptInvite(invite.getToken(), user));

        verify(inviteMapper, never()).claimAcceptance(
            invite.getId(), invite.getToken(), invite.getWorkspaceId(), user.getId());
        verifyNoInteractions(userOffboardingService, notificationStateVersionService, auditService);
    }

    @Test
    void lockedGrantAuthorizationStopsBeforeSupersedeAndPendingInsertion() {
        User actor = user("owner@example.com");
        User existing = user("recipient@example.com");
        existing.setId(10);
        when(workspaceService.getOrgId(7)).thenReturn(3);
        when(orgAllowedDomainService.isJoinAllowed(3, existing.getEmail())).thenReturn(true);
        doThrow(new BadRequestException(
            "That person is already a member of or invited to this workspace"))
            .when(workspaceService)
            .lockInviteGrantAuthorization(7, actor.getId(), null, "member");

        BadRequestException exception = assertThrows(
            BadRequestException.class,
            () -> inviteService.createInvite(7, actor, existing.getEmail(), "member"));

        assertEquals(
            "That person is already a member of or invited to this workspace",
            exception.getMessage());
        verify(inviteMapper, never()).revokePendingForEmail(7, existing.getEmail());
        verify(workspaceService, never()).addPendingMember(7, actor, existing, "member");
        verifyNoInteractions(userOffboardingService, notificationStateVersionService, auditService);
    }

    @Test
    void deniedGrantStopsBeforeInviteSideEffects() {
        User actor = user("delegate@example.com");
        String email = "recipient@example.com";
        when(workspaceService.getOrgId(7)).thenReturn(3);
        when(orgAllowedDomainService.isJoinAllowed(3, email)).thenReturn(true);
        doThrow(new ForbiddenException("Grant exceeds the actor's permissions"))
            .when(workspaceService)
            .lockInviteGrantAuthorization(7, actor.getId(), null, "admin");

        assertThrows(
            ForbiddenException.class,
            () -> inviteService.createInvite(7, actor, email, "admin"));

        verify(inviteMapper, never()).revokePendingForEmail(7, email);
        verify(inviteMapper, never()).insertHashed(any(WorkspaceInvite.class));
        verifyNoInteractions(auditService, inviteEmailService);
    }

    @Test
    void lockedUserEmailMustStillMatchBeforeClaim() {
        WorkspaceInvite invite = pendingInvite();
        User initialUser = user("recipient@example.com");
        User lockedUser = user("changed@example.com");
        stubPreliminaryValidation(invite, lockedUser);
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
        invite.setInvitedById(1);
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
