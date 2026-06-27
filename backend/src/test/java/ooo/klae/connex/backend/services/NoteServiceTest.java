package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import ooo.klae.connex.backend.beans.Note;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.mappers.NoteMapper;

class NoteServiceTest extends AbstractServiceTest {

    @Autowired NoteService noteService;
    @Autowired NoteMapper noteMapper;

    private Note draft(String content, User spoofedAuthor) {
        Note note = new Note();
        note.setContent(content);
        note.setAuthor(spoofedAuthor);
        return note;
    }

    @Test
    void create_attributesAuthorToSessionUser_ignoringClient() {
        User other = newUser();
        Note created = noteService.create(draft("hi", other));
        Note found = noteMapper.getNoteById(workspace.getId(), created.getId());
        assertEquals(currentUser.getId(), found.getAuthor().getId());
    }

    @Test
    void update_preservesOriginalAuthor_ignoringClient() {
        Note created = noteService.create(draft("first", null));
        User other = newUser();
        noteService.update(created.getId(), draft("edited", other));
        Note found = noteMapper.getNoteById(workspace.getId(), created.getId());
        assertEquals(currentUser.getId(), found.getAuthor().getId());
        assertEquals("edited", found.getContent());
    }
}
