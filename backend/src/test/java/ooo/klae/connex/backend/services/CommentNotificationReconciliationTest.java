package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import ooo.klae.connex.backend.beans.Notification;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.RecordCommentThread;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.dto.WorkspaceMembershipDto;
import ooo.klae.connex.backend.tenant.TenantContext;

class CommentNotificationReconciliationTest extends AbstractServiceTest {

    @Autowired RecordCommentService recordCommentService;
    @Autowired NotificationReconciliationService reconciliationService;
    @Autowired ShareService shareService;
    @Autowired WorkspaceService workspaceService;
    @Autowired TenantContext localTenantContext;

    @Test
    void redactedCommentNotificationIsNoLongerAccessible() {
        User recipient = newUser();
        Person person = newPerson(newCompany());
        RecordCommentThread thread = recordCommentService.createThread(
            "person", person.getId(), mention("Recipient", recipient), token());
        long commentId = thread.getComments().getFirst().getId();
        assertEquals(1, commentNotifications(recipient.getId(), commentId).size());

        recordCommentService.deleteComment(commentId);
        reconciliationService.reconcileSourcePayloads(workspace.getId());

        assertTrue(commentNotifications(recipient.getId(), commentId).isEmpty());
        Notification reconciled = notificationMapper.findByDedupe(
            workspace.getId(),
            recipient.getId(),
            "comment.mention:" + commentId + ":" + recipient.getId());
        assertNotNull(reconciled);
        assertNotNull(reconciled.getResolvedAt());
    }

    @Test
    void resolvingThreadKeepsNotificationForAnUnredactedVisibleComment() {
        User recipient = newUser();
        Person person = newPerson(newCompany());
        RecordCommentThread thread = recordCommentService.createThread(
            "person", person.getId(), mention("Recipient", recipient), token());
        long commentId = thread.getComments().getFirst().getId();

        recordCommentService.resolve(thread.getId(), 0);
        reconciliationService.reconcileSourcePayloads(workspace.getId());

        assertEquals(1, commentNotifications(recipient.getId(), commentId).size());
        Notification surviving = notificationMapper.findByDedupe(
            workspace.getId(),
            recipient.getId(),
            "comment.mention:" + commentId + ":" + recipient.getId());
        assertNotNull(surviving);
        assertNull(surviving.getResolvedAt());
    }

    @Test
    void unsharingCrossWorkspaceTargetRemovesGranteeCommentNotification() {
        WorkspaceMembershipDto ownerWorkspace = workspaceService.createWorkspace(
            "Comment owner " + unique(), currentUser.getId());
        WorkspaceMembershipDto granteeWorkspace = createSiblingWorkspace(
            ownerWorkspace, "Comment grantee " + unique());
        Person person = new Person();
        person.setWorkspaceId(ownerWorkspace.getId());
        person.setName("Shared person " + unique());
        personMapper.insert(person);
        User recipient = newUserInWorkspace(granteeWorkspace.getId());

        authenticateAs(currentUser, ownerWorkspace.getId());
        shareService.share("person", person.getId(), granteeWorkspace.getId(), false);
        authenticateAs(currentUser, granteeWorkspace.getId());
        RecordCommentThread thread = recordCommentService.createThread(
            "person", person.getId(), mention("Recipient", recipient), token());
        long commentId = thread.getComments().getFirst().getId();
        assertEquals(1, commentNotifications(recipient.getId(), commentId).size());

        authenticateAs(currentUser, ownerWorkspace.getId());
        shareService.unshare("person", person.getId(), granteeWorkspace.getId());
        authenticateAs(currentUser, granteeWorkspace.getId());
        reconciliationService.reconcileSourcePayloads(granteeWorkspace.getId());

        assertTrue(commentNotifications(recipient.getId(), commentId).isEmpty());
        Notification reconciled = notificationMapper.findByDedupe(
            granteeWorkspace.getId(),
            recipient.getId(),
            "comment.mention:" + commentId + ":" + recipient.getId());
        assertNotNull(reconciled);
        assertNotNull(reconciled.getResolvedAt());
    }

    private WorkspaceMembershipDto createSiblingWorkspace(
            WorkspaceMembershipDto first,
            String name) {
        localTenantContext.set(
            first.getId(),
            workspaceService.getOrgId(first.getId()),
            currentUser.getId(),
            "owner",
            null);
        WorkspaceMembershipDto sibling = workspaceService.createWorkspace(name, currentUser.getId());
        authenticateAs(currentUser, first.getId());
        return sibling;
    }

    private User newUserInWorkspace(int workspaceId) {
        User user = new User();
        user.setUsername("comment_" + unique());
        user.setDisplayName("Comment user " + unique());
        user.setEmail(unique() + "@example.com");
        user.setPasswordHash("hash_" + unique());
        user.setTimezone("UTC");
        userMapper.insert(user);
        workspaceMapper.addMember(workspaceId, user.getId(), "member");
        return user;
    }

    private List<Notification> commentNotifications(int recipientId, long commentId) {
        return notificationMapper.findPage(recipientId, null, "comment", null, null, 50, 0)
            .stream()
            .filter(notification -> notification.getSourceId() != null
                && notification.getSourceId() == Math.toIntExact(commentId))
            .toList();
    }

    private static String mention(String label, User user) {
        return "[" + label + "](user:" + user.getId() + ")";
    }

    private static String token() {
        return UUID.randomUUID().toString();
    }
}
