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
        WorkflowStepRun step = new WorkflowStepRun();
        step.setWorkspaceId(workspaceId);
        step.setWorkflowRunId(runId);
        step.setSequenceNumber(Math.min(sequence, 49));
        step.setNodeId(expectedNodeId);
        step.setNodeType(nodeType.name().toLowerCase(Locale.ROOT));
        step.setStatus("failed");
        step.setAttemptCount(1);
        step.setFailureCode(classified.code());
        step.setFailureMessage(classified.message());
        step.setStartedAt(finishedAt);
        step.setFinishedAt(finishedAt);
        workflowRunMapper.insertStep(step);
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

    private static final class ListStatus {
        private static final java.util.Set<String> NONTERMINAL = java.util.Set.of(
            "queued", "running", "waiting");

        private ListStatus() {
        }
    }
}
