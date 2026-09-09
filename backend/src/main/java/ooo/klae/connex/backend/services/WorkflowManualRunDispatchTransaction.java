package ooo.klae.connex.backend.services;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import ooo.klae.connex.backend.beans.WorkflowInvocation;
import ooo.klae.connex.backend.beans.WorkflowRun;
import ooo.klae.connex.backend.mappers.WorkflowOperationsMapper;

/** Isolates one exact manual record claim and invocation-link checkpoint. */
@Service
@RequiredArgsConstructor
public class WorkflowManualRunDispatchTransaction {

    private final WorkflowRuntimeClaimService claimService;
    private final WorkflowManualRunConfirmationTransaction confirmationTransaction;
    private final WorkflowOperationsMapper operationsMapper;
    private final ObjectMapper objectMapper;

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public DispatchResult dispatch(
            int workspaceId,
            int workflowId,
            long versionId,
            long invocationId,
            int recordId) {
        WorkflowInvocation invocation = operationsMapper.getInvocation(
            workspaceId, workflowId, invocationId);
        if (invocation == null || invocation.getRequestedById() == null) {
            return new DispatchResult(null, true);
        }
        confirmationTransaction.lockAuthorizationForDispatch(
            workspaceId, workflowId, versionId, invocation);
        WorkflowRuntimeClaimService.CanonicalClaim claim = claimService.claimManual(
            workspaceId,
            workflowId,
            versionId,
            invocationId,
            recordId,
            launchInputs(invocation.getScopeContractJson()));
        WorkflowRun run = claim.run();
        if (claim.rejected() || run == null) {
            operationsMapper.markInvocationRecordSkipped(
                workspaceId,
                invocationId,
                recordId,
                claim.statusReason() == null
                    ? "configuration"
                    : WorkflowInterventionRecorder.failureCategory(claim.statusReason()));
            return new DispatchResult(null, true);
        }
        int linked = operationsMapper.linkInvocationRun(
            workspaceId, invocationId, recordId, run.getId());
        if (linked != 1) {
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
            return new DispatchResult(null, true);
        }
        return new DispatchResult(run.getId(), false);
    }

    private String launchInputs(String scopeContractJson) {
        try {
            JsonNode contract = objectMapper.readTree(scopeContractJson);
            JsonNode inputs = contract == null ? null : contract.get("resolvedInputs");
            if (inputs == null || !inputs.isObject()) {
                return "{}";
            }
            String json = objectMapper.writeValueAsString(inputs);
            if (json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 16_384) {
                throw new IllegalStateException("Workflow launch inputs exceed their durable limit");
            }
            return json;
        } catch (IllegalStateException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("Workflow launch inputs are malformed", exception);
        }
    }

    /** One durable record dispatch result. */
    public record DispatchResult(Long runId, boolean skipped) { }
}
