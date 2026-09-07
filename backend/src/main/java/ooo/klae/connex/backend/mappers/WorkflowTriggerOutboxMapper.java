package ooo.klae.connex.backend.mappers;

import java.time.LocalDateTime;
import java.util.List;

import org.apache.ibatis.annotations.Param;

import ooo.klae.connex.backend.beans.WorkflowOutboxTarget;
import ooo.klae.connex.backend.beans.WorkflowTriggerOutbox;

/** Tenant-scoped durable workflow trigger intake, lease, and retention persistence. */
public interface WorkflowTriggerOutboxMapper {

    List<WorkflowOutboxTarget> findEntityTargets(
        @Param("workspaceId") int workspaceId,
        @Param("recordType") String recordType,
        @Param("triggerEvent") String triggerEvent,
        @Param("limit") int limit);

    List<WorkflowOutboxTarget> findScheduleTargets(
        @Param("workspaceId") int workspaceId,
        @Param("cadence") String cadence,
        @Param("limit") int limit);

    List<Integer> workspaceIdsPage(
        @Param("afterId") int afterId,
        @Param("limit") int limit);

    void insert(WorkflowTriggerOutbox outbox);

    void ensureWorkspaceGate(@Param("workspaceId") int workspaceId);

    String getNextQueueForUpdate(@Param("workspaceId") int workspaceId);

    int setNextQueue(
        @Param("workspaceId") int workspaceId,
        @Param("nextQueue") String nextQueue);

    int countActiveLeases(@Param("workspaceId") int workspaceId);

    Long findDueIdForUpdate(@Param("workspaceId") int workspaceId);

    int deadLetterExpiredExhausted(
        @Param("workspaceId") int workspaceId,
        @Param("maxAttempts") int maxAttempts);

    int lease(
        @Param("workspaceId") int workspaceId,
        @Param("id") long id,
        @Param("leaseOwner") String leaseOwner,
        @Param("leaseSeconds") long leaseSeconds,
        @Param("maxAttempts") int maxAttempts);

    WorkflowTriggerOutbox getById(
        @Param("workspaceId") int workspaceId,
        @Param("id") long id);

    WorkflowTriggerOutbox getOwnedForUpdate(
        @Param("workspaceId") int workspaceId,
        @Param("id") long id,
        @Param("leaseOwner") String leaseOwner);

    int complete(
        @Param("workspaceId") int workspaceId,
        @Param("id") long id,
        @Param("leaseOwner") String leaseOwner);

    int invalidate(
        @Param("workspaceId") int workspaceId,
        @Param("id") long id,
        @Param("leaseOwner") String leaseOwner);

    int saveSchedulePage(
        @Param("workspaceId") int workspaceId,
        @Param("id") long id,
        @Param("leaseOwner") String leaseOwner,
        @Param("recordScanAfterId") int recordScanAfterId,
        @Param("scheduleMatchCount") int scheduleMatchCount,
        @Param("completed") boolean completed);

    int resolveDeadForWorkflow(
        @Param("workspaceId") int workspaceId,
        @Param("workflowId") int workflowId);

    int releaseForRetry(
        @Param("workspaceId") int workspaceId,
        @Param("id") long id,
        @Param("leaseOwner") String leaseOwner,
        @Param("delaySeconds") long delaySeconds,
        @Param("errorCode") String errorCode);

    int deadLetter(
        @Param("workspaceId") int workspaceId,
        @Param("id") long id,
        @Param("leaseOwner") String leaseOwner,
        @Param("errorCode") String errorCode);

    int purgeCompletedBefore(
        @Param("workspaceId") int workspaceId,
        @Param("cutoff") LocalDateTime cutoff,
        @Param("limit") int limit);

}
