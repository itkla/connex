package ooo.klae.connex.backend.mappers;

import org.apache.ibatis.annotations.Param;

import ooo.klae.connex.backend.beans.NoteReference;
import java.util.List;

/**
 * Mapper interface for {@code NoteReference} persistence.
 * SQL is defined in {@code resources/mappers/NoteReferenceMapper.xml}.
 * Used by {@code ReferenceService}.
 */

public interface NoteReferenceMapper {
    List<NoteReference> findByNote(@Param("workspaceId") int workspaceId, @Param("noteId") int noteId);
    List<NoteReference> findByNotes(@Param("workspaceId") int workspaceId, @Param("noteIds") List<Integer> noteIds);
    int insert(NoteReference reference);
    int deleteByNote(@Param("workspaceId") int workspaceId, @Param("noteId") int noteId);
}
