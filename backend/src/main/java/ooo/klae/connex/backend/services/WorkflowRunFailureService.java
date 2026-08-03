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
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.WorkflowRunMapper;
import ooo.klae.connex.backend.services.WorkflowDefinitionValidator.NodeType;

/** Persists fixed-code failed node and run evidence after a node transaction rolls back. */
@Service
@RequiredArgsConstructor
public class WorkflowRunFailureService {

    private final WorkflowRunMapper workflowRunMapper;
    private final WorkflowActionRetryPolicy retryPolicy;
    private final WorkflowRuntimeProperties properties;

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public boolean fail(
            int workspaceId,
            long runId,
            String expectedNodeId,
            NodeType nodeType,
            RuntimeException failure) {
        WorkflowRun run = workflowRunMapper.getByIdForUpdate(workspaceId, runId);
        if (run == null
                || !ListStatus.NONTERMINAL.contains(run.getStatus())
                || !Objects.equals(expectedNodeId, run.getCurrentNodeId())) {
            return false;
        }
        ClassifiedFailure classified = classify(failure);
        int sequence = workflowRunMapper.nextSequence(workspaceId, runId);
        if (sequence < 0 || sequence > 49) {
            classified = new ClassifiedFailure(
                "traversal_limit",
                "The workflow traversal exceeded its bounded node limit.",
                true);
        }
        LocalDateTime finishedAt = LocalDateTime.now();
        if (sequence >= 0 && sequence <= 49) {
            WorkflowStepRun step = new WorkflowStepRun();
            step.setWorkspaceId(workspaceId);
            step.setWorkflowRunId(runId);
            step.setSequenceNumber(sequence);
            step.setNodeId(expectedNodeId);
            step.setNodeType(nodeType.name().toLowerCase(Locale.ROOT));
            step.setStatus("failed");
            step.setAttemptCount(1);
            step.setRetrySafety("none");
            step.setFailureCode(classified.code());
            step.setFailureMessage(classified.message());
            step.setStartedAt(finishedAt);
            step.setFinishedAt(finishedAt);
            workflowRunMapper.insertStep(step);
        }
        String status = classified.interventionRequired()
            ? "intervention_required" : "failed";
        if (workflowRunMapper.failRun(
                workspaceId,
                runId,
                expectedNodeId,
                status,
                classified.code(),
                classified.message(),
                finishedAt) != 1) {
            throw new IllegalStateException("Workflow failure checkpoint was not advanced");
        }
        return true;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public FailureResult failClaimed(
            int workspaceId,
            long runId,
            String expectedNodeId,
            String leaseOwner,
            NodeType nodeType,
            RuntimeException failure) {
        WorkflowRun run = workflowRunMapper.getOwnedByIdForUpdate(
            workspaceId, runId, leaseOwner);
        if (run == null || !Objects.equals(expectedNodeId, run.getCurrentNodeId())) {
            return FailureResult.STALE;
        }
        if (run.getCancelRequestedAt() != null) {
            cancelClaimed(run, leaseOwner);
            return FailureResult.CANCELLED;
        }
        ClassifiedFailure classified = classify(failure);
        WorkflowStepRun step = workflowRunMapper.getStepByNodeForUpdate(
            workspaceId, runId, expectedNodeId);
        LocalDateTime finishedAt = LocalDateTime.now();
        if (nodeType == NodeType.ACTION && step != null) {
            return failAction(
                run, step, leaseOwner, failure, classified, finishedAt);
        }
        classified = persistFailedStep(
            run, step, nodeType, classified, finishedAt);
        if (workflowRunMapper.failClaimedRun(
                workspaceId,
                runId,
                expectedNodeId,
                leaseOwner,
                classified.interventionRequired()
                    ? "intervention_required" : "failed",
                classified.code(),
                classified.message(),
                finishedAt) != 1) {
            throw new IllegalStateException("Workflow failure checkpoint was not advanced");
        }
        return classified.interventionRequired()
            ? FailureResult.INTERVENTION_REQUIRED : FailureResult.FAILED;
    }

    private FailureResult failAction(
            WorkflowRun run,
            WorkflowStepRun step,
            String leaseOwner,
            RuntimeException failure,
            ClassifiedFailure classified,
            LocalDateTime finishedAt) {
        boolean retryable = !"none".equals(step.getRetrySafety())
            && retryPolicy.transientDatabaseFailure(failure)
            && step.getAttemptCount() < properties.maxActionAttempts();
        String code = classified.code();
        String message = classified.message();
        if (retryPolicy.transientDatabaseFailure(failure)) {
            if ("none".equals(step.getRetrySafety())) {
                code = "retry_not_safe";
                message = "The workflow action failed and is not safe to retry.";
            } else if (step.getAttemptCount() >= properties.maxActionAttempts()) {
                code = "retry_exhausted";
                message = "The workflow action exhausted its bounded attempts.";
            } else {
                code = "transient_database_failure";
                message = "The workflow action encountered a transient database failure.";
            }
        }
        failAttempt(run, step, code, finishedAt);
        failStep(run, code, message, finishedAt);
        if (retryable) {
            long delaySeconds = retryPolicy.retryDelay(
                run.getId(), run.getCurrentNodeId(), step.getAttemptCount()).toSeconds();
            if (workflowRunMapper.waitForRetry(
                    run.getWorkspaceId(),
                    run.getId(),
                    run.getCurrentNodeId(),
                    leaseOwner,
                    delaySeconds) != 1) {
                throw new IllegalStateException("Workflow retry checkpoint was not scheduled");
            }
            return FailureResult.RETRY_SCHEDULED;
        }
        if (workflowRunMapper.failClaimedRun(
                run.getWorkspaceId(),
                run.getId(),
                run.getCurrentNodeId(),
                leaseOwner,
                "intervention_required",
                code,
                message,
                finishedAt) != 1) {
            throw new IllegalStateException("Workflow action failure was not finalized");
        }
        return FailureResult.INTERVENTION_REQUIRED;
    }

    private void failAttempt(
            WorkflowRun run,
            WorkflowStepRun step,
            String code,
            LocalDateTime finishedAt) {
        if (workflowRunMapper.failAttempt(
                run.getWorkspaceId(),
                run.getId(),
                step.getId(),
                step.getAttemptCount(),
                "failed",
                code,
                finishedAt) != 1) {
            throw new IllegalStateException("Workflow action attempt failure was not recorded");
        }
    }

    private void failStep(
            WorkflowRun run,
            String code,
            String message,
            LocalDateTime finishedAt) {
        if (workflowRunMapper.failExistingStep(
                run.getWorkspaceId(),
                run.getId(),
                run.getCurrentNodeId(),
                code,
                message,
                finishedAt) != 1) {
            throw new IllegalStateException("Workflow action step failure was not recorded");
        }
    }

    private ClassifiedFailure persistFailedStep(
            WorkflowRun run,
            WorkflowStepRun step,
            NodeType nodeType,
            ClassifiedFailure classified,
            LocalDateTime finishedAt) {
        if (step != null) {
            if ("action".equals(step.getNodeType())
                    && "running".equals(step.getStatus())) {
                workflowRunMapper.abandonRunningAttempts(
                    run.getWorkspaceId(),
                    run.getId(),
                    step.getId(),
                    classified.code(),
                    finishedAt);
            }
            failStep(run, classified.code(), classified.message(), finishedAt);
            return classified;
        }
        int sequence = workflowRunMapper.nextSequence(run.getWorkspaceId(), run.getId());
        if (sequence < 0 || sequence > 49) {
            classified = new ClassifiedFailure(
                "traversal_limit",
                "The workflow traversal exceeded its bounded node limit.",
                true);
            return classified;
        }
        WorkflowStepRun failedStep = new WorkflowStepRun();
        failedStep.setWorkspaceId(run.getWorkspaceId());
        failedStep.setWorkflowRunId(run.getId());
        failedStep.setSequenceNumber(Math.min(sequence, 49));
        failedStep.setNodeId(run.getCurrentNodeId());
        failedStep.setNodeType(nodeType.name().toLowerCase(Locale.ROOT));
        failedStep.setStatus("failed");
        failedStep.setAttemptCount(1);
        failedStep.setRetrySafety("none");
        failedStep.setFailureCode(classified.code());
        failedStep.setFailureMessage(classified.message());
        failedStep.setStartedAt(finishedAt);
        failedStep.setFinishedAt(finishedAt);
        workflowRunMapper.insertStep(failedStep);
        return classified;
    }

    private void cancelClaimed(WorkflowRun run, String leaseOwner) {
        LocalDateTime finishedAt = LocalDateTime.now();
        WorkflowStepRun step = workflowRunMapper.getStepByNodeForUpdate(
            run.getWorkspaceId(), run.getId(), run.getCurrentNodeId());
        if (step != null) {
            workflowRunMapper.abandonRunningAttempts(
                run.getWorkspaceId(), run.getId(), step.getId(), "cancelled", finishedAt);
            workflowRunMapper.cancelExistingStep(
                run.getWorkspaceId(), run.getId(), run.getCurrentNodeId(), finishedAt);
        }
        if (workflowRunMapper.cancelClaimed(
                run.getWorkspaceId(), run.getId(), leaseOwner, finishedAt) != 1) {
            throw new IllegalStateException("Workflow cancellation was not finalized");
        }
    }

    private static ClassifiedFailure classify(RuntimeException failure) {
        if (failure instanceof WorkflowExecutionException workflowFailure) {
            return new ClassifiedFailure(
                workflowFailure.code(),
                workflowFailure.safeMessage(),
                workflowFailure.interventionRequired());
        }
        if (failure instanceof ForbiddenException) {
            return new ClassifiedFailure(
                "permission_denied",
                "The workflow actor no longer has permission to execute this node.",
                true);
        }
        if (failure instanceof ResourceNotFoundException) {
            return new ClassifiedFailure(
                "reference_unavailable",
                "A record or configured reference required by this node is unavailable.",
                true);
        }
        if (failure instanceof BadRequestException) {
            return new ClassifiedFailure(
                "configuration_invalid",
                "The active node configuration is no longer valid.",
                true);
        }
        if (failure instanceof ConflictException) {
            return new ClassifiedFailure(
                "state_conflict",
                "Current record state prevents this workflow node from completing.",
                true);
        }
        return new ClassifiedFailure(
            "execution_failed",
            "The workflow node failed before its checkpoint committed.",
            false);
    }

    private record ClassifiedFailure(
        String code,
        String message,
        boolean interventionRequired
    ) { }

    /** Durable outcome after a claimed node transaction fails. */
    public enum FailureResult {
        RETRY_SCHEDULED,
        INTERVENTION_REQUIRED,
        FAILED,
        CANCELLED,
        STALE
    }

    private static final class ListStatus {
        private static final java.util.Set<String> NONTERMINAL = java.util.Set.of(
            "queued", "running", "waiting");

        private ListStatus() {
        }
    }
}
