package ooo.klae.connex.backend.services;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.EntityReference;
import ooo.klae.connex.backend.beans.Note;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.Pipeline;
import ooo.klae.connex.backend.beans.Stage;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.mappers.EntityReferenceMapper;

class ReferenceServiceTest extends AbstractServiceTest {

    @Autowired ReferenceService referenceService;
    @Autowired EntityReferenceMapper entityReferenceMapper;

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

    private List<EntityReference> stored(Note note) {
        return entityReferenceMapper.findBySource(workspace.getId(), ReferenceService.SOURCE_NOTE, note.getId());
    }

    /**
     * A member token is persisted and returned as newly-added.
     */
    @Test
    void syncReferences_persistsMemberMention_andReturnsItAsNewlyAdded() {
        User mentioned = newUser();
        Note note = newNote(currentUser, null, null);

        List<Integer> added = referenceService.syncReferences(workspace.getId(), note.getId(),
            "Hey " + mention("Mentioned", mentioned) + " take a look");

        assertEquals(List.of(mentioned.getId()), added);
        assertEquals(1, stored(note).size());
        assertEquals(mentioned.getId(), stored(note).get(0).getRefId());
        assertEquals("Mentioned", stored(note).get(0).getLabel());
    }

    /**
     * A member who appears in the content is stored (so the chip renders) even
     * when they are the editor; excluding the author from notification is the
     * caller's job, not this method's.
     */
    @Test
    void syncReferences_storesSelfReference() {
        Note note = newNote(currentUser, null, null);

        List<Integer> added = referenceService.syncReferences(workspace.getId(), note.getId(),
            "Note to self " + mention("Me", currentUser));

        assertEquals(List.of(currentUser.getId()), added);
        assertEquals(1, stored(note).size());
        assertEquals(currentUser.getId(), stored(note).get(0).getRefId());
    }

    /**
     * A pending (invited-but-not-yet-joined) member is a valid mention target:
     * their reference is persisted so the chip renders, and they are returned as
     * newly-added so the caller can notify them; they see it once they accept.
     */
    @Test
    void syncReferences_persistsPendingMemberMention() {
        User pending = newPendingMember();
        Note note = newNote(currentUser, null, null);

        List<Integer> added = referenceService.syncReferences(workspace.getId(), note.getId(),
            "Welcome " + mention("Invitee", pending));

        assertEquals(List.of(pending.getId()), added);
        assertEquals(1, stored(note).size());
        assertEquals(pending.getId(), stored(note).get(0).getRefId());
    }

    /**
     * A token for a user outside the workspace is ignored (no cross-tenant mention).
     */
    @Test
    void syncReferences_excludesNonMembers() {
        User outsider = newNonMember();
        Note note = newNote(currentUser, null, null);

        List<Integer> added = referenceService.syncReferences(workspace.getId(), note.getId(),
            "Hi " + mention("Outsider", outsider));

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
            mention("First", mentioned) + " and again " + mention("Second", mentioned));

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
            mention("Alice", alice));

        List<Integer> addedOnEdit = referenceService.syncReferences(workspace.getId(), note.getId(),
            mention("Alice", alice) + " " + mention("Bob", bob));

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
            mention("Alice", alice));

        List<Integer> added = referenceService.syncReferences(workspace.getId(), note.getId(),
            "no mentions now");

        assertTrue(added.isEmpty());
        assertTrue(stored(note).isEmpty());
    }

    /**
     * Record-reference tokens pointing at non-existent ids are dropped, not stored.
     */
    @Test
    void syncReferences_dropsUnknownRecordReferences() {
        Note note = newNote(currentUser, null, null);

        List<Integer> added = referenceService.syncReferences(workspace.getId(), note.getId(),
            "See [Acme](deal:999999) and [Jane](person:999998) and [Globex](company:999997)");

        assertTrue(added.isEmpty());
        assertTrue(stored(note).isEmpty());
    }

    /**
     * A visible contact reference is stored but is not a mention (no notification).
     */
    @Test
    void syncReferences_storesContactReference_withoutNotifying() {
        Person person = newPerson(newCompany());
        Note note = newNote(currentUser, null, null);

        List<Integer> added = referenceService.syncReferences(workspace.getId(), note.getId(),
            "see [Jane](person:" + person.getId() + ")");

        assertTrue(added.isEmpty());
        List<EntityReference> refs = stored(note);
        assertEquals(1, refs.size());
        assertEquals("person", refs.get(0).getRefType());
        assertEquals(person.getId(), refs.get(0).getRefId());
    }

    /**
     * Visible deal and company references are stored.
     */
    @Test
    void syncReferences_storesDealAndCompanyReferences() {
        Company company = newCompany();
        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 0);
        Deal deal = newDeal(pipeline, stage, company);
        Note note = newNote(currentUser, null, null);

        referenceService.syncReferences(workspace.getId(), note.getId(),
            "[Acme](company:" + company.getId() + ") and [Q3](deal:" + deal.getId() + ")");

        List<EntityReference> refs = stored(note);
        assertEquals(2, refs.size());
        assertTrue(refs.stream().anyMatch(r -> "company".equals(r.getRefType()) && r.getRefId() == company.getId()));
        assertTrue(refs.stream().anyMatch(r -> "deal".equals(r.getRefType()) && r.getRefId() == deal.getId()));
    }

    /**
     * A record owned by another workspace is not visible and is dropped.
     */
    @Test
    void syncReferences_dropsForeignRecordReference() {
        Workspace other = new Workspace();
        other.setName("Other " + unique());
        other.setSlug("other_" + unique());
        workspaceMapper.insert(other);
        Company foreign = new Company();
        foreign.setName("Foreign " + unique());
        foreign.setWorkspaceId(other.getId());
        companyMapper.insert(foreign);
        Note note = newNote(currentUser, null, null);

        referenceService.syncReferences(workspace.getId(), note.getId(),
            "[Foreign](company:" + foreign.getId() + ")");

        assertTrue(stored(note).isEmpty());
    }

}
