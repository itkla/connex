package ooo.klae.connex.backend.services;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import ooo.klae.connex.backend.beans.WorkflowRun;
import ooo.klae.connex.backend.mappers.WorkflowOperationsMapper;

/** Isolates one exact manual record claim and invocation-link checkpoint. */
@Service
@RequiredArgsConstructor
public class WorkflowManualRunDispatchTransaction {

    private final WorkflowRuntimeClaimService claimService;
    private final WorkflowOperationsMapper operationsMapper;

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public DispatchResult dispatch(
            int workspaceId,
            int workflowId,
            long versionId,
            long invocationId,
            int recordId) {
        WorkflowRuntimeClaimService.CanonicalClaim claim = claimService.claimManual(
            workspaceId, workflowId, versionId, invocationId, recordId);
        WorkflowRun run = claim.run();
        if (claim.rejected() || run == null) {
            operationsMapper.markInvocationRecordSkipped(
                workspaceId, invocationId, recordId, "configuration");
            return new DispatchResult(null, true);
        }
        operationsMapper.linkInvocationRun(
            workspaceId, invocationId, recordId, run.getId());
        return new DispatchResult(run.getId(), false);
    }

    /** One durable record dispatch result. */
    public record DispatchResult(Long runId, boolean skipped) { }
}
