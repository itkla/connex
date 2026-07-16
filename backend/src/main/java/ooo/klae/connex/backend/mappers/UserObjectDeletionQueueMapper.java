package ooo.klae.connex.backend.mappers;

import java.time.LocalDateTime;
import java.util.List;

import org.apache.ibatis.annotations.Param;

import ooo.klae.connex.backend.storage.ObjectDeletionTask;

/**
 * Durable control-plane deletion tasks for globally stored user profile images.
 */
public interface UserObjectDeletionQueueMapper {
    int enqueue(
        @Param("objectKey") String objectKey,
        @Param("deletePassesRemaining") int deletePassesRemaining,
        @Param("nextAttemptAt") LocalDateTime nextAttemptAt);

    long countPending();

    long countPendingForPrefix(@Param("objectKeyPrefix") String objectKeyPrefix);

    List<ObjectDeletionTask> findDue(
        @Param("now") LocalDateTime now,
        @Param("limit") int limit);

    ObjectDeletionTask lockByKey(@Param("objectKey") String objectKey);

    ObjectDeletionTask lockByIdentity(
        @Param("id") long id,
        @Param("objectKey") String objectKey);

    ObjectDeletionTask lockDueByKey(
        @Param("objectKey") String objectKey,
        @Param("now") LocalDateTime now);

    ObjectDeletionTask lockDueByIdentity(
        @Param("id") long id,
        @Param("objectKey") String objectKey,
        @Param("now") LocalDateTime now);

    int deleteById(@Param("id") long id);

    int deleteByKey(@Param("objectKey") String objectKey);

    int deleteByIdentity(
        @Param("id") long id,
        @Param("objectKey") String objectKey);

    int reschedule(
        @Param("id") long id,
        @Param("nextAttemptAt") LocalDateTime nextAttemptAt);

    int rescheduleByKey(
        @Param("objectKey") String objectKey,
        @Param("nextAttemptAt") LocalDateTime nextAttemptAt);

    int confirmDeletePass(
        @Param("id") long id,
        @Param("nextAttemptAt") LocalDateTime nextAttemptAt);
}
