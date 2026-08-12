package ooo.klae.connex.backend.mappers;

import java.util.List;

import org.apache.ibatis.annotations.Param;

import ooo.klae.connex.backend.beans.RecordComment;
import ooo.klae.connex.backend.beans.RecordCommentReactionSummary;
import ooo.klae.connex.backend.beans.RecordCommentThread;
import ooo.klae.connex.backend.dto.RecordCommentIndicatorDto;

/** Workspace-scoped persistence for record comment threads and comments. */
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

    List<RecordCommentReactionSummary> getReactionSummaries(
        @Param("workspaceId") int workspaceId,
        @Param("commentIds") List<Long> commentIds,
        @Param("userId") int userId);


    int insertReaction(
        @Param("workspaceId") int workspaceId,
        @Param("commentId") long commentId,
        @Param("userId") int userId,
        @Param("reaction") String reaction);

    int deleteReaction(
        @Param("workspaceId") int workspaceId,
        @Param("commentId") long commentId,
        @Param("userId") int userId,
        @Param("reaction") String reaction);

    List<RecordCommentIndicatorDto> getOpenThreadIndicators(
        @Param("workspaceId") int workspaceId,
        @Param("targetType") String targetType,
        @Param("targetIds") List<Integer> targetIds);

    int insertThread(RecordCommentThread thread);

    int insertComment(RecordComment comment);

    int updateCommentContent(
        @Param("workspaceId") int workspaceId,
        @Param("id") long id,
        @Param("content") String content);

    int countCommentsInThread(
        @Param("workspaceId") int workspaceId,
        @Param("threadId") long threadId);

    int softDeleteComment(
        @Param("workspaceId") int workspaceId,
        @Param("id") long id,
        @Param("deletedByUserId") int deletedByUserId);

    int resolveThread(
        @Param("workspaceId") int workspaceId,
        @Param("id") long id,
        @Param("resolvedByUserId") int resolvedByUserId);

    int reopenThread(
        @Param("workspaceId") int workspaceId,
        @Param("id") long id);

    int deleteEmptyThread(
        @Param("workspaceId") int workspaceId,
        @Param("id") long id);

    List<Long> getCommentIdsForTarget(
        @Param("workspaceId") int workspaceId,
        @Param("targetType") String targetType,
        @Param("targetId") int targetId
    );

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

    /** Deletes reactions for a permanently erased account. */
    void deleteReactionsAnywhere(@Param("userId") int userId);
}
