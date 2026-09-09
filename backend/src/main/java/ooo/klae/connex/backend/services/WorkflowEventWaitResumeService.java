package ooo.klae.connex.backend.services;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import ooo.klae.connex.backend.beans.TaskCompletionEvent;
import ooo.klae.connex.backend.beans.WorkflowEventWait;
import ooo.klae.connex.backend.beans.WorkflowRun;
import ooo.klae.connex.backend.beans.WorkflowStepRun;
import ooo.klae.connex.backend.beans.WorkflowVersion;
import ooo.klae.connex.backend.dto.WorkflowEdge;
import ooo.klae.connex.backend.dto.WorkflowNode;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.mappers.TaskMapper;
import ooo.klae.connex.backend.mappers.WorkflowEventWaitMapper;
import ooo.klae.connex.backend.mappers.WorkflowRunMapper;
import ooo.klae.connex.backend.mappers.WorkflowVersionMapper;
import ooo.klae.connex.backend.services.WorkflowDefinitionValidator.CompiledWorkflow;

/** Resolves one leased correlated task-completion wait without creating another node step. */
@Service
@RequiredArgsConstructor
public class WorkflowEventWaitResumeService {

    private final WorkflowRunMapper runMapper;
    private final WorkflowVersionMapper versionMapper;
    private final WorkflowEventWaitMapper eventWaitMapper;
    private final TaskMapper taskMapper;
    private final WorkflowTraversalService traversalService;
    private final WorkflowExecutionPrincipalService principalService;
    private final WorkflowRecordPolicyService recordPolicyService;
    private final AutomationExecutor automationExecutor;
    private final WorkspaceService workspaceService;

    @Transactional(
        propagation = Propagation.REQUIRES_NEW,
        isolation = Isolation.READ_COMMITTED)
    public boolean resume(int workspaceId, long runId, String leaseOwner) {
        WorkflowRun discovered = runMapper.getByIdInWorkspace(workspaceId, runId);
        if (discovered == null || !"running".equals(discovered.getStatus())
                || !"event".equals(discovered.getWaitKind())) {
            return false;
        }
        WorkflowVersion version = versionMapper.getById(
            workspaceId, discovered.getWorkflowId(), discovered.getWorkflowVersionId());
        if (version == null) {
            throw unavailable("definition_unavailable", "The pinned workflow version is unavailable.");
        }
        WorkspaceService.LockedPermissionSnapshot authorization = lockAuthorization(
            workspaceId, version);
        WorkflowExecutionPrincipal principal = principalService.resolveLocked(
            workspaceId, version, authorization);
        WorkflowRun run = runMapper.getOwnedByIdForUpdate(workspaceId, runId, leaseOwner);
        if (run == null
                || !"event".equals(run.getWaitKind())
                || !Objects.equals(discovered.getCurrentNodeId(), run.getCurrentNodeId())
                || discovered.getWorkflowId() != run.getWorkflowId()
                || discovered.getWorkflowVersionId() != run.getWorkflowVersionId()) {
            return false;
        }
        if (!Objects.equals(run.getActorUserId(), principal.actorUserId())
                || !Objects.equals(run.getAttributionUserId(), principal.attributionUserId())) {
            throw unavailable(
                "actor_changed",
                "The configured workflow actor changed after the run started.");
        }
        CompiledWorkflow compiled = traversalService.compiled(run);
        WorkflowNode node = compiled.node(run.getCurrentNodeId());
        if (!(node instanceof WorkflowNode.Wait)) {
            throw unavailable(
                "wait_checkpoint_invalid",
                "The waiting workflow event checkpoint is invalid.");
        }
        String stopReason = automationExecutor.runAs(
            workspaceId,
            principal.principal(),
            principal.role(),
            () -> recordPolicyService.stopReason(
                run, compiled, principal.attributionUserId()));
        WorkflowStepRun step = runMapper.getStepByNodeForUpdate(
            workspaceId, runId, run.getCurrentNodeId());
        if (step == null || !"wait".equals(step.getNodeType())
                || !"waiting".equals(step.getStatus())) {
            throw unavailable(
                "wait_checkpoint_invalid",
                "The waiting workflow event checkpoint is invalid.");
        }
        WorkflowEventWait wait = eventWaitMapper.getByRunNodeForUpdate(
            workspaceId, runId, run.getCurrentNodeId());
        if (wait == null || wait.getResolution() != null
                || wait.getWorkflowStepRunId() != step.getId()) {
            throw unavailable(
                "wait_checkpoint_invalid",
                "The waiting workflow event evidence is invalid.");
        }
        if (stopReason != null) {
            LocalDateTime stoppedAt = runMapper.currentTimestamp(
                workspaceId, run.getWorkflowId());
            if (stoppedAt == null
                    || eventWaitMapper.resolve(
                        workspaceId, wait.getId(), "stopped", null, stoppedAt) != 1
                    || runMapper.skipExistingStep(
                        workspaceId, runId, run.getCurrentNodeId(), stoppedAt) != 1
                    || runMapper.stopClaimedRun(
                        workspaceId, runId, run.getCurrentNodeId(), leaseOwner,
                        stopReason, stoppedAt) != 1) {
                throw new IllegalStateException("Workflow event wait stop was not persisted");
            }
            return false;
        }
        taskMapper.getTaskByIdForUpdate(workspaceId, wait.getSourceTaskId());
        TaskCompletionEvent completion = eventWaitMapper.getEarliestCompletion(
            workspaceId, wait.getSourceTaskId(), wait.getTimeoutAt());
        LocalDateTime databaseNow = runMapper.currentTimestamp(workspaceId, run.getWorkflowId());
        if (databaseNow == null) {
            throw unavailable("definition_unavailable", "The workflow is unavailable.");
        }
        String outcome;
        Long matchedEventId;
        if (completion != null) {
            outcome = "completed";
            matchedEventId = completion.getId();
        } else if (!databaseNow.isBefore(wait.getTimeoutAt())) {
            outcome = "timeout";
            matchedEventId = null;
        } else {
            if (runMapper.returnEventWaitToWaiting(
                    workspaceId, runId, run.getCurrentNodeId(), leaseOwner) != 1) {
                throw new IllegalStateException("Workflow event wait lease was not returned");
            }
            return false;
        }
        WorkflowEdge.Outcome edgeOutcome = "completed".equals(outcome)
            ? WorkflowEdge.Outcome.COMPLETED : WorkflowEdge.Outcome.TIMEOUT;
        WorkflowEdge edge = compiled.transition(run.getCurrentNodeId(), edgeOutcome);
        if (edge == null) {
            throw unavailable(
                "definition_corrupt",
                "The pinned workflow wait transition is unavailable.");
        }
        if (eventWaitMapper.resolve(
                workspaceId, wait.getId(), outcome, matchedEventId, databaseNow) != 1) {
            return false;
        }
        if (runMapper.succeedWaitingEventStep(
                workspaceId, runId, run.getCurrentNodeId(), outcome,
                edge.id(), edge.targetNodeId(), databaseNow) != 1) {
            throw new IllegalStateException("Workflow event wait step was not resolved");
        }
        if (runMapper.advanceClaimedRun(
                workspaceId, runId, run.getCurrentNodeId(),
                edge.targetNodeId(), leaseOwner) != 1) {
            throw new IllegalStateException("Workflow event wait was not resumed");
        }
        return true;
    }

    private WorkspaceService.LockedPermissionSnapshot lockAuthorization(
            int workspaceId, WorkflowVersion version) {
        Integer memberId = "system".equals(version.getExecutionMode())
            ? version.getCreatedById() : version.getRunAsUserId();
        if (memberId == null || memberId < 1) {
            throw unavailable(
                "actor_unavailable",
                "The configured workflow actor is unavailable.");
        }
        try {
            return workspaceService.lockAndRequirePermissionsSnapshot(
                workspaceId, Map.of(memberId, Set.of()));
        } catch (ForbiddenException exception) {
            throw unavailable(
                "actor_unavailable",
                "The configured workflow actor is unavailable.");
        }
    }

    private static WorkflowExecutionException unavailable(String code, String message) {
        return new WorkflowExecutionException(code, message, true);
    }
}
