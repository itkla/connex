package ooo.klae.connex.backend.services;

import java.time.LocalDateTime;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import ooo.klae.connex.backend.beans.WorkflowRun;
import ooo.klae.connex.backend.beans.WorkflowStepRun;
import ooo.klae.connex.backend.mappers.WorkflowRunMapper;
import ooo.klae.connex.backend.mappers.WorkflowTriggerOutboxMapper;
import ooo.klae.connex.backend.services.WorkflowWorkClaim.Kind;

/** Serializes one bounded trigger-or-run lease under the tenant workspace gate. */
@Service
@RequiredArgsConstructor
public class WorkflowRuntimeClaimTransaction {

    private final WorkflowTriggerOutboxMapper outboxMapper;
    private final WorkflowRunMapper runMapper;
    private final WorkflowRuntimeProperties properties;
    private final WorkflowInterventionRecorder interventionRecorder;

    @Transactional(
        propagation = Propagation.REQUIRES_NEW,
        isolation = Isolation.READ_COMMITTED)
    public WorkflowWorkClaim claimNext(int workspaceId) {
        outboxMapper.ensureWorkspaceGate(workspaceId);
        finalizeExpiredCancellation(workspaceId);
        outboxMapper.deadLetterExpiredExhausted(
            workspaceId, properties.maxTriggerDeliveryAttempts());
        String preferred = outboxMapper.getNextQueueForUpdate(workspaceId);
        WorkflowWorkClaim claim = "run".equals(preferred)
            ? claimRun(workspaceId)
            : claimTrigger(workspaceId);
        if (claim == null) {
            claim = "run".equals(preferred)
                ? claimTrigger(workspaceId)
                : claimRun(workspaceId);
        }
        if (claim != null) {
            String next = claim.kind() == Kind.TRIGGER ? "run" : "trigger";
            if (outboxMapper.setNextQueue(workspaceId, next) != 1) {
                throw new IllegalStateException("Workflow runtime workspace gate was lost");
            }
        }
        return claim;
    }

    private WorkflowWorkClaim claimTrigger(int workspaceId) {
        if (outboxMapper.countActiveLeases(workspaceId)
                >= properties.maxOutboxLeasesPerWorkspace()) {
            return null;
        }
        Long outboxId = outboxMapper.findDueIdForUpdate(workspaceId);
        if (outboxId == null) {
            return null;
        }
        String owner = UUID.randomUUID().toString();
        if (outboxMapper.lease(
                workspaceId,
                outboxId,
                owner,
                properties.leaseDuration().toSeconds(),
                properties.maxTriggerDeliveryAttempts()) != 1) {
            return null;
        }
        return new WorkflowWorkClaim(Kind.TRIGGER, workspaceId, outboxId, owner, null);
    }

    private WorkflowWorkClaim claimRun(int workspaceId) {
        if (runMapper.countActiveRunLeases(workspaceId)
                >= properties.maxActiveRunsPerWorkspace()) {
            return null;
        }
        WorkflowRun run = runMapper.findDueRunForUpdate(workspaceId);
        if (run == null) {
            return null;
        }
        if (run.getDispatchCount() >= properties.maxRunDispatches()) {
            failExhaustedRun(run);
            return null;
        }
        String owner = UUID.randomUUID().toString();
        String waitKind = run.getWaitKind();
        if (runMapper.leaseRun(
                workspaceId,
                run.getId(),
                owner,
                properties.leaseDuration().toSeconds(),
                properties.maxRunDispatches()) != 1) {
            return null;
        }
        return new WorkflowWorkClaim(Kind.RUN, workspaceId, run.getId(), owner, waitKind);
    }

    private void finalizeExpiredCancellation(int workspaceId) {
        WorkflowRun run = runMapper.findExpiredCancellationForUpdate(workspaceId);
        if (run == null) {
            return;
        }
        LocalDateTime finishedAt = LocalDateTime.now();
        WorkflowStepRun step = runMapper.getStepByNodeForUpdate(
            workspaceId, run.getId(), run.getCurrentNodeId());
        if (step != null) {
            runMapper.abandonRunningAttempts(
                workspaceId,
                run.getId(),
                step.getId(),
                "cancelled",
                finishedAt);
            runMapper.cancelExistingStep(
                workspaceId, run.getId(), run.getCurrentNodeId(), finishedAt);
        }
        if (runMapper.cancelExpired(workspaceId, run.getId(), finishedAt) != 1) {
            throw new IllegalStateException("Expired workflow cancellation was not finalized");
        }
    }

    private void failExhaustedRun(WorkflowRun run) {
        LocalDateTime finishedAt = LocalDateTime.now();
        WorkflowStepRun step = runMapper.getStepByNodeForUpdate(
            run.getWorkspaceId(), run.getId(), run.getCurrentNodeId());
        if (step != null) {
            runMapper.abandonRunningAttempts(
                run.getWorkspaceId(),
                run.getId(),
                step.getId(),
                "dispatch_limit",
                finishedAt);
            runMapper.failExistingStep(
                run.getWorkspaceId(),
                run.getId(),
                run.getCurrentNodeId(),
                "dispatch_limit",
                "The workflow run exhausted its bounded worker dispatches.",
                finishedAt);
        }
        if (runMapper.interveneExhaustedRun(
                run.getWorkspaceId(),
                run.getId(),
                properties.maxRunDispatches(),
                finishedAt) != 1) {
            throw new IllegalStateException("Exhausted workflow run was not finalized");
        }
        interventionRecorder.record(run, "dispatch_limit");
    }
}
