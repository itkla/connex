package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import ooo.klae.connex.backend.dto.WorkflowCanvas;
import ooo.klae.connex.backend.dto.WorkflowDefinition;
import ooo.klae.connex.backend.dto.WorkflowRecipePreviewDto;
import ooo.klae.connex.backend.dto.WorkflowRecipePreviewRequest;
import ooo.klae.connex.backend.dto.WorkflowSimulationDto;
import ooo.klae.connex.backend.mappers.WorkflowOperationsMapper;
import ooo.klae.connex.backend.services.WorkflowActionRetryPolicy.RetrySafety;
import ooo.klae.connex.backend.services.WorkflowDraftCanonicalizer.CanonicalDraft;
import ooo.klae.connex.backend.tenant.Permission;

@ExtendWith(MockitoExtension.class)
class WorkflowRecipeServiceTest {

    @Mock private WorkflowService workflowService;
    @Mock private WorkflowOperationsMapper operationsMapper;
    @Mock private WorkflowDraftCanonicalizer canonicalizer;
    @Mock private WorkflowDefinitionValidator definitionValidator;
    @Mock private WorkflowActionRetryPolicy retryPolicy;
    @Mock private WorkflowSimulationService simulationService;
    @Mock private WorkspaceService workspaceService;

    private ObjectMapper objectMapper;
    private WorkflowRecipeService service;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        service = new WorkflowRecipeService(
            workflowService,
            operationsMapper,
            canonicalizer,
            definitionValidator,
            retryPolicy,
            simulationService,
            workspaceService,
            objectMapper);
        when(workspaceService.getCurrentUserId()).thenReturn(41);
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(7);
        when(workspaceService.getRole(7, 41)).thenReturn("admin");
        when(workspaceService.permissionsFor(7, 41)).thenReturn(Set.of(Permission.TASK_CREATE));
        when(canonicalizer.canonicalizeDraft(
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            any(WorkflowDefinition.class),
            any(WorkflowCanvas.class)))
            .thenReturn(new CanonicalDraft(
                "Job change follow-up",
                "Description",
                "person",
                "user",
                "{}",
                "{}",
                new byte[32]));
        when(definitionValidator.validateForMutation(
            anyString(), anyString(), any(WorkflowDefinition.class)))
            .thenReturn(Set.of(Permission.TASK_CREATE));
        when(retryPolicy.safety(any())).thenReturn(RetrySafety.TRANSACTIONAL);
        when(simulationService.simulateDraft(
            any(CanonicalDraft.class),
            any(WorkflowDefinition.class),
            anyInt(),
            anyInt(),
            anyInt()))
            .thenReturn(new WorkflowSimulationDto(
                WorkflowSimulationDto.Result.WOULD_COMPLETE,
                java.util.List.of(),
                java.util.List.of()));
    }

    @Test
    void previewIsReadOnlyAndReturnsNoCreatedWrites() {
        Map<String, JsonNode> parameters = new LinkedHashMap<>();
        parameters.put("actorUserId", objectMapper.valueToTree(41));
        parameters.put("targetUserId", objectMapper.valueToTree(41));
        parameters.put("taskTitle", objectMapper.valueToTree("Follow up"));
        parameters.put("dueInDays", objectMapper.valueToTree(7));

        WorkflowRecipePreviewDto preview = service.preview(
            "person-job-change-follow-up",
            new WorkflowRecipePreviewRequest(null, null, parameters, 11));

        assertFalse(preview.writesCreated());
        assertTrue(preview.unresolvedParameters().isEmpty());
        assertTrue(preview.validation().canPublish());
        assertTrue(preview.exampleResult() != null);
        verifyNoInteractions(workflowService, operationsMapper);
    }
}
