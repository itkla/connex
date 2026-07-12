package ooo.klae.connex.backend.services;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

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
    @Autowired ReferenceService referenceService;

    private String mention(String label, User user) {
        return "[" + label + "](user:" + user.getId() + ")";
    }

    private Note draft(String content) {
        Note note = new Note();
        note.setContent(content);
        note.setVisibility("workspace");
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
     * Mentioning a pending (invited-but-not-yet-joined) member queues the mention:
     * the notification is created but withheld from their cross-workspace inbox
     * while pending, so the note's content is not disclosed before they accept.
     * Once their membership activates, the queued mention surfaces.
     */
    @Test
    void create_withMention_toPendingMember_isWithheldUntilAccepted() {
        User pending = newPendingMember();
        Note created = noteService.create(draft("Welcome aboard " + mention("Invitee", pending)));

        assertTrue(mentions(pending.getId()).isEmpty());

        workspaceMapper.activateMember(workspace.getId(), pending.getId());

        List<Notification> delivered = mentions(pending.getId());
        assertEquals(1, delivered.size());
        assertEquals("note.mention:" + created.getId() + ":" + pending.getId(), delivered.get(0).getDedupeKey());
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
     * Deleting a note purges its references (the polymorphic table has no FK cascade).
     */
    @Test
    void delete_purgesReferences() {
        User mentioned = newUser();
        Note created = noteService.create(draft(mention("Mentioned", mentioned)));
        assertEquals(1,
            referenceService.referencesFor(workspace.getId(), ReferenceService.SOURCE_NOTE, created.getId()).size());

        noteService.delete(created.getId());

        assertTrue(referenceService
            .referencesFor(workspace.getId(), ReferenceService.SOURCE_NOTE, created.getId()).isEmpty());
    }

    /**
     * A note may reference a task and an activity; both resolve to hydrated
     * references, while a token pointing at a non-existent task is dropped.
     */
    @Test
    void taskAndActivityReferences_areResolved() {
        var task = newTask(currentUser, null, null);
        var activity = newActivity(currentUser, null, null);
        int ghostTaskId = task.getId() + 90000;

        Note created = noteService.create(draft(
            "Follow up on [Task](task:" + task.getId() + ") after [Call](activity:" + activity.getId() + ")"
            + " but ignore [Ghost](task:" + ghostTaskId + ")"));

        var refs = created.getReferences();
        assertNotNull(refs);
        assertTrue(refs.stream()
            .anyMatch(r -> "task".equals(r.getRefType()) && r.getRefId() == task.getId()));
        assertTrue(refs.stream()
            .anyMatch(r -> "activity".equals(r.getRefType()) && r.getRefId() == activity.getId()));
        assertTrue(refs.stream().noneMatch(r -> r.getRefId() == ghostTaskId));
    }

    /**
     * A private note linked as a reference target inside a more-visible note does
     * not leak its label/existence to a non-author: the reference is dropped and
     * the content token is masked on the read path.
     */
    @Test
    void privateNoteTarget_isRedactedForNonAuthor() {
        Note draftP = draft("secret acquisition plan");
        draftP.setVisibility("private");
        Note privateNote = noteService.create(draftP);

        Note draftW = draft("See [Secret Plan](note:" + privateNote.getId() + ") for details");
        Note workspaceNote = noteService.create(draftW);

        Note asAuthor = noteService.getNoteById(workspaceNote.getId());
        assertTrue(asAuthor.getReferences().stream()
            .anyMatch(r -> "note".equals(r.getRefType()) && r.getRefId() == privateNote.getId()));
        assertTrue(asAuthor.getContent().contains("note:" + privateNote.getId()));

        User other = newUser();
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(other, null, other.getAuthorities()));

        Note asOther = noteService.getNoteById(workspaceNote.getId());
        assertTrue(asOther.getReferences().stream().noneMatch(r -> "note".equals(r.getRefType())));
        assertFalse(asOther.getContent().contains("Secret Plan"));
        assertFalse(asOther.getContent().contains("note:" + privateNote.getId()));
        assertTrue(asOther.getContent().contains("(private note)"));
    }

    @Test
    void privateReferenceStyleNoteTarget_isRedactedForNonAuthor() {
        Note privateDraft = draft("secret acquisition plan");
        privateDraft.setVisibility("private");
        Note privateNote = noteService.create(privateDraft);

        Note workspaceDraft = draft(
            "[Secret \\] Plan][n]\n\n[n]:\n  note:" + privateNote.getId());
        Note workspaceNote = noteService.create(workspaceDraft);

        User other = newUser();
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(other, null, other.getAuthorities()));

        Note asOther = noteService.getNoteById(workspaceNote.getId());
        assertEquals("(private note)", asOther.getContent());
    }

    @Test
    void escapedAndEntityEncodedPrivateNoteTargets_areRedactedForNonAuthor() {
        Note privateDraft = draft("secret acquisition plan");
        privateDraft.setVisibility("private");
        Note privateNote = noteService.create(privateDraft);
        String id = Integer.toString(privateNote.getId());
        String encodedFirstDigit = "&#" + (int) id.charAt(0) + ";" + id.substring(1);
        List<Note> sources = List.of(
            noteService.create(draft("[Secret][n]\n\n[n]: note\\:" + id)),
            noteService.create(draft("[Secret][n]\n\n[n]: note&#58;" + id)),
            noteService.create(draft("[Secret][n]\n\n[n]: note:" + encodedFirstDigit)),
            noteService.create(draft("[Secret][n]\n\n[n]: note&colon;" + id)));
        User other = newUser();
        authenticateAs(other, workspace.getId());

        for (Note source : sources) {
            assertEquals("(private note)", noteService.getNoteById(source.getId()).getContent());
        }
    }

    @Test
    void oversizedNoteTarget_isRedactedWithoutBreakingReads() {
        Note created = noteService.create(draft(
            "[Invalid](note:999999999999999999999999999999999999)"));

        Note fetched = noteService.getNoteById(created.getId());

        assertEquals("(private note)", fetched.getContent());
    }

    @Test
    void emptyLabelPrivateNoteTarget_isRedactedForNonAuthor() {
        Note privateDraft = draft("secret acquisition plan");
        privateDraft.setVisibility("private");
        Note privateNote = noteService.create(privateDraft);
        Note source = noteService.create(draft("[](note:" + privateNote.getId() + ")"));
        User other = newUser();
        authenticateAs(other, workspace.getId());

        assertEquals("(private note)", noteService.getNoteById(source.getId()).getContent());
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
