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
        @Param("nextAttemptAt") LocalDateTime nextAttemptAt);

    long countPending();

    List<ObjectDeletionTask> findDue(
        @Param("now") LocalDateTime now,
        @Param("limit") int limit);

    int deleteById(@Param("id") long id);

    int deleteByKey(@Param("objectKey") String objectKey);

    int reschedule(
        @Param("id") long id,
        @Param("nextAttemptAt") LocalDateTime nextAttemptAt);

    int rescheduleByKey(
        @Param("objectKey") String objectKey,
        @Param("nextAttemptAt") LocalDateTime nextAttemptAt);
}
