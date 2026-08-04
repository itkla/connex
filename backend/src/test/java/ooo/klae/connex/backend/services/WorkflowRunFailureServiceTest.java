package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ooo.klae.connex.backend.beans.WorkflowRun;
import ooo.klae.connex.backend.beans.WorkflowStepRun;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.mappers.WorkflowRunMapper;
import ooo.klae.connex.backend.services.WorkflowDefinitionValidator.NodeType;

@ExtendWith(MockitoExtension.class)
class WorkflowRunFailureServiceTest {

    @Mock private WorkflowRunMapper workflowRunMapper;

    private WorkflowRunFailureService service;

    @BeforeEach
    void setUp() {
        service = new WorkflowRunFailureService(workflowRunMapper);
        WorkflowRun run = new WorkflowRun();
        run.setWorkspaceId(7);
        run.setId(31L);
        run.setStatus("running");
        run.setCurrentNodeId("action");
        when(workflowRunMapper.getByIdForUpdate(7, 31L)).thenReturn(run);
        when(workflowRunMapper.nextSequence(7, 31L)).thenReturn(2);
        when(workflowRunMapper.failRun(
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
}
