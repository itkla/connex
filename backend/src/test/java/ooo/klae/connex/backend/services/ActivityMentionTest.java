package ooo.klae.connex.backend.services;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import ooo.klae.connex.backend.beans.Activity;
import ooo.klae.connex.backend.beans.Notification;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.mappers.NotificationMapper;

class ActivityMentionTest extends AbstractServiceTest {

    @Autowired ActivityService activityService;
    @Autowired ReferenceService referenceService;
    @Autowired NotificationMapper notificationMapper;
    @Autowired PersonService personService;

    private String mention(String label, User user) {
        return "[" + label + "](user:" + user.getId() + ")";
    }

    private Activity draft(String notes) {
        Activity activity = new Activity();
        activity.setType("call");
        activity.setSubject("Subject " + unique());
        activity.setNotes(notes);
        return activity;
    }

    private List<Notification> mentions(int recipientId) {
        return notificationMapper.findPage(recipientId, null, "activity", null, null, 50, 0)
            .stream().filter(n -> "activity.mention".equals(n.getType())).toList();
    }

    /**
     * Creating an activity that mentions a member dispatches an activity.mention notification.
     */
    @Test
    void create_withMention_emitsNotificationToMentionedMember() {
        User mentioned = newUser();
        Activity created = activityService.create(draft("Discussed with " + mention("Mentioned", mentioned)));

        List<Notification> notifications = mentions(mentioned.getId());
        assertEquals(1, notifications.size());
        Notification notification = notifications.get(0);
        assertEquals("activity.mention", notification.getType());
        assertEquals("activity", notification.getCategory());
        assertEquals(currentUser.getId(), notification.getActorId());
        assertEquals("activity", notification.getSourceType());
        assertEquals(created.getId(), notification.getSourceId());
        assertEquals("activity.mention:" + created.getId() + ":" + mentioned.getId(), notification.getDedupeKey());
    }

    /**
     * The created activity is returned with its resolved references hydrated.
     */
    @Test
    void create_returnsActivityWithResolvedReferences() {
        User mentioned = newUser();
        Activity created = activityService.create(draft(mention("Mentioned", mentioned)));

        assertNotNull(created.getReferences());
        assertEquals(1, created.getReferences().size());
        assertEquals("user", created.getReferences().get(0).getRefType());
        assertEquals(mentioned.getId(), created.getReferences().get(0).getRefId());
    }

    /**
     * The author is never notified for mentioning themselves.
     */
    @Test
    void create_selfMention_doesNotNotify() {
        activityService.create(draft("reminder for " + mention("Me", currentUser)));

        assertTrue(mentions(currentUser.getId()).isEmpty());
    }

    /**
     * Mentioning a pending member queues the mention; it surfaces once they activate.
     */
    @Test
    void create_withMention_toPendingMember_isWithheldUntilAccepted() {
        User pending = newPendingMember();
        Activity created = activityService.create(draft("Met " + mention("Invitee", pending)));

        assertTrue(mentions(pending.getId()).isEmpty());

        workspaceMapper.activateMember(workspace.getId(), pending.getId());

        List<Notification> delivered = mentions(pending.getId());
        assertEquals(1, delivered.size());
        assertEquals("activity.mention:" + created.getId() + ":" + pending.getId(), delivered.get(0).getDedupeKey());
    }

    /**
     * Editing an activity to add a mention notifies only the newly-added member.
     */
    @Test
    void update_addingMention_notifiesOnlyTheNewMember() {
        User alice = newUser();
        User bob = newUser();
        Activity created = activityService.create(draft(mention("Alice", alice)));
        assertEquals(1, mentions(alice.getId()).size());

        activityService.update(created.getId(), draft(mention("Alice", alice) + " " + mention("Bob", bob)));

        assertEquals(1, mentions(bob.getId()).size());
        assertEquals(1, mentions(alice.getId()).size());
    }

    /**
     * Deleting an activity purges its references.
     */
    @Test
    void delete_purgesReferences() {
        User mentioned = newUser();
        Activity created = activityService.create(draft(mention("Mentioned", mentioned)));
        assertEquals(1, activityService.getActivityById(created.getId()).getReferences().size());

        activityService.delete(created.getId());

        assertTrue(referenceService
            .referencesFor(workspace.getId(), ReferenceService.SOURCE_ACTIVITY, created.getId()).isEmpty());
    }

    /**
     * Loading a contact hydrates references on its embedded activities so mentions render as chips.
     */
    @Test
    void personDetail_hydratesReferencesOnEmbeddedActivities() {
        User mentioned = newUser();
        Person person = newPerson(newCompany());
        Activity activity = draft(mention("Mentioned", mentioned));
        activity.setPerson(person);
        activityService.create(activity);

        Activity[] activities = personService.getPersonById(person.getId()).getActivities();
        assertNotNull(activities);
        var reference = java.util.Arrays.stream(activities)
            .filter((a) -> a.getReferences() != null && !a.getReferences().isEmpty())
            .findFirst()
            .orElseThrow()
            .getReferences()
            .get(0);
        assertEquals(mentioned.getId(), reference.getRefId());
        assertEquals("user", reference.getRefType());
    }
}
