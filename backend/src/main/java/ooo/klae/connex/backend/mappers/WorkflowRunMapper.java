package ooo.klae.connex.backend.mappers;

import java.time.LocalDateTime;
import java.util.List;

import org.apache.ibatis.annotations.Param;

import ooo.klae.connex.backend.beans.WorkflowRun;
import ooo.klae.connex.backend.beans.WorkflowRunView;
import ooo.klae.connex.backend.beans.WorkflowStepAttempt;
import ooo.klae.connex.backend.beans.WorkflowStepRun;

/** Workspace-scoped canonical workflow run and per-node checkpoint persistence. */
public interface WorkflowRunMapper {

    void insertRun(WorkflowRun run);

    WorkflowRun getByDedupe(
        @Param("workspaceId") int workspaceId,
        @Param("workflowId") int workflowId,
        @Param("dedupeKey") String dedupeKey);

    WorkflowRun getById(
        @Param("workspaceId") int workspaceId,
        @Param("workflowId") int workflowId,
        @Param("id") long id);

    WorkflowRun getByIdInWorkspace(
        @Param("workspaceId") int workspaceId,
        @Param("id") long id);

    WorkflowRun getByIdForUpdate(
        @Param("workspaceId") int workspaceId,
        @Param("id") long id);

    WorkflowRun getOwnedByIdForUpdate(
        @Param("workspaceId") int workspaceId,
        @Param("id") long id,
        @Param("leaseOwner") String leaseOwner);

    List<WorkflowRun> getRunningByTrigger(
        @Param("workspaceId") int workspaceId,
        @Param("workflowId") int workflowId,
        @Param("triggerKey") String triggerKey,
        @Param("limit") int limit);

    int nextSequence(
        @Param("workspaceId") int workspaceId,
        @Param("workflowRunId") long workflowRunId);

    void insertStep(WorkflowStepRun step);

    WorkflowStepRun getStepByNodeForUpdate(
        @Param("workspaceId") int workspaceId,
        @Param("workflowRunId") long workflowRunId,
        @Param("nodeId") String nodeId);

    void insertAttempt(WorkflowStepAttempt attempt);

    int reserveExistingStep(
        @Param("workspaceId") int workspaceId,
        @Param("workflowRunId") long workflowRunId,
        @Param("nodeId") String nodeId,
        @Param("attemptCount") int attemptCount,
        @Param("retrySafety") String retrySafety,
        @Param("startedAt") LocalDateTime startedAt);

    int completeAttempt(
        @Param("workspaceId") int workspaceId,
        @Param("workflowRunId") long workflowRunId,
        @Param("workflowStepRunId") long workflowStepRunId,
        @Param("attemptNumber") int attemptNumber,
        @Param("finishedAt") LocalDateTime finishedAt);

    int failAttempt(
        @Param("workspaceId") int workspaceId,
        @Param("workflowRunId") long workflowRunId,
        @Param("workflowStepRunId") long workflowStepRunId,
        @Param("attemptNumber") int attemptNumber,
        @Param("status") String status,
        @Param("errorCode") String errorCode,
        @Param("finishedAt") LocalDateTime finishedAt);

    int abandonRunningAttempts(
        @Param("workspaceId") int workspaceId,
        @Param("workflowRunId") long workflowRunId,
        @Param("workflowStepRunId") long workflowStepRunId,
        @Param("errorCode") String errorCode,
        @Param("finishedAt") LocalDateTime finishedAt);

    int succeedExistingStep(
        @Param("workspaceId") int workspaceId,
        @Param("workflowRunId") long workflowRunId,
        @Param("nodeId") String nodeId,
        @Param("attemptCount") int attemptCount,
        @Param("selectedOutcome") String selectedOutcome,
        @Param("selectedEdgeId") String selectedEdgeId,
        @Param("nextNodeId") String nextNodeId,
        @Param("actionOutcome") String actionOutcome,
        @Param("actionReferenceId") Long actionReferenceId,
        @Param("finishedAt") LocalDateTime finishedAt);

    int succeedWaitingDelayStep(
        @Param("workspaceId") int workspaceId,
        @Param("workflowRunId") long workflowRunId,
        @Param("nodeId") String nodeId,
        @Param("selectedEdgeId") String selectedEdgeId,
        @Param("nextNodeId") String nextNodeId,
        @Param("finishedAt") LocalDateTime finishedAt);

    int failExistingStep(
        @Param("workspaceId") int workspaceId,
        @Param("workflowRunId") long workflowRunId,
        @Param("nodeId") String nodeId,
        @Param("failureCode") String failureCode,
        @Param("failureMessage") String failureMessage,
        @Param("finishedAt") LocalDateTime finishedAt);

    int cancelExistingStep(
        @Param("workspaceId") int workspaceId,
        @Param("workflowRunId") long workflowRunId,
        @Param("nodeId") String nodeId,
        @Param("finishedAt") LocalDateTime finishedAt);

    int countActiveRunLeases(@Param("workspaceId") int workspaceId);

    WorkflowRun findDueRunForUpdate(@Param("workspaceId") int workspaceId);

    WorkflowRun findExpiredCancellationForUpdate(
        @Param("workspaceId") int workspaceId);

    int interveneExhaustedRun(
        @Param("workspaceId") int workspaceId,
        @Param("id") long id,
        @Param("maxDispatches") int maxDispatches,
        @Param("finishedAt") LocalDateTime finishedAt);

    int leaseRun(
        @Param("workspaceId") int workspaceId,
        @Param("id") long id,
        @Param("leaseOwner") String leaseOwner,
        @Param("leaseSeconds") long leaseSeconds,
        @Param("maxDispatches") int maxDispatches);

    int releaseRunLease(
        @Param("workspaceId") int workspaceId,
        @Param("id") long id,
        @Param("leaseOwner") String leaseOwner);

    int waitForDelay(
        @Param("workspaceId") int workspaceId,
        @Param("id") long id,
        @Param("expectedNodeId") String expectedNodeId,
        @Param("leaseOwner") String leaseOwner,
        @Param("durationSeconds") int durationSeconds);

    int waitForRetry(
        @Param("workspaceId") int workspaceId,
        @Param("id") long id,
        @Param("expectedNodeId") String expectedNodeId,
        @Param("leaseOwner") String leaseOwner,
        @Param("delaySeconds") long delaySeconds);

    int clearClaimedRetryWait(
        @Param("workspaceId") int workspaceId,
        @Param("id") long id,
        @Param("expectedNodeId") String expectedNodeId,
        @Param("leaseOwner") String leaseOwner);

    int advanceRun(
        @Param("workspaceId") int workspaceId,
        @Param("id") long id,
        @Param("expectedNodeId") String expectedNodeId,
        @Param("nextNodeId") String nextNodeId);

    int advanceClaimedRun(
        @Param("workspaceId") int workspaceId,
        @Param("id") long id,
        @Param("expectedNodeId") String expectedNodeId,
        @Param("nextNodeId") String nextNodeId,
        @Param("leaseOwner") String leaseOwner);

    int completeRun(
        @Param("workspaceId") int workspaceId,
        @Param("id") long id,
        @Param("expectedNodeId") String expectedNodeId,
        @Param("finishedAt") LocalDateTime finishedAt);

    int completeClaimedRun(
        @Param("workspaceId") int workspaceId,
        @Param("id") long id,
        @Param("expectedNodeId") String expectedNodeId,
        @Param("leaseOwner") String leaseOwner,
        @Param("finishedAt") LocalDateTime finishedAt);

    int failRun(
        @Param("workspaceId") int workspaceId,
        @Param("id") long id,
        @Param("expectedNodeId") String expectedNodeId,
        @Param("status") String status,
        @Param("failureCode") String failureCode,
        @Param("failureMessage") String failureMessage,
        @Param("finishedAt") LocalDateTime finishedAt);

    int failClaimedRun(
        @Param("workspaceId") int workspaceId,
        @Param("id") long id,
        @Param("expectedNodeId") String expectedNodeId,
        @Param("leaseOwner") String leaseOwner,
        @Param("status") String status,
        @Param("failureCode") String failureCode,
        @Param("failureMessage") String failureMessage,
        @Param("finishedAt") LocalDateTime finishedAt);

    int cancelImmediately(
        @Param("workspaceId") int workspaceId,
        @Param("id") long id,
        @Param("finishedAt") LocalDateTime finishedAt);

    int requestCancellation(
        @Param("workspaceId") int workspaceId,
        @Param("id") long id,
        @Param("requestedAt") LocalDateTime requestedAt);

    int cancelClaimed(
        @Param("workspaceId") int workspaceId,
        @Param("id") long id,
        @Param("leaseOwner") String leaseOwner,
        @Param("finishedAt") LocalDateTime finishedAt);

    int cancelExpired(
        @Param("workspaceId") int workspaceId,
        @Param("id") long id,
        @Param("finishedAt") LocalDateTime finishedAt);

    int scheduleManualRetry(
        @Param("workspaceId") int workspaceId,
        @Param("id") long id,
        @Param("expectedNodeId") String expectedNodeId);

    boolean hasRunHistory(
        @Param("workspaceId") int workspaceId,
        @Param("workflowId") int workflowId);

    LocalDateTime currentTimestamp(
        @Param("workspaceId") int workspaceId,
        @Param("workflowId") int workflowId);

    List<WorkflowRunView> getPage(
        @Param("workspaceId") int workspaceId,
        @Param("workflowId") int workflowId,
        @Param("asOf") LocalDateTime asOf,
        @Param("beforeStartedAt") LocalDateTime beforeStartedAt,
        @Param("beforeId") Long beforeId,
        @Param("limit") int limit);

    WorkflowRunView getViewById(
        @Param("workspaceId") int workspaceId,
        @Param("workflowId") int workflowId,
        @Param("id") long id);

    List<WorkflowStepRun> getSteps(
        @Param("workspaceId") int workspaceId,
        @Param("workflowRunId") long workflowRunId);

    /** Reclassifies workflow steps whose queued delivery was skipped by the frequency cap. */
    int markActionDeliveryCapped(
        @Param("workspaceId") int workspaceId,
        @Param("deliveryId") long deliveryId);
}
