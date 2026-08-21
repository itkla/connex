package ooo.klae.connex.backend.mappers;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.Note;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.Pipeline;
import ooo.klae.connex.backend.beans.Stage;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;

class NoteMapperTest extends AbstractMapperTest {

    @Autowired NoteMapper noteMapper;
    @Autowired JdbcTemplate jdbcTemplate;

    // Builds note object
    private Note build(String content, User author, Person person, Deal deal) {
        Note n = new Note();
        n.setWorkspaceId(workspace.getId());
        n.setContent(content);
        n.setAuthor(author);
        n.setPerson(person);
        n.setDeal(deal);
        return n;
    }

    /**
     * Inserts a new note and checks if the generated ID is not zero.
     */
    @Test
    void insert_assignsGeneratedId() {
        Note note = build("hello", newUser(), null, null);

        noteMapper.insert(note);

        assertNotEquals(0, note.getId());
    }

    /**
     * Gets a note by ID and checks if the returned note is not null.
     */
    @Test
    void getNoteById_returnsInsertedRow() {
        User user = newUser();
        Person person = newPerson(newCompany());
        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 0);
        Deal deal = newDeal(pipeline, stage, newCompany());

        Note note = build("the content", user, person, deal);
        noteMapper.insert(note);

        Note found = noteMapper.getNoteById(workspace.getId(), note.getId());

        assertNotNull(found);
        assertEquals("the content", found.getContent());
        assertEquals(user.getId(), found.getAuthor().getId());
        assertEquals(person.getId(), found.getPerson().getId());
        assertEquals(deal.getId(), found.getDeal().getId());
        assertNotNull(found.getCreatedAt());
        assertNotNull(found.getUpdatedAt());
    }

    /**
     * Gets a note by ID and checks if the returned note is null when the ID is missing.
     */
    @Test
    void getNoteById_returnsNullWhenMissing() {
        assertNull(noteMapper.getNoteById(workspace.getId(), -1));
    }

    /**
     * Inserts a new note and checks if the person and deal are null when they are not provided.
     */
    @Test
    void insert_acceptsNullPersonAndDeal() {
        Note note = build("orphan note", newUser(), null, null);

        noteMapper.insert(note);

        Note found = noteMapper.getNoteById(workspace.getId(), note.getId());
        assertNotNull(found);
        assertTrue(found.getPerson() == null || found.getPerson().getId() == 0);
        assertTrue(found.getDeal() == null || found.getDeal().getId() == 0);
    }

    /**
     * Gets all notes and checks if the returned list includes the inserted note.
     */
    @Test
    void getAllNotes_includesInsertedRow() {
        Note note = build("listed", newUser(), null, null);
        noteMapper.insert(note);

        List<Note> all = noteMapper.getAllNotes(workspace.getId());

        assertTrue(all.stream().anyMatch(x -> x.getId() == note.getId()));
    }

    @Test
    void assistantCompanyNotesApplyProcessingRestrictionsBeforeTheirLimit() {
        User user = newUser();
        Company company = newCompany();
        Person visiblePerson = newPerson(company);
        Person restrictedPerson = newPerson(company);
        personMapper.updateProcessingRestrictions(
                workspace.getId(), restrictedPerson.getId(), true, false);
        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 0);
        Deal deal = newDeal(pipeline, stage, company);
        Note visibleNote = build("visible", user, visiblePerson, deal);
        Note restrictedDirectNote = build("restricted direct", user, restrictedPerson, deal);
        Note restrictedReferenceNote = build("restricted reference", user, visiblePerson, deal);
        visibleNote.setVisibility("workspace");
        restrictedDirectNote.setVisibility("workspace");
        restrictedReferenceNote.setVisibility("workspace");
        noteMapper.insert(visibleNote);
        noteMapper.insert(restrictedDirectNote);
        noteMapper.insert(restrictedReferenceNote);
        jdbcTemplate.update(
                "INSERT INTO entity_reference "
                    + "(workspace_id, source_type, source_id, ref_type, ref_id, label) "
                    + "VALUES (?, 'note', ?, 'person', ?, ?)",
                workspace.getId(),
                restrictedReferenceNote.getId(),
                restrictedPerson.getId(),
                restrictedPerson.getName());

        List<Note> notes = noteMapper.getAiAssistantVisibleNotesByCompanyId(
                workspace.getId(),
                company.getId(),
                user.getId(),
                List.of(workspace.getId()),
                1);

        assertEquals(List.of(visibleNote.getId()), notes.stream().map(Note::getId).toList());
    }

    @Test
    void getNotesByPersonIdsBatchesOnlyRequestedWorkspaceContacts() {
        User user = newUser();
        Person included = newPerson(newCompany());
        Person excluded = newPerson(newCompany());
        Note includedNote = build("included", user, included, null);
        Note excludedNote = build("excluded", user, excluded, null);
        noteMapper.insert(includedNote);
        noteMapper.insert(excludedNote);

        List<Note> notes = noteMapper.getNotesByPersonIds(
            workspace.getId(), List.of(included.getId()));

        assertEquals(List.of(includedNote.getId()), notes.stream().map(Note::getId).toList());
    }

    @Test
    void getVisibleNotesPageLimitsAndCountsOnlyVisibleRows() {
        Workspace pageWorkspace = newWorkspace();
        User current = newUser();
        User other = newUser();
        Note workspaceNote = build("workspace", current, null, null);
        workspaceNote.setWorkspaceId(pageWorkspace.getId());
        noteMapper.insert(workspaceNote);
        Note ownPrivate = build("own private", current, null, null);
        ownPrivate.setWorkspaceId(pageWorkspace.getId());
        ownPrivate.setVisibility("private");
        noteMapper.insert(ownPrivate);
        Note otherPrivate = build("other private", other, null, null);
        otherPrivate.setWorkspaceId(pageWorkspace.getId());
        otherPrivate.setVisibility("private");
        noteMapper.insert(otherPrivate);

        List<Note> page = noteMapper.getVisibleNotesPage(pageWorkspace.getId(), current.getId(), 10, 0);

        assertEquals(2, page.size());
        assertEquals(2, noteMapper.countVisibleNotes(pageWorkspace.getId(), current.getId()));
        assertTrue(page.stream().anyMatch(note -> note.getId() == workspaceNote.getId()));
        assertTrue(page.stream().anyMatch(note -> note.getId() == ownPrivate.getId()));
        assertTrue(page.stream().noneMatch(note -> note.getId() == otherPrivate.getId()));
    }

    @Test
    void workspaceNotesPageExcludesPrivateAndForeignRowsAndHonorsLimit() {
        Workspace pageWorkspace = newWorkspace();
        User user = newUser();
        Note first = build("first workspace", user, null, null);
        first.setWorkspaceId(pageWorkspace.getId());
        noteMapper.insert(first);
        Note second = build("second workspace", user, null, null);
        second.setWorkspaceId(pageWorkspace.getId());
        noteMapper.insert(second);
        Note privateNote = build("private", user, null, null);
        privateNote.setWorkspaceId(pageWorkspace.getId());
        privateNote.setVisibility("private");
        noteMapper.insert(privateNote);
        Note foreign = build("foreign", user, null, null);
        noteMapper.insert(foreign);

        List<Note> page = noteMapper.getWorkspaceNotesPage(pageWorkspace.getId(), 1, 0);

        assertEquals(1, page.size());
        assertEquals(2, noteMapper.countWorkspaceNotes(pageWorkspace.getId()));
        assertTrue(page.stream().noneMatch(note -> note.getId() == privateNote.getId()));
        assertTrue(page.stream().noneMatch(note -> note.getId() == foreign.getId()));
    }

    /**
     * Updates a note and checks if the new values are persisted.
     */
    @Test
    void update_persistsNewValues() {
        Note note = build("before", newUser(), null, null);
        noteMapper.insert(note);

        note.setContent("after");

        noteMapper.update(note);

        Note found = noteMapper.getNoteById(workspace.getId(), note.getId());
        assertEquals("after", found.getContent());
    }

    /**
     * Deletes a note and checks if the note is removed.
     */
    @Test
    void delete_removesRow() {
        Note note = build("temp", newUser(), null, null);
        noteMapper.insert(note);

        noteMapper.delete(workspace.getId(), note.getId());

        assertNull(noteMapper.getNoteById(workspace.getId(), note.getId()));
    }

    /**
     * Gets notes by person ID and checks if the returned list includes the inserted note.
     */
    @Test
    void getNotesByPersonId_filtersByPerson() {
        User user = newUser();
        Person person1 = newPerson(newCompany());
        Person person2 = newPerson(newCompany());

        Note note1 = build("for p1", user, person1, null);
        Note note2 = build("for p2", user, person2, null);
        noteMapper.insert(note1);
        noteMapper.insert(note2);

        List<Note> matched = noteMapper.getNotesByPersonId(workspace.getId(), person1.getId());

        assertTrue(matched.stream().anyMatch(x -> x.getId() == note1.getId()));
        assertTrue(matched.stream().noneMatch(x -> x.getId() == note2.getId()));
    }

    /**
     * Gets notes by deal ID and checks if the returned list includes the inserted note.
     */
    @Test
    void getNotesByDealId_filtersByDeal() {
        User user = newUser();
        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 0);
        Deal deal1 = newDeal(pipeline, stage, newCompany());
        Deal deal2 = newDeal(pipeline, stage, newCompany());

        Note note1 = build("for d1", user, null, deal1);
        Note note2 = build("for d2", user, null, deal2);
        noteMapper.insert(note1);
        noteMapper.insert(note2);

        List<Note> matched = noteMapper.getNotesByDealId(workspace.getId(), deal1.getId());

        assertTrue(matched.stream().anyMatch(x -> x.getId() == note1.getId()));
        assertTrue(matched.stream().noneMatch(x -> x.getId() == note2.getId()));
    }

    /**
     * Gets notes by author ID and checks the returned list contains only notes authored by that user.
     */
    @Test
    void getNotesByAuthorId_filtersByAuthor() {
        User user1 = newUser();
        User user2 = newUser();

        Note note1 = build("by u1", user1, null, null);
        Note note2 = build("by u2", user2, null, null);
        noteMapper.insert(note1);
        noteMapper.insert(note2);

        List<Note> matched = noteMapper.getNotesByAuthorId(workspace.getId(), user1.getId());

        assertTrue(matched.stream().anyMatch(x -> x.getId() == note1.getId()));
        assertTrue(matched.stream().noneMatch(x -> x.getId() == note2.getId()));
    }

    /**
     * A note in another workspace is invisible and immutable from this workspace.
     */
    @Test
    void notes_areIsolatedByWorkspace() {
        User user = newUser();
        Note mine = build("mine", user, null, null);
        noteMapper.insert(mine);

        Workspace other = newWorkspace();
        Note foreign = build("foreign", user, null, null);
        foreign.setWorkspaceId(other.getId());
        noteMapper.insert(foreign);

        assertNull(noteMapper.getNoteById(workspace.getId(), foreign.getId()));
        assertTrue(noteMapper.getAllNotes(workspace.getId()).stream().noneMatch(n -> n.getId() == foreign.getId()));
        assertTrue(noteMapper.getAllNotes(workspace.getId()).stream().anyMatch(n -> n.getId() == mine.getId()));

        assertEquals(0, noteMapper.delete(workspace.getId(), foreign.getId()));
        assertNotNull(noteMapper.getNoteById(other.getId(), foreign.getId()));
    }

    private Workspace newWorkspace() {
        Workspace ws = new Workspace();
        ws.setName("WS " + unique());
        ws.setSlug("ws_" + unique());
        workspaceMapper.insert(ws);
        return ws;
    }
}
