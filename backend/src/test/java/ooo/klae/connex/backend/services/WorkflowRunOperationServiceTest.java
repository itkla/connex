package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ooo.klae.connex.backend.beans.Workflow;
import ooo.klae.connex.backend.beans.WorkflowRun;
import ooo.klae.connex.backend.beans.WorkflowStepRun;
import ooo.klae.connex.backend.dto.WorkflowRunOperationDto;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.mappers.WorkflowMapper;
import ooo.klae.connex.backend.mappers.WorkflowRunMapper;

@ExtendWith(MockitoExtension.class)
class WorkflowRunOperationServiceTest {

    @Mock private WorkflowMapper workflowMapper;
    @Mock private WorkflowRunMapper runMapper;
    @Mock private WorkflowRuntimeProperties properties;
    @Mock private WorkspaceService workspaceService;
    @Mock private AuditService auditService;

    private WorkflowRunOperationService service;
    private WorkflowRun run;

    @BeforeEach
    void setUp() {
        service = new WorkflowRunOperationService(
            workflowMapper, runMapper, properties, workspaceService, auditService);
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(7);
        Workflow workflow = new Workflow();
        workflow.setId(11);
        workflow.setWorkspaceId(7);
        workflow.setName("Workflow");
        when(workflowMapper.getById(7, 11)).thenReturn(workflow);
        run = new WorkflowRun();
        run.setId(31L);
        run.setWorkspaceId(7);
        run.setWorkflowId(11);
        run.setCurrentNodeId("action");
        when(runMapper.getByIdForUpdate(7, 31L)).thenReturn(run);
    }

    @Test
    void queuedCancellationTerminatesImmediatelyAndAuditsOnce() {
        run.setStatus("queued");
        when(runMapper.cancelImmediately(eq(7), eq(31L), any())).thenReturn(1);

        WorkflowRunOperationDto result = service.cancel(11, "canonical-31");

        assertEquals("cancelled", result.status());
        assertTrue(result.cancellationRequested());
        verify(runMapper).cancelImmediately(eq(7), eq(31L), any());
        verify(auditService).recordStrict(
            eq("workflow.run.cancel"),
            eq("workflow"),
            eq(11),
            eq("Workflow"),
            eq("Workflow run cancellation requested"),
            any());
    }

    @Test
    void duplicateRunningCancellationDoesNotWriteOrAuditAgain() {
        run.setStatus("running");
        run.setCancelRequestedAt(java.time.LocalDateTime.of(2026, 8, 2, 12, 0));

        WorkflowRunOperationDto result = service.cancel(11, "canonical-31");

        assertEquals("running", result.status());
        assertTrue(result.cancellationRequested());
        verify(runMapper, never()).requestCancellation(anyInt(), anyLong(), any());
        verifyNoInteractions(auditService);
    }

    @Test
    void manualRetryRequiresPersistedRetrySafety() {
        run.setStatus("intervention_required");
        WorkflowStepRun step = new WorkflowStepRun();
        step.setNodeType("action");
        step.setStatus("failed");
        step.setRetrySafety("none");
        step.setAttemptCount(1);
        when(runMapper.getStepByNodeForUpdate(7, 31L, "action")).thenReturn(step);

        assertThrows(
            ConflictException.class,
            () -> service.retry(11, "canonical-31"));

        verify(runMapper, never()).scheduleManualRetry(anyInt(), anyLong(), any());
    }

    @Test
    void safeManualRetryReusesTheFailedStep() {
        run.setStatus("intervention_required");
        WorkflowStepRun step = new WorkflowStepRun();
        step.setNodeType("action");
        step.setStatus("failed");
        step.setRetrySafety("transactional");
        step.setAttemptCount(1);
        when(properties.maxActionAttempts()).thenReturn(3);
        when(runMapper.getStepByNodeForUpdate(7, 31L, "action")).thenReturn(step);
        when(runMapper.scheduleManualRetry(7, 31L, "action")).thenReturn(1);

        WorkflowRunOperationDto result = service.retry(11, "canonical-31");

        assertEquals("waiting", result.status());
        assertFalse(result.cancellationRequested());
        verify(runMapper).scheduleManualRetry(7, 31L, "action");
    }
}
