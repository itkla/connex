package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import ooo.klae.connex.backend.beans.Notification;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.RecordComment;
import ooo.klae.connex.backend.beans.RecordCommentThread;
import ooo.klae.connex.backend.beans.User;

class CommentReplyNotificationTest extends AbstractServiceTest {

    @Autowired RecordCommentService recordCommentService;

    @Test
    void replyNotifiesDistinctParticipantsWithMentionPrecedence() {
        Person person = newPerson(newCompany());
        User redactedParticipant = newUser();
        User actor = newUser();
        RecordCommentThread thread = recordCommentService.createThread(
            "person", person.getId(), "Root", token());

        authenticateAs(redactedParticipant, workspace.getId());
        RecordComment redacted = recordCommentService.reply(thread.getId(), "Participant reply", token());
        recordCommentService.deleteComment(redacted.getId());

        authenticateAs(actor, workspace.getId());
        RecordComment reply = recordCommentService.reply(
            thread.getId(), "Please review " + mention("Root author", currentUser), token());

        Notification mention = notificationMapper.findByDedupe(
            workspace.getId(),
            currentUser.getId(),
            "comment.mention:" + reply.getId() + ":" + currentUser.getId());
        Notification duplicateReply = notificationMapper.findByDedupe(
            workspace.getId(),
            currentUser.getId(),
            "comment.reply:" + reply.getId() + ":" + currentUser.getId());
        Notification participantReply = notificationMapper.findByDedupe(
            workspace.getId(),
            redactedParticipant.getId(),
            "comment.reply:" + reply.getId() + ":" + redactedParticipant.getId());
        Notification actorReply = notificationMapper.findByDedupe(
            workspace.getId(),
            actor.getId(),
            "comment.reply:" + reply.getId() + ":" + actor.getId());

        assertNotNull(mention);
        assertNull(duplicateReply);
        assertNotNull(participantReply);
        assertEquals("comment.reply", participantReply.getType());
        assertNull(actorReply);
    }

    private static String mention(String label, User user) {
        return "[" + label + "](user:" + user.getId() + ")";
    }

    private static String token() {
        return UUID.randomUUID().toString();
    }
}
