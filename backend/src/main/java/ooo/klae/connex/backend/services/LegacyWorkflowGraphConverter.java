package ooo.klae.connex.backend.services;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

import ooo.klae.connex.backend.beans.Rule;
import ooo.klae.connex.backend.dto.RuleAction;
import ooo.klae.connex.backend.dto.RuleTrigger;
import ooo.klae.connex.backend.dto.SegmentDefinition;
import ooo.klae.connex.backend.dto.WorkflowCanvas;
import ooo.klae.connex.backend.dto.WorkflowDefinition;
import ooo.klae.connex.backend.dto.WorkflowEdge;
import ooo.klae.connex.backend.dto.WorkflowNode;
import ooo.klae.connex.backend.exceptions.BadRequestException;

/** Converts the legacy rule projection to and from its deterministic schema-v1 workflow graph. */
@Component
@RequiredArgsConstructor
public class LegacyWorkflowGraphConverter {

    private static final String TRIGGER_ID = "trigger";
    private static final String CONDITION_ID = "condition";
    private static final String END_ID = "end";

    private final RuleDefinitionCodec definitionCodec;

    ConvertedWorkflow convert(Rule rule) {
        RuleTrigger trigger = definitionCodec.parse(rule.getTriggerConfig(), RuleTrigger.class);
        SegmentDefinition condition = rule.getConditionJson() == null
            ? null
            : definitionCodec.parse(rule.getConditionJson(), SegmentDefinition.class);
        List<RuleAction> actions = List.of(definitionCodec.parse(rule.getActionsJson(), RuleAction[].class));

        List<WorkflowNode> nodes = new ArrayList<>();
        nodes.add(new WorkflowNode.Trigger(TRIGGER_ID, trigger));
        if (condition != null) {
            nodes.add(new WorkflowNode.Condition(CONDITION_ID, condition));
        }
        for (int index = 0; index < actions.size(); index++) {
            nodes.add(new WorkflowNode.Action(actionId(index), actions.get(index)));
        }
        nodes.add(new WorkflowNode.End(END_ID));

        List<WorkflowEdge> edges = new ArrayList<>();
        String firstActionOrEnd = actions.isEmpty() ? END_ID : actionId(0);
        if (condition == null) {
            edges.add(edge(TRIGGER_ID, WorkflowEdge.Outcome.NEXT, firstActionOrEnd));
        } else {
            edges.add(edge(TRIGGER_ID, WorkflowEdge.Outcome.NEXT, CONDITION_ID));
            edges.add(edge(CONDITION_ID, WorkflowEdge.Outcome.YES, firstActionOrEnd));
            edges.add(edge(CONDITION_ID, WorkflowEdge.Outcome.NO, END_ID));
        }
        for (int index = 0; index < actions.size(); index++) {
            String target = index + 1 < actions.size() ? actionId(index + 1) : END_ID;
            edges.add(edge(actionId(index), WorkflowEdge.Outcome.NEXT, target));
        }

        Map<String, WorkflowCanvas.Position> positions = new LinkedHashMap<>();
        for (int index = 0; index < nodes.size(); index++) {
            positions.put(nodes.get(index).id(), new WorkflowCanvas.Position(
                BigDecimal.valueOf(index * 240L), BigDecimal.ZERO));
        }
        WorkflowCanvas canvas = new WorkflowCanvas(
            Collections.unmodifiableMap(positions),
            new WorkflowCanvas.Viewport(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ONE));
        Integer runAsUserId = "system".equals(rule.getExecutionMode()) ? null : rule.getCreatedById();
        return new ConvertedWorkflow(
            rule.getId(),
            rule.getWorkspaceId(),
            rule.getName(),
            rule.getDescription(),
            rule.isEnabled(),
            rule.getRecordType(),
            rule.getExecutionMode(),
            runAsUserId,
            rule.getCreatedById(),
            new WorkflowDefinition(1, TRIGGER_ID, List.copyOf(nodes), List.copyOf(edges)),
            canvas);
    }

    Rule project(ConvertedWorkflow workflow) {
        WorkflowDefinition definition = workflow.definition();
        Map<String, WorkflowNode> nodes = indexNodes(definition.nodes());
        WorkflowNode entry = nodes.get(definition.entryNodeId());
        if (!(entry instanceof WorkflowNode.Trigger trigger)) {
            throw new BadRequestException("Legacy workflow entry must be a trigger");
        }

        SegmentDefinition condition = null;
        String nextId = target(definition.edges(), trigger.id(), WorkflowEdge.Outcome.NEXT);
        WorkflowNode next = nodes.get(nextId);
        if (next instanceof WorkflowNode.Condition conditionNode) {
            condition = conditionNode.config();
            String noTarget = target(definition.edges(), conditionNode.id(), WorkflowEdge.Outcome.NO);
            if (!(nodes.get(noTarget) instanceof WorkflowNode.End)) {
                throw new BadRequestException("Legacy workflow condition no branch must end");
            }
            nextId = target(definition.edges(), conditionNode.id(), WorkflowEdge.Outcome.YES);
            next = nodes.get(nextId);
        }

        List<RuleAction> actions = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        while (next instanceof WorkflowNode.Action action) {
            if (!visited.add(action.id())) {
                throw new BadRequestException("Legacy workflow action chain contains a cycle");
            }
            actions.add(action.config());
            nextId = target(definition.edges(), action.id(), WorkflowEdge.Outcome.NEXT);
            next = nodes.get(nextId);
        }
        if (!(next instanceof WorkflowNode.End)) {
            throw new BadRequestException("Legacy workflow action chain must end");
        }

        Rule rule = new Rule();
        rule.setId(workflow.legacyRuleId());
        rule.setWorkspaceId(workflow.workspaceId());
        rule.setName(workflow.name());
        rule.setDescription(workflow.description());
        rule.setEnabled(workflow.enabled());
        rule.setRecordType(workflow.recordType());
        rule.setTriggerType(trigger.config().getType());
        rule.setTriggerConfig(definitionCodec.serialize(trigger.config()));
        rule.setConditionJson(condition == null ? null : definitionCodec.serialize(condition));
        rule.setActionsJson(definitionCodec.serialize(actions));
        rule.setExecutionMode(workflow.executionMode());
        rule.setRunAsUserId(workflow.runAsUserId());
        rule.setCreatedById(workflow.createdById());
        return rule;
    }

    private static Map<String, WorkflowNode> indexNodes(List<WorkflowNode> nodes) {
        Map<String, WorkflowNode> indexed = new HashMap<>();
        for (WorkflowNode node : nodes) {
            if (indexed.put(node.id(), node) != null) {
                throw new BadRequestException("Legacy workflow contains duplicate node ids");
            }
        }
        return indexed;
    }

    private static String target(
            List<WorkflowEdge> edges, String sourceNodeId, WorkflowEdge.Outcome outcome) {
        String target = null;
        for (WorkflowEdge edge : edges) {
            if (!sourceNodeId.equals(edge.sourceNodeId()) || outcome != edge.outcome()) {
                continue;
            }
            if (target != null) {
                throw new BadRequestException("Legacy workflow contains an ambiguous branch");
            }
            target = edge.targetNodeId();
        }
        if (target == null) {
            throw new BadRequestException("Legacy workflow contains a missing branch");
        }
        return target;
    }

    private static WorkflowEdge edge(
            String sourceNodeId, WorkflowEdge.Outcome outcome, String targetNodeId) {
        return new WorkflowEdge(
            sourceNodeId + "--" + outcome.value() + "--" + targetNodeId,
            sourceNodeId,
            targetNodeId,
            outcome);
    }

    private static String actionId(int zeroBasedIndex) {
        return "action-" + (zeroBasedIndex + 1);
    }

    record ConvertedWorkflow(
        int legacyRuleId,
        int workspaceId,
        String name,
        String description,
        boolean enabled,
        String recordType,
        String executionMode,
        Integer runAsUserId,
        Integer createdById,
        WorkflowDefinition definition,
        WorkflowCanvas canvas
    ) { }
}
