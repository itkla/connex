package ooo.klae.connex.backend.services;

import org.springframework.stereotype.Service;

import ooo.klae.connex.backend.mappers.NoteMapper;
import ooo.klae.connex.backend.beans.Note;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;

import java.util.List;

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

    public List<Note> getAllNotes() {
        return noteMapper.getAllNotes();
    }

    public List<Note> getNotesByPersonId(int personId) {
        return noteMapper.getNotesByPersonId(personId);
    }

    public List<Note> getNotesByDealId(int dealId) {
        return noteMapper.getNotesByDealId(dealId);
    }

    public Note getNoteById(int id) {
        Note note = noteMapper.getNoteById(id);
        if (note == null) throw new ResourceNotFoundException("Note not found with id: " + id);
        return note;
    }

    public Note create(Note note) {
        noteMapper.insert(note);
        return note;
    }

    public Note update(int id, Note note) {
        if (noteMapper.getNoteById(id) == null) throw new ResourceNotFoundException("Note not found with id: " + id);
        note.setId(id);
        noteMapper.update(note);
        return note;
    }

    public void delete(int id) {
        if (noteMapper.getNoteById(id) == null) throw new ResourceNotFoundException("Note not found with id: " + id);
        noteMapper.delete(id);
    }
}
