package ooo.klae.connex.backend.mappers;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.Note;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.Pipeline;
import ooo.klae.connex.backend.beans.Stage;
import ooo.klae.connex.backend.beans.User;

class NoteMapperTest extends AbstractMapperTest {

    @Autowired NoteMapper noteMapper;

    // Builds note object
    private Note build(String content, User author, Person person, Deal deal) {
        Note n = new Note();
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

        Note found = noteMapper.getNoteById(note.getId());

        assertNotNull(found);
        assertEquals("the content", found.getContent());
        assertEquals(user.getId(), found.getAuthor().getId());
        assertEquals(person.getId(), found.getPerson().getId());
        assertEquals(deal.getId(), found.getDeal().getId());
        assertNotNull(found.getCreatedAt());
        assertNotNull(found.getUpdatedAt());
    }

    /**
     * Gets a note by ID and checks if the returned note is null when the ID is negative.
     */
    @Test
    void getNoteById_returnsNullWhenMissing() {
        assertNull(noteMapper.getNoteById(-1));
    }

    /**
     * Inserts a new note and checks if the person and deal are null when they are not provided.
     */
    @Test
    void insert_acceptsNullPersonAndDeal() {
        Note note = build("orphan note", newUser(), null, null);

        noteMapper.insert(note);

        Note found = noteMapper.getNoteById(note.getId());
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

        List<Note> all = noteMapper.getAllNotes();

        assertTrue(all.stream().anyMatch(x -> x.getId() == note.getId()));
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

        Note found = noteMapper.getNoteById(note.getId());
        assertEquals("after", found.getContent());
    }

    /**
     * Deletes a note and checks if the note is removed.
     */
    @Test
    void delete_removesRow() {
        Note note = build("temp", newUser(), null, null);
        noteMapper.insert(note);

        noteMapper.delete(note.getId());

        assertNull(noteMapper.getNoteById(note.getId()));
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

        List<Note> matched = noteMapper.getNotesByPersonId(person1.getId());

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

        List<Note> matched = noteMapper.getNotesByDealId(deal1.getId());

        assertTrue(matched.stream().anyMatch(x -> x.getId() == note1.getId()));
        assertTrue(matched.stream().noneMatch(x -> x.getId() == note2.getId()));
    }
}
