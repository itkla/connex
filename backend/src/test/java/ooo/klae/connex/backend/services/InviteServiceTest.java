package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.WorkspaceRole;
import ooo.klae.connex.backend.dto.InvitePreviewDto;
import ooo.klae.connex.backend.dto.InviteResultDto;
import ooo.klae.connex.backend.dto.WorkspaceMembershipDto;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.mappers.NotificationMapper;
import ooo.klae.connex.backend.tenant.Permission;
import ooo.klae.connex.backend.support.OwnershipOfferTestFixtures;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.jdbc.core.JdbcTemplate;

class InviteServiceTest extends AbstractServiceTest {
    @Autowired private JdbcTemplate approvalJdbc;

    @Autowired InviteService inviteService;
    @Autowired WorkspaceService workspaceService;
    @Autowired RoleService roleService;
    @Autowired NotificationMapper notificationMapper;

    @Transactional(isolation = Isolation.READ_COMMITTED)
    @Test
    void inviteNewEmail_returnsTokenInviteThenAccepts() {
        WorkspaceMembershipDto ws = workspaceService.createWorkspace("Invite WS", currentUser.getId());
        String email = "newcomer-" + unique() + "@example.com";

        InviteResultDto result = inviteService.createInvite(ws.getId(), currentUser, email, "member");
        assertNotNull(result.getInvite());

        User invitee = register(email);
        assertFalse(workspaceMapper.isMember(ws.getId(), invitee.getId()));

        WorkspaceMembershipDto membership = inviteService.acceptInvite(result.getInvite().getToken(), invitee);

        assertEquals(ws.getId(), membership.getId());
        assertEquals("member", membership.getRole());
        assertTrue(workspaceMapper.isMember(ws.getId(), invitee.getId()));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    @Test
    void acceptInvite_revalidatesInvitersCurrentGrantAuthority() {
        tenantContext.clear();
        WorkspaceMembershipDto ws = workspaceService.createWorkspace(
            "Stored Invite Grant WS", currentUser.getId());
        authenticateAs(currentUser, ws.getId());
        approveForStepUp(currentUser);
        User recoveryOwner = newUser();
        workspaceMapper.addMember(ws.getId(), recoveryOwner.getId(), "owner");
        String email = "stored-invite-" + unique() + "@example.com";
        InviteResultDto result = inviteService.createInvite(
            ws.getId(), currentUser, email, "member");
        WorkspaceRole restricted = roleService.createRole(
            ws.getId(), currentUser.getId(), "Restricted invite creator",
            List.of(Permission.MEMBER_MANAGE.name()));
        workspaceService.assignCustomRole(
            ws.getId(), currentUser.getId(), currentUser.getId(), restricted.getId());
        User invitee = register(email);

        ForbiddenException failure = assertThrows(
            ForbiddenException.class,
            () -> inviteService.acceptInvite(result.getInvite().getToken(), invitee));
        assertEquals("You cannot grant the COMPANY_CREATE permission because you do not hold it",
            failure.getMessage());
        assertFalse(workspaceMapper.isMember(ws.getId(), invitee.getId()));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    @Test
    void inviteExistingUser_returnsOnlyTokenInvitation() {
        WorkspaceMembershipDto ws = workspaceService.createWorkspace("Notify WS", currentUser.getId());
        User existing = newUser();

        InviteResultDto result = inviteService.createInvite(ws.getId(), currentUser, existing.getEmail(), "admin");

        assertNotNull(result.getInvite());
        assertEquals(existing.getEmail(), result.getInvite().getEmail());
        assertEquals("admin", result.getInvite().getRole());
        assertEquals("pending", result.getInvite().getStatus());
        assertFalse(workspaceMapper.isMemberIncludingPending(ws.getId(), existing.getId()));
        assertEquals(0, notificationMapper.getUnreadCounts(existing.getId()).getUnread());
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    @Test
    void inviteExistingUserTwice_supersedesPendingToken() {
        WorkspaceMembershipDto ws = workspaceService.createWorkspace("Dupe WS", currentUser.getId());
        User existing = newUser();
        inviteService.createInvite(ws.getId(), currentUser, existing.getEmail(), "member");

        InviteResultDto replacement = inviteService.createInvite(ws.getId(), currentUser, existing.getEmail(), "member");
        assertNotNull(replacement.getInvite());
        assertEquals(1, inviteService.listInvites(ws.getId(), currentUser.getId()).size());
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    @Test
    void preview_reportsWorkspaceAndValidity() {
        WorkspaceMembershipDto ws = workspaceService.createWorkspace("Preview WS", currentUser.getId());
        String email = "preview-" + unique() + "@example.com";
        InviteResultDto result = inviteService.createInvite(ws.getId(), currentUser, email, "member");

        InvitePreviewDto preview = inviteService.previewInvite(result.getInvite().getToken());

        assertEquals("Preview WS", preview.getWorkspaceName());
        assertTrue(preview.isValid());
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    @Test
    void accept_rejectsMismatchedEmail() {
        WorkspaceMembershipDto ws = workspaceService.createWorkspace("Mismatch WS", currentUser.getId());
        User other = newUser();
        InviteResultDto result = inviteService.createInvite(ws.getId(), currentUser,
            "someone-else-" + unique() + "@example.com", "member");

        assertThrows(ForbiddenException.class,
            () -> inviteService.acceptInvite(result.getInvite().getToken(), other));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    @Test
    void accept_rejectsRevokedInvite() {
        WorkspaceMembershipDto ws = workspaceService.createWorkspace("Revoke WS", currentUser.getId());
        String email = "revoked-" + unique() + "@example.com";
        InviteResultDto result = inviteService.createInvite(ws.getId(), currentUser, email, "member");

        inviteService.revokeInvite(ws.getId(), result.getInvite().getId(), currentUser.getId());

        User invitee = register(email);
        assertThrows(BadRequestException.class,
            () -> inviteService.acceptInvite(result.getInvite().getToken(), invitee));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    @Test
    void createInvite_requiresAdmin() {
        WorkspaceMembershipDto ws = workspaceService.createWorkspace("Gated WS", currentUser.getId());
        User member = newUser();
        workspaceMapper.addMember(ws.getId(), member.getId(), "member");

        assertThrows(ForbiddenException.class,
            () -> inviteService.createInvite(ws.getId(), member, "x@example.com", "member"));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    @Test
    void memberManageDelegateCanInviteMemberButNotAdmin() {
        WorkspaceMembershipDto ws = workspaceService.createWorkspace(
            "Invite Ceiling WS", currentUser.getId());
        User delegate = memberManagingDelegate(ws);
        authenticateAs(delegate, ws.getId());
        approveForStepUp(delegate);

        InviteResultDto memberInvite = inviteService.createInvite(
            ws.getId(), delegate, "member-" + unique() + "@example.com", "member");
        assertNotNull(memberInvite.getInvite());
        assertThrows(
            ForbiddenException.class,
            () -> inviteService.createInvite(
                ws.getId(), delegate, "admin-" + unique() + "@example.com", "admin"));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    @Test
    void memberManageDelegateCannotAddExistingAdmin() {
        WorkspaceMembershipDto ws = workspaceService.createWorkspace(
            "Existing Invite Ceiling WS", currentUser.getId());
        User delegate = memberManagingDelegate(ws);
        User existing = newUser();
        authenticateAs(delegate, ws.getId());
        approveForStepUp(delegate);

        assertThrows(
            ForbiddenException.class,
            () -> inviteService.addExistingMember(
                ws.getId(), delegate.getId(), existing.getEmail(), "admin"));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    @Test
    void addExistingMember_returnsTokenInvitation() {
        WorkspaceMembershipDto ws = workspaceService.createWorkspace("Direct WS", currentUser.getId());
        User existing = newUser();

        InviteResultDto added = inviteService.addExistingMember(ws.getId(), currentUser.getId(), existing.getEmail(), "admin");

        assertEquals("admin", added.getInvite().getRole());
        assertEquals("pending", added.getInvite().getStatus());
        assertFalse(workspaceMapper.isMemberIncludingPending(ws.getId(), existing.getId()));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    @Test
    void addExistingMember_acceptsUnknownEmailUniformly() {
        WorkspaceMembershipDto ws = workspaceService.createWorkspace("Unknown WS", currentUser.getId());

        assertNotNull(inviteService.addExistingMember(ws.getId(), currentUser.getId(),
            "ghost-" + unique() + "@example.com", "member").getInvite());
    }

    private User register(String email) {
        String s = unique();
        User user = new User();
        user.setUsername("user_" + s);
        user.setDisplayName("User " + s);
        user.setEmail(email);
        user.setPasswordHash("hash_" + s);
        user.setTimezone("UTC");
        userMapper.insert(user);
        return user;
    }

    private User memberManagingDelegate(WorkspaceMembershipDto workspace) {
        User delegate = newUser();
        OwnershipOfferTestFixtures.approvedPasskey(approvalJdbc, delegate);
        workspaceMapper.addMember(workspace.getId(), delegate.getId(), "member");
        WorkspaceRole memberRole = workspaceService.builtInRoles().stream()
            .filter(role -> "member".equals(role.getName()))
            .findFirst()
            .orElseThrow();
        List<String> permissions = new ArrayList<>(memberRole.getPermissions());
        permissions.add(Permission.MEMBER_MANAGE.name());
        WorkspaceRole manager = roleService.createRole(
            workspace.getId(), currentUser.getId(), "Member Inviter", permissions);
        workspaceService.assignCustomRole(
            workspace.getId(), currentUser.getId(), delegate.getId(), manager.getId());
        return delegate;
    }
}
