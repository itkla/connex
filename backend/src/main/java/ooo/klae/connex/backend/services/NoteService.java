package ooo.klae.connex.backend.services;

import org.springframework.stereotype.Service;

import ooo.klae.connex.backend.mappers.NoteMapper;
import ooo.klae.connex.backend.mappers.DealMapper;
import ooo.klae.connex.backend.beans.Note;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;

import java.util.List;
import java.util.Set;

import lombok.RequiredArgsConstructor;

/**
 * Business logic for {@code Note} operations.
 * Handles mapping between {@code NoteDto} and {@code Note} bean.
 * Delegates persistence to {@code NoteMapper}.
 */

@Service
@RequiredArgsConstructor
public class NoteService {
    private final NoteMapper noteMapper;
    private final DealMapper dealMapper;
    private final AuditService auditService;
    private final WorkspaceService workspaceService;

    private static final Set<String> AUDIT_FIELDS =
        Set.of("content");

    public List<Note> getAllNotes() {
        return noteMapper.getAllNotes();
    }

    public List<Note> getNotesByPersonId(int personId) {
        return noteMapper.getNotesByPersonId(personId);
    }

    public List<Note> getNotesByDealId(int dealId) {
        if (!dealMapper.exists(workspaceService.getCurrentWorkspaceId(), dealId)) {
            throw new ResourceNotFoundException("Deal not found with id: " + dealId);
        }
        return noteMapper.getNotesByDealId(dealId);
    }

    public List<Note> getNotesByAuthorId(int authorId) {
        return noteMapper.getNotesByAuthorId(authorId);
    }

    public Note getNoteById(int id) {
        Note note = noteMapper.getNoteById(id);
        if (note == null) throw new ResourceNotFoundException("Note not found with id: " + id);
        return note;
    }

    public Note create(Note note) {
        noteMapper.insert(note);
        auditService.record("note.create", "note", note.getId(), note.getContent(),
            "Created note",
            auditService.diff(null, note, AUDIT_FIELDS));
        return note;
    }

    public Note update(int id, Note note) {
        Note before = noteMapper.getNoteById(id);
        if (before == null) throw new ResourceNotFoundException("Note not found with id: " + id);
        note.setId(id);
        noteMapper.update(note);
        auditService.record("note.update", "note", id, note.getContent(),
            "Updated note",
            auditService.diff(before, note, AUDIT_FIELDS));
        return note;
    }

    public void delete(int id) {
        Note before = noteMapper.getNoteById(id);
        if (before == null) throw new ResourceNotFoundException("Note not found with id: " + id);
        noteMapper.delete(id);
        auditService.record("note.delete", "note", id, before.getContent(),
            "Deleted note",
            auditService.diff(before, null, AUDIT_FIELDS));
    }
}
