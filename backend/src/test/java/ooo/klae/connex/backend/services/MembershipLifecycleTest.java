package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.dto.WorkspaceMembershipDto;
import ooo.klae.connex.backend.exceptions.BadRequestException;

class MembershipLifecycleTest extends AbstractServiceTest {

    @Autowired WorkspaceService workspaceService;
    @Autowired InviteService inviteService;

    @Test
    void addExistingMember_createsPendingMembershipWithNoAccess() {
        WorkspaceMembershipDto ws = workspaceService.createWorkspace("Pending WS", currentUser.getId());
        User invitee = newUser();

        inviteService.addExistingMember(ws.getId(), currentUser.getId(), invitee.getEmail(), "member");

        assertFalse(workspaceMapper.isMember(ws.getId(), invitee.getId()));
        assertTrue(workspaceService.pendingMemberships(invitee.getId()).stream().anyMatch(m -> m.getId() == ws.getId()));
        assertTrue(workspaceService.permissionsFor(ws.getId(), invitee.getId()).isEmpty());
    }

    @Test
    void approveMembership_activatesMember() {
        WorkspaceMembershipDto ws = workspaceService.createWorkspace("Approve WS", currentUser.getId());
        User invitee = newUser();
        inviteService.addExistingMember(ws.getId(), currentUser.getId(), invitee.getEmail(), "member");

        workspaceService.approveMembership(ws.getId(), invitee.getId());

        assertTrue(workspaceMapper.isMember(ws.getId(), invitee.getId()));
        assertTrue(workspaceService.pendingMemberships(invitee.getId()).isEmpty());
    }

    @Test
    void declineMembership_removesPendingRow() {
        WorkspaceMembershipDto ws = workspaceService.createWorkspace("Decline WS", currentUser.getId());
        User invitee = newUser();
        inviteService.addExistingMember(ws.getId(), currentUser.getId(), invitee.getEmail(), "member");

        workspaceService.declineMembership(ws.getId(), invitee.getId());

        assertNull(workspaceMapper.getMember(ws.getId(), invitee.getId()));
    }

    @Test
    void leaveWorkspace_removesActiveMember() {
        WorkspaceMembershipDto ws = workspaceService.createWorkspace("Leave WS", currentUser.getId());
        User member = newUser();
        workspaceMapper.addMember(ws.getId(), member.getId(), "member");

        workspaceService.leaveWorkspace(ws.getId(), member.getId());

        assertFalse(workspaceMapper.isMember(ws.getId(), member.getId()));
    }

    @Test
    void leaveWorkspace_lastOwnerCannotLeave() {
        WorkspaceMembershipDto ws = workspaceService.createWorkspace("Solo WS", currentUser.getId());
        assertThrows(BadRequestException.class,
            () -> workspaceService.leaveWorkspace(ws.getId(), currentUser.getId()));
    }
}
