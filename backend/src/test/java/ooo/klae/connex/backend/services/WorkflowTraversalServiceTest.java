package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ooo.klae.connex.backend.beans.WorkflowRun;
import ooo.klae.connex.backend.beans.WorkflowVersion;
import ooo.klae.connex.backend.dto.WorkflowDefinition;
import ooo.klae.connex.backend.mappers.WorkflowRunMapper;
import ooo.klae.connex.backend.mappers.WorkflowVersionMapper;
import ooo.klae.connex.backend.services.WorkflowDefinitionValidator.CompiledWorkflow;
import ooo.klae.connex.backend.services.WorkflowDraftCanonicalizer.CanonicalDraft;

@ExtendWith(MockitoExtension.class)
class WorkflowTraversalServiceTest {

    @Mock private WorkflowRunMapper workflowRunMapper;
    @Mock private WorkflowVersionMapper workflowVersionMapper;
    @Mock private WorkflowDraftCanonicalizer canonicalizer;
    @Mock private WorkflowDefinitionValidator definitionValidator;
    @Mock private WorkflowStepTransactionService stepTransactionService;
    @Mock private WorkflowActionAttemptReservationService attemptReservationService;
    @Mock private WorkflowRunCancellationService cancellationService;
    @Mock private WorkflowRunFailureService failureService;
    @Mock private CompiledWorkflow compiled;

    private WorkflowTraversalService service;

    @BeforeEach
    void setUp() {
        service = new WorkflowTraversalService(
            workflowRunMapper,
            workflowVersionMapper,
            canonicalizer,
            definitionValidator,
            stepTransactionService,
            attemptReservationService,
            cancellationService,
            failureService);
    }

    @Test
    void resumeUsesTheRunPinnedVersionAfterAConcurrentPublication() {
        WorkflowRun run = new WorkflowRun();
        run.setId(31L);
        run.setWorkspaceId(7);
        run.setWorkflowId(11);
        run.setWorkflowVersionId(19L);
        run.setStatus("running");
        run.setCurrentNodeId("trigger");
        when(workflowRunMapper.getByIdInWorkspace(7, 31L)).thenReturn(run);
        WorkflowVersion pinned = new WorkflowVersion();
        pinned.setId(19L);
        pinned.setWorkspaceId(7);
        pinned.setWorkflowId(11);
        pinned.setName("Pinned");
        pinned.setRecordType("company");
        pinned.setExecutionMode("user");
        pinned.setDefinitionJson("{}");
        pinned.setCanvasJson("{}");
        pinned.setDefinitionHash(new byte[32]);
        when(workflowVersionMapper.getById(7, 11, 19L)).thenReturn(pinned);
        CanonicalDraft canonical = new CanonicalDraft(
            "Pinned", null, "company", "user", "{}", "{}", new byte[32]);
        when(canonicalizer.canonicalizeDraftJson(
            "Pinned", null, "company", "user", "{}", "{}"))
            .thenReturn(canonical);
        WorkflowDefinition definition = new WorkflowDefinition(
            1, "trigger", List.of(), List.of());
        when(canonicalizer.parseDefinition("{}")).thenReturn(definition);
        when(definitionValidator.validate("company", "user", definition))
            .thenReturn(compiled);
        when(compiled.nodeType("trigger"))
            .thenReturn(WorkflowDefinitionValidator.NodeType.TRIGGER);
        when(stepTransactionService.execute(7, 31L, "trigger", compiled))
            .thenReturn(new WorkflowStepTransactionService.StepResult(
                true, null, true, false));

        WorkflowResumeResult result = service.resume(
            new WorkflowRunResumeCommand(7, 31L, "trigger"));

        assertEquals(WorkflowResumeResult.SUCCEEDED, result);
        verify(workflowVersionMapper).getById(7, 11, 19L);
        verify(stepTransactionService).execute(7, 31L, "trigger", compiled);
    }
}
