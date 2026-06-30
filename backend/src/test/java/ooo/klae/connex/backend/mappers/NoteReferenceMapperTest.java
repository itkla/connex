package ooo.klae.connex.backend.mappers;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import ooo.klae.connex.backend.beans.Note;
import ooo.klae.connex.backend.beans.NoteReference;
import ooo.klae.connex.backend.beans.User;

class NoteReferenceMapperTest extends AbstractMapperTest {

    @Autowired NoteMapper noteMapper;
    @Autowired NoteReferenceMapper noteReferenceMapper;

    private Note newNote(User author) {
        Note note = new Note();
        note.setWorkspaceId(workspace.getId());
        note.setContent("note " + unique());
        note.setAuthor(author);
        noteMapper.insert(note);
        return note;
    }

    private NoteReference reference(Note note, String type, int refId, String label) {
        NoteReference reference = new NoteReference();
        reference.setWorkspaceId(workspace.getId());
        reference.setNoteId(note.getId());
        reference.setRefType(type);
        reference.setRefId(refId);
        reference.setLabel(label);
        return reference;
    }

    /**
     * An inserted reference is returned by findByNote with its fields intact.
     */
    @Test
    void insert_then_findByNote_returnsRow() {
        Note note = newNote(newUser());
        User mentioned = newUser();

        noteReferenceMapper.insert(reference(note, "user", mentioned.getId(), "User M"));

        List<NoteReference> found = noteReferenceMapper.findByNote(workspace.getId(), note.getId());
        assertEquals(1, found.size());
        assertEquals("user", found.get(0).getRefType());
        assertEquals(mentioned.getId(), found.get(0).getRefId());
        assertEquals("User M", found.get(0).getLabel());
    }

    /**
     * deleteByNote clears every reference for that note.
     */
    @Test
    void deleteByNote_removesRows() {
        Note note = newNote(newUser());
        noteReferenceMapper.insert(reference(note, "user", newUser().getId(), "A"));
        noteReferenceMapper.insert(reference(note, "user", newUser().getId(), "B"));

        noteReferenceMapper.deleteByNote(workspace.getId(), note.getId());

        assertTrue(noteReferenceMapper.findByNote(workspace.getId(), note.getId()).isEmpty());
    }

    /**
     * Deleting the parent note cascades to its references (composite FK).
     */
    @Test
    void noteDelete_cascadesReferences() {
        Note note = newNote(newUser());
        noteReferenceMapper.insert(reference(note, "user", newUser().getId(), "A"));

        noteMapper.delete(workspace.getId(), note.getId());

        assertTrue(noteReferenceMapper.findByNote(workspace.getId(), note.getId()).isEmpty());
    }

    /**
     * findByNote returns only the requested note's references.
     */
    @Test
    void findByNote_isScopedToNote() {
        Note note1 = newNote(newUser());
        Note note2 = newNote(newUser());
        noteReferenceMapper.insert(reference(note1, "user", newUser().getId(), "A"));

        assertEquals(1, noteReferenceMapper.findByNote(workspace.getId(), note1.getId()).size());
        assertTrue(noteReferenceMapper.findByNote(workspace.getId(), note2.getId()).isEmpty());
    }
}
