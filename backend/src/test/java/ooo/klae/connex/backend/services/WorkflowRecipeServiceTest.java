package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import jakarta.validation.Validation;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import ooo.klae.connex.backend.dto.WorkflowCanvas;
import ooo.klae.connex.backend.dto.WorkflowDefinition;
import ooo.klae.connex.backend.dto.WorkflowRecipePreviewDto;
import ooo.klae.connex.backend.dto.WorkflowRecipePreviewRequest;
import ooo.klae.connex.backend.dto.WorkflowSimulationDto;
import ooo.klae.connex.backend.beans.Stage;
import ooo.klae.connex.backend.mappers.WorkflowOperationsMapper;
import ooo.klae.connex.backend.mappers.PipelineMapper;
import ooo.klae.connex.backend.services.WorkflowActionRetryPolicy.RetrySafety;
import ooo.klae.connex.backend.services.WorkflowDraftCanonicalizer.CanonicalDraft;
import ooo.klae.connex.backend.tenant.Permission;

@ExtendWith(MockitoExtension.class)
class WorkflowRecipeServiceTest {

    @Mock private WorkflowService workflowService;
    @Mock private WorkflowOperationsMapper operationsMapper;
    @Mock private PipelineMapper pipelineMapper;
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
            pipelineMapper,
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
        lenient().when(simulationService.simulateDraft(
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

    @Test
    void everyRecipeMaterializesAsAValidSchemaTwoDefinitionFromTheCatalog() {
        Stage stage = new Stage();
        stage.setId(13);
        when(pipelineMapper.getStageById(7, 13)).thenReturn(stage);
        SegmentService segmentService = org.mockito.Mockito.mock(SegmentService.class);
        SystemActor systemActor = org.mockito.Mockito.mock(SystemActor.class);
        WorkflowCapabilityCatalog catalog = new WorkflowCapabilityCatalog();
        try (var validatorFactory = Validation.buildDefaultValidatorFactory()) {
            RuleDefinitionValidator ruleValidator = new RuleDefinitionValidator(
                segmentService,
                workspaceService,
                validatorFactory.getValidator(),
                new WorkflowDocumentAutomationGate(true),
                new WorkflowTriggeredSendGate(true),
                systemActor,
                catalog);
            WorkflowDefinitionValidator actualValidator = new WorkflowDefinitionValidator(
                ruleValidator, catalog);
            Map<String, String> recordTypes = Map.of(
                "person-job-change-follow-up", "person",
                "person-qualified-routing", "person",
                "deal-follow-through", "deal",
                "cooling-company-review", "company",
                "deal-won-handoff", "deal",
                "deal-renewal-preparation", "deal");

            for (Map.Entry<String, String> recipe : recordTypes.entrySet()) {
                WorkflowRecipePreviewDto preview = service.preview(
                    recipe.getKey(),
                    new WorkflowRecipePreviewRequest(
                        null, null, recipeParameters(recipe.getKey()), null));

                assertEquals(2, preview.definition().schemaVersion(), recipe.getKey());
                assertTrue(preview.unresolvedParameters().isEmpty(), recipe.getKey());
                assertDoesNotThrow(
                    () -> actualValidator.validateForMutation(
                        recipe.getValue(), "user", preview.definition()),
                    recipe.getKey());
            }
        }
    }

    private Map<String, JsonNode> recipeParameters(String recipeKey) {
        Map<String, JsonNode> parameters = new LinkedHashMap<>();
        parameters.put("actorUserId", objectMapper.valueToTree(41));
        parameters.put("targetUserId", objectMapper.valueToTree(41));
        parameters.put("taskTitle", objectMapper.valueToTree("Follow up"));
        parameters.put("dueInDays", objectMapper.valueToTree(7));
        switch (recipeKey) {
            case "deal-won-handoff" -> {
                parameters.put("activityNote", objectMapper.valueToTree("Handoff complete"));
                parameters.put("completionTimeoutDays", objectMapper.valueToTree(7));
            }
            case "cooling-company-review" ->
                parameters.put("coolingDays", objectMapper.valueToTree(30));
            case "deal-follow-through" -> {
                parameters.put("targetStageId", objectMapper.valueToTree(13));
                parameters.put("completionTimeoutDays", objectMapper.valueToTree(7));
            }
            case "deal-renewal-preparation" -> {
                parameters.put("offsetDays", objectMapper.valueToTree(-30));
                parameters.put("localTime", objectMapper.valueToTree("09:00"));
                parameters.put("timezone", objectMapper.valueToTree("Pacific/Honolulu"));
                parameters.put("completionTimeoutDays", objectMapper.valueToTree(7));
            }
            default -> {
            }
        }
        return parameters;
    }
}
