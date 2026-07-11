package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import ooo.klae.connex.backend.beans.Notification;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.dto.WorkspaceMembershipDto;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.mappers.NotificationMapper;

class MembershipLifecycleTest extends AbstractServiceTest {

    @Autowired WorkspaceService workspaceService;
    @Autowired InviteService inviteService;
    @Autowired NotificationMapper notificationMapper;

    private void seedNotification(int workspaceId, int recipientId) {
        Notification notification = new Notification();
        notification.setWorkspaceId(workspaceId);
        notification.setRecipientId(recipientId);
        notification.setType("task.assigned");
        notification.setCategory("tasks");
        notification.setSeverity("info");
        notification.setTemplateVersion(1);
        notification.setTitle("Lifecycle " + unique());
        notification.setDedupeKey("lifecycle-" + unique());
        notification.setTriggeredAt("2026-07-11 00:00:00");
        notificationMapper.upsert(notification);
    }

    private long recipientNotificationCount(int recipientId) {
        return notificationMapper.countPage(recipientId, null, null, null, null);
    }

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

    @Test
    void declineMembership_removesTheInviteNotifications() {
        WorkspaceMembershipDto ws = workspaceService.createWorkspace("Decline Notif WS", currentUser.getId());
        User invitee = newUser();
        inviteService.addExistingMember(ws.getId(), currentUser.getId(), invitee.getEmail(), "member");
        seedNotification(ws.getId(), invitee.getId());

        workspaceService.declineMembership(ws.getId(), invitee.getId());

        assertEquals(0, recipientNotificationCount(invitee.getId()));
    }

    @Test
    void removeMember_cleansNotificationsAndCollaboratorSeats() {
        User member = newUser();
        var pipeline = newPipeline();
        var deal = newDeal(pipeline, newStage(pipeline, 1), newCompany());
        dealMapper.insertCollaborators(workspace.getId(), deal.getId(), java.util.List.of(member.getId()));
        seedNotification(workspace.getId(), member.getId());

        workspaceService.removeMember(workspace.getId(), currentUser.getId(), member.getId());

        assertEquals(0, recipientNotificationCount(member.getId()));
        assertTrue(dealMapper.getCollaborators(workspace.getId(), deal.getId()).isEmpty());
    }

    @Test
    void leaveWorkspace_cleansTheLeaverContent() {
        User member = newUser();
        seedNotification(workspace.getId(), member.getId());

        workspaceService.leaveWorkspace(workspace.getId(), member.getId());

        assertEquals(0, recipientNotificationCount(member.getId()));
    }
}
