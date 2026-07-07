package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import ooo.klae.connex.backend.beans.Note;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
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

    /**
     * Backlinks to a private note are not queryable by a non-author, even when
     * the visible source note references that private target.
     */
    @Test
    void getNotesReferencing_privateNoteTarget_isNotQueryableByNonAuthor() {
        Note privateTarget = draft("secret target", null);
        privateTarget.setTitle("Secret Target");
        privateTarget.setVisibility("private");
        Note target = noteService.create(privateTarget);

        Note sourceDraft = draft("See [Secret Target](note:" + target.getId() + ")", null);
        sourceDraft.setVisibility("workspace");
        Note source = noteService.create(sourceDraft);

        assertTrue(noteService.getNotesReferencing("note", target.getId()).stream()
            .anyMatch(note -> note.getId() == source.getId()));

        User other = newUser();
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(other, null, other.getAuthorities()));

        assertThrows(ResourceNotFoundException.class,
            () -> noteService.getNotesReferencing("note", target.getId()));
    }
}
