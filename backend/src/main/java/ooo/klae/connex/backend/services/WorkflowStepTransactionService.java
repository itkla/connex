package ooo.klae.connex.backend.services;

import java.time.LocalDateTime;
import java.util.Locale;
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

/** Commits one leased node effect and its durable checkpoint atomically. */
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
        return executeInternal(workspaceId, runId, expectedNodeId, compiled, null);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public StepResult executeClaimed(
            int workspaceId,
            long runId,
            String expectedNodeId,
            CompiledWorkflow compiled,
            String leaseOwner) {
        return executeInternal(
            workspaceId, runId, expectedNodeId, compiled, leaseOwner);
    }

    private StepResult executeInternal(
            int workspaceId,
            long runId,
            String expectedNodeId,
            CompiledWorkflow compiled,
            String leaseOwner) {
        WorkflowRun run = leaseOwner == null
            ? workflowRunMapper.getByIdForUpdate(workspaceId, runId)
            : workflowRunMapper.getOwnedByIdForUpdate(workspaceId, runId, leaseOwner);
        if (run == null
                || !"running".equals(run.getStatus())
                || run.getCancelRequestedAt() != null
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
        if (node instanceof WorkflowNode.Delay delay) {
            return enterDelay(run, delay, compiled, leaseOwner, startedAt);
        }
        WorkflowStepRun reservedActionStep = node instanceof WorkflowNode.Action
                && leaseOwner != null
            ? requireReservedActionStep(run)
            : null;
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
        if (reservedActionStep == null) {
            WorkflowStepRun step = successfulStep(
                run, nodeType, nextSequence(run), edge, transition, startedAt, finishedAt);
            workflowRunMapper.insertStep(step);
        } else {
            completeReservedAction(run, reservedActionStep, edge, transition, finishedAt);
        }
        if (transition.continuation() == WorkflowStepTransition.Continuation.TERMINAL) {
            int completed = leaseOwner == null
                ? workflowRunMapper.completeRun(
                    workspaceId, runId, expectedNodeId, finishedAt)
                : workflowRunMapper.completeClaimedRun(
                    workspaceId, runId, expectedNodeId, leaseOwner, finishedAt);
            requireCheckpoint(completed, "completion");
            return new StepResult(true, null, true, false);
        }
        if (transition.continuation() == WorkflowStepTransition.Continuation.SUSPENDED) {
            return new StepResult(true, expectedNodeId, false, true);
        }
        int advanced = leaseOwner == null
            ? workflowRunMapper.advanceRun(
                workspaceId, runId, expectedNodeId, edge.targetNodeId())
            : workflowRunMapper.advanceClaimedRun(
                workspaceId, runId, expectedNodeId, edge.targetNodeId(), leaseOwner);
        requireCheckpoint(advanced, "node");
        return new StepResult(true, edge.targetNodeId(), false, false);
    }

    private StepResult enterDelay(
            WorkflowRun run,
            WorkflowNode.Delay delay,
            CompiledWorkflow compiled,
            String leaseOwner,
            LocalDateTime startedAt) {
        if (leaseOwner == null || delay.config() == null) {
            throw new WorkflowExecutionException(
                "delay_runtime_unavailable",
                "The durable Delay runtime is unavailable.",
                true);
        }
        WorkflowEdge edge = compiled.transition(run.getCurrentNodeId(), WorkflowEdge.Outcome.NEXT);
        if (edge == null) {
            throw new WorkflowExecutionException(
                "definition_corrupt",
                "The pinned Delay transition is unavailable.",
                true);
        }
        WorkflowStepRun step = new WorkflowStepRun();
        step.setWorkspaceId(run.getWorkspaceId());
        step.setWorkflowRunId(run.getId());
        step.setSequenceNumber(nextSequence(run));
        step.setNodeId(run.getCurrentNodeId());
        step.setNodeType(NodeType.DELAY.name().toLowerCase(Locale.ROOT));
        step.setStatus("waiting");
        step.setAttemptCount(1);
        step.setRetrySafety("none");
        step.setStartedAt(startedAt);
        workflowRunMapper.insertStep(step);
        if (workflowRunMapper.waitForDelay(
                run.getWorkspaceId(),
                run.getId(),
                run.getCurrentNodeId(),
                leaseOwner,
                delay.config().durationSeconds()) != 1) {
            throw new IllegalStateException("Workflow Delay checkpoint was not suspended");
        }
        return new StepResult(true, run.getCurrentNodeId(), false, true);
    }

    private WorkflowStepRun requireReservedActionStep(WorkflowRun run) {
        WorkflowStepRun step = workflowRunMapper.getStepByNodeForUpdate(
            run.getWorkspaceId(), run.getId(), run.getCurrentNodeId());
        if (step == null || !"running".equals(step.getStatus())) {
            throw new WorkflowExecutionException(
                "attempt_unavailable",
                "The workflow action attempt is unavailable.",
                true);
        }
        return step;
    }

    private void completeReservedAction(
            WorkflowRun run,
            WorkflowStepRun step,
            WorkflowEdge edge,
            WorkflowStepTransition transition,
            LocalDateTime finishedAt) {
        if (workflowRunMapper.completeAttempt(
                run.getWorkspaceId(),
                run.getId(),
                step.getId(),
                step.getAttemptCount(),
                finishedAt) != 1) {
            throw new IllegalStateException("Workflow action attempt was not completed");
        }
        if (workflowRunMapper.succeedExistingStep(
                run.getWorkspaceId(),
                run.getId(),
                run.getCurrentNodeId(),
                step.getAttemptCount(),
                transition.outcome() == null ? null : transition.outcome().value(),
                edge == null ? null : edge.id(),
                edge == null ? null : edge.targetNodeId(),
                finishedAt) != 1) {
            throw new IllegalStateException("Workflow action step was not completed");
        }
    }

    private int nextSequence(WorkflowRun run) {
        int sequence = workflowRunMapper.nextSequence(run.getWorkspaceId(), run.getId());
        if (sequence < 0 || sequence > 49) {
            throw new WorkflowExecutionException(
                "traversal_limit",
                "The workflow traversal exceeded its bounded node limit.",
                true);
        }
        return sequence;
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
        step.setNodeType(nodeType.name().toLowerCase(Locale.ROOT));
        step.setStatus("succeeded");
        step.setAttemptCount(1);
        step.setRetrySafety("none");
        step.setSelectedOutcome(transition.outcome() == null
            ? null : transition.outcome().value());
        step.setSelectedEdgeId(edge == null ? null : edge.id());
        step.setNextNodeId(edge == null ? null : edge.targetNodeId());
        step.setStartedAt(startedAt);
        step.setFinishedAt(finishedAt);
        return step;
    }

    private static void requireCheckpoint(int updated, String checkpoint) {
        if (updated != 1) {
            throw new IllegalStateException(
                "Workflow run " + checkpoint + " checkpoint was not advanced");
        }
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
