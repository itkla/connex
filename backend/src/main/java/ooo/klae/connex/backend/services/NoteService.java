package ooo.klae.connex.backend.services;

import org.springframework.stereotype.Service;

import ooo.klae.connex.backend.mappers.NoteMapper;
import ooo.klae.connex.backend.mappers.DealMapper;
import ooo.klae.connex.backend.beans.Note;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.tenant.Permission;
import ooo.klae.connex.backend.tenant.RequirePermission;

import java.util.List;
import java.util.Set;

import lombok.RequiredArgsConstructor;

/**
 * Business logic for {@code Note} operations.
 * Every read/write is scoped to the caller's active workspace.
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
        return noteMapper.getAllNotes(workspaceService.getCurrentWorkspaceId());
    }

    public List<Note> getNotesByPersonId(int personId) {
        return noteMapper.getNotesByPersonId(workspaceService.getCurrentWorkspaceId(), personId);
    }

    public List<Note> getNotesByDealId(int dealId) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        if (!dealMapper.exists(workspaceId, dealId)) {
            throw new ResourceNotFoundException("Deal not found with id: " + dealId);
        }
        return noteMapper.getNotesByDealId(workspaceId, dealId);
    }

    public List<Note> getNotesByAuthorId(int authorId) {
        return noteMapper.getNotesByAuthorId(workspaceService.getCurrentWorkspaceId(), authorId);
    }

    public Note getNoteById(int id) {
        Note note = noteMapper.getNoteById(workspaceService.getCurrentWorkspaceId(), id);
        if (note == null) throw new ResourceNotFoundException("Note not found with id: " + id);
        return note;
    }

    @RequirePermission(Permission.NOTE_CREATE)
    public Note create(Note note) {
        note.setWorkspaceId(workspaceService.getCurrentWorkspaceId());
        noteMapper.insert(note);
        auditService.record("note.create", "note", note.getId(), note.getContent(),
            "Created note",
            auditService.diff(null, note, AUDIT_FIELDS));
        return note;
    }

    @RequirePermission(Permission.NOTE_UPDATE)
    public Note update(int id, Note note) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        Note before = noteMapper.getNoteById(workspaceId, id);
        if (before == null) throw new ResourceNotFoundException("Note not found with id: " + id);
        note.setId(id);
        note.setWorkspaceId(workspaceId);
        noteMapper.update(note);
        auditService.record("note.update", "note", id, note.getContent(),
            "Updated note",
            auditService.diff(before, note, AUDIT_FIELDS));
        return note;
    }

    @RequirePermission(Permission.NOTE_DELETE)
    public void delete(int id) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        Note before = noteMapper.getNoteById(workspaceId, id);
        if (before == null) throw new ResourceNotFoundException("Note not found with id: " + id);
        noteMapper.delete(workspaceId, id);
        auditService.record("note.delete", "note", id, before.getContent(),
            "Deleted note",
            auditService.diff(before, null, AUDIT_FIELDS));
    }
}
