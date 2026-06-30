package ooo.klae.connex.backend.services;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import ooo.klae.connex.backend.beans.Note;
import ooo.klae.connex.backend.beans.NoteReference;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.mappers.NoteReferenceMapper;

class ReferenceServiceTest extends AbstractServiceTest {

    @Autowired ReferenceService referenceService;
    @Autowired NoteReferenceMapper noteReferenceMapper;

    private String mention(String label, User user) {
        return "[" + label + "](user:" + user.getId() + ")";
    }

    private User newNonMember() {
        String s = unique();
        User user = new User();
        user.setUsername("user_" + s);
        user.setDisplayName("User " + s);
        user.setEmail(s + "@example.com");
        user.setPasswordHash("hash_" + s);
        user.setTimezone("UTC");
        userMapper.insert(user);
        return user;
    }

    private List<NoteReference> stored(Note note) {
        return noteReferenceMapper.findByNote(workspace.getId(), note.getId());
    }

    /**
     * A member token is persisted and returned as newly-added.
     */
    @Test
    void syncReferences_persistsMemberMention_andReturnsItAsNewlyAdded() {
        User mentioned = newUser();
        Note note = newNote(currentUser, null, null);

        List<Integer> added = referenceService.syncReferences(workspace.getId(), note.getId(),
            "Hey " + mention("Mentioned", mentioned) + " take a look", currentUser.getId());

        assertEquals(List.of(mentioned.getId()), added);
        assertEquals(1, stored(note).size());
        assertEquals(mentioned.getId(), stored(note).get(0).getRefId());
        assertEquals("Mentioned", stored(note).get(0).getLabel());
    }

    /**
     * The author is never mentioned by their own note.
     */
    @Test
    void syncReferences_excludesSelfMention() {
        Note note = newNote(currentUser, null, null);

        List<Integer> added = referenceService.syncReferences(workspace.getId(), note.getId(),
            "Note to self " + mention("Me", currentUser), currentUser.getId());

        assertTrue(added.isEmpty());
        assertTrue(stored(note).isEmpty());
    }

    /**
     * A token for a user outside the workspace is ignored (no cross-tenant mention).
     */
    @Test
    void syncReferences_excludesNonMembers() {
        User outsider = newNonMember();
        Note note = newNote(currentUser, null, null);

        List<Integer> added = referenceService.syncReferences(workspace.getId(), note.getId(),
            "Hi " + mention("Outsider", outsider), currentUser.getId());

        assertTrue(added.isEmpty());
        assertTrue(stored(note).isEmpty());
    }

    /**
     * The same member mentioned twice yields a single reference.
     */
    @Test
    void syncReferences_dedupesRepeatedMention() {
        User mentioned = newUser();
        Note note = newNote(currentUser, null, null);

        List<Integer> added = referenceService.syncReferences(workspace.getId(), note.getId(),
            mention("First", mentioned) + " and again " + mention("Second", mentioned), currentUser.getId());

        assertEquals(List.of(mentioned.getId()), added);
        assertEquals(1, stored(note).size());
    }

    /**
     * Editing to add a mention notifies only the newcomer, not the existing mention.
     */
    @Test
    void syncReferences_onEdit_returnsOnlyNewlyAddedMentions() {
        User alice = newUser();
        User bob = newUser();
        Note note = newNote(currentUser, null, null);

        referenceService.syncReferences(workspace.getId(), note.getId(),
            mention("Alice", alice), currentUser.getId());

        List<Integer> addedOnEdit = referenceService.syncReferences(workspace.getId(), note.getId(),
            mention("Alice", alice) + " " + mention("Bob", bob), currentUser.getId());

        assertEquals(List.of(bob.getId()), addedOnEdit);
        assertEquals(2, stored(note).size());
    }

    /**
     * Editing to drop a mention removes its reference row.
     */
    @Test
    void syncReferences_removesDroppedMentions() {
        User alice = newUser();
        Note note = newNote(currentUser, null, null);
        referenceService.syncReferences(workspace.getId(), note.getId(),
            mention("Alice", alice), currentUser.getId());

        List<Integer> added = referenceService.syncReferences(workspace.getId(), note.getId(),
            "no mentions now", currentUser.getId());

        assertTrue(added.isEmpty());
        assertTrue(stored(note).isEmpty());
    }

    /**
     * Phase A resolves only member tokens; record-reference types are ignored.
     */
    @Test
    void syncReferences_ignoresNonUserReferenceTypes() {
        Note note = newNote(currentUser, null, null);

        List<Integer> added = referenceService.syncReferences(workspace.getId(), note.getId(),
            "See [Acme](deal:1) and [Jane](person:2) and [Globex](company:3)", currentUser.getId());

        assertTrue(added.isEmpty());
        assertTrue(stored(note).isEmpty());
    }
}
