package ooo.klae.connex.backend.services;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import ooo.klae.connex.backend.beans.Workflow;
import ooo.klae.connex.backend.beans.WorkflowRun;
import ooo.klae.connex.backend.beans.WorkflowStepRun;
import ooo.klae.connex.backend.dto.WorkflowRunOperationDto;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.WorkflowMapper;
import ooo.klae.connex.backend.mappers.WorkflowRunMapper;
import ooo.klae.connex.backend.tenant.Permission;
import ooo.klae.connex.backend.tenant.RequirePermission;

/** Audited operator controls for canonical workflow run cancellation and retry. */
@Service
@RequiredArgsConstructor
public class WorkflowRunOperationService {

    private static final Set<String> TERMINAL_STATUSES = Set.of(
        "succeeded", "failed", "skipped", "cancelled", "intervention_required");

    private final WorkflowMapper workflowMapper;
    private final WorkflowRunMapper runMapper;
    private final WorkflowRuntimeProperties properties;
    private final WorkspaceService workspaceService;
    private final AuditService auditService;

    @Transactional
    @RequirePermission(Permission.RULE_MANAGE)
    public WorkflowRunOperationDto cancel(int workflowId, String runKeyValue) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        Workflow workflow = requireWorkflow(workspaceId, workflowId);
        WorkflowRunKey runKey = requireCanonical(runKeyValue);
        WorkflowRun run = requireRunForUpdate(
            workspaceId, workflowId, runKey.id());
        String priorStatus = run.getStatus();
        boolean requested = run.getCancelRequestedAt() != null;
        boolean changed = false;
        if ("queued".equals(priorStatus) || "waiting".equals(priorStatus)) {
            LocalDateTime finishedAt = LocalDateTime.now();
            cancelCurrentStep(run, finishedAt);
            if (runMapper.cancelImmediately(workspaceId, run.getId(), finishedAt) != 1) {
                throw new IllegalStateException("Workflow run was not cancelled");
            }
            priorStatus = "cancelled";
            requested = true;
            changed = true;
        } else if ("running".equals(priorStatus)) {
            if (!requested) {
                LocalDateTime requestedAt = LocalDateTime.now();
                if (runMapper.requestCancellation(
                        workspaceId, run.getId(), requestedAt) != 1) {
                    throw new IllegalStateException("Workflow cancellation was not requested");
                }
                changed = true;
            }
            requested = true;
        } else if (!TERMINAL_STATUSES.contains(priorStatus)) {
            throw new ConflictException("Workflow run cannot be cancelled in its current state");
        }
        if (changed) {
            auditService.recordStrict(
                "workflow.run.cancel",
                "workflow",
                workflowId,
                workflow.getName(),
                "Workflow run cancellation requested",
                Map.of("runKey", runKeyValue, "status", priorStatus));
        }
        return new WorkflowRunOperationDto(runKeyValue, priorStatus, requested);
    }

    @Transactional
    @RequirePermission(Permission.RULE_MANAGE)
    public WorkflowRunOperationDto retry(int workflowId, String runKeyValue) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        Workflow workflow = requireWorkflow(workspaceId, workflowId);
        WorkflowRunKey runKey = requireCanonical(runKeyValue);
        WorkflowRun run = requireRunForUpdate(
            workspaceId, workflowId, runKey.id());
        if (!"intervention_required".equals(run.getStatus())) {
            throw new ConflictException("Workflow run is not awaiting intervention");
        }
        WorkflowStepRun step = runMapper.getStepByNodeForUpdate(
            workspaceId, run.getId(), run.getCurrentNodeId());
        if (step == null
                || !"action".equals(step.getNodeType())
                || !"failed".equals(step.getStatus())
                || "none".equals(step.getRetrySafety())) {
            throw new ConflictException("Workflow action is not retry-safe");
        }
        boolean laterSucceeded = runMapper.getSteps(workspaceId, run.getId()).stream()
            .anyMatch(candidate -> candidate.getSequenceNumber() > step.getSequenceNumber()
                && "succeeded".equals(candidate.getStatus()));
        if (laterSucceeded) {
            throw new ConflictException("Workflow run has a later committed step");
        }
        if (step.getAttemptCount() >= properties.maxActionAttempts()) {
            throw new ConflictException("Workflow action exhausted its bounded attempts");
        }
        if (runMapper.scheduleManualRetry(
                workspaceId, run.getId(), run.getCurrentNodeId()) != 1) {
            throw new IllegalStateException("Workflow retry was not scheduled");
        }
        auditService.recordStrict(
            "workflow.run.retry",
            "workflow",
            workflowId,
            workflow.getName(),
            "Workflow run retry scheduled",
            Map.of("runKey", runKeyValue, "nodeId", run.getCurrentNodeId()));
        return new WorkflowRunOperationDto(runKeyValue, "waiting", false);
    }

    private void cancelCurrentStep(WorkflowRun run, LocalDateTime finishedAt) {
        WorkflowStepRun step = runMapper.getStepByNodeForUpdate(
            run.getWorkspaceId(), run.getId(), run.getCurrentNodeId());
        if (step == null) {
            return;
        }
        runMapper.abandonRunningAttempts(
            run.getWorkspaceId(), run.getId(), step.getId(), "cancelled", finishedAt);
        runMapper.cancelExistingStep(
            run.getWorkspaceId(), run.getId(), run.getCurrentNodeId(), finishedAt);
    }

    private Workflow requireWorkflow(int workspaceId, int workflowId) {
        Workflow workflow = workflowMapper.getById(workspaceId, workflowId);
        if (workflow == null) {
            throw new ResourceNotFoundException("Workflow not found");
        }
        return workflow;
    }

    private WorkflowRun requireRunForUpdate(
            int workspaceId, int workflowId, long runId) {
        WorkflowRun run = runMapper.getByIdForUpdate(workspaceId, runId);
        if (run == null || run.getWorkflowId() != workflowId) {
            throw new ResourceNotFoundException("Workflow run not found");
        }
        return run;
    }

    private static WorkflowRunKey requireCanonical(String value) {
        WorkflowRunKey runKey = WorkflowRunKey.parse(value);
        if (!"canonical".equals(runKey.source())) {
            throw new ConflictException("Legacy workflow runs do not support this operation");
        }
        return runKey;
    }
}
