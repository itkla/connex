package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.dto.InviteDto;
import ooo.klae.connex.backend.dto.InvitePreviewDto;
import ooo.klae.connex.backend.dto.MemberDto;
import ooo.klae.connex.backend.dto.WorkspaceMembershipDto;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ForbiddenException;

class InviteServiceTest extends AbstractServiceTest {

    @Autowired InviteService inviteService;
    @Autowired WorkspaceService workspaceService;

    @Test
    void inviteThenAccept_joinsTheWorkspace() {
        WorkspaceMembershipDto ws = workspaceService.createWorkspace("Invite WS", currentUser.getId());
        User invitee = newUser();

        InviteDto invite = inviteService.createInvite(ws.getId(), currentUser, invitee.getEmail(), "member");
        assertFalse(workspaceMapper.isMember(ws.getId(), invitee.getId()));

        WorkspaceMembershipDto membership = inviteService.acceptInvite(invite.getToken(), invitee);

        assertEquals(ws.getId(), membership.getId());
        assertEquals("member", membership.getRole());
        assertTrue(workspaceMapper.isMember(ws.getId(), invitee.getId()));
    }

    @Test
    void preview_reportsWorkspaceAndValidity() {
        WorkspaceMembershipDto ws = workspaceService.createWorkspace("Preview WS", currentUser.getId());
        User invitee = newUser();
        InviteDto invite = inviteService.createInvite(ws.getId(), currentUser, invitee.getEmail(), "member");

        InvitePreviewDto preview = inviteService.previewInvite(invite.getToken());

        assertEquals("Preview WS", preview.getWorkspaceName());
        assertTrue(preview.isValid());
    }

    @Test
    void accept_rejectsMismatchedEmail() {
        WorkspaceMembershipDto ws = workspaceService.createWorkspace("Mismatch WS", currentUser.getId());
        User other = newUser();
        InviteDto invite = inviteService.createInvite(ws.getId(), currentUser, "someone-else@example.com", "member");

        assertThrows(ForbiddenException.class, () -> inviteService.acceptInvite(invite.getToken(), other));
    }

    @Test
    void accept_rejectsRevokedInvite() {
        WorkspaceMembershipDto ws = workspaceService.createWorkspace("Revoke WS", currentUser.getId());
        User invitee = newUser();
        InviteDto invite = inviteService.createInvite(ws.getId(), currentUser, invitee.getEmail(), "member");

        inviteService.revokeInvite(ws.getId(), invite.getId(), currentUser.getId());

        assertThrows(BadRequestException.class, () -> inviteService.acceptInvite(invite.getToken(), invitee));
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
    void addExistingMember_addsByEmail() {
        WorkspaceMembershipDto ws = workspaceService.createWorkspace("Direct WS", currentUser.getId());
        User existing = newUser();

        MemberDto added = inviteService.addExistingMember(ws.getId(), currentUser.getId(), existing.getEmail(), "admin");

        assertEquals("admin", added.getRole());
        assertEquals(existing.getId(), added.getId());
        assertTrue(workspaceMapper.isMember(ws.getId(), existing.getId()));
    }

    @Test
    void addExistingMember_rejectsUnknownEmail() {
        WorkspaceMembershipDto ws = workspaceService.createWorkspace("Unknown WS", currentUser.getId());

        assertThrows(BadRequestException.class,
            () -> inviteService.addExistingMember(ws.getId(), currentUser.getId(), "ghost-" + unique() + "@example.com", "member"));
    }
}
