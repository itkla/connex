package ooo.klae.connex.backend.services;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

import ooo.klae.connex.backend.beans.WorkflowInvocationDispatch;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.mappers.WorkflowOperationsMapper;

/** Recovers bounded confirmed manual invocation records after interrupted request fan-out. */
@Service
@RequiredArgsConstructor
public class WorkflowManualRunRecoveryService {

    private final WorkflowOperationsMapper operationsMapper;
    private final WorkflowManualRunDispatchTransaction dispatchTransaction;

    /**
     * Skips authorization refusals after the isolated dispatch transaction rolls back, so a revoked
     * requester cannot prevent subsequent pending records and other workspace work from progressing.
     */
    public int dispatchPending(int workspaceId, int limit) {
        int dispatched = 0;
        for (WorkflowInvocationDispatch pending
                : operationsMapper.getPendingInvocationDispatches(workspaceId, limit)) {
            try {
                dispatchTransaction.dispatch(
                    pending.getWorkspaceId(),
                    pending.getWorkflowId(),
                    pending.getWorkflowVersionId(),
                    pending.getInvocationId(),
                    pending.getRecordId());
            } catch (ForbiddenException exception) {
                operationsMapper.markInvocationRecordSkipped(
                    pending.getWorkspaceId(),
                    pending.getInvocationId(),
                    pending.getRecordId(),
                    "permission");
            }
            operationsMapper.markInvocationRunning(
                pending.getWorkspaceId(), pending.getInvocationId());
            dispatched++;
        }
        return dispatched;
    }
}
