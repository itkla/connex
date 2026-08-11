package ooo.klae.connex.backend.mappers;

import java.util.List;

import org.apache.ibatis.annotations.Param;

import ooo.klae.connex.backend.beans.RecordComment;
import ooo.klae.connex.backend.beans.RecordCommentThread;

/** Workspace-scoped persistence for record comment threads and immutable comments. */
public interface RecordCommentMapper {
    List<RecordCommentThread> getThreadPage(
        @Param("workspaceId") int workspaceId,
        @Param("targetType") String targetType,
        @Param("targetId") int targetId,
        @Param("state") String state,
        @Param("limit") int limit,
        @Param("offset") int offset);

    long countThreads(
        @Param("workspaceId") int workspaceId,
        @Param("targetType") String targetType,
        @Param("targetId") int targetId,
        @Param("state") String state);

    RecordCommentThread getThreadById(
        @Param("workspaceId") int workspaceId,
        @Param("id") long id);

    RecordCommentThread getThreadByIdForUpdate(
        @Param("workspaceId") int workspaceId,
        @Param("id") long id);

    List<RecordComment> getCommentsByThread(
        @Param("workspaceId") int workspaceId,
        @Param("threadId") long threadId);

    List<RecordComment> getCommentsByThreadIds(
        @Param("workspaceId") int workspaceId,
        @Param("threadIds") List<Long> threadIds);

    RecordComment getCommentById(
        @Param("workspaceId") int workspaceId,
        @Param("id") long id);

    RecordComment getCommentByIdForUpdate(
        @Param("workspaceId") int workspaceId,
        @Param("id") long id);

    RecordComment getCommentByClientToken(
        @Param("workspaceId") int workspaceId,
        @Param("clientToken") String clientToken);

    int insertThread(RecordCommentThread thread);

    int insertComment(RecordComment comment);

    int countCommentsInThread(
        @Param("workspaceId") int workspaceId,
        @Param("threadId") long threadId);

    int softDeleteComment(
        @Param("workspaceId") int workspaceId,
        @Param("id") long id,
        @Param("deletedByUserId") int deletedByUserId);

    int deleteEmptyThread(
        @Param("workspaceId") int workspaceId,
        @Param("id") long id);

    int deleteThreadsForTarget(
        @Param("workspaceId") int workspaceId,
        @Param("targetType") String targetType,
        @Param("targetId") int targetId);

    /** Clears comment author provenance for a permanently erased account. */
    void clearAuthorsAnywhere(@Param("userId") int userId);

    /** Clears comment redaction provenance for a permanently erased account. */
    void clearDeletersAnywhere(@Param("userId") int userId);

    /** Clears thread creator provenance for a permanently erased account. */
    void clearThreadCreatorsAnywhere(@Param("userId") int userId);

    /** Clears thread resolution provenance for a permanently erased account. */
    void clearThreadResolversAnywhere(@Param("userId") int userId);
}
