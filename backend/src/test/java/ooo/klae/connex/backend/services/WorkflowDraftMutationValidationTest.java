package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import tools.jackson.databind.json.JsonMapper;

import ooo.klae.connex.backend.beans.Workflow;
import ooo.klae.connex.backend.dto.RuleAction;
import ooo.klae.connex.backend.dto.RuleTrigger;
import ooo.klae.connex.backend.dto.WorkflowCanvas;
import ooo.klae.connex.backend.dto.WorkflowCreateRequest;
import ooo.klae.connex.backend.dto.WorkflowDefinition;
import ooo.klae.connex.backend.dto.WorkflowDraftRequest;
import ooo.klae.connex.backend.dto.WorkflowEdge;
import ooo.klae.connex.backend.dto.WorkflowNode;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.mappers.RuleMapper;
import ooo.klae.connex.backend.mappers.WorkflowMapper;
import ooo.klae.connex.backend.mappers.WorkflowVersionMapper;
import ooo.klae.connex.backend.services.WorkflowDraftCanonicalizer.CanonicalDraft;
import ooo.klae.connex.backend.services.WorkflowPrincipalLockService.LockedPrincipals;
import ooo.klae.connex.backend.tenant.Permission;

class WorkflowDraftMutationValidationTest {

    private static final ValidatorFactory VALIDATOR_FACTORY =
        Validation.buildDefaultValidatorFactory();

    private WorkflowMapper workflowMapper;
    private WorkflowVersionMapper workflowVersionMapper;
    private RuleMapper ruleMapper;
    private WorkflowPrincipalLockService principalLockService;
    private WorkspaceService workspaceService;
    private AuditService auditService;
    private WorkflowDraftCanonicalizer canonicalizer;
    private WorkflowService service;

    @BeforeEach
    void setUp() {
        workflowMapper = mock(WorkflowMapper.class);
        workflowVersionMapper = mock(WorkflowVersionMapper.class);
        ruleMapper = mock(RuleMapper.class);
        principalLockService = mock(WorkflowPrincipalLockService.class);
        workspaceService = mock(WorkspaceService.class);
        auditService = mock(AuditService.class);
        canonicalizer = new WorkflowDraftCanonicalizer();
        SegmentService segmentService = mock(SegmentService.class);
        SystemActor systemActor = mock(SystemActor.class);
        RuleDefinitionValidator ruleValidator = new RuleDefinitionValidator(
            segmentService,
            workspaceService,
            VALIDATOR_FACTORY.getValidator(),
            new WorkflowDocumentAutomationGate(true),
            new WorkflowTriggeredSendGate(true),
            systemActor);
        WorkflowDefinitionValidator definitionValidator =
            new WorkflowDefinitionValidator(ruleValidator);
        RuleDefinitionCodec definitionCodec = new RuleDefinitionCodec(
            JsonMapper.builder().build());
        service = new WorkflowService(
            workflowMapper,
            workflowVersionMapper,
            ruleMapper,
            principalLockService,
            workspaceService,
            auditService,
            canonicalizer,
            definitionValidator,
            new LegacyWorkflowGraphConverter(definitionCodec),
            definitionCodec,
            new WorkflowVersionProjection(definitionCodec),
            mock(WorkflowRuntimeProperties.class));
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(7);
        when(workspaceService.getCurrentUserId()).thenReturn(41);
    }

    @AfterAll
    static void closeValidatorFactory() {
        VALIDATOR_FACTORY.close();
    }

    @Test
    void triggerToEndDraftCanBeCreatedAndSaved() throws Exception {
        Set<Permission> permissions = Set.of(Permission.RULE_MANAGE);
        when(principalLockService.lockUserMutation(7, 41, Set.of(41), Set.of(41)))
            .thenReturn(principals(permissions));
        doAnswer(invocation -> {
            invocation.<Workflow>getArgument(0).setId(101);
            return null;
        }).when(workflowMapper).insert(any(Workflow.class));

        assertEquals(101, service.create(createRequest(triggerEndDefinition())).id());

        Workflow discovered = workflow(3, triggerEndDefinition());
        Workflow saved = workflow(4, triggerEndDefinition());
        when(workflowMapper.getById(7, 101)).thenReturn(discovered, saved);
        when(workflowMapper.getByIdForUpdate(7, 101)).thenReturn(discovered);
        when(workflowMapper.updateDraft(any(Workflow.class), eq(3))).thenReturn(1);

        assertEquals(4, service.saveDraft(101, draftRequest(3, triggerEndDefinition())).draftRevision());
    }

    @Test
    void unauthorizedSendMessageDraftIsRefusedBeforeTheWorkflowLock() throws Exception {
        Workflow discovered = workflow(3, triggerEndDefinition());
        when(workflowMapper.getById(7, 101)).thenReturn(discovered);
        when(principalLockService.lockUserMutation(7, 41, Set.of(41), Set.of(41)))
            .thenReturn(principals(Set.of(Permission.RULE_MANAGE)));

        assertThrows(
            ForbiddenException.class,
            () -> service.saveDraft(101, draftRequest(3, sendMessageDefinition())));

        verify(workflowMapper, never()).getByIdForUpdate(7, 101);
        verify(workflowMapper, never()).updateDraft(any(Workflow.class), anyInt());
    }

    @Test
    void authorizedSendMessageDraftCanBeSaved() throws Exception {
        Workflow discovered = workflow(3, triggerEndDefinition());
        Workflow saved = workflow(4, sendMessageDefinition());
        when(workflowMapper.getById(7, 101)).thenReturn(discovered, saved);
        when(workflowMapper.getByIdForUpdate(7, 101)).thenReturn(discovered);
        when(workflowMapper.updateDraft(any(Workflow.class), eq(3))).thenReturn(1);
        when(principalLockService.lockUserMutation(7, 41, Set.of(41), Set.of(41)))
            .thenReturn(principals(Set.of(
                Permission.RULE_MANAGE,
                Permission.CAMPAIGN_MANAGE,
                Permission.CAMPAIGN_SEND,
                Permission.CONSENT_MANAGE)));

        assertEquals(4, service.saveDraft(
            101, draftRequest(3, sendMessageDefinition())).draftRevision());
    }

    private WorkflowCreateRequest createRequest(WorkflowDefinition definition) throws Exception {
        WorkflowCreateRequest request = new WorkflowCreateRequest();
        request.setName("Draft");
        request.setRecordType("person");
        request.setExecutionMode("user");
        request.setDefinition(JsonMapper.builder().build().valueToTree(definition));
        request.setCanvas(JsonMapper.builder().build().valueToTree(canvas(definition)));
        return request;
    }

    private WorkflowDraftRequest draftRequest(
            int expectedRevision, WorkflowDefinition definition) throws Exception {
        WorkflowDraftRequest request = new WorkflowDraftRequest();
        request.setExpectedRevision(expectedRevision);
        request.setName("Draft");
        request.setRecordType("person");
        request.setExecutionMode("user");
        request.setDefinition(JsonMapper.builder().build().valueToTree(definition));
        request.setCanvas(JsonMapper.builder().build().valueToTree(canvas(definition)));
        return request;
    }

    private Workflow workflow(int revision, WorkflowDefinition definition) {
        JsonMapper mapper = JsonMapper.builder().build();
        CanonicalDraft draft = canonicalizer.canonicalizeDraftNodes(
            "Draft", null, "person", "user",
            mapper.valueToTree(definition), mapper.valueToTree(canvas(definition)));
        Workflow workflow = new Workflow();
        workflow.setId(101);
        workflow.setWorkspaceId(7);
        workflow.setName("Draft");
        workflow.setEnabled(false);
        workflow.setRuntimeOwner("canonical");
        workflow.setDraftRevision(revision);
        workflow.setDraftRecordType("person");
        workflow.setDraftExecutionMode("user");
        workflow.setDraftRunAsUserId(41);
        workflow.setDraftDefinitionJson(draft.definitionJson());
        workflow.setDraftCanvasJson(draft.canvasJson());
        workflow.setCreatedById(41);
        workflow.setUpdatedById(41);
        return workflow;
    }

    private static WorkflowDefinition triggerEndDefinition() {
        RuleTrigger trigger = new RuleTrigger();
        trigger.setType("entity_change");
        trigger.setEvents(List.of("person.updated"));
        return new WorkflowDefinition(
            1,
            "trigger",
            List.of(new WorkflowNode.Trigger("trigger", trigger), new WorkflowNode.End("end")),
            List.of(new WorkflowEdge("next", "trigger", "end", WorkflowEdge.Outcome.NEXT)));
    }

    private static WorkflowDefinition sendMessageDefinition() {
        RuleTrigger trigger = new RuleTrigger();
        trigger.setType("entity_change");
        trigger.setEvents(List.of("person.updated"));
        RuleAction action = new RuleAction();
        action.setType("send_message");
        action.setCampaignMessageId(17);
        action.setCampaignMessageVersion(2);
        return new WorkflowDefinition(
            1,
            "trigger",
            List.of(
                new WorkflowNode.Trigger("trigger", trigger),
                new WorkflowNode.Action("send", action),
                new WorkflowNode.End("end")),
            List.of(
                new WorkflowEdge("send", "trigger", "send", WorkflowEdge.Outcome.NEXT),
                new WorkflowEdge("end", "send", "end", WorkflowEdge.Outcome.NEXT)));
    }

    private static WorkflowCanvas canvas(WorkflowDefinition definition) {
        Map<String, WorkflowCanvas.Position> positions = new java.util.LinkedHashMap<>();
        for (int index = 0; index < definition.nodes().size(); index++) {
            positions.put(
                definition.nodes().get(index).id(),
                new WorkflowCanvas.Position(
                    BigDecimal.valueOf(index * 240L), BigDecimal.ZERO));
        }
        return new WorkflowCanvas(
            positions,
            new WorkflowCanvas.Viewport(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ONE));
    }

    private static LockedPrincipals principals(Set<Permission> permissions) {
        return new LockedPrincipals(Set.of(41), Set.of(41), permissions);
    }
}
