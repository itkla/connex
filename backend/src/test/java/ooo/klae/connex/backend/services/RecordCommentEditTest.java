package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import ooo.klae.connex.backend.beans.EntityReference;
import ooo.klae.connex.backend.beans.Notification;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.RecordComment;
import ooo.klae.connex.backend.beans.RecordCommentThread;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.WorkspaceRole;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.mappers.RecordCommentMapper;
import ooo.klae.connex.backend.notifications.NotificationDelivery;
import ooo.klae.connex.backend.tenant.Permission;

class RecordCommentEditTest extends AbstractServiceTest {

    @Autowired RecordCommentService recordCommentService;
    @Autowired RecordCommentMapper recordCommentMapper;
    @Autowired ReferenceService referenceService;
    @Autowired RoleService roleService;
    @Autowired WorkspaceService workspaceService;
    @Autowired JdbcTemplate jdbcTemplate;
    @MockitoSpyBean NotificationDelivery notificationDelivery;

    @Test
    void authorEditsWithinWindowWithoutChangingCreationTimeOrThreadVersion() {
        RecordCommentThread thread = newThread("Original content");
        long commentId = thread.getComments().getFirst().getId();
        RecordComment before = recordCommentMapper.getCommentById(workspace.getId(), commentId);
        assertNotNull(before);

        RecordComment edited = recordCommentService.editComment(commentId, "Edited content");

        RecordComment persisted = recordCommentMapper.getCommentById(workspace.getId(), commentId);
        RecordCommentThread persistedThread = recordCommentMapper.getThreadById(
            workspace.getId(), thread.getId());
        assertNotNull(persisted);
        assertNotNull(persistedThread);
        assertEquals("Edited content", edited.getContent());
        assertEquals("Edited content", persisted.getContent());
        assertNotNull(edited.getEditedAt());
        assertNotNull(persisted.getEditedAt());
        assertEquals(before.getCreatedAt(), persisted.getCreatedAt());
        assertEquals(thread.getVersion(), persistedThread.getVersion());
    }

    @Test
    void nonAuthorCannotEditEvenWithModerationPermission() {
        RecordCommentThread thread = newThread("Author content");
        User moderator = newUser();
        WorkspaceRole role = roleService.createRole(
            workspace.getId(),
            currentUser.getId(),
            "Comment moderator " + unique(),
            List.of(Permission.COMMENT_CREATE.name(), Permission.COMMENT_MODERATE.name()));
        workspaceService.assignCustomRole(
            workspace.getId(), currentUser.getId(), moderator.getId(), role.getId());
        authenticateAs(moderator, workspace.getId());

        assertThrows(ForbiddenException.class, () -> recordCommentService.editComment(
            thread.getComments().getFirst().getId(), "Moderator rewrite"));
    }

    @Test
    void editAfterWindowExpiryIsRejected() {
        RecordCommentThread thread = newThread("Original content");
        long commentId = thread.getComments().getFirst().getId();
        jdbcTemplate.update(
            "UPDATE record_comment SET created_at = UTC_TIMESTAMP(6) - INTERVAL 16 MINUTE "
                + "WHERE workspace_id = ? AND id = ?",
            workspace.getId(),
            commentId);

        BadRequestException exception = assertThrows(
            BadRequestException.class,
            () -> recordCommentService.editComment(commentId, "Late edit"));

        assertEquals("The edit window has closed", exception.getMessage());
    }

    @Test
    void redactedCommentCannotBeEdited() {
        RecordCommentThread thread = newThread("Original content");
        long commentId = thread.getComments().getFirst().getId();
        recordCommentService.deleteComment(commentId);

        assertThrows(
            BadRequestException.class,
            () -> recordCommentService.editComment(commentId, "Restored content"));
    }

    @Test
    void resolvedThreadRejectsCommentEdits() {
        RecordCommentThread thread = newThread("Original content");
        recordCommentService.resolve(thread.getId(), thread.getVersion());

        assertThrows(
            ConflictException.class,
            () -> recordCommentService.editComment(
                thread.getComments().getFirst().getId(), "Resolved edit"));
    }

    @Test
    void editNotifiesOnlyNewlyAddedMemberMentions() {
        User previouslyMentioned = newUser();
        User newlyMentioned = newUser();
        RecordCommentThread thread = newThread(mention("Existing", previouslyMentioned));
        long commentId = thread.getComments().getFirst().getId();
        assertEquals(1, mentionNotifications(previouslyMentioned.getId(), commentId).size());
        assertEquals(0, mentionNotifications(newlyMentioned.getId(), commentId).size());
        clearInvocations(notificationDelivery);

        recordCommentService.editComment(
            commentId,
            mention("Existing", previouslyMentioned) + " " + mention("New", newlyMentioned));

        ArgumentCaptor<Notification> notification = ArgumentCaptor.forClass(Notification.class);
        verify(notificationDelivery, times(1)).deliver(notification.capture());
        assertEquals(newlyMentioned.getId(), notification.getValue().getRecipientId());
        assertEquals("comment.mention", notification.getValue().getType());
        assertEquals(1, mentionNotifications(previouslyMentioned.getId(), commentId).size());
        assertEquals(1, mentionNotifications(newlyMentioned.getId(), commentId).size());
    }

    @Test
    void removingMentionDeletesItsReference() {
        User mentioned = newUser();
        RecordCommentThread thread = newThread(mention("Mentioned", mentioned));
        int commentId = Math.toIntExact(thread.getComments().getFirst().getId());

        RecordComment edited = recordCommentService.editComment(commentId, "Mention removed");

        List<EntityReference> references = referenceService.referencesFor(
            workspace.getId(), ReferenceService.SOURCE_COMMENT, commentId);
        assertEquals(List.of(), references);
        assertEquals(List.of(), edited.getReferences());
    }

    private RecordCommentThread newThread(String content) {
        Person person = newPerson(newCompany());
        return recordCommentService.createThread(
            "person", person.getId(), content, UUID.randomUUID().toString());
    }

    private List<Notification> mentionNotifications(int recipientId, long commentId) {
        return notificationMapper.findPage(recipientId, null, "comment", null, null, 50, 0)
            .stream()
            .filter(notification -> "comment.mention".equals(notification.getType()))
            .filter(notification -> notification.getSourceId() != null
                && notification.getSourceId() == Math.toIntExact(commentId))
            .toList();
    }

    private static String mention(String label, User user) {
        return "[" + label + "](user:" + user.getId() + ")";
    }
}
