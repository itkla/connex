package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;

import tools.jackson.databind.ObjectMapper;

import ooo.klae.connex.backend.beans.Rule;
import ooo.klae.connex.backend.dto.RuleAction;
import ooo.klae.connex.backend.dto.RuleTrigger;
import ooo.klae.connex.backend.dto.SegmentCondition;
import ooo.klae.connex.backend.dto.SegmentDefinition;
import ooo.klae.connex.backend.dto.WorkflowDefinition;
import ooo.klae.connex.backend.dto.WorkflowEdge;
import ooo.klae.connex.backend.dto.WorkflowNode;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.services.WorkflowDefinitionValidator.CompiledWorkflow;
import ooo.klae.connex.backend.services.WorkflowDefinitionValidator.NodeType;
import ooo.klae.connex.backend.tenant.Permission;

@ExtendWith(MockitoExtension.class)
class WorkflowDefinitionValidatorTest {

    private static final ValidatorFactory VALIDATOR_FACTORY =
        Validation.buildDefaultValidatorFactory();
    private static final Validator BEAN_VALIDATOR = VALIDATOR_FACTORY.getValidator();

    @Mock private SegmentService segmentService;
    @Mock private WorkspaceService workspaceService;

    private WorkflowDefinitionValidator validator;

    @BeforeEach
    void setUp() {
        RuleDefinitionValidator ruleDefinitionValidator = new RuleDefinitionValidator(
            segmentService, workspaceService, BEAN_VALIDATOR);
        validator = new WorkflowDefinitionValidator(ruleDefinitionValidator);
    }

    @AfterAll
    static void closeValidatorFactory() {
        VALIDATOR_FACTORY.close();
    }

    @Test
    void compilesBranchingDagDeterministicallyAndValidatesEveryConfig() {
        SegmentDefinition condition = condition();
        RuleAction action = notifyAction();
        WorkflowDefinition definition = definition(
            List.of(
                new WorkflowNode.End("end"),
                new WorkflowNode.Action("action", action),
                new WorkflowNode.Condition("condition", condition),
                new WorkflowNode.Trigger("trigger", entityChange())),
            List.of(
                edge("action-end", "action", "end", WorkflowEdge.Outcome.NEXT),
                edge("condition-no", "condition", "action", WorkflowEdge.Outcome.NO),
                edge("trigger-condition", "trigger", "condition", WorkflowEdge.Outcome.NEXT),
                edge("condition-yes", "condition", "action", WorkflowEdge.Outcome.YES)));

        CompiledWorkflow compiled = validator.validate("deal", "user", definition);

        assertEquals(List.of("trigger", "condition", "action", "end"),
            compiled.topologicalOrder());
        assertEquals(NodeType.ACTION, compiled.nodeType("action"));
        assertEquals("action",
            compiled.transition("condition", WorkflowEdge.Outcome.YES).targetNodeId());
        assertEquals("action",
            compiled.transition("condition", WorkflowEdge.Outcome.NO).targetNodeId());
        assertThrows(
            UnsupportedOperationException.class,
            () -> compiled.nodeTypes().put("other", NodeType.END));
        assertFalse(Arrays.stream(CompiledWorkflow.class.getRecordComponents())
            .anyMatch(component -> component.getType() == WorkflowNode.class
                || component.getType() == RuleAction.class
                || component.getType() == RuleTrigger.class
                || component.getType() == SegmentDefinition.class));
        verify(segmentService).validate("deal", condition);
        verifyNoInteractions(workspaceService);
    }

    @Test
    void mutationValidationAggregatesBranchActionPermissionsWithoutAuthorizationReads() {
        RuleAction createTask = action("create_task");
        createTask.setTitle("Follow up");
        RuleAction changeStage = action("change_stage");
        changeStage.setTargetStageId(14);
        WorkflowDefinition definition = definition(
            List.of(
                new WorkflowNode.Trigger("trigger", entityChange()),
                new WorkflowNode.Condition("condition", condition()),
                new WorkflowNode.Action("create-task", createTask),
                new WorkflowNode.Action("change-stage", changeStage),
                new WorkflowNode.End("end-task"),
                new WorkflowNode.End("end-stage")),
            List.of(
                edge("trigger-condition", "trigger", "condition", WorkflowEdge.Outcome.NEXT),
                edge("condition-yes", "condition", "create-task", WorkflowEdge.Outcome.YES),
                edge("condition-no", "condition", "change-stage", WorkflowEdge.Outcome.NO),
                edge("task-end", "create-task", "end-task", WorkflowEdge.Outcome.NEXT),
                edge("stage-end", "change-stage", "end-stage", WorkflowEdge.Outcome.NEXT)));

        Set<Permission> permissions = validator.validateForMutation("deal", "system", definition);

        assertEquals(Set.of(Permission.TASK_CREATE, Permission.DEAL_UPDATE), permissions);
        verifyNoInteractions(workspaceService);
    }

    @Test
    void rejectsInvalidTriggerInventoryAndEntry() {
        assertInvalid(
            new WorkflowDefinition(
                1,
                "action",
                List.of(new WorkflowNode.Action("action", notifyAction())),
                List.of()),
            "Workflow must contain exactly one trigger node");
        assertInvalid(
            new WorkflowDefinition(
                1,
                "trigger-a",
                List.of(
                    new WorkflowNode.Trigger("trigger-a", entityChange()),
                    new WorkflowNode.Trigger("trigger-b", entityChange())),
                List.of()),
            "Workflow must contain exactly one trigger node");
        assertInvalid(
            new WorkflowDefinition(
                1,
                null,
                List.of(new WorkflowNode.Trigger("trigger", entityChange())),
                List.of()),
            "Workflow entryNodeId is required");
        assertInvalid(
            new WorkflowDefinition(
                1,
                "action",
                List.of(
                    new WorkflowNode.Trigger("trigger", entityChange()),
                    new WorkflowNode.Action("action", notifyAction())),
                List.of()),
            "Workflow entryNodeId must reference the trigger node");
    }

    @Test
    void rejectsInvalidTriggerAndConditionConnections() {
        assertInvalid(
            definition(
                List.of(
                    new WorkflowNode.Trigger("trigger", entityChange()),
                    new WorkflowNode.Action("action", notifyAction())),
                List.of(
                    edge("trigger-action", "trigger", "action", WorkflowEdge.Outcome.NEXT),
                    edge("action-trigger", "action", "trigger", WorkflowEdge.Outcome.NEXT))),
            "Workflow trigger node must not have an incoming edge");
        assertInvalid(
            definition(
                List.of(
                    new WorkflowNode.Trigger("trigger", entityChange()),
                    new WorkflowNode.Action("action", notifyAction()),
                    new WorkflowNode.End("end")),
                List.of(
                    edge("trigger-action", "trigger", "action", WorkflowEdge.Outcome.YES),
                    edge("action-end", "action", "end", WorkflowEdge.Outcome.NEXT))),
            "Workflow trigger node must have exactly one next edge");

        WorkflowNode.Trigger trigger = new WorkflowNode.Trigger("trigger", entityChange());
        WorkflowNode.Condition condition = new WorkflowNode.Condition("condition", condition());
        WorkflowNode.Action action = new WorkflowNode.Action("action", notifyAction());
        assertInvalid(
            definition(
                List.of(trigger, condition, action, new WorkflowNode.End("end")),
                List.of(
                    edge("trigger-condition", "trigger", "condition", WorkflowEdge.Outcome.NEXT),
                    edge("condition-yes", "condition", "action", WorkflowEdge.Outcome.YES),
                    edge("action-end", "action", "end", WorkflowEdge.Outcome.NEXT))),
            "Workflow condition node must have exactly one yes and one no edge");
        assertInvalid(
            definition(
                List.of(trigger, condition, action),
                List.of(
                    edge("trigger-condition", "trigger", "condition", WorkflowEdge.Outcome.NEXT),
                    edge("condition-yes-a", "condition", "action", WorkflowEdge.Outcome.YES),
                    edge("condition-yes-b", "condition", "action", WorkflowEdge.Outcome.YES))),
            "Workflow node contains a duplicate branch outcome: condition");
    }

    @Test
    void rejectsInvalidActionAndEndConnections() {
        assertInvalid(
            definition(
                List.of(
                    new WorkflowNode.Trigger("trigger", entityChange()),
                    new WorkflowNode.Action("action", notifyAction()),
                    new WorkflowNode.End("end")),
                List.of(edge("trigger-end", "trigger", "end", WorkflowEdge.Outcome.NEXT))),
            "Workflow action node must have at least one incoming edge");

        assertInvalid(
            definition(
                List.of(
                    new WorkflowNode.Trigger("trigger", entityChange()),
                    new WorkflowNode.Action("action", notifyAction()),
                    new WorkflowNode.End("end")),
                List.of(
                    edge("trigger-action", "trigger", "action", WorkflowEdge.Outcome.NEXT),
                    edge("action-end", "action", "end", WorkflowEdge.Outcome.YES))),
            "Workflow action node must have exactly one next edge");
        assertInvalid(
            definition(
                List.of(
                    new WorkflowNode.Trigger("trigger", entityChange()),
                    new WorkflowNode.Action("action", notifyAction())),
                List.of(edge(
                    "trigger-action", "trigger", "action", WorkflowEdge.Outcome.NEXT))),
            "Workflow action node must have exactly one next edge");
        assertInvalid(
            definition(
                List.of(
                    new WorkflowNode.Trigger("trigger", entityChange()),
                    new WorkflowNode.Action("action", notifyAction()),
                    new WorkflowNode.End("end")),
                List.of(
                    edge("trigger-end", "trigger", "end", WorkflowEdge.Outcome.NEXT),
                    edge("end-action", "end", "action", WorkflowEdge.Outcome.NEXT),
                    edge("action-end", "action", "end", WorkflowEdge.Outcome.NEXT))),
            "Workflow end node must not have an outgoing edge");
    }

    @Test
    void rejectsUnreachableNodesAndCycles() {
        assertInvalid(
            definition(
                List.of(
                    new WorkflowNode.Trigger("trigger", entityChange()),
                    new WorkflowNode.Action("reachable", notifyAction()),
                    new WorkflowNode.End("end"),
                    new WorkflowNode.Action("unreachable-a", notifyAction()),
                    new WorkflowNode.Action("unreachable-b", notifyAction())),
                List.of(
                    edge("trigger-reachable", "trigger", "reachable", WorkflowEdge.Outcome.NEXT),
                    edge("reachable-end", "reachable", "end", WorkflowEdge.Outcome.NEXT),
                    edge("unreachable-a-b", "unreachable-a", "unreachable-b", WorkflowEdge.Outcome.NEXT),
                    edge("unreachable-b-a", "unreachable-b", "unreachable-a", WorkflowEdge.Outcome.NEXT))),
            "Workflow contains an unreachable node: unreachable-a");

        assertInvalid(
            definition(
                List.of(
                    new WorkflowNode.Trigger("trigger", entityChange()),
                    new WorkflowNode.Condition("condition", condition()),
                    new WorkflowNode.Action("action-a", notifyAction()),
                    new WorkflowNode.Action("action-b", notifyAction()),
                    new WorkflowNode.End("end")),
                List.of(
                    edge("trigger-condition", "trigger", "condition", WorkflowEdge.Outcome.NEXT),
                    edge("condition-no", "condition", "end", WorkflowEdge.Outcome.NO),
                    edge("condition-yes", "condition", "action-a", WorkflowEdge.Outcome.YES),
                    edge("action-a-b", "action-a", "action-b", WorkflowEdge.Outcome.NEXT),
                    edge("action-b-a", "action-b", "action-a", WorkflowEdge.Outcome.NEXT))),
            "Workflow graph must not contain a cycle");
    }

    @Test
    void rejectsDanglingEdgesAndInvalidTypedConfigurations() {
        assertInvalid(
            definition(
                List.of(new WorkflowNode.Trigger("trigger", entityChange())),
                List.of(edge("dangling", "trigger", "missing", WorkflowEdge.Outcome.NEXT))),
            "Workflow edge references a missing node: dangling");

        assertInvalid(
            definition(
                List.of(
                    new WorkflowNode.Trigger("trigger", null),
                    new WorkflowNode.Action("action", notifyAction()),
                    new WorkflowNode.End("end")),
                List.of(
                    edge("trigger-action", "trigger", "action", WorkflowEdge.Outcome.NEXT),
                    edge("action-end", "action", "end", WorkflowEdge.Outcome.NEXT))),
            "Rule trigger is required");
        assertInvalid(
            definition(
                List.of(
                    new WorkflowNode.Trigger("trigger", entityChange()),
                    new WorkflowNode.Condition("condition", null),
                    new WorkflowNode.Action("action", notifyAction()),
                    new WorkflowNode.End("end")),
                List.of(
                    edge("trigger-condition", "trigger", "condition", WorkflowEdge.Outcome.NEXT),
                    edge("condition-yes", "condition", "action", WorkflowEdge.Outcome.YES),
                    edge("condition-no", "condition", "action", WorkflowEdge.Outcome.NO),
                    edge("action-end", "action", "end", WorkflowEdge.Outcome.NEXT))),
            "Workflow condition config is required");
        assertInvalid(
            definition(
                List.of(
                    new WorkflowNode.Trigger("trigger", entityChange()),
                    new WorkflowNode.Action("action", null),
                    new WorkflowNode.End("end")),
                List.of(
                    edge("trigger-action", "trigger", "action", WorkflowEdge.Outcome.NEXT),
                    edge("action-end", "action", "end", WorkflowEdge.Outcome.NEXT))),
            "Rule action config is required");
    }

    @Test
    void legacyLinearProjectionsWithAndWithoutConditionsPassValidation() {
        RuleDefinitionCodec codec = new RuleDefinitionCodec(new ObjectMapper());
        LegacyWorkflowGraphConverter converter = new LegacyWorkflowGraphConverter(codec);
        Rule withCondition = rule(codec, condition());
        validator.validate("deal", "user", converter.convert(withCondition).definition());

        Rule withoutCondition = rule(codec, null);
        validator.validate("deal", "user", converter.convert(withoutCondition).definition());
    }

    @Test
    void scheduleRequiresImmediateEnrollmentConditionAndAllowsLaterBranches() {
        WorkflowNode.Trigger trigger = new WorkflowNode.Trigger("trigger", schedule());
        WorkflowNode.Condition enrollment = new WorkflowNode.Condition(
            "enrollment", condition());
        WorkflowNode.Condition later = new WorkflowNode.Condition("later", condition());
        WorkflowDefinition valid = definition(
            List.of(
                trigger,
                enrollment,
                later,
                new WorkflowNode.Action("action", notifyAction()),
                new WorkflowNode.End("end-no"),
                new WorkflowNode.End("end-yes")),
            List.of(
                edge("trigger-enrollment", "trigger", "enrollment", WorkflowEdge.Outcome.NEXT),
                edge("enrollment-yes", "enrollment", "later", WorkflowEdge.Outcome.YES),
                edge("enrollment-no", "enrollment", "end-no", WorkflowEdge.Outcome.NO),
                edge("later-yes", "later", "action", WorkflowEdge.Outcome.YES),
                edge("later-no", "later", "end-no", WorkflowEdge.Outcome.NO),
                edge("action-end", "action", "end-yes", WorkflowEdge.Outcome.NEXT)));

        CompiledWorkflow compiled = validator.validate("deal", "user", valid);

        assertEquals("enrollment", compiled.enrollmentConditionNodeId());
        assertEquals("end-no",
            compiled.transition("later", WorkflowEdge.Outcome.NO).targetNodeId());

        WorkflowDefinition missingEnrollment = definition(
            List.of(
                trigger,
                new WorkflowNode.Action("action", notifyAction()),
                new WorkflowNode.End("end")),
            List.of(
                edge("trigger-action", "trigger", "action", WorkflowEdge.Outcome.NEXT),
                edge("action-end", "action", "end", WorkflowEdge.Outcome.NEXT)));
        assertInvalid(
            missingEnrollment,
            "Workflow schedule trigger must immediately target its enrollment condition");
    }

    private void assertInvalid(WorkflowDefinition definition, String expectedMessage) {
        BadRequestException exception = assertThrows(
            BadRequestException.class,
            () -> validator.validateForMutation("deal", "user", definition));
        assertEquals(expectedMessage, exception.getMessage());
    }

    private static WorkflowDefinition definition(
            List<WorkflowNode> nodes, List<WorkflowEdge> edges) {
        return new WorkflowDefinition(1, "trigger", nodes, edges);
    }

    private static WorkflowEdge edge(
            String id,
            String source,
            String target,
            WorkflowEdge.Outcome outcome) {
        return new WorkflowEdge(id, source, target, outcome);
    }

    private static Rule rule(RuleDefinitionCodec codec, SegmentDefinition condition) {
        Rule rule = new Rule();
        rule.setId(11);
        rule.setWorkspaceId(7);
        rule.setName("Legacy");
        rule.setRecordType("deal");
        rule.setTriggerType("entity_change");
        rule.setTriggerConfig(codec.serialize(entityChange()));
        rule.setConditionJson(condition == null ? null : codec.serialize(condition));
        rule.setActionsJson(codec.serialize(List.of(notifyAction())));
        rule.setExecutionMode("user");
        rule.setRunAsUserId(41);
        rule.setCreatedById(41);
        return rule;
    }

    private static RuleTrigger entityChange() {
        RuleTrigger trigger = new RuleTrigger();
        trigger.setType("entity_change");
        trigger.setEvents(List.of("deal.won"));
        return trigger;
    }

    private static RuleTrigger schedule() {
        RuleTrigger trigger = new RuleTrigger();
        trigger.setType("schedule");
        trigger.setCadence("daily");
        return trigger;
    }

    private static RuleAction notifyAction() {
        RuleAction action = action("notify");
        action.setTitle("Notify owner");
        return action;
    }

    private static RuleAction action(String type) {
        RuleAction action = new RuleAction();
        action.setType(type);
        return action;
    }

    private static SegmentDefinition condition() {
        SegmentCondition field = new SegmentCondition();
        field.setType("field");
        field.setField("name");
        field.setOp("contains");
        field.setValue("Acme");
        SegmentDefinition condition = new SegmentDefinition();
        condition.setMatch("all");
        condition.setConditions(List.of(field));
        return condition;
    }
}
