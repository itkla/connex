package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workflow;
import ooo.klae.connex.backend.dto.RuleTrigger;
import ooo.klae.connex.backend.dto.SegmentDefinition;
import ooo.klae.connex.backend.dto.WorkflowDelayConfig;
import ooo.klae.connex.backend.dto.WorkflowDefinition;
import ooo.klae.connex.backend.dto.WorkflowDiagnosticCode;
import ooo.klae.connex.backend.dto.WorkflowEdge;
import ooo.klae.connex.backend.dto.WorkflowNode;
import ooo.klae.connex.backend.dto.WorkflowSimulateRequest;
import ooo.klae.connex.backend.dto.WorkflowSimulationDto;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.mappers.DealMapper;
import ooo.klae.connex.backend.mappers.WorkflowMapper;
import ooo.klae.connex.backend.services.WorkflowDefinitionValidator.CompiledWorkflow;
import ooo.klae.connex.backend.services.WorkflowDefinitionValidator.NodeType;
import ooo.klae.connex.backend.services.WorkflowDefinitionValidator.ValidatedWorkflow;
import ooo.klae.connex.backend.services.WorkflowDraftCanonicalizer.CanonicalDraft;

@ExtendWith(MockitoExtension.class)
class WorkflowSimulationServiceTest {

    @Mock private WorkflowMapper workflowMapper;
    @Mock private DealMapper dealMapper;
    @Mock private WorkspaceService workspaceService;
    @Mock private WorkflowDraftCanonicalizer canonicalizer;
    @Mock private WorkflowDefinitionValidator definitionValidator;
    @Mock private WorkflowExecutionPrincipalService principalService;
    @Mock private WorkflowRecordGuard recordGuard;
    @Mock private WorkflowNodeDecisionService decisionService;
    @Mock private WorkflowActionGuard actionGuard;

    private WorkflowSimulationService service;
    private Workflow workflow;
    private CanonicalDraft draft;
    private WorkflowDefinition definition;

    @BeforeEach
    void setUp() {
        service = new WorkflowSimulationService(
            workflowMapper,
            dealMapper,
            workspaceService,
            canonicalizer,
            definitionValidator,
            principalService,
            recordGuard,
            decisionService,
            actionGuard);
        workflow = new Workflow();
        workflow.setId(42);
        workflow.setWorkspaceId(7);
        workflow.setName("Workflow");
        workflow.setDraftRevision(3);
        workflow.setDraftRecordType("deal");
        workflow.setDraftExecutionMode("user");
        workflow.setDraftDefinitionJson("{}");
        workflow.setDraftCanvasJson("{}");
        workflow.setDraftRunAsUserId(41);
        workflow.setCreatedById(41);
        draft = new CanonicalDraft(
            "Workflow", null, "deal", "user", "{}", "{}", new byte[32]);
        definition = new WorkflowDefinition(1, null, List.of(), List.of());
        User actor = new User();
        actor.setId(41);
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(7);
        when(workflowMapper.getById(7, 42)).thenReturn(workflow);
        lenient().when(canonicalizer.canonicalizeDraftJson(
            "Workflow", null, "deal", "user", "{}", "{}"))
            .thenReturn(draft);
        lenient().when(canonicalizer.parseDefinition("{}")).thenReturn(definition);
        lenient().when(principalService.resolveDraft(7, "user", 41, 41)).thenReturn(
            new WorkflowExecutionPrincipal(actor, "owner", 41, 41));
    }

    @Test
    void scheduleEnrollmentNoResultDoesNotTraverseTheNoEdge() {
        RuleTrigger config = new RuleTrigger();
        config.setType("schedule");
        config.setCadence("daily");
        config.setTargetStageId(99);
        WorkflowNode.Trigger trigger = new WorkflowNode.Trigger("trigger", config);
        WorkflowNode.Condition enrollment = new WorkflowNode.Condition(
            "enrollment", new SegmentDefinition());
        WorkflowNode.End end = new WorkflowNode.End("end");
        WorkflowEdge triggerEdge = edge("trigger-enrollment", "trigger", "enrollment");
        WorkflowEdge yesEdge = new WorkflowEdge(
            "enrollment-yes", "enrollment", "end", WorkflowEdge.Outcome.YES);
        WorkflowEdge noEdge = new WorkflowEdge(
            "enrollment-no", "enrollment", "end", WorkflowEdge.Outcome.NO);
        CompiledWorkflow compiled = compiled(
            Map.of("trigger", trigger, "enrollment", enrollment, "end", end),
            Map.of(
                "trigger", Map.of(WorkflowEdge.Outcome.NEXT, triggerEdge),
                "enrollment", Map.of(
                    WorkflowEdge.Outcome.YES, yesEdge,
                    WorkflowEdge.Outcome.NO, noEdge),
                "end", Map.of()),
            Map.of(
                "trigger", NodeType.TRIGGER,
                "enrollment", NodeType.CONDITION,
                "end", NodeType.END),
            "enrollment");
        when(definitionValidator.validateForMutationAndCompile("deal", "user", definition))
            .thenReturn(new ValidatedWorkflow(compiled, Set.of()));
        when(decisionService.decide(any(), any(WorkflowNode.Trigger.class)))
            .thenReturn(immediate(WorkflowEdge.Outcome.NEXT));
        when(decisionService.decide(any(), any(WorkflowNode.Condition.class)))
            .thenReturn(immediate(WorkflowEdge.Outcome.NO));

        WorkflowSimulationDto result = service.simulate(
            42, new WorkflowSimulateRequest(3, 91));

        assertEquals(WorkflowSimulationDto.Result.NOT_ENROLLED, result.result());
        assertEquals(List.of("trigger", "enrollment"),
            result.path().stream().map(WorkflowSimulationDto.PathStep::nodeId).toList());
        assertEquals(WorkflowDiagnosticCode.ENROLLMENT_NOT_MATCHED,
            result.path().getLast().code());
        verify(decisionService, never()).decide(any(), any(WorkflowNode.End.class));
        verifyNoInteractions(dealMapper);
    }

    @Test
    void delayStopsSpeculativeTraversalWithWouldWait() {
        RuleTrigger config = new RuleTrigger();
        config.setType("entity_change");
        config.setEvents(List.of("deal.updated"));
        WorkflowNode.Trigger trigger = new WorkflowNode.Trigger("trigger", config);
        WorkflowNode.Delay delay = new WorkflowNode.Delay(
            "delay", new WorkflowDelayConfig(3_600));
        WorkflowNode.End end = new WorkflowNode.End("end");
        CompiledWorkflow compiled = compiled(
            Map.of("trigger", trigger, "delay", delay, "end", end),
            Map.of(
                "trigger", Map.of(
                    WorkflowEdge.Outcome.NEXT, edge("trigger-delay", "trigger", "delay")),
                "delay", Map.of(
                    WorkflowEdge.Outcome.NEXT, edge("delay-end", "delay", "end")),
                "end", Map.of()),
            Map.of(
                "trigger", NodeType.TRIGGER,
                "delay", NodeType.DELAY,
                "end", NodeType.END),
            null);
        when(definitionValidator.validateForMutationAndCompile("deal", "user", definition))
            .thenReturn(new ValidatedWorkflow(compiled, Set.of()));
        when(decisionService.decide(any(), any(WorkflowNode.Trigger.class)))
            .thenReturn(immediate(WorkflowEdge.Outcome.NEXT));
        when(decisionService.decide(any(), any(WorkflowNode.Delay.class)))
            .thenReturn(new WorkflowStepTransition(
                WorkflowStepTransition.Continuation.SUSPENDED, null));

        WorkflowSimulationDto result = service.simulate(
            42, new WorkflowSimulateRequest(3, 91));

        assertEquals(WorkflowSimulationDto.Result.WOULD_WAIT, result.result());
        assertEquals(List.of("trigger", "delay"),
            result.path().stream().map(WorkflowSimulationDto.PathStep::nodeId).toList());
        assertEquals(WorkflowDiagnosticCode.DELAY_WAIT, result.path().getLast().code());
        verify(decisionService, never()).decide(any(), any(WorkflowNode.End.class));
    }

    @Test
    void revisionMismatchStopsBeforeDraftParsing() {
        workflow.setDraftRevision(4);

        assertThrows(
            ConflictException.class,
            () -> service.simulate(42, new WorkflowSimulateRequest(3, 91)));

        verify(canonicalizer, never()).parseDefinition(any());
    }

    private static CompiledWorkflow compiled(
            Map<String, WorkflowNode> nodes,
            Map<String, Map<WorkflowEdge.Outcome, WorkflowEdge>> outgoing,
            Map<String, NodeType> nodeTypes,
            String enrollmentNodeId) {
        return new CompiledWorkflow(
            "trigger",
            nodes,
            nodeTypes,
            outgoing,
            List.copyOf(nodes.keySet()),
            enrollmentNodeId);
    }

    private static WorkflowEdge edge(String id, String source, String target) {
        return new WorkflowEdge(id, source, target, WorkflowEdge.Outcome.NEXT);
    }

    private static WorkflowStepTransition immediate(WorkflowEdge.Outcome outcome) {
        return new WorkflowStepTransition(
            WorkflowStepTransition.Continuation.IMMEDIATE, outcome);
    }
}
