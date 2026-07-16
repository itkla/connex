package ooo.klae.connex.backend.mappers;

import java.time.LocalDateTime;
import java.util.List;

import org.apache.ibatis.annotations.Param;

import ooo.klae.connex.backend.storage.ObjectDeletionTask;

/**
 * Durable workspace-object deletion tasks stored in each org-data catalog.
 */
public interface ObjectDeletionQueueMapper {
    int enqueue(
        @Param("workspaceId") int workspaceId,
        @Param("objectKey") String objectKey,
        @Param("deletePassesRemaining") int deletePassesRemaining,
        @Param("nextAttemptAt") LocalDateTime nextAttemptAt);

    long countPending(@Param("workspaceId") int workspaceId);

    long countPendingAmbiguousWrites(@Param("workspaceId") int workspaceId);

    List<Integer> workspaceIdsWithDueTasks(
        @Param("now") LocalDateTime now,
        @Param("limit") int limit);

    List<ObjectDeletionTask> findDue(
        @Param("workspaceId") int workspaceId,
        @Param("now") LocalDateTime now,
        @Param("limit") int limit);

    ObjectDeletionTask lockByKey(
        @Param("workspaceId") int workspaceId,
        @Param("objectKey") String objectKey);

    ObjectDeletionTask lockByIdentity(
        @Param("workspaceId") int workspaceId,
        @Param("id") long id,
        @Param("objectKey") String objectKey);

    ObjectDeletionTask lockDueByKey(
        @Param("workspaceId") int workspaceId,
        @Param("objectKey") String objectKey,
        @Param("now") LocalDateTime now);

    ObjectDeletionTask lockDueByIdentity(
        @Param("workspaceId") int workspaceId,
        @Param("id") long id,
        @Param("objectKey") String objectKey,
        @Param("now") LocalDateTime now);

    int deleteById(
        @Param("workspaceId") int workspaceId,
        @Param("id") long id);

    int deleteByKey(
        @Param("workspaceId") int workspaceId,
        @Param("objectKey") String objectKey);

    int deleteByIdentity(
        @Param("workspaceId") int workspaceId,
        @Param("id") long id,
        @Param("objectKey") String objectKey);

    int reschedule(
        @Param("workspaceId") int workspaceId,
        @Param("id") long id,
        @Param("nextAttemptAt") LocalDateTime nextAttemptAt);

    int rescheduleByKey(
        @Param("workspaceId") int workspaceId,
        @Param("objectKey") String objectKey,
        @Param("nextAttemptAt") LocalDateTime nextAttemptAt);

    int confirmDeletePass(
        @Param("workspaceId") int workspaceId,
        @Param("id") long id,
        @Param("nextAttemptAt") LocalDateTime nextAttemptAt);
}
