package ooo.klae.connex.backend.services;

import java.security.MessageDigest;
import java.util.Objects;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

import ooo.klae.connex.backend.beans.WorkflowRun;
import ooo.klae.connex.backend.beans.WorkflowVersion;
import ooo.klae.connex.backend.dto.WorkflowDefinition;
import ooo.klae.connex.backend.mappers.WorkflowRunMapper;
import ooo.klae.connex.backend.mappers.WorkflowVersionMapper;
import ooo.klae.connex.backend.services.WorkflowDefinitionValidator.CompiledWorkflow;
import ooo.klae.connex.backend.services.WorkflowDefinitionValidator.NodeType;
import ooo.klae.connex.backend.services.WorkflowDraftCanonicalizer.CanonicalDraft;

/** Resumes a pinned canonical DAG from its last committed node checkpoint. */
@Service
@RequiredArgsConstructor
public class WorkflowTraversalService {

    private static final int MAX_STEPS = 50;

    private final WorkflowRunMapper workflowRunMapper;
    private final WorkflowVersionMapper workflowVersionMapper;
    private final WorkflowDraftCanonicalizer canonicalizer;
    private final WorkflowDefinitionValidator definitionValidator;
    private final WorkflowStepTransactionService stepTransactionService;
    private final WorkflowActionAttemptReservationService attemptReservationService;
    private final WorkflowRunCancellationService cancellationService;
    private final WorkflowRunFailureService failureService;

    public WorkflowResumeResult resume(WorkflowRunResumeCommand command) {
        WorkflowRun initial = workflowRunMapper.getByIdInWorkspace(
            command.workspaceId(), command.runId());
        if (initial == null) {
            return WorkflowResumeResult.NO_OP;
        }
        if (command.expectedNodeId() != null
                && !Objects.equals(command.expectedNodeId(), initial.getCurrentNodeId())) {
            return WorkflowResumeResult.NO_OP;
        }
        if (!"running".equals(initial.getStatus())) {
            return terminalResult(initial.getStatus());
        }
        CompiledWorkflow compiled;
        try {
            compiled = compiled(initial);
        } catch (RuntimeException failure) {
            failureService.fail(
                initial.getWorkspaceId(), initial.getId(), initial.getCurrentNodeId(),
                NodeType.TRIGGER, failure);
            return WorkflowResumeResult.FAILED;
        }
        String nodeId = initial.getCurrentNodeId();
        for (int step = 0; step < MAX_STEPS && nodeId != null; step++) {
            NodeType nodeType = compiled.nodeType(nodeId);
            if (nodeType == null) {
                failureService.fail(
                    initial.getWorkspaceId(), initial.getId(), nodeId,
                    NodeType.TRIGGER,
                    new WorkflowExecutionException(
                        "definition_corrupt",
                        "The pinned workflow definition is inconsistent.",
                        true));
                return WorkflowResumeResult.FAILED;
            }
            WorkflowStepTransactionService.StepResult result;
            try {
                result = stepTransactionService.execute(
                    initial.getWorkspaceId(), initial.getId(), nodeId, compiled);
            } catch (RuntimeException failure) {
                boolean persisted = failureService.fail(
                    initial.getWorkspaceId(), initial.getId(), nodeId, nodeType, failure);
                if (persisted) {
                    return WorkflowResumeResult.FAILED;
                }
                WorkflowRun concurrent = workflowRunMapper.getByIdInWorkspace(
                    initial.getWorkspaceId(), initial.getId());
                if (concurrent == null || !"running".equals(concurrent.getStatus())) {
                    return concurrent == null
                        ? WorkflowResumeResult.NO_OP
                        : terminalResult(concurrent.getStatus());
                }
                nodeId = concurrent.getCurrentNodeId();
                continue;
            }
            if (!result.executed()) {
                WorkflowRun concurrent = workflowRunMapper.getByIdInWorkspace(
                    initial.getWorkspaceId(), initial.getId());
                if (concurrent == null || !"running".equals(concurrent.getStatus())) {
                    return concurrent == null
                        ? WorkflowResumeResult.NO_OP
                        : terminalResult(concurrent.getStatus());
                }
                nodeId = concurrent.getCurrentNodeId();
                continue;
            }
            if (result.terminal()) {
                return WorkflowResumeResult.SUCCEEDED;
            }
            if (result.suspended()) {
                return WorkflowResumeResult.RUNNING;
            }
            nodeId = result.nextNodeId();
        }
        if (nodeId != null) {
            NodeType nodeType = compiled.nodeType(nodeId);
            failureService.fail(
                initial.getWorkspaceId(), initial.getId(), nodeId,
                nodeType == null ? NodeType.TRIGGER : nodeType,
                new WorkflowExecutionException(
                    "traversal_limit",
                    "The workflow traversal exceeded its bounded node limit.",
                    true));
            return WorkflowResumeResult.FAILED;
        }
        return WorkflowResumeResult.NO_OP;
    }

    public WorkflowResumeResult resumeClaimed(
            int workspaceId,
            long runId,
            String leaseOwner,
            int stepBudget) {
        WorkflowRun initial = workflowRunMapper.getByIdInWorkspace(workspaceId, runId);
        if (initial == null || !"running".equals(initial.getStatus())) {
            return initial == null
                ? WorkflowResumeResult.NO_OP : terminalResult(initial.getStatus());
        }
        CompiledWorkflow compiled;
        try {
            compiled = compiled(initial);
        } catch (RuntimeException failure) {
            failureService.failClaimed(
                workspaceId,
                runId,
                initial.getCurrentNodeId(),
                leaseOwner,
                NodeType.TRIGGER,
                failure);
            return WorkflowResumeResult.FAILED;
        }
        String nodeId = initial.getCurrentNodeId();
        for (int step = 0; step < stepBudget && nodeId != null; step++) {
            NodeType nodeType = compiled.nodeType(nodeId);
            if (nodeType == null) {
                failureService.failClaimed(
                    workspaceId,
                    runId,
                    nodeId,
                    leaseOwner,
                    NodeType.TRIGGER,
                    new WorkflowExecutionException(
                        "definition_corrupt",
                        "The pinned workflow definition is inconsistent.",
                        true));
                return WorkflowResumeResult.FAILED;
            }
            try {
                if (nodeType == NodeType.ACTION
                        && attemptReservationService.reserve(
                            workspaceId, runId, nodeId, leaseOwner, compiled) == null) {
                    return lostClaimResult(workspaceId, runId, leaseOwner);
                }
                WorkflowStepTransactionService.StepResult result =
                    stepTransactionService.executeClaimed(
                        workspaceId, runId, nodeId, compiled, leaseOwner);
                if (!result.executed()) {
                    return lostClaimResult(workspaceId, runId, leaseOwner);
                }
                if (result.terminal()) {
                    return WorkflowResumeResult.SUCCEEDED;
                }
                if (result.suspended()) {
                    return WorkflowResumeResult.RUNNING;
                }
                nodeId = result.nextNodeId();
            } catch (RuntimeException failure) {
                WorkflowRunFailureService.FailureResult outcome = failureService.failClaimed(
                    workspaceId, runId, nodeId, leaseOwner, nodeType, failure);
                return switch (outcome) {
                    case RETRY_SCHEDULED -> WorkflowResumeResult.RUNNING;
                    case CANCELLED, STALE -> WorkflowResumeResult.NO_OP;
                    case INTERVENTION_REQUIRED, FAILED -> WorkflowResumeResult.FAILED;
                };
            }
        }
        if (nodeId != null) {
            if (cancellationService.yieldClaimed(workspaceId, runId, leaseOwner)) {
                return WorkflowResumeResult.NO_OP;
            }
            return WorkflowResumeResult.RUNNING;
        }
        return WorkflowResumeResult.NO_OP;
    }

    private WorkflowResumeResult lostClaimResult(
            int workspaceId, long runId, String leaseOwner) {
        if (cancellationService.finalizeClaimed(workspaceId, runId, leaseOwner)) {
            return WorkflowResumeResult.NO_OP;
        }
        WorkflowRun current = workflowRunMapper.getByIdInWorkspace(workspaceId, runId);
        return current == null
            ? WorkflowResumeResult.NO_OP : terminalResult(current.getStatus());
    }

    CompiledWorkflow compiled(WorkflowRun run) {
        WorkflowVersion version = workflowVersionMapper.getById(
            run.getWorkspaceId(), run.getWorkflowId(), run.getWorkflowVersionId());
        if (version == null) {
            throw new WorkflowExecutionException(
                "definition_unavailable",
                "The pinned workflow version is unavailable.",
                true);
        }
        CanonicalDraft canonical = canonicalizer.canonicalizeDraftJson(
            version.getName(),
            version.getDescription(),
            version.getRecordType(),
            version.getExecutionMode(),
            version.getDefinitionJson(),
            version.getCanvasJson());
        if (version.getDefinitionHash() == null
                || !MessageDigest.isEqual(
                    version.getDefinitionHash(), canonical.definitionHash())) {
            throw new WorkflowExecutionException(
                "definition_corrupt",
                "The pinned workflow definition failed its integrity check.",
                true);
        }
        WorkflowDefinition definition = canonicalizer.parseDefinition(canonical.definitionJson());
        return definitionValidator.validate(
            version.getRecordType(), version.getExecutionMode(), definition);
    }

    private static WorkflowResumeResult terminalResult(String status) {
        return "succeeded".equals(status)
            ? WorkflowResumeResult.SUCCEEDED
            : "failed".equals(status) || "intervention_required".equals(status)
                ? WorkflowResumeResult.FAILED
                : WorkflowResumeResult.NO_OP;
    }
}
