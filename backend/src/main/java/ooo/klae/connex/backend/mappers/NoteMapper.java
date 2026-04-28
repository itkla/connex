package ooo.klae.connex.backend.mappers;

import ooo.klae.connex.backend.beans.Note;
import java.util.List;

/**
 * Mapper interface for {@code Note} persistence.
 * SQL is defined in {@code resources/mappers/NoteMapper.xml}.
 * Used by {@code NoteService}.
 */

public interface NoteMapper {
    List<Note> getAllNotes();
    List<Note> getNotesByPersonId(int personId);
    List<Note> getNotesByDealId(int dealId);
    Note getNoteById(int id);
    int insert(Note note);
    int update(Note note);
    int delete(int id);
}
