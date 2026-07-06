package ooo.klae.connex.backend.services;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import ooo.klae.connex.backend.beans.Note;
import ooo.klae.connex.backend.beans.Notification;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.Task;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.mappers.NotificationMapper;

class TaskMentionTest extends AbstractServiceTest {

    @Autowired TaskService taskService;
    @Autowired NoteService noteService;
    @Autowired ReferenceService referenceService;
    @Autowired NotificationMapper notificationMapper;
    @Autowired PersonService personService;

    private String mention(String label, User user) {
        return "[" + label + "](user:" + user.getId() + ")";
    }

    private Task draft(String description) {
        Task task = new Task();
        task.setDescription(description);
        task.setAssignedTo(currentUser);
        return task;
    }

    private List<Notification> mentions(int recipientId) {
        return notificationMapper.findPage(recipientId, null, "task", null, null, 50, 0)
            .stream().filter(n -> "task.mention".equals(n.getType())).toList();
    }

    /**
     * Creating a task that mentions a member dispatches a task.mention notification.
     */
    @Test
    void create_withMention_emitsNotificationToMentionedMember() {
        User mentioned = newUser();
        Task created = taskService.create(draft("Please help " + mention("Mentioned", mentioned)));

        List<Notification> notifications = mentions(mentioned.getId());
        assertEquals(1, notifications.size());
        Notification notification = notifications.get(0);
        assertEquals("task.mention", notification.getType());
        assertEquals("task", notification.getCategory());
        assertEquals(currentUser.getId(), notification.getActorId());
        assertEquals("task", notification.getSourceType());
        assertEquals(created.getId(), notification.getSourceId());
        assertEquals("task.mention:" + created.getId() + ":" + mentioned.getId(), notification.getDedupeKey());
    }

    /**
     * The created task is returned with its resolved references hydrated.
     */
    @Test
    void create_returnsTaskWithResolvedReferences() {
        User mentioned = newUser();
        Task created = taskService.create(draft(mention("Mentioned", mentioned)));

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
        taskService.create(draft("reminder for " + mention("Me", currentUser)));

        assertTrue(mentions(currentUser.getId()).isEmpty());
    }

    /**
     * Mentioning a pending (invited-but-not-yet-joined) member queues the mention:
     * the notification is created but withheld from their cross-workspace inbox
     * while pending; once their membership activates it surfaces.
     */
    @Test
    void create_withMention_toPendingMember_isWithheldUntilAccepted() {
        User pending = newPendingMember();
        Task created = taskService.create(draft("Onboarding for " + mention("Invitee", pending)));

        assertTrue(mentions(pending.getId()).isEmpty());

        workspaceMapper.activateMember(workspace.getId(), pending.getId());

        List<Notification> delivered = mentions(pending.getId());
        assertEquals(1, delivered.size());
        assertEquals("task.mention:" + created.getId() + ":" + pending.getId(), delivered.get(0).getDedupeKey());
    }

    /**
     * Editing a task to add a mention notifies only the newly-added member.
     */
    @Test
    void update_addingMention_notifiesOnlyTheNewMember() {
        User alice = newUser();
        User bob = newUser();
        Task created = taskService.create(draft(mention("Alice", alice)));
        assertEquals(1, mentions(alice.getId()).size());

        taskService.update(created.getId(), draft(mention("Alice", alice) + " " + mention("Bob", bob)));

        assertEquals(1, mentions(bob.getId()).size());
        assertEquals(1, mentions(alice.getId()).size());
    }

    /**
     * The mention notification deep-links to the task's linked deal.
     */
    @Test
    void mentionNotification_deepLinksToLinkedDeal() {
        User mentioned = newUser();
        var company = newCompany();
        var pipeline = newPipeline();
        var stage = newStage(pipeline, 0);
        var deal = newDeal(pipeline, stage, company);
        Task task = draft(mention("Mentioned", mentioned));
        task.setDeal(deal);
        Task created = taskService.create(task);

        Notification notification = mentions(mentioned.getId()).get(0);
        assertEquals("deal", notification.getContextType());
        assertEquals(deal.getId(), notification.getContextId());
        assertEquals("/records/deals/" + deal.getId() + "?task=" + created.getId(), notification.getActionUrl());
    }

    /**
     * Deleting a task purges its references (the polymorphic table has no FK cascade).
     */
    @Test
    void delete_purgesReferences() {
        User mentioned = newUser();
        Task created = taskService.create(draft(mention("Mentioned", mentioned)));
        assertEquals(1, taskService.getTaskById(created.getId()).getReferences().size());

        taskService.delete(created.getId());

        assertTrue(referenceService
            .referencesFor(workspace.getId(), ReferenceService.SOURCE_TASK, created.getId()).isEmpty());
    }

    /**
     * Loading a contact hydrates references on its embedded tasks so mentions render as chips.
     */
    @Test
    void personDetail_hydratesReferencesOnEmbeddedTasks() {
        User mentioned = newUser();
        Person person = newPerson(newCompany());
        Task task = draft(mention("Mentioned", mentioned));
        task.setPerson(person);
        taskService.create(task);

        Task[] tasks = personService.getPersonById(person.getId()).getTasks();
        assertNotNull(tasks);
        var reference = java.util.Arrays.stream(tasks)
            .filter((tk) -> tk.getReferences() != null && !tk.getReferences().isEmpty())
            .findFirst()
            .orElseThrow()
            .getReferences()
            .get(0);
        assertEquals(mentioned.getId(), reference.getRefId());
        assertEquals("user", reference.getRefType());
    }

    /**
     * A saved task read back through a list path carries its references for chip rendering.
     */
    @Test
    void getTasksByAssignedTo_hydratesReferences() {
        User mentioned = newUser();
        taskService.create(draft(mention("Mentioned", mentioned)));

        Task withRefs = taskService.getTasksByAssignedToId(currentUser.getId()).stream()
            .filter(t -> t.getReferences() != null && !t.getReferences().isEmpty())
            .findFirst()
            .orElseThrow();
        assertEquals(mentioned.getId(), withRefs.getReferences().get(0).getRefId());
    }

    /**
     * A private note linked inside a task's description does not leak its
     * label/existence to a non-author: on read the reference is dropped and the
     * inline token is masked to "(private note)".
     */
    @Test
    void privateNoteInTaskDescription_isRedactedForNonAuthor() {
        Note draftNote = new Note();
        draftNote.setContent("secret acquisition plan");
        draftNote.setVisibility("private");
        Note privateNote = noteService.create(draftNote);

        Task task = taskService.create(
            draft("Prep for [Secret Plan](note:" + privateNote.getId() + ") review"));

        Task asAuthor = taskService.getTaskById(task.getId());
        assertTrue(asAuthor.getReferences().stream()
            .anyMatch(r -> "note".equals(r.getRefType()) && r.getRefId() == privateNote.getId()));
        assertTrue(asAuthor.getDescription().contains("note:" + privateNote.getId()));

        User other = newUser();
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(other, null, other.getAuthorities()));

        Task asOther = taskService.getTaskById(task.getId());
        assertTrue(asOther.getReferences().stream().noneMatch(r -> "note".equals(r.getRefType())));
        assertFalse(asOther.getDescription().contains("Secret Plan"));
        assertFalse(asOther.getDescription().contains("note:" + privateNote.getId()));
        assertTrue(asOther.getDescription().contains("(private note)"));
    }

    /**
     * Rescheduling a task returns it hydrated (references populated), so the read
     * path that only changes the due date still routes through the redacting
     * hydrate rather than a raw mapper bean.
     */
    @Test
    void reschedule_hydratesReferences() {
        User mentioned = newUser();
        Task task = taskService.create(draft(mention("Mentioned", mentioned)));

        Task rescheduled = taskService.reschedule(task.getId(), "2025-01-15");

        assertNotNull(rescheduled.getReferences());
        assertTrue(rescheduled.getReferences().stream()
            .anyMatch(r -> "user".equals(r.getRefType()) && r.getRefId() == mentioned.getId()));
    }

    /**
     * A mention notification's snippet never carries a referenced note's label:
     * a note token in the source content is masked, so a private note's title
     * cannot leak to a mentioned member through the notification.
     */
    @Test
    void mentionNotification_doesNotLeakReferencedNoteLabel() {
        Note draftNote = new Note();
        draftNote.setContent("secret acquisition plan");
        draftNote.setVisibility("private");
        Note privateNote = noteService.create(draftNote);

        User mentioned = newUser();
        taskService.create(draft(
            mention("Mentioned", mentioned) + " see [Q3 Secret](note:" + privateNote.getId() + ")"));

        Notification notification = mentions(mentioned.getId()).get(0);
        assertFalse(notification.getSourceLabel().contains("Q3 Secret"));
        assertTrue(notification.getSourceLabel().contains("a note"));
    }
}
