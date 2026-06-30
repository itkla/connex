package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.dto.InvitePreviewDto;
import ooo.klae.connex.backend.dto.InviteResultDto;
import ooo.klae.connex.backend.dto.MemberDto;
import ooo.klae.connex.backend.dto.WorkspaceMembershipDto;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.mappers.NotificationMapper;

class InviteServiceTest extends AbstractServiceTest {

    @Autowired InviteService inviteService;
    @Autowired WorkspaceService workspaceService;
    @Autowired NotificationMapper notificationMapper;

    @Test
    void inviteNewEmail_returnsTokenInviteThenAccepts() {
        WorkspaceMembershipDto ws = workspaceService.createWorkspace("Invite WS", currentUser.getId());
        String email = "newcomer-" + unique() + "@example.com";

        InviteResultDto result = inviteService.createInvite(ws.getId(), currentUser, email, "member");
        assertNotNull(result.getInvite());
        assertNull(result.getMember());

        User invitee = register(email);
        assertFalse(workspaceMapper.isMember(ws.getId(), invitee.getId()));

        WorkspaceMembershipDto membership = inviteService.acceptInvite(result.getInvite().getToken(), invitee);

        assertEquals(ws.getId(), membership.getId());
        assertEquals("member", membership.getRole());
        assertTrue(workspaceMapper.isMember(ws.getId(), invitee.getId()));
    }

    @Test
    void inviteExistingUser_addsPendingMemberAndNotifies() {
        WorkspaceMembershipDto ws = workspaceService.createWorkspace("Notify WS", currentUser.getId());
        User existing = newUser();

        InviteResultDto result = inviteService.createInvite(ws.getId(), currentUser, existing.getEmail(), "admin");

        assertNull(result.getInvite());
        assertNotNull(result.getMember());
        assertEquals(existing.getId(), result.getMember().getId());
        assertEquals("admin", result.getMember().getRole());
        assertEquals("pending", result.getMember().getStatus());
        assertFalse(workspaceMapper.isMember(ws.getId(), existing.getId()));
        assertEquals(1, notificationMapper.getUnreadCounts(existing.getId()).getUnread());
    }

    @Test
    void inviteExistingUserTwice_isRejectedWhilePending() {
        WorkspaceMembershipDto ws = workspaceService.createWorkspace("Dupe WS", currentUser.getId());
        User existing = newUser();
        inviteService.createInvite(ws.getId(), currentUser, existing.getEmail(), "member");

        assertThrows(BadRequestException.class,
            () -> inviteService.createInvite(ws.getId(), currentUser, existing.getEmail(), "member"));
    }

    @Test
    void preview_reportsWorkspaceAndValidity() {
        WorkspaceMembershipDto ws = workspaceService.createWorkspace("Preview WS", currentUser.getId());
        String email = "preview-" + unique() + "@example.com";
        InviteResultDto result = inviteService.createInvite(ws.getId(), currentUser, email, "member");

        InvitePreviewDto preview = inviteService.previewInvite(result.getInvite().getToken());

        assertEquals("Preview WS", preview.getWorkspaceName());
        assertTrue(preview.isValid());
    }

    @Test
    void accept_rejectsMismatchedEmail() {
        WorkspaceMembershipDto ws = workspaceService.createWorkspace("Mismatch WS", currentUser.getId());
        User other = newUser();
        InviteResultDto result = inviteService.createInvite(ws.getId(), currentUser,
            "someone-else-" + unique() + "@example.com", "member");

        assertThrows(ForbiddenException.class,
            () -> inviteService.acceptInvite(result.getInvite().getToken(), other));
    }

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

    @Test
    void createInvite_requiresAdmin() {
        WorkspaceMembershipDto ws = workspaceService.createWorkspace("Gated WS", currentUser.getId());
        User member = newUser();
        workspaceMapper.addMember(ws.getId(), member.getId(), "member");

        assertThrows(ForbiddenException.class,
            () -> inviteService.createInvite(ws.getId(), member, "x@example.com", "member"));
    }

    @Test
    void addExistingMember_createsPendingMembership() {
        WorkspaceMembershipDto ws = workspaceService.createWorkspace("Direct WS", currentUser.getId());
        User existing = newUser();

        MemberDto added = inviteService.addExistingMember(ws.getId(), currentUser.getId(), existing.getEmail(), "admin");

        assertEquals("admin", added.getRole());
        assertEquals(existing.getId(), added.getId());
        assertEquals("pending", added.getStatus());
        assertFalse(workspaceMapper.isMember(ws.getId(), existing.getId()));
    }

    @Test
    void addExistingMember_rejectsUnknownEmail() {
        WorkspaceMembershipDto ws = workspaceService.createWorkspace("Unknown WS", currentUser.getId());

        assertThrows(BadRequestException.class,
            () -> inviteService.addExistingMember(ws.getId(), currentUser.getId(), "ghost-" + unique() + "@example.com", "member"));
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
}
