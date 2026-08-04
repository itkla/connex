package ooo.klae.connex.backend.services;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

import ooo.klae.connex.backend.beans.WorkflowRun;
import ooo.klae.connex.backend.mappers.WorkflowRunMapper;
import ooo.klae.connex.backend.services.WorkflowDefinitionValidator.NodeType;

/** Resumes one database-leased workflow run through a bounded node slice. */
@Service
@RequiredArgsConstructor
public class WorkflowRunWorker {

    private final WorkflowRunMapper runMapper;
    private final WorkflowDelayResumeService delayResumeService;
    private final WorkflowTraversalService traversalService;
    private final WorkflowRunCancellationService cancellationService;
    private final WorkflowRunFailureService failureService;
    private final WorkflowRuntimeProperties properties;

    public void process(WorkflowWorkClaim claim) {
        if (cancellationService.finalizeClaimed(
                claim.workspaceId(), claim.id(), claim.leaseOwner())) {
            return;
        }
        if ("delay".equals(claim.resumedWaitKind()) && !resumeDelay(claim)) {
            cancellationService.finalizeClaimed(
                claim.workspaceId(), claim.id(), claim.leaseOwner());
            return;
        }
        traversalService.resumeClaimed(
            claim.workspaceId(),
            claim.id(),
            claim.leaseOwner(),
            properties.maxStepsPerSlice());
    }

    private boolean resumeDelay(WorkflowWorkClaim claim) {
        WorkflowRun claimedRun = runMapper.getByIdInWorkspace(
            claim.workspaceId(), claim.id());
        String expectedNodeId = claimedRun == null
            ? null : claimedRun.getCurrentNodeId();
        try {
            return delayResumeService.resume(
                claim.workspaceId(), claim.id(), claim.leaseOwner());
        } catch (RuntimeException failure) {
            if (expectedNodeId != null) {
                failureService.failClaimed(
                    claim.workspaceId(),
                    claim.id(),
                    expectedNodeId,
                    claim.leaseOwner(),
                    NodeType.DELAY,
                    failure);
            }
            return false;
        }
    }
}
