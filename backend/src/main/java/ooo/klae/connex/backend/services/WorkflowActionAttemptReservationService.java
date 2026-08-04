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
import ooo.klae.connex.backend.beans.WorkflowStepAttempt;
import ooo.klae.connex.backend.beans.WorkflowStepRun;
import ooo.klae.connex.backend.dto.WorkflowNode;
import ooo.klae.connex.backend.mappers.WorkflowRunMapper;
import ooo.klae.connex.backend.services.WorkflowActionRetryPolicy.RetrySafety;
import ooo.klae.connex.backend.services.WorkflowDefinitionValidator.CompiledWorkflow;
import ooo.klae.connex.backend.services.WorkflowDefinitionValidator.NodeType;

/** Reserves one bounded action attempt before any action effect can begin. */
@Service
@RequiredArgsConstructor
public class WorkflowActionAttemptReservationService {

    private final WorkflowRunMapper runMapper;
    private final WorkflowActionRetryPolicy retryPolicy;
    private final WorkflowRuntimeProperties properties;

    @Transactional(
        propagation = Propagation.REQUIRES_NEW,
        isolation = Isolation.READ_COMMITTED)
    public Reservation reserve(
            int workspaceId,
            long runId,
            String nodeId,
            String leaseOwner,
            CompiledWorkflow compiled) {
        WorkflowRun run = runMapper.getOwnedByIdForUpdate(workspaceId, runId, leaseOwner);
        if (run == null
                || run.getCancelRequestedAt() != null
                || !Objects.equals(nodeId, run.getCurrentNodeId())) {
            return null;
        }
        WorkflowNode node = compiled.node(nodeId);
        if (!(node instanceof WorkflowNode.Action action)) {
            return null;
        }
        RetrySafety safety = retryPolicy.safety(action.config());
        if ("retry".equals(run.getWaitKind())
                && runMapper.clearClaimedRetryWait(
                    workspaceId, runId, nodeId, leaseOwner) != 1) {
            throw new IllegalStateException("Workflow retry wait was not claimed");
        }
        LocalDateTime now = LocalDateTime.now();
        WorkflowStepRun step = runMapper.getStepByNodeForUpdate(
            workspaceId, runId, nodeId);
        int attemptNumber;
        if (step == null) {
            int sequence = runMapper.nextSequence(workspaceId, runId);
            if (sequence < 0 || sequence > 49) {
                throw traversalLimit();
            }
            step = new WorkflowStepRun();
            step.setWorkspaceId(workspaceId);
            step.setWorkflowRunId(runId);
            step.setSequenceNumber(sequence);
            step.setNodeId(nodeId);
            step.setNodeType(NodeType.ACTION.name().toLowerCase(Locale.ROOT));
            step.setStatus("running");
            step.setAttemptCount(1);
            step.setRetrySafety(safety.value());
            step.setStartedAt(now);
            runMapper.insertStep(step);
            attemptNumber = 1;
        } else {
            if ("succeeded".equals(step.getStatus()) || "cancelled".equals(step.getStatus())) {
                return null;
            }
            attemptNumber = step.getAttemptCount() + 1;
            if (attemptNumber > properties.maxActionAttempts()) {
                throw new WorkflowExecutionException(
                    "retry_exhausted",
                    "The workflow action exhausted its bounded attempts.",
                    true);
            }
            if ("running".equals(step.getStatus())) {
                runMapper.abandonRunningAttempts(
                    workspaceId,
                    runId,
                    step.getId(),
                    "worker_handoff",
                    now);
            }
            if (runMapper.reserveExistingStep(
                    workspaceId,
                    runId,
                    nodeId,
                    attemptNumber,
                    safety.value(),
                    now) != 1) {
                return null;
            }
        }
        WorkflowStepAttempt attempt = new WorkflowStepAttempt();
        attempt.setWorkspaceId(workspaceId);
        attempt.setWorkflowRunId(runId);
        attempt.setWorkflowStepRunId(step.getId());
        attempt.setAttemptNumber(attemptNumber);
        attempt.setRetrySafety(safety.value());
        attempt.setStatus("running");
        attempt.setStartedAt(now);
        runMapper.insertAttempt(attempt);
        return new Reservation(step.getId(), attemptNumber, safety.value());
    }

    private static WorkflowExecutionException traversalLimit() {
        return new WorkflowExecutionException(
            "traversal_limit",
            "The workflow traversal exceeded its bounded node limit.",
            true);
    }

    /** Persisted action attempt selected for the next leased node transaction. */
    public record Reservation(
        long stepRunId,
        int attemptNumber,
        String retrySafety
    ) { }
}
