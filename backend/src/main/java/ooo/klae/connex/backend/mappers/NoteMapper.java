package ooo.klae.connex.backend.mappers;

import org.apache.ibatis.annotations.Param;

import ooo.klae.connex.backend.beans.Note;
import java.util.List;

/**
 * Mapper interface for {@code Note} persistence.
 * SQL is defined in {@code resources/mappers/NoteMapper.xml}.
 * Used by {@code NoteService}.
 */

public interface NoteMapper {
    List<Note> getAllNotes(int workspaceId);
    List<Note> getNotesByPersonId(@Param("workspaceId") int workspaceId, @Param("personId") int personId);
    List<Note> getNotesByDealId(@Param("workspaceId") int workspaceId, @Param("dealId") int dealId);
    List<Note> getNotesByAuthorId(@Param("workspaceId") int workspaceId, @Param("authorId") int authorId);
    Note getNoteById(@Param("workspaceId") int workspaceId, @Param("id") int id);
    List<Note> search(@Param("workspaceId") int workspaceId, @Param("query") String query);
    int insert(Note note);
    int update(Note note);
    int delete(@Param("workspaceId") int workspaceId, @Param("id") int id);
}
