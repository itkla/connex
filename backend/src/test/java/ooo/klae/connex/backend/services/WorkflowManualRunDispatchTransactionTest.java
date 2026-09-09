package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import tools.jackson.databind.ObjectMapper;

import ooo.klae.connex.backend.beans.WorkflowInvocation;
import ooo.klae.connex.backend.mappers.WorkflowOperationsMapper;

@ExtendWith(MockitoExtension.class)
class WorkflowManualRunDispatchTransactionTest {

    @Mock private WorkflowRuntimeClaimService claimService;
    @Mock private WorkflowManualRunConfirmationTransaction confirmationTransaction;
    @Mock private WorkflowOperationsMapper operationsMapper;
    @Mock private ObjectMapper objectMapper;

    @InjectMocks private WorkflowManualRunDispatchTransaction transaction;

    @Test
    void enrollmentRacePersistsTheClosedExecutionCategory() {
        WorkflowInvocation invocation = new WorkflowInvocation();
        invocation.setRequestedById(41);
        invocation.setScopeContractJson("{}");
        when(operationsMapper.getInvocation(7, 11, 31L)).thenReturn(invocation);
        when(claimService.claimManual(7, 11, 19L, 31L, 91, "{}"))
            .thenReturn(new WorkflowRuntimeClaimService.CanonicalClaim(
                null, false, false, true, "active_run_exists"));

        WorkflowManualRunDispatchTransaction.DispatchResult result = transaction.dispatch(
            7, 11, 19L, 31L, 91);

        assertTrue(result.skipped());
        verify(operationsMapper).markInvocationRecordSkipped(
            7, 31L, 91, "execution");
        verify(operationsMapper, never()).linkInvocationRun(
            eq(7), eq(31L), eq(91),
            anyLong());
    }
}
