package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;

import ooo.klae.connex.backend.beans.Rule;
import ooo.klae.connex.backend.dto.RuleAction;
import ooo.klae.connex.backend.dto.RuleTrigger;
import ooo.klae.connex.backend.dto.SegmentCondition;
import ooo.klae.connex.backend.dto.SegmentDefinition;
import ooo.klae.connex.backend.dto.WorkflowCanvas;
import ooo.klae.connex.backend.dto.WorkflowDefinition;
import ooo.klae.connex.backend.dto.WorkflowEdge;
import ooo.klae.connex.backend.dto.WorkflowNode;
import ooo.klae.connex.backend.exceptions.BadRequestException;

/** Verifies deterministic legacy graph conversion and exact reverse runtime projection. */
class LegacyWorkflowGraphConverterTest {

    private final RuleDefinitionCodec codec = new RuleDefinitionCodec(new ObjectMapper());
    private final LegacyWorkflowGraphConverter converter = new LegacyWorkflowGraphConverter(codec);

    @Test
    void convertsRuleWithoutConditionAndRoundTripsActionOrder() {
        Rule source = rule(false, "user", action("notify", "First"), action("create_task", "Second"));
        source.setRunAsUserId(999);

        LegacyWorkflowGraphConverter.ConvertedWorkflow converted = converter.convert(source);

        assertEquals(List.of("trigger", "action-1", "action-2", "end"),
            converted.definition().nodes().stream().map(WorkflowNode::id).toList());
        assertEquals(List.of(
            "trigger--next--action-1",
            "action-1--next--action-2",
            "action-2--next--end"),
            converted.definition().edges().stream().map(WorkflowEdge::id).toList());
        assertEquals(List.of("trigger", "action-1", "action-2", "end"),
            new ArrayList<>(converted.canvas().positions().keySet()));
        assertEquals(new WorkflowCanvas.Position(BigDecimal.valueOf(480), BigDecimal.ZERO),
            converted.canvas().positions().get("action-2"));
        assertEquals(new WorkflowCanvas.Viewport(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ONE),
            converted.canvas().viewport());
        assertEquals(999, converted.runAsUserId());

        assertProjection(source, converter.project(converted));
    }

    @Test
    void convertsRuleWithConditionAndPreservesYesNoBranches() {
        Rule source = rule(true, "system", action("notify", "First"), action("notify", "Second"));

        LegacyWorkflowGraphConverter.ConvertedWorkflow converted = converter.convert(source);

        assertEquals(List.of("trigger", "condition", "action-1", "action-2", "end"),
            converted.definition().nodes().stream().map(WorkflowNode::id).toList());
        assertInstanceOf(WorkflowNode.Condition.class, converted.definition().nodes().get(1));
        assertEquals(List.of(
            "trigger--next--condition",
            "condition--yes--action-1",
            "condition--no--end",
            "action-1--next--action-2",
            "action-2--next--end"),
            converted.definition().edges().stream().map(WorkflowEdge::id).toList());
        assertNull(converted.runAsUserId());
        assertProjection(source, converter.project(converted));
    }

    @Test
    void reverseProjectionFollowsEdgesAfterCanonicalNodeSorting() {
        RuleAction[] actions = new RuleAction[12];
        for (int index = 0; index < actions.length; index++) {
            actions[index] = action("notify", "Action " + (index + 1));
        }
        Rule source = rule(false, "user", actions);
        LegacyWorkflowGraphConverter.ConvertedWorkflow converted = converter.convert(source);
        List<WorkflowNode> sortedNodes = converted.definition().nodes().stream()
            .sorted(Comparator.comparing(WorkflowNode::id))
            .toList();
        List<WorkflowEdge> sortedEdges = converted.definition().edges().stream()
            .sorted(Comparator.comparing(WorkflowEdge::id))
            .toList();
        LegacyWorkflowGraphConverter.ConvertedWorkflow sorted = new LegacyWorkflowGraphConverter.ConvertedWorkflow(
            converted.legacyRuleId(),
            converted.workspaceId(),
            converted.name(),
            converted.description(),
            converted.enabled(),
            converted.recordType(),
            converted.executionMode(),
            converted.runAsUserId(),
            converted.createdById(),
            new WorkflowDefinition(
                1, converted.definition().entryNodeId(), sortedNodes, sortedEdges),
            converted.canvas());

        assertProjection(source, converter.project(sorted));
    }

    @Test
    void preservesNullableUserIdentityAndRejectsSystemIdentity() {
        Rule user = rule(false, "user", action("notify", "First"));
        user.setRunAsUserId(null);
        assertNull(converter.project(converter.convert(user)).getRunAsUserId());

        Rule system = rule(false, "system", action("notify", "First"));
        system.setRunAsUserId(999);
        assertThrows(BadRequestException.class, () -> converter.convert(system));
    }

    @Test
    void rejectsContradictorySourceTriggerType() {
        Rule source = rule(false, "user", action("notify", "First"));
        source.setTriggerType("schedule");

        assertThrows(BadRequestException.class, () -> converter.convert(source));
    }

    @Test
    void projectsZeroActionsAndRejectsDisconnectedContent() {
        LegacyWorkflowGraphConverter.ConvertedWorkflow zeroActions = converter.convert(
            rule(false, "user"));
        Rule projected = converter.project(zeroActions);

        assertEquals("[]", projected.getActionsJson());
        assertEquals(List.of("trigger", "end"),
            zeroActions.definition().nodes().stream().map(WorkflowNode::id).toList());

        List<WorkflowNode> extraNodes = new ArrayList<>(zeroActions.definition().nodes());
        extraNodes.add(new WorkflowNode.Action("extra", action("notify", "Extra")));
        assertRejected(zeroActions, new WorkflowDefinition(
            1, "trigger", extraNodes, zeroActions.definition().edges()));

        List<WorkflowEdge> extraEdges = new ArrayList<>(zeroActions.definition().edges());
        extraEdges.add(new WorkflowEdge(
            "end--next--trigger", "end", "trigger", WorkflowEdge.Outcome.NEXT));
        assertRejected(zeroActions, new WorkflowDefinition(
            1, "trigger", zeroActions.definition().nodes(), extraEdges));
    }

    @Test
    void rejectsDuplicateEdgeIdsAndNullConfigs() {
        LegacyWorkflowGraphConverter.ConvertedWorkflow converted = converter.convert(
            rule(true, "user", action("notify", "First")));
        List<WorkflowNode> duplicateNodes = new ArrayList<>(converted.definition().nodes());
        duplicateNodes.add(new WorkflowNode.End("end"));
        assertRejected(converted, new WorkflowDefinition(
            1, "trigger", duplicateNodes, converted.definition().edges()));

        List<WorkflowEdge> duplicateEdges = new ArrayList<>(converted.definition().edges());
        WorkflowEdge first = duplicateEdges.getFirst();
        duplicateEdges.add(new WorkflowEdge(
            first.id(), first.sourceNodeId(), first.targetNodeId(), first.outcome()));
        assertRejected(converted, new WorkflowDefinition(
            1, "trigger", converted.definition().nodes(), duplicateEdges));

        List<WorkflowNode> nullTrigger = replaceNode(
            converted.definition().nodes(), "trigger", new WorkflowNode.Trigger("trigger", null));
        assertRejected(converted, new WorkflowDefinition(
            1, "trigger", nullTrigger, converted.definition().edges()));

        List<WorkflowNode> nullCondition = replaceNode(
            converted.definition().nodes(), "condition", new WorkflowNode.Condition("condition", null));
        assertRejected(converted, new WorkflowDefinition(
            1, "trigger", nullCondition, converted.definition().edges()));

        List<WorkflowNode> nullAction = replaceNode(
            converted.definition().nodes(), "action-1", new WorkflowNode.Action("action-1", null));
        assertRejected(converted, new WorkflowDefinition(
            1, "trigger", nullAction, converted.definition().edges()));
    }

    @Test
    void rejectsIllegalOutcomesIncomingEdgesAndCycles() {
        LegacyWorkflowGraphConverter.ConvertedWorkflow converted = converter.convert(
            rule(false, "user", action("notify", "First")));
        List<WorkflowEdge> illegalOutcome = replaceEdge(
            converted.definition().edges(),
            "trigger--next--action-1",
            new WorkflowEdge(
                "trigger--yes--action-1",
                "trigger",
                "action-1",
                WorkflowEdge.Outcome.YES));
        assertRejected(converted, new WorkflowDefinition(
            1, "trigger", converted.definition().nodes(), illegalOutcome));

        List<WorkflowEdge> incomingTrigger = new ArrayList<>(converted.definition().edges());
        incomingTrigger.add(new WorkflowEdge(
            "end--next--trigger", "end", "trigger", WorkflowEdge.Outcome.NEXT));
        assertRejected(converted, new WorkflowDefinition(
            1, "trigger", converted.definition().nodes(), incomingTrigger));

        List<WorkflowEdge> cycle = replaceEdge(
            converted.definition().edges(),
            "action-1--next--end",
            new WorkflowEdge(
                "action-1--next--action-1",
                "action-1",
                "action-1",
                WorkflowEdge.Outcome.NEXT));
        assertRejected(converted, new WorkflowDefinition(
            1, "trigger", converted.definition().nodes(), cycle));

        LegacyWorkflowGraphConverter.ConvertedWorkflow withCondition = converter.convert(
            rule(true, "user", action("notify", "First")));
        List<WorkflowEdge> nonterminalNoBranch = replaceEdge(
            withCondition.definition().edges(),
            "condition--no--end",
            new WorkflowEdge(
                "condition--no--action-1",
                "condition",
                "action-1",
                WorkflowEdge.Outcome.NO));
        assertRejected(withCondition, new WorkflowDefinition(
            1, "trigger", withCondition.definition().nodes(), nonterminalNoBranch));
    }

    private void assertRejected(
            LegacyWorkflowGraphConverter.ConvertedWorkflow converted,
            WorkflowDefinition definition) {
        LegacyWorkflowGraphConverter.ConvertedWorkflow invalid = withDefinition(converted, definition);
        assertThrows(BadRequestException.class, () -> converter.project(invalid));
    }

    private static LegacyWorkflowGraphConverter.ConvertedWorkflow withDefinition(
            LegacyWorkflowGraphConverter.ConvertedWorkflow converted,
            WorkflowDefinition definition) {
        return new LegacyWorkflowGraphConverter.ConvertedWorkflow(
            converted.legacyRuleId(),
            converted.workspaceId(),
            converted.name(),
            converted.description(),
            converted.enabled(),
            converted.recordType(),
            converted.executionMode(),
            converted.runAsUserId(),
            converted.createdById(),
            definition,
            converted.canvas());
    }

    private static List<WorkflowNode> replaceNode(
            List<WorkflowNode> nodes, String id, WorkflowNode replacement) {
        List<WorkflowNode> changed = new ArrayList<>(nodes);
        int index = changed.stream().map(WorkflowNode::id).toList().indexOf(id);
        changed.set(index, replacement);
        return changed;
    }

    private static List<WorkflowEdge> replaceEdge(
            List<WorkflowEdge> edges, String id, WorkflowEdge replacement) {
        List<WorkflowEdge> changed = new ArrayList<>(edges);
        int index = changed.stream().map(WorkflowEdge::id).toList().indexOf(id);
        changed.set(index, replacement);
        return changed;
    }

    private void assertProjection(Rule source, Rule projected) {
        assertEquals(source.getId(), projected.getId());
        assertEquals(source.getWorkspaceId(), projected.getWorkspaceId());
        assertEquals(source.getName(), projected.getName());
        assertEquals(source.getDescription(), projected.getDescription());
        assertEquals(source.isEnabled(), projected.isEnabled());
        assertEquals(source.getRecordType(), projected.getRecordType());
        assertEquals(source.getTriggerType(), projected.getTriggerType());
        assertEquals(source.getTriggerConfig(), projected.getTriggerConfig());
        assertEquals(source.getConditionJson(), projected.getConditionJson());
        assertEquals(source.getActionsJson(), projected.getActionsJson());
        assertEquals(source.getExecutionMode(), projected.getExecutionMode());
        assertEquals(source.getRunAsUserId(), projected.getRunAsUserId());
        assertEquals(source.getCreatedById(), projected.getCreatedById());
    }

    private Rule rule(boolean withCondition, String executionMode, RuleAction... actions) {
        RuleTrigger trigger = new RuleTrigger();
        trigger.setType("entity_change");
        trigger.setEvents(List.of("deal.won", "deal.updated"));

        Rule rule = new Rule();
        rule.setId(23);
        rule.setWorkspaceId(7);
        rule.setName("Legacy rule");
        rule.setDescription("Description");
        rule.setEnabled(true);
        rule.setRecordType("deal");
        rule.setTriggerType("entity_change");
        rule.setTriggerConfig(codec.serialize(trigger));
        rule.setConditionJson(withCondition ? codec.serialize(condition()) : null);
        rule.setActionsJson(codec.serialize(List.of(actions)));
        rule.setExecutionMode(executionMode);
        rule.setRunAsUserId("system".equals(executionMode) ? null : 41);
        rule.setCreatedById(41);
        return rule;
    }

    private static RuleAction action(String type, String title) {
        RuleAction action = new RuleAction();
        action.setType(type);
        action.setTitle(title);
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
        condition.setGroups(List.of());
        return condition;
    }
}
