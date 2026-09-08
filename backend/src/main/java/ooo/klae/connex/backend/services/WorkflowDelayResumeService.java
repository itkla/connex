package ooo.klae.connex.backend.services;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import ooo.klae.connex.backend.beans.WorkflowRun;
import ooo.klae.connex.backend.beans.WorkflowVersion;
import ooo.klae.connex.backend.dto.WorkflowEdge;
import ooo.klae.connex.backend.dto.WorkflowNode;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
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
    private final WorkflowRecordPolicyService recordPolicyService;
    private final WorkspaceService workspaceService;

    @Transactional(
        propagation = Propagation.REQUIRES_NEW,
        isolation = Isolation.READ_COMMITTED)
    public boolean resume(
            int workspaceId, long runId, String leaseOwner) {
        WorkflowRun discovered = runMapper.getByIdInWorkspace(workspaceId, runId);
        if (discovered == null || !"running".equals(discovered.getStatus())) {
            return false;
        }
        WorkflowVersion version = versionMapper.getById(
            workspaceId, discovered.getWorkflowId(), discovered.getWorkflowVersionId());
        if (version == null) {
            throw new WorkflowExecutionException(
                "definition_unavailable",
                "The pinned workflow version is unavailable.",
                true);
        }
        Integer memberId = "system".equals(version.getExecutionMode())
            ? version.getCreatedById() : version.getRunAsUserId();
        if (memberId == null || memberId < 1) {
            throw actorUnavailable();
        }
        WorkspaceService.LockedPermissionSnapshot authorization;
        try {
            authorization = workspaceService.lockAndRequirePermissionsSnapshot(
                workspaceId, Map.of(memberId, Set.of()));
        } catch (ForbiddenException exception) {
            throw actorUnavailable();
        }
        WorkflowExecutionPrincipal principal = principalService.resolveLocked(
            workspaceId, version, authorization);
        WorkflowRun run = runMapper.getOwnedByIdForUpdate(workspaceId, runId, leaseOwner);
        if (run == null || run.getCancelRequestedAt() != null
                || !Objects.equals(discovered.getCurrentNodeId(), run.getCurrentNodeId())
                || discovered.getWorkflowId() != run.getWorkflowId()
                || discovered.getWorkflowVersionId() != run.getWorkflowVersionId()) {
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
        if (!Objects.equals(run.getActorUserId(), principal.actorUserId())
                || !Objects.equals(run.getAttributionUserId(), principal.attributionUserId())) {
            throw new WorkflowExecutionException(
                "actor_changed",
                "The configured workflow actor changed after the run started.",
                true);
        }
        if (compiled.schemaVersion() >= 2) {
            String stopReason = recordPolicyService.stopReason(
                run, compiled, principal.attributionUserId());
            if (stopReason != null) {
                LocalDateTime stoppedAt = LocalDateTime.now();
                if (runMapper.skipExistingStep(
                        workspaceId, runId, run.getCurrentNodeId(), stoppedAt) != 1
                        || runMapper.stopClaimedRun(
                            workspaceId, runId, run.getCurrentNodeId(), leaseOwner,
                            stopReason, stoppedAt) != 1) {
                    throw new IllegalStateException("Workflow Delay stop was not persisted");
                }
                return false;
            }
        } else {
            recordGuard.requireAccessible(run);
        }
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

    private static WorkflowExecutionException actorUnavailable() {
        return new WorkflowExecutionException(
            "actor_unavailable",
            "The configured workflow actor is unavailable.",
            true);
    }
}
