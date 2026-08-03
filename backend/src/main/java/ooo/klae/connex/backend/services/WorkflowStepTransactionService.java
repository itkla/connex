package ooo.klae.connex.backend.services;

import java.time.LocalDateTime;
import java.util.Objects;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import ooo.klae.connex.backend.beans.WorkflowRun;
import ooo.klae.connex.backend.beans.WorkflowStepRun;
import ooo.klae.connex.backend.beans.WorkflowVersion;
import ooo.klae.connex.backend.dto.WorkflowEdge;
import ooo.klae.connex.backend.dto.WorkflowNode;
import ooo.klae.connex.backend.mappers.WorkflowRunMapper;
import ooo.klae.connex.backend.mappers.WorkflowVersionMapper;
import ooo.klae.connex.backend.services.WorkflowDefinitionValidator.CompiledWorkflow;
import ooo.klae.connex.backend.services.WorkflowDefinitionValidator.NodeType;

/** Commits one node's side effect and checkpoint atomically in a fresh transaction. */
@Service
@RequiredArgsConstructor
public class WorkflowStepTransactionService {

    private final WorkflowRunMapper workflowRunMapper;
    private final WorkflowVersionMapper workflowVersionMapper;
    private final WorkflowExecutionPrincipalService principalService;
    private final WorkflowRecordGuard recordGuard;
    private final WorkflowNodeExecutor nodeExecutor;

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public StepResult execute(
            int workspaceId,
            long runId,
            String expectedNodeId,
            CompiledWorkflow compiled) {
        WorkflowRun run = workflowRunMapper.getByIdForUpdate(workspaceId, runId);
        if (run == null
                || !"running".equals(run.getStatus())
                || !Objects.equals(expectedNodeId, run.getCurrentNodeId())) {
            return StepResult.noOp();
        }
        LocalDateTime startedAt = LocalDateTime.now();
        WorkflowVersion version = workflowVersionMapper.getById(
            workspaceId, run.getWorkflowId(), run.getWorkflowVersionId());
        if (version == null) {
            throw new WorkflowExecutionException(
                "definition_unavailable",
                "The pinned workflow version is unavailable.",
                true);
        }
        WorkflowNode node = compiled.node(expectedNodeId);
        NodeType nodeType = compiled.nodeType(expectedNodeId);
        if (node == null || nodeType == null) {
            throw new WorkflowExecutionException(
                "definition_corrupt",
                "The pinned workflow definition is inconsistent.",
                true);
        }
        WorkflowExecutionPrincipal principal = principalService.resolve(workspaceId, version);
        if (!Objects.equals(run.getActorUserId(), principal.actorUserId())
                || !Objects.equals(run.getAttributionUserId(), principal.attributionUserId())) {
            throw new WorkflowExecutionException(
                "actor_changed",
                "The configured workflow actor changed after the run started.",
                true);
        }
        recordGuard.requireAccessible(run);
        WorkflowStepTransition transition = nodeExecutor.execute(
            new WorkflowNodeExecutionContext(run, version, compiled, principal), node);
        LocalDateTime finishedAt = LocalDateTime.now();
        WorkflowEdge edge = transition.outcome() == null
            ? null
            : compiled.transition(expectedNodeId, transition.outcome());
        if (transition.continuation() == WorkflowStepTransition.Continuation.IMMEDIATE
                && edge == null) {
            throw new WorkflowExecutionException(
                "definition_corrupt",
                "The pinned workflow transition is unavailable.",
                true);
        }
        int sequence = workflowRunMapper.nextSequence(workspaceId, runId);
        if (sequence < 0 || sequence > 49) {
            throw new WorkflowExecutionException(
                "traversal_limit",
                "The workflow traversal exceeded its bounded node limit.",
                true);
        }
        WorkflowStepRun step = successfulStep(
            run, nodeType, sequence, edge, transition, startedAt, finishedAt);
        workflowRunMapper.insertStep(step);
        if (transition.continuation() == WorkflowStepTransition.Continuation.TERMINAL) {
            if (workflowRunMapper.completeRun(
                    workspaceId, runId, expectedNodeId, finishedAt) != 1) {
                throw new IllegalStateException("Workflow run completion checkpoint was not advanced");
            }
            return new StepResult(true, null, true, false);
        }
        if (transition.continuation() == WorkflowStepTransition.Continuation.SUSPENDED) {
            return new StepResult(true, expectedNodeId, false, true);
        }
        if (workflowRunMapper.advanceRun(
                workspaceId, runId, expectedNodeId, edge.targetNodeId()) != 1) {
            throw new IllegalStateException("Workflow run node checkpoint was not advanced");
        }
        return new StepResult(true, edge.targetNodeId(), false, false);
    }

    private static WorkflowStepRun successfulStep(
            WorkflowRun run,
            NodeType nodeType,
            int sequence,
            WorkflowEdge edge,
            WorkflowStepTransition transition,
            LocalDateTime startedAt,
            LocalDateTime finishedAt) {
        WorkflowStepRun step = new WorkflowStepRun();
        step.setWorkspaceId(run.getWorkspaceId());
        step.setWorkflowRunId(run.getId());
        step.setSequenceNumber(sequence);
        step.setNodeId(run.getCurrentNodeId());
        step.setNodeType(nodeType.name().toLowerCase(java.util.Locale.ROOT));
        step.setStatus("succeeded");
        step.setAttemptCount(1);
        step.setSelectedOutcome(transition.outcome() == null
            ? null : transition.outcome().value());
        step.setSelectedEdgeId(edge == null ? null : edge.id());
        step.setNextNodeId(edge == null ? null : edge.targetNodeId());
        step.setStartedAt(startedAt);
        step.setFinishedAt(finishedAt);
        return step;
    }

    /** Result of one isolated node transaction. */
    public record StepResult(
        boolean executed,
        String nextNodeId,
        boolean terminal,
        boolean suspended
    ) {

        static StepResult noOp() {
            return new StepResult(false, null, false, false);
        }
    }
}
