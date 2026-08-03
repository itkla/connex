package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ooo.klae.connex.backend.beans.Workflow;
import ooo.klae.connex.backend.beans.WorkflowInvocation;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.mappers.WorkflowMapper;
import ooo.klae.connex.backend.mappers.WorkflowOperationsMapper;

@ExtendWith(MockitoExtension.class)
class WorkflowManualRunConfirmationTransactionTest {

    @Mock private WorkflowMapper workflowMapper;
    @Mock private WorkflowOperationsMapper operationsMapper;

    @InjectMocks private WorkflowManualRunConfirmationTransaction transaction;

    @Test
    void rejectsFrozenScopeWithoutRunnableRecords() {
        Workflow workflow = new Workflow();
        workflow.setId(11);
        workflow.setWorkspaceId(7);
        workflow.setEnabled(true);
        workflow.setRuntimeOwner("canonical");
        workflow.setActiveVersionId(19L);
        WorkflowInvocation invocation = new WorkflowInvocation();
        invocation.setId(31L);
        invocation.setRequestedById(41);
        invocation.setWorkflowVersionId(19L);
        invocation.setScopeHash(new byte[32]);
        invocation.setReadyCount(0);
        invocation.setStatus("prepared");
        invocation.setExpiresAt(LocalDateTime.now().plusMinutes(5));
        when(workflowMapper.getByIdForUpdate(7, 11)).thenReturn(workflow);
        when(operationsMapper.getInvocationByTokenForUpdate(
            anyInt(), anyInt(), any()))
            .thenReturn(invocation);

        assertThrows(
            ConflictException.class,
            () -> transaction.confirm(
                7, 11, 41, new byte[32], new byte[32], new byte[16]));

        verify(operationsMapper, never()).confirmInvocation(
            anyInt(), anyLong(), anyInt(), any(), any(LocalDateTime.class));
    }
}
