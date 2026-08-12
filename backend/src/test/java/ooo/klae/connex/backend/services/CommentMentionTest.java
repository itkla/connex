package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import ooo.klae.connex.backend.beans.Notification;
import ooo.klae.connex.backend.beans.NotificationPreference;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.RecordCommentThread;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.mappers.PreferenceMapper;

class CommentMentionTest extends AbstractServiceTest {

    @Autowired RecordCommentService recordCommentService;
    @Autowired PreferenceMapper preferenceMapper;

    @Test
    void newCommentNotifiesMentionedMemberAndHydratesReferences() {
        User mentioned = newUser();
        Person person = newPerson(newCompany());

        RecordCommentThread thread = recordCommentService.createThread(
            "person", person.getId(), mention("Mentioned", mentioned), token());

        Notification notification = notificationMapper.findByDedupe(
            workspace.getId(),
            mentioned.getId(),
            "comment.mention:" + thread.getComments().getFirst().getId() + ":" + mentioned.getId());
        assertNotNull(notification);
        assertEquals("comment.mention", notification.getType());
        assertEquals("comment", notification.getCategory());
        assertEquals("comment", notification.getSourceType());
        assertEquals(person.getId(), notification.getContextId());
        assertEquals("person", notification.getContextType());
        assertEquals(
            "/records/contacts/" + person.getId() + "?comment="
                + thread.getComments().getFirst().getId(),
            notification.getActionUrl());
        assertEquals(1, thread.getComments().getFirst().getReferences().size());
        assertEquals(mentioned.getId(), thread.getComments().getFirst().getReferences().getFirst().getRefId());
    }

    @Test
    void actorAndNonMemberAreNeverNotified() {
        User outsider = new User();
        outsider.setUsername("outsider_" + unique());
        outsider.setDisplayName("Outsider " + unique());
        outsider.setEmail(unique() + "@example.com");
        outsider.setPasswordHash("hash_" + unique());
        outsider.setTimezone("UTC");
        userMapper.insert(outsider);
        Person person = newPerson(newCompany());

        RecordCommentThread thread = recordCommentService.createThread(
            "person",
            person.getId(),
            mention("Me", currentUser) + " " + mention("Outsider", outsider),
            token());
        long commentId = thread.getComments().getFirst().getId();

        assertTrue(notifications(currentUser.getId(), commentId).isEmpty());
        assertTrue(notifications(outsider.getId(), commentId).isEmpty());
    }

    @Test
    void disabledPreferenceSuppressesMention() {
        User mentioned = newUser();
        NotificationPreference preference = new NotificationPreference();
        preference.setUserId(mentioned.getId());
        preference.setType("comment.mention");
        preference.setChannel("in_app");
        preference.setEnabled(false);
        preferenceMapper.upsert(preference);
        Person person = newPerson(newCompany());

        RecordCommentThread thread = recordCommentService.createThread(
            "person", person.getId(), mention("Mentioned", mentioned), token());

        assertTrue(notifications(
            mentioned.getId(), thread.getComments().getFirst().getId()).isEmpty());
    }

    @Test
    void replayKeepsOneStableDedupeNotification() {
        User mentioned = newUser();
        Person person = newPerson(newCompany());
        String clientToken = token();
        RecordCommentThread created = recordCommentService.createThread(
            "person", person.getId(), mention("Mentioned", mentioned), clientToken);

        RecordCommentThread replayed = recordCommentService.createThread(
            "person", person.getId(), mention("Mentioned", mentioned), clientToken);

        long commentId = created.getComments().getFirst().getId();
        assertEquals(created.getId(), replayed.getId());
        List<Notification> notifications = notifications(mentioned.getId(), commentId);
        assertEquals(1, notifications.size());
        assertEquals(
            "comment.mention:" + commentId + ":" + mentioned.getId(),
            notifications.getFirst().getDedupeKey());
    }

    private List<Notification> notifications(int recipientId, long commentId) {
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
