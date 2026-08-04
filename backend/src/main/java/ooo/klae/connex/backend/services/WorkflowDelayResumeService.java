package ooo.klae.connex.backend.services;

import java.time.LocalDateTime;
import java.util.Objects;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import ooo.klae.connex.backend.beans.WorkflowRun;
import ooo.klae.connex.backend.beans.WorkflowVersion;
import ooo.klae.connex.backend.dto.WorkflowEdge;
import ooo.klae.connex.backend.dto.WorkflowNode;
import ooo.klae.connex.backend.mappers.WorkflowRunMapper;
import ooo.klae.connex.backend.mappers.WorkflowVersionMapper;
import ooo.klae.connex.backend.services.WorkflowDefinitionValidator.CompiledWorkflow;

/** Completes one due Delay checkpoint without inserting another step row. */
@Service
@RequiredArgsConstructor
public class WorkflowDelayResumeService {

    private final WorkflowRunMapper runMapper;
    private final WorkflowVersionMapper versionMapper;
    private final WorkflowTraversalService traversalService;
    private final WorkflowExecutionPrincipalService principalService;
    private final WorkflowRecordGuard recordGuard;

    @Transactional(
        propagation = Propagation.REQUIRES_NEW,
        isolation = Isolation.READ_COMMITTED)
    public boolean resume(
            int workspaceId, long runId, String leaseOwner) {
        WorkflowRun run = runMapper.getOwnedByIdForUpdate(workspaceId, runId, leaseOwner);
        if (run == null || run.getCancelRequestedAt() != null) {
            return false;
        }
        CompiledWorkflow compiled = traversalService.compiled(run);
        WorkflowNode node = compiled.node(run.getCurrentNodeId());
        if (!(node instanceof WorkflowNode.Delay)) {
            throw new WorkflowExecutionException(
                "delay_checkpoint_invalid",
                "The waiting workflow Delay checkpoint is invalid.",
                true);
        }
        WorkflowVersion version = versionMapper.getById(
            workspaceId, run.getWorkflowId(), run.getWorkflowVersionId());
        if (version == null) {
            throw new WorkflowExecutionException(
                "definition_unavailable",
                "The pinned workflow version is unavailable.",
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
        WorkflowEdge edge = compiled.transition(
            run.getCurrentNodeId(), WorkflowEdge.Outcome.NEXT);
        if (edge == null) {
            throw new WorkflowExecutionException(
                "definition_corrupt",
                "The pinned Delay transition is unavailable.",
                true);
        }
        LocalDateTime finishedAt = LocalDateTime.now();
        if (runMapper.succeedWaitingDelayStep(
                workspaceId,
                runId,
                run.getCurrentNodeId(),
                edge.id(),
                edge.targetNodeId(),
                finishedAt) != 1) {
            return false;
        }
        if (runMapper.advanceClaimedRun(
                workspaceId,
                runId,
                run.getCurrentNodeId(),
                edge.targetNodeId(),
                leaseOwner) != 1) {
            throw new IllegalStateException("Workflow Delay checkpoint was not resumed");
        }
        return true;
    }
}
