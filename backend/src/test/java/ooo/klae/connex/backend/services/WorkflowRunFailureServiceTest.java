package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.CannotAcquireLockException;

import ooo.klae.connex.backend.beans.WorkflowRun;
import ooo.klae.connex.backend.beans.WorkflowStepRun;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.mappers.WorkflowRunMapper;
import ooo.klae.connex.backend.services.WorkflowDefinitionValidator.NodeType;

@ExtendWith(MockitoExtension.class)
class WorkflowRunFailureServiceTest {

    @Mock private WorkflowRunMapper workflowRunMapper;
    @Mock private WorkflowActionRetryPolicy retryPolicy;
    @Mock private WorkflowRuntimeProperties properties;
    @Mock private WorkflowInterventionRecorder interventionRecorder;

    private WorkflowRunFailureService service;

    @BeforeEach
    void setUp() {
        service = new WorkflowRunFailureService(
            workflowRunMapper, interventionRecorder, retryPolicy, properties);
        WorkflowRun run = new WorkflowRun();
        run.setWorkspaceId(7);
        run.setId(31L);
        run.setStatus("running");
        run.setCurrentNodeId("action");
        lenient().when(workflowRunMapper.getByIdForUpdate(7, 31L)).thenReturn(run);
        lenient().when(workflowRunMapper.nextSequence(7, 31L)).thenReturn(2);
        lenient().when(workflowRunMapper.failRun(
            eq(7), eq(31L), eq("action"), any(), any(), any(), any()))
            .thenReturn(1);
    }

    @Test
    void permissionChangeBecomesInterventionRequiredWithFixedSafeEvidence() {
        assertTrue(service.fail(
            7, 31L, "action", NodeType.ACTION,
            new ForbiddenException("raw permission detail")));

        ArgumentCaptor<WorkflowStepRun> step = ArgumentCaptor.forClass(
            WorkflowStepRun.class);
        verify(workflowRunMapper).insertStep(step.capture());
        assertEquals("failed", step.getValue().getStatus());
        assertEquals("permission_denied", step.getValue().getFailureCode());
        assertEquals(
            "The workflow actor no longer has permission to execute this node.",
            step.getValue().getFailureMessage());
        verify(workflowRunMapper).failRun(
            eq(7),
            eq(31L),
            eq("action"),
            eq("intervention_required"),
            eq("permission_denied"),
            eq("The workflow actor no longer has permission to execute this node."),
            any());
    }

    @Test
    void unexpectedFailureNeverPersistsRawExceptionText() {
        assertTrue(service.fail(
            7, 31L, "action", NodeType.ACTION,
            new IllegalStateException("customer content")));

        ArgumentCaptor<WorkflowStepRun> step = ArgumentCaptor.forClass(
            WorkflowStepRun.class);
        verify(workflowRunMapper).insertStep(step.capture());
        assertEquals("execution_failed", step.getValue().getFailureCode());
        assertEquals(
            "The workflow node failed before its checkpoint committed.",
            step.getValue().getFailureMessage());
    }

    @Test
    void recordStateChangeBecomesInterventionRequiredWithFixedSafeEvidence() {
        assertTrue(service.fail(
            7, 31L, "action", NodeType.ACTION,
            new ConflictException("raw record state")));

        verify(workflowRunMapper).failRun(
            eq(7),
            eq(31L),
            eq("action"),
            eq("intervention_required"),
            eq("state_conflict"),
            eq("Current record state prevents this workflow node from completing."),
            any());
    }

    @Test
    void safeTransientDatabaseFailureSchedulesABoundedRetry() {
        WorkflowRun run = new WorkflowRun();
        run.setWorkspaceId(7);
        run.setId(31L);
        run.setStatus("running");
        run.setCurrentNodeId("action");
        WorkflowStepRun step = new WorkflowStepRun();
        step.setId(41L);
        step.setWorkflowRunId(31L);
        step.setNodeId("action");
        step.setNodeType("action");
        step.setStatus("running");
        step.setAttemptCount(1);
        step.setRetrySafety("transactional");
        when(workflowRunMapper.getOwnedByIdForUpdate(7, 31L, "owner"))
            .thenReturn(run);
        when(workflowRunMapper.getStepByNodeForUpdate(7, 31L, "action"))
            .thenReturn(step);
        when(properties.maxActionAttempts()).thenReturn(3);
        when(retryPolicy.transientDatabaseFailure(any())).thenReturn(true);
        when(retryPolicy.retryDelay(31L, "action", 1))
            .thenReturn(java.time.Duration.ofSeconds(30));
        when(workflowRunMapper.failAttempt(
            eq(7), eq(31L), eq(41L), eq(1), eq("failed"),
            eq("transient_database_failure"), any())).thenReturn(1);
        when(workflowRunMapper.failExistingStep(
            eq(7), eq(31L), eq("action"), eq("transient_database_failure"),
            eq("The workflow action encountered a transient database failure."), any()))
            .thenReturn(1);
        when(workflowRunMapper.waitForRetry(
            7, 31L, "action", "owner", 30L)).thenReturn(1);

        WorkflowRunFailureService.FailureResult result = service.failClaimed(
            7,
            31L,
            "action",
            "owner",
            NodeType.ACTION,
            new CannotAcquireLockException("lock"));

        assertEquals(WorkflowRunFailureService.FailureResult.RETRY_SCHEDULED, result);
        verify(workflowRunMapper).waitForRetry(7, 31L, "action", "owner", 30L);
    }

    @Test
    void definitionFailureAbandonsAnEarlierReservedActionAttempt() {
        WorkflowRun run = new WorkflowRun();
        run.setWorkspaceId(7);
        run.setId(31L);
        run.setStatus("running");
        run.setCurrentNodeId("action");
        WorkflowStepRun step = new WorkflowStepRun();
        step.setId(41L);
        step.setNodeType("action");
        step.setStatus("running");
        when(workflowRunMapper.getOwnedByIdForUpdate(7, 31L, "owner"))
            .thenReturn(run);
        when(workflowRunMapper.getStepByNodeForUpdate(7, 31L, "action"))
            .thenReturn(step);
        when(workflowRunMapper.failExistingStep(
            eq(7), eq(31L), eq("action"), eq("permission_denied"), any(), any()))
            .thenReturn(1);
        when(workflowRunMapper.failClaimedRun(
            eq(7),
            eq(31L),
            eq("action"),
            eq("owner"),
            eq("intervention_required"),
            eq("permission_denied"),
            any(),
            any())).thenReturn(1);

        WorkflowRunFailureService.FailureResult result = service.failClaimed(
            7,
            31L,
            "action",
            "owner",
            NodeType.TRIGGER,
            new ForbiddenException("raw permission detail"));

        assertEquals(
            WorkflowRunFailureService.FailureResult.INTERVENTION_REQUIRED,
            result);
        verify(workflowRunMapper).abandonRunningAttempts(
            eq(7), eq(31L), eq(41L), eq("permission_denied"), any());
    }
}
