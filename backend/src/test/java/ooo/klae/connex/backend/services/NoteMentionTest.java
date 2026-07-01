package ooo.klae.connex.backend.services;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.beans.Note;
import ooo.klae.connex.backend.beans.Notification;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.mappers.NotificationMapper;

class NoteMentionTest extends AbstractServiceTest {

    @Autowired NoteService noteService;
    @Autowired NotificationMapper notificationMapper;
    @Autowired PersonService personService;

    private String mention(String label, User user) {
        return "[" + label + "](user:" + user.getId() + ")";
    }

    private Note draft(String content) {
        Note note = new Note();
        note.setContent(content);
        return note;
    }

    private List<Notification> mentions(int recipientId) {
        return notificationMapper.findPage(recipientId, null, "note", null, null, 50, 0)
            .stream().filter(n -> "note.mention".equals(n.getType())).toList();
    }

    /**
     * Creating a note that mentions a member dispatches a note.mention notification.
     */
    @Test
    void create_withMention_emitsNotificationToMentionedMember() {
        User mentioned = newUser();
        Note created = noteService.create(draft("Hey " + mention("Mentioned", mentioned) + " please review"));

        List<Notification> notifications = mentions(mentioned.getId());
        assertEquals(1, notifications.size());
        Notification notification = notifications.get(0);
        assertEquals("note.mention", notification.getType());
        assertEquals("note", notification.getCategory());
        assertEquals(currentUser.getId(), notification.getActorId());
        assertEquals("note", notification.getSourceType());
        assertEquals(created.getId(), notification.getSourceId());
        assertEquals("note.mention:" + created.getId() + ":" + mentioned.getId(), notification.getDedupeKey());
    }

    /**
     * The created note is returned with its resolved references hydrated.
     */
    @Test
    void create_returnsNoteWithResolvedReferences() {
        User mentioned = newUser();
        Note created = noteService.create(draft(mention("Mentioned", mentioned)));

        assertNotNull(created.getReferences());
        assertEquals(1, created.getReferences().size());
        assertEquals("user", created.getReferences().get(0).getRefType());
        assertEquals(mentioned.getId(), created.getReferences().get(0).getRefId());
    }

    /**
     * Editing a note to add a mention notifies only the newly-added member.
     */
    @Test
    void update_addingMention_notifiesOnlyTheNewMember() {
        User alice = newUser();
        User bob = newUser();
        Note created = noteService.create(draft(mention("Alice", alice)));
        assertEquals(1, mentions(alice.getId()).size());

        noteService.update(created.getId(), draft(mention("Alice", alice) + " " + mention("Bob", bob)));

        assertEquals(1, mentions(bob.getId()).size());
        assertEquals(1, mentions(alice.getId()).size());
    }

    /**
     * A note with no mentions dispatches nothing.
     */
    @Test
    void create_withoutMention_emitsNoNotification() {
        User other = newUser();
        noteService.create(draft("just a plain note"));

        assertTrue(mentions(other.getId()).isEmpty());
    }

    /**
     * The author is never notified for mentioning themselves.
     */
    @Test
    void create_selfMention_doesNotNotify() {
        noteService.create(draft("note to self " + mention("Me", currentUser)));

        assertTrue(mentions(currentUser.getId()).isEmpty());
    }

    /**
     * The mention notification deep-links to the linked contact and discloses no record label.
     */
    @Test
    void mentionNotification_deepLinksToLinkedContact() {
        User mentioned = newUser();
        Person person = newPerson(newCompany());
        Note note = draft(mention("Mentioned", mentioned));
        note.setPerson(person);
        noteService.create(note);

        Notification notification = mentions(mentioned.getId()).get(0);
        assertEquals("person", notification.getContextType());
        assertEquals(person.getId(), notification.getContextId());
        assertEquals("/records/contacts/" + person.getId() + "?note=" + note.getId(), notification.getActionUrl());
        assertNull(notification.getContextLabel());
    }

    /**
     * A note may not link a record outside the workspace — create is rejected,
     * closing the search side-channel on foreign record names.
     */
    @Test
    void noteCreate_rejectsForeignLinkedRecord() {
        Workspace other = new Workspace();
        other.setName("Other " + unique());
        other.setSlug("other_" + unique());
        workspaceMapper.insert(other);
        Person foreign = new Person();
        foreign.setName("Foreign " + unique());
        foreign.setEmail(unique() + ".foreign@example.com");
        foreign.setWorkspaceId(other.getId());
        personMapper.insert(foreign);

        Note note = draft("a linked note");
        note.setPerson(foreign);

        assertThrows(ResourceNotFoundException.class, () -> noteService.create(note));
    }

    /**
     * Loading a contact hydrates references on its embedded notes so mentions render as chips.
     */
    @Test
    void personDetail_hydratesReferencesOnEmbeddedNotes() {
        User mentioned = newUser();
        Person person = newPerson(newCompany());
        Note note = draft(mention("Mentioned", mentioned));
        note.setPerson(person);
        noteService.create(note);

        Note[] notes = personService.getPersonById(person.getId()).getNotes();
        assertNotNull(notes);
        var reference = java.util.Arrays.stream(notes)
            .filter((n) -> n.getReferences() != null && !n.getReferences().isEmpty())
            .findFirst()
            .orElseThrow()
            .getReferences()
            .get(0);
        assertEquals(mentioned.getId(), reference.getRefId());
        assertEquals("user", reference.getRefType());
    }
}
