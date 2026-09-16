package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ooo.klae.connex.backend.beans.WorkflowInvocationDispatch;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.mappers.WorkflowOperationsMapper;

@ExtendWith(MockitoExtension.class)
class WorkflowManualRunRecoveryServiceTest {

    @Mock private WorkflowOperationsMapper operationsMapper;
    @Mock private WorkflowManualRunDispatchTransaction dispatchTransaction;

    @InjectMocks private WorkflowManualRunRecoveryService service;

    @Test
    void restartRecoveryCompletesOnlyTheRemainingPendingRecordExactlyOnce() {
        WorkflowInvocationDispatch remaining = new WorkflowInvocationDispatch();
        remaining.setWorkspaceId(7);
        remaining.setWorkflowId(11);
        remaining.setWorkflowVersionId(19L);
        remaining.setInvocationId(31L);
        remaining.setRecordId(102);
        when(operationsMapper.getPendingInvocationDispatches(7, 4))
            .thenReturn(List.of(remaining))
            .thenReturn(List.of());

        assertEquals(1, service.dispatchPending(7, 4));
        assertEquals(0, service.dispatchPending(7, 4));

        verify(dispatchTransaction, times(1)).dispatch(7, 11, 19L, 31L, 102);
        verify(operationsMapper, times(1)).markInvocationRunning(7, 31L);
    }

    @Test
    void restartRecoverySkipsRevokedRequesterAndDispatchesNextPendingRecord() {
        WorkflowInvocationDispatch denied = new WorkflowInvocationDispatch();
        denied.setWorkspaceId(7);
        denied.setWorkflowId(11);
        denied.setWorkflowVersionId(19L);
        denied.setInvocationId(31L);
        denied.setRecordId(102);
        WorkflowInvocationDispatch eligible = new WorkflowInvocationDispatch();
        eligible.setWorkspaceId(7);
        eligible.setWorkflowId(12);
        eligible.setWorkflowVersionId(20L);
        eligible.setInvocationId(32L);
        eligible.setRecordId(103);
        when(operationsMapper.getPendingInvocationDispatches(7, 4))
            .thenReturn(List.of(denied, eligible));
        doThrow(new ForbiddenException("Requires a built-in admin role in this workspace"))
            .when(dispatchTransaction).dispatch(7, 11, 19L, 31L, 102);

        assertEquals(2, service.dispatchPending(7, 4));

        InOrder progress = inOrder(dispatchTransaction, operationsMapper);
        progress.verify(dispatchTransaction).dispatch(7, 11, 19L, 31L, 102);
        progress.verify(operationsMapper).markInvocationRecordSkipped(7, 31L, 102, "permission");
        progress.verify(operationsMapper).markInvocationRunning(7, 31L);
        progress.verify(dispatchTransaction).dispatch(7, 12, 20L, 32L, 103);
        progress.verify(operationsMapper).markInvocationRunning(7, 32L);
    }
}
