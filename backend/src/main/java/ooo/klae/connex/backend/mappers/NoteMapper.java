package ooo.klae.connex.backend.mappers;

import java.time.LocalDateTime;
import java.util.List;

import org.apache.ibatis.annotations.Param;

import ooo.klae.connex.backend.beans.HistoryImportProvenance;
import ooo.klae.connex.backend.beans.HistoryImportWrite;
import ooo.klae.connex.backend.beans.Note;

/**
 * Mapper interface for {@code Note} persistence.
 * SQL is defined in {@code resources/mappers/NoteMapper.xml}.
 * Used by {@code NoteService}.
 */

public interface NoteMapper {
    List<Note> getAllNotes(int workspaceId);
    List<Note> getNotesByPersonId(@Param("workspaceId") int workspaceId, @Param("personId") int personId);
    List<Note> getNotesByPersonIds(@Param("workspaceId") int workspaceId,
            @Param("personIds") List<Integer> personIds);
    List<Note> getNotesByDealId(@Param("workspaceId") int workspaceId, @Param("dealId") int dealId);
    List<Note> getNotesByAuthorId(@Param("workspaceId") int workspaceId, @Param("authorId") int authorId);
    Note getNoteById(@Param("workspaceId") int workspaceId, @Param("id") int id);
    List<Note> search(@Param("workspaceId") int workspaceId, @Param("query") String query);
    List<Note> getVisibleNotes(@Param("workspaceId") int workspaceId, @Param("currentUserId") int currentUserId);
    List<Note> getVisibleNotesPage(@Param("workspaceId") int workspaceId, @Param("currentUserId") int currentUserId, @Param("limit") int limit, @Param("offset") int offset);
    long countVisibleNotes(@Param("workspaceId") int workspaceId, @Param("currentUserId") int currentUserId);
    List<Note> getWorkspaceNotesPage(@Param("workspaceId") int workspaceId,
            @Param("limit") int limit, @Param("offset") int offset);
    long countWorkspaceNotes(int workspaceId);
    List<Note> getVisibleNotesByPersonId(@Param("workspaceId") int workspaceId, @Param("personId") int personId, @Param("currentUserId") int currentUserId);
    List<Note> getVisibleNotesByDealId(@Param("workspaceId") int workspaceId, @Param("dealId") int dealId, @Param("currentUserId") int currentUserId);
    List<Note> getVisibleNotesByAuthorId(@Param("workspaceId") int workspaceId, @Param("authorId") int authorId, @Param("currentUserId") int currentUserId);
    List<Note> getVisibleCompanyNotes(@Param("workspaceId") int workspaceId,
            @Param("companyId") int companyId, @Param("currentUserId") int currentUserId,
            @Param("limit") int limit);
    List<Note> getAiAssistantVisibleNotesByCompanyId(
            @Param("workspaceId") int workspaceId,
            @Param("companyId") int companyId,
            @Param("currentUserId") int currentUserId,
            @Param("organizationWorkspaceIds") List<Integer> organizationWorkspaceIds,
            @Param("limit") int limit);
    boolean exists(@Param("workspaceId") int workspaceId, @Param("id") int id);
    List<Note> getWorkspaceNotesByCompanyIds(@Param("workspaceId") int workspaceId,
            @Param("companyIds") List<Integer> companyIds);
    int countOwnPrivateNotesForPersonEvidence(
            @Param("workspaceId") int workspaceId,
            @Param("personId") int personId,
            @Param("currentUserId") int currentUserId,
            @Param("reference") LocalDateTime reference,
            @Param("sourceLimit") int sourceLimit);
    int countOwnPrivateNotesForCompanyEvidence(
            @Param("workspaceId") int workspaceId,
            @Param("companyId") int companyId,
            @Param("currentUserId") int currentUserId,
            @Param("reference") LocalDateTime reference,
            @Param("sourceLimit") int sourceLimit);
    Note getVisibleNoteById(@Param("workspaceId") int workspaceId, @Param("id") int id, @Param("currentUserId") int currentUserId);
    Note getVisibleNoteByIdForUpdate(@Param("workspaceId") int workspaceId, @Param("id") int id,
            @Param("currentUserId") int currentUserId);
    List<Note> searchVisible(@Param("workspaceId") int workspaceId, @Param("query") String query, @Param("currentUserId") int currentUserId);
    List<Note> getNotesReferencing(@Param("workspaceId") int workspaceId, @Param("refType") String refType, @Param("refId") int refId, @Param("currentUserId") int currentUserId);
    List<Integer> getVisibleNoteIdsIn(@Param("workspaceId") int workspaceId, @Param("ids") List<Integer> ids, @Param("currentUserId") int currentUserId);
    int insert(Note note);
    List<HistoryImportProvenance> findHistoryImports(
        @Param("workspaceId") int workspaceId,
        @Param("historyImportKeys") List<String> historyImportKeys
    );
    int insertHistoryBatch(
        @Param("workspaceId") int workspaceId,
        @Param("rows") List<HistoryImportWrite> rows
    );
    int update(Note note);
    int delete(@Param("workspaceId") int workspaceId, @Param("id") int id);

    /**
     * Counts notes authored by a user across all workspaces. Service-layer
     * mirror of the {@code note.author_id} ON DELETE RESTRICT: account deletion
     * is refused while authored notes exist (#440 increment 3).
     */
    int countAuthoredAnywhere(@Param("userId") int userId);
}
