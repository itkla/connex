package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import ooo.klae.connex.backend.beans.Organization;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.beans.WorkspaceInvite;
import ooo.klae.connex.backend.dto.WorkspaceMembershipDto;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.mappers.InviteMapper;
import ooo.klae.connex.backend.mappers.OrganizationMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.notifications.NotificationStateVersionService;

/** Verifies invite acceptance and revocation serialization against real MySQL transactions. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class InviteAcceptanceConcurrencyIntegrationTest {

    @Autowired private InviteService inviteService;
    @Autowired private OrganizationMapper organizationMapper;
    @Autowired private UserMapper userMapper;
    @Autowired private WorkspaceMapper workspaceMapper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private SqlSessionTemplate sqlSessionTemplate;
    @MockitoSpyBean private InviteMapper inviteMapper;
    @MockitoBean private UserOffboardingService userOffboardingService;
    @MockitoBean private NotificationStateVersionService notificationStateVersionService;
    @MockitoBean private AuditService auditService;

    private Organization organization;
    private Workspace workspace;
    private WorkspaceInvite invite;
    private User inviter;
    private User recipient;

    @BeforeEach
    void setUp() {
        String unique = UUID.randomUUID().toString().substring(0, 8);
        organization = new Organization();
        organization.setName("Invite Claim " + unique);
        organization.setSlug("invite-claim-" + unique);
        organizationMapper.insert(organization);

        workspace = new Workspace();
        workspace.setOrgId(organization.getId());
        workspace.setName("Invite Claim " + unique);
        workspace.setSlug("invite-claim-" + unique);
        workspaceMapper.insert(workspace);

        inviter = user("invite-owner-" + unique, "owner-" + unique + "@example.com");
        recipient = user("invite-recipient-" + unique, "recipient-" + unique + "@example.com");
        workspaceMapper.addMember(workspace.getId(), inviter.getId(), "owner");

        invite = new WorkspaceInvite();
        invite.setWorkspaceId(workspace.getId());
        invite.setEmail(recipient.getEmail());
        invite.setRole("member");
        invite.setToken("invite-" + unique);
        invite.setInvitedById(inviter.getId());
        inviteMapper.insert(invite);
    }

    @AfterEach
    void cleanUp() {
        if (workspace != null) {
            jdbcTemplate.update("DELETE FROM workspace_member WHERE workspace_id = ?", workspace.getId());
            jdbcTemplate.update("DELETE FROM workspace_invite WHERE workspace_id = ?", workspace.getId());
            jdbcTemplate.update("DELETE FROM workspace WHERE id = ?", workspace.getId());
        }
        if (inviter != null) {
            userMapper.delete(inviter.getId());
        }
        if (recipient != null) {
            userMapper.delete(recipient.getId());
        }
        if (organization != null) {
            jdbcTemplate.update("DELETE FROM organization WHERE id = ?", organization.getId());
        }
    }

    @Test
    void committedRevocationMakesWaitingAcceptanceFailWithoutMembership() throws Exception {
        InviteMapper realMapper = sqlSessionTemplate.getMapper(InviteMapper.class);
        CountDownLatch claimReached = new CountDownLatch(1);
        CountDownLatch releaseClaim = new CountDownLatch(1);
        doAnswer(invocation -> {
            claimReached.countDown();
            if (!releaseClaim.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Acceptance claim was not released");
            }
            return realMapper.claimAcceptance(
                invite.getId(), invite.getToken(), workspace.getId(), recipient.getId());
        }).when(inviteMapper).claimAcceptance(
            invite.getId(), invite.getToken(), workspace.getId(), recipient.getId());

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<BadRequestException> acceptance = executor.submit(() -> assertThrows(
                BadRequestException.class,
                () -> inviteService.acceptInvite(invite.getToken(), recipient)));
            assertTrue(claimReached.await(10, TimeUnit.SECONDS));

            assertEquals(1, realMapper.markRevoked(invite.getId(), workspace.getId()));
            releaseClaim.countDown();

            assertEquals(
                "This invite is no longer available",
                acceptance.get(20, TimeUnit.SECONDS).getMessage());
        } finally {
            releaseClaim.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }

        WorkspaceInvite revoked = realMapper.findByToken(invite.getToken());
        assertEquals("revoked", revoked.getStatus());
        assertNull(revoked.getAcceptedById());
        assertNull(revoked.getAcceptedAt());
        assertFalse(workspaceMapper.isMember(workspace.getId(), recipient.getId()));
        verify(userOffboardingService, never()).prepareFreshMembership(workspace.getId(), recipient.getId());
        verify(notificationStateVersionService, never()).markChanged(recipient.getId());
        verify(auditService, never()).recordScoped(
            "workspace.invite.accept", "workspace", workspace.getId(), workspace.getId(),
            organization.getId(), recipient.getDisplayName(),
            recipient.getDisplayName() + " joined via invite", null);
    }

    @Test
    void committedAcceptanceMakesWaitingRevocationLose() throws Exception {
        InviteMapper realMapper = sqlSessionTemplate.getMapper(InviteMapper.class);
        CountDownLatch inviteClaimed = new CountDownLatch(1);
        CountDownLatch releaseAcceptance = new CountDownLatch(1);
        CountDownLatch revocationAttempted = new CountDownLatch(1);
        doAnswer(invocation -> {
            int claimed = realMapper.claimAcceptance(
                invite.getId(), invite.getToken(), workspace.getId(), recipient.getId());
            inviteClaimed.countDown();
            if (!releaseAcceptance.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Claimed acceptance was not released");
            }
            return claimed;
        }).when(inviteMapper).claimAcceptance(
            invite.getId(), invite.getToken(), workspace.getId(), recipient.getId());
        doAnswer(invocation -> {
            revocationAttempted.countDown();
            return realMapper.markRevoked(invite.getId(), workspace.getId());
        }).when(inviteMapper).markRevoked(invite.getId(), workspace.getId());

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<WorkspaceMembershipDto> acceptance = executor.submit(
                () -> inviteService.acceptInvite(invite.getToken(), recipient));
            assertTrue(inviteClaimed.await(10, TimeUnit.SECONDS));

            Future<Integer> revocation = executor.submit(
                () -> inviteMapper.markRevoked(invite.getId(), workspace.getId()));
            assertTrue(revocationAttempted.await(10, TimeUnit.SECONDS));
            releaseAcceptance.countDown();

            assertEquals(workspace.getId(), acceptance.get(20, TimeUnit.SECONDS).getId());
            assertEquals(0, revocation.get(20, TimeUnit.SECONDS));
        } finally {
            releaseAcceptance.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }

        WorkspaceInvite accepted = realMapper.findByToken(invite.getToken());
        assertEquals("accepted", accepted.getStatus());
        assertEquals(recipient.getId(), accepted.getAcceptedById());
        assertNotNull(accepted.getAcceptedAt());
        assertTrue(workspaceMapper.isMember(workspace.getId(), recipient.getId()));
        verify(userOffboardingService).prepareFreshMembership(workspace.getId(), recipient.getId());
        verify(notificationStateVersionService).markChanged(recipient.getId());
        verify(auditService).recordScoped(
            "workspace.invite.accept", "workspace", workspace.getId(), workspace.getId(),
            organization.getId(), recipient.getDisplayName(),
            recipient.getDisplayName() + " joined via invite", null);
    }

    @Test
    void membershipRemovalWaitsForTheLockedAuthorizationSnapshot() throws Exception {
        workspaceMapper.addMember(workspace.getId(), recipient.getId(), "member");
        InviteMapper realMapper = sqlSessionTemplate.getMapper(InviteMapper.class);
        CountDownLatch claimReached = new CountDownLatch(1);
        CountDownLatch releaseClaim = new CountDownLatch(1);
        doAnswer(invocation -> {
            claimReached.countDown();
            if (!releaseClaim.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Acceptance claim was not released");
            }
            return realMapper.claimAcceptance(
                invite.getId(), invite.getToken(), workspace.getId(), recipient.getId());
        }).when(inviteMapper).claimAcceptance(
            invite.getId(), invite.getToken(), workspace.getId(), recipient.getId());

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<WorkspaceMembershipDto> acceptance = executor.submit(
                () -> inviteService.acceptInvite(invite.getToken(), recipient));
            assertTrue(claimReached.await(10, TimeUnit.SECONDS));

            Future<Integer> removal = executor.submit(
                () -> workspaceMapper.removeMember(workspace.getId(), recipient.getId()));
            assertThrows(TimeoutException.class, () -> removal.get(500, TimeUnit.MILLISECONDS));
            releaseClaim.countDown();

            assertEquals(workspace.getId(), acceptance.get(20, TimeUnit.SECONDS).getId());
            assertEquals(1, removal.get(20, TimeUnit.SECONDS));
        } finally {
            releaseClaim.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }

        WorkspaceInvite accepted = realMapper.findByToken(invite.getToken());
        assertEquals("accepted", accepted.getStatus());
        assertEquals(recipient.getId(), accepted.getAcceptedById());
        assertFalse(workspaceMapper.isMember(workspace.getId(), recipient.getId()));
        verify(userOffboardingService, never())
            .prepareFreshMembership(workspace.getId(), recipient.getId());
        verify(notificationStateVersionService, never()).markChanged(recipient.getId());
    }

    @Test
    void membershipAdditionWaitsForTheLockedAuthorizationSnapshot() throws Exception {
        InviteMapper realMapper = sqlSessionTemplate.getMapper(InviteMapper.class);
        CountDownLatch claimReached = new CountDownLatch(1);
        CountDownLatch releaseClaim = new CountDownLatch(1);
        doAnswer(invocation -> {
            claimReached.countDown();
            if (!releaseClaim.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Acceptance claim was not released");
            }
            return realMapper.claimAcceptance(
                invite.getId(), invite.getToken(), workspace.getId(), recipient.getId());
        }).when(inviteMapper).claimAcceptance(
            invite.getId(), invite.getToken(), workspace.getId(), recipient.getId());

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<WorkspaceMembershipDto> acceptance = executor.submit(
                () -> inviteService.acceptInvite(invite.getToken(), recipient));
            assertTrue(claimReached.await(10, TimeUnit.SECONDS));

            Future<Integer> addition = executor.submit(
                () -> workspaceMapper.addMember(workspace.getId(), recipient.getId(), "member"));
            assertThrows(TimeoutException.class, () -> addition.get(500, TimeUnit.MILLISECONDS));
            releaseClaim.countDown();

            assertEquals(workspace.getId(), acceptance.get(20, TimeUnit.SECONDS).getId());
            ExecutionException duplicate = assertThrows(
                ExecutionException.class,
                () -> addition.get(20, TimeUnit.SECONDS));
            assertTrue(duplicate.getCause() instanceof DuplicateKeyException);
        } finally {
            releaseClaim.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }

        WorkspaceInvite accepted = realMapper.findByToken(invite.getToken());
        assertEquals("accepted", accepted.getStatus());
        assertEquals(recipient.getId(), accepted.getAcceptedById());
        assertTrue(workspaceMapper.isMember(workspace.getId(), recipient.getId()));
        verify(userOffboardingService).prepareFreshMembership(workspace.getId(), recipient.getId());
        verify(notificationStateVersionService).markChanged(recipient.getId());
    }

    @Test
    void failureAfterClaimRollsAcceptanceBackToPending() {
        doThrow(new IllegalStateException("cleanup failed"))
            .when(userOffboardingService)
            .prepareFreshMembership(workspace.getId(), recipient.getId());

        assertThrows(
            IllegalStateException.class,
            () -> inviteService.acceptInvite(invite.getToken(), recipient));

        WorkspaceInvite pending = inviteMapper.findByToken(invite.getToken());
        assertEquals("pending", pending.getStatus());
        assertNull(pending.getAcceptedById());
        assertNull(pending.getAcceptedAt());
        assertFalse(workspaceMapper.isMember(workspace.getId(), recipient.getId()));
        verify(notificationStateVersionService, never()).markChanged(recipient.getId());
        verify(auditService, never()).recordScoped(
            "workspace.invite.accept", "workspace", workspace.getId(), workspace.getId(),
            organization.getId(), recipient.getDisplayName(),
            recipient.getDisplayName() + " joined via invite", null);
    }

    private User user(String username, String email) {
        User user = new User();
        user.setUsername(username);
        user.setDisplayName(username);
        user.setEmail(email);
        user.setPasswordHash("hash-" + username);
        user.setTimezone("UTC");
        userMapper.insert(user);
        return user;
    }
}
