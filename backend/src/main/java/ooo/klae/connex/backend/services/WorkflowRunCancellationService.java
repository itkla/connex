package ooo.klae.connex.backend.services;

import java.time.LocalDateTime;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import ooo.klae.connex.backend.beans.WorkflowRun;
import ooo.klae.connex.backend.beans.WorkflowStepRun;
import ooo.klae.connex.backend.mappers.WorkflowRunMapper;

/** Finalizes a requested cancellation at a claimed node boundary. */
@Service
@RequiredArgsConstructor
public class WorkflowRunCancellationService {

    private final WorkflowRunMapper runMapper;

    @Transactional(
        propagation = Propagation.REQUIRES_NEW,
        isolation = Isolation.READ_COMMITTED)
    public boolean finalizeClaimed(int workspaceId, long runId, String leaseOwner) {
        WorkflowRun run = runMapper.getOwnedByIdForUpdate(workspaceId, runId, leaseOwner);
        if (run == null || run.getCancelRequestedAt() == null) {
            return false;
        }
        finalizeLocked(run, leaseOwner);
        return true;
    }

    @Transactional(
        propagation = Propagation.REQUIRES_NEW,
        isolation = Isolation.READ_COMMITTED)
    public boolean yieldClaimed(int workspaceId, long runId, String leaseOwner) {
        WorkflowRun run = runMapper.getOwnedByIdForUpdate(workspaceId, runId, leaseOwner);
        if (run == null) {
            return false;
        }
        if (run.getCancelRequestedAt() != null) {
            finalizeLocked(run, leaseOwner);
            return true;
        }
        if (runMapper.releaseRunLease(workspaceId, runId, leaseOwner) != 1) {
            throw new IllegalStateException("Workflow run lease was not yielded");
        }
        return false;
    }

    private void finalizeLocked(WorkflowRun run, String leaseOwner) {
        LocalDateTime finishedAt = LocalDateTime.now();
        WorkflowStepRun step = runMapper.getStepByNodeForUpdate(
            run.getWorkspaceId(), run.getId(), run.getCurrentNodeId());
        if (step != null) {
            runMapper.abandonRunningAttempts(
                run.getWorkspaceId(),
                run.getId(),
                step.getId(),
                "cancelled",
                finishedAt);
            runMapper.cancelExistingStep(
                run.getWorkspaceId(),
                run.getId(),
                run.getCurrentNodeId(),
                finishedAt);
        }
        if (runMapper.cancelClaimed(
                run.getWorkspaceId(), run.getId(), leaseOwner, finishedAt) != 1) {
            throw new IllegalStateException("Workflow cancellation was not finalized");
        }
    }
}
