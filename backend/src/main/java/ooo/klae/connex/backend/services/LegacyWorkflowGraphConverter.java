package ooo.klae.connex.backend.services;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
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
    private static final int MAX_ACTIONS = 16;

    private final RuleDefinitionCodec definitionCodec;

    ConvertedWorkflow convert(Rule rule) {
        RuleTrigger trigger = definitionCodec.parse(rule.getTriggerConfig(), RuleTrigger.class);
        if (trigger == null
                || !Objects.equals(normalize(rule.getTriggerType()), normalize(trigger.getType()))) {
            throw new BadRequestException("Legacy rule trigger type does not match its configuration");
        }
        String executionMode = normalize(rule.getExecutionMode());
        if (!"user".equals(executionMode) && !"system".equals(executionMode)) {
            throw new BadRequestException("Legacy rule execution mode is invalid");
        }
        if ("system".equals(executionMode) && rule.getRunAsUserId() != null) {
            throw new BadRequestException("Legacy system rule must not have a run-as user");
        }
        SegmentDefinition condition = rule.getConditionJson() == null
            ? null
            : definitionCodec.parse(rule.getConditionJson(), SegmentDefinition.class);
        if (rule.getConditionJson() != null && condition == null) {
            throw new BadRequestException("Legacy rule condition is null");
        }
        RuleAction[] parsedActions = definitionCodec.parse(rule.getActionsJson(), RuleAction[].class);
        if (parsedActions == null) {
            throw new BadRequestException("Legacy rule actions are null");
        }
        if (parsedActions.length > MAX_ACTIONS) {
            throw new BadRequestException("Legacy rule exceeds 16 actions");
        }
        for (RuleAction action : parsedActions) {
            if (action == null) {
                throw new BadRequestException("Legacy rule action config is required");
            }
        }
        List<RuleAction> actions = List.of(parsedActions);

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
        return new ConvertedWorkflow(
            rule.getId(),
            rule.getWorkspaceId(),
            rule.getName(),
            rule.getDescription(),
            rule.isEnabled(),
            rule.getRecordType(),
            executionMode,
            rule.getRunAsUserId(),
            rule.getCreatedById(),
            new WorkflowDefinition(1, TRIGGER_ID, List.copyOf(nodes), List.copyOf(edges)),
            canvas);
    }

    Rule project(ConvertedWorkflow workflow) {
        WorkflowDefinition definition = workflow.definition();
        if (definition == null
                || definition.schemaVersion() != 1
                || definition.entryNodeId() == null
                || definition.nodes() == null
                || definition.edges() == null) {
            throw new BadRequestException("Legacy workflow definition is incomplete");
        }
        Map<String, WorkflowNode> nodes = indexNodes(definition.nodes());
        List<WorkflowEdge> edges = validateEdges(definition.edges(), nodes.keySet());
        NodeInventory inventory = inventory(nodes.values());
        WorkflowNode entry = nodes.get(definition.entryNodeId());
        if (!(entry instanceof WorkflowNode.Trigger trigger)
                || !inventory.triggerId().equals(trigger.id())) {
            throw new BadRequestException("Legacy workflow entry must be a trigger");
        }
        requireExecutionIdentity(workflow.executionMode(), workflow.runAsUserId());

        Set<String> consumedNodes = new HashSet<>();
        Set<String> consumedEdges = new HashSet<>();
        consumedNodes.add(trigger.id());
        SegmentDefinition condition = null;
        String nextId = target(edges, trigger.id(), WorkflowEdge.Outcome.NEXT, consumedEdges);
        WorkflowNode next = nodes.get(nextId);
        if (next instanceof WorkflowNode.Condition conditionNode) {
            condition = conditionNode.config();
            consumedNodes.add(conditionNode.id());
            String noTarget = target(
                edges, conditionNode.id(), WorkflowEdge.Outcome.NO, consumedEdges);
            if (!inventory.endId().equals(noTarget)) {
                throw new BadRequestException("Legacy workflow condition no branch must end");
            }
            nextId = target(
                edges, conditionNode.id(), WorkflowEdge.Outcome.YES, consumedEdges);
            next = nodes.get(nextId);
        }

        List<RuleAction> actions = new ArrayList<>();
        while (next instanceof WorkflowNode.Action action) {
            if (!consumedNodes.add(action.id())) {
                throw new BadRequestException("Legacy workflow action chain contains a cycle");
            }
            actions.add(action.config());
            if (actions.size() > MAX_ACTIONS) {
                throw new BadRequestException("Legacy workflow exceeds 16 actions");
            }
            nextId = target(edges, action.id(), WorkflowEdge.Outcome.NEXT, consumedEdges);
            next = nodes.get(nextId);
        }
        if (!(next instanceof WorkflowNode.End end) || !inventory.endId().equals(end.id())) {
            throw new BadRequestException("Legacy workflow action chain must end");
        }
        consumedNodes.add(end.id());
        if (consumedNodes.size() != nodes.size() || consumedEdges.size() != edges.size()) {
            throw new BadRequestException("Legacy workflow contains unconsumed nodes or edges");
        }

        Rule rule = new Rule();
        rule.setId(workflow.legacyRuleId());
        rule.setWorkspaceId(workflow.workspaceId());
        rule.setName(workflow.name());
        rule.setDescription(workflow.description());
        rule.setEnabled(workflow.enabled());
        rule.setRecordType(workflow.recordType());
        rule.setTriggerType(normalize(trigger.config().getType()));
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
            if (node == null || node.id() == null || node.id().isBlank()) {
                throw new BadRequestException("Legacy workflow contains an invalid node");
            }
            if (indexed.put(node.id(), node) != null) {
                throw new BadRequestException("Legacy workflow contains duplicate node ids");
            }
            if (node instanceof WorkflowNode.Trigger trigger && trigger.config() == null) {
                throw new BadRequestException("Legacy workflow trigger config is required");
            }
            if (node instanceof WorkflowNode.Condition condition && condition.config() == null) {
                throw new BadRequestException("Legacy workflow condition config is required");
            }
            if (node instanceof WorkflowNode.Action action && action.config() == null) {
                throw new BadRequestException("Legacy workflow action config is required");
            }
        }
        return indexed;
    }

    private static List<WorkflowEdge> validateEdges(
            List<WorkflowEdge> edges, Set<String> nodeIds) {
        Set<String> edgeIds = new HashSet<>();
        for (WorkflowEdge edge : edges) {
            if (edge == null
                    || edge.id() == null
                    || edge.id().isBlank()
                    || edge.sourceNodeId() == null
                    || edge.targetNodeId() == null
                    || edge.outcome() == null
                    || !nodeIds.contains(edge.sourceNodeId())
                    || !nodeIds.contains(edge.targetNodeId())) {
                throw new BadRequestException("Legacy workflow contains an invalid edge");
            }
            if (!edgeIds.add(edge.id())) {
                throw new BadRequestException("Legacy workflow contains duplicate edge ids");
            }
        }
        return edges;
    }

    private static NodeInventory inventory(Iterable<WorkflowNode> nodes) {
        String triggerId = null;
        String conditionId = null;
        String endId = null;
        int actionCount = 0;
        for (WorkflowNode node : nodes) {
            if (node instanceof WorkflowNode.Trigger) {
                triggerId = uniqueNodeId(triggerId, node.id(), "trigger");
            } else if (node instanceof WorkflowNode.Condition) {
                conditionId = uniqueNodeId(conditionId, node.id(), "condition");
            } else if (node instanceof WorkflowNode.Action) {
                actionCount++;
            } else if (node instanceof WorkflowNode.End) {
                endId = uniqueNodeId(endId, node.id(), "end");
            }
        }
        if (triggerId == null || endId == null || actionCount > MAX_ACTIONS) {
            throw new BadRequestException("Legacy workflow node inventory is unsupported");
        }
        return new NodeInventory(triggerId, endId);
    }

    private static String uniqueNodeId(String existing, String candidate, String type) {
        if (existing != null) {
            throw new BadRequestException("Legacy workflow must contain exactly one " + type + " node");
        }
        return candidate;
    }

    private static String target(
            List<WorkflowEdge> edges,
            String sourceNodeId,
            WorkflowEdge.Outcome outcome,
            Set<String> consumedEdgeIds) {
        WorkflowEdge matched = null;
        for (WorkflowEdge edge : edges) {
            if (!sourceNodeId.equals(edge.sourceNodeId()) || outcome != edge.outcome()) {
                continue;
            }
            if (matched != null) {
                throw new BadRequestException("Legacy workflow contains an ambiguous branch");
            }
            matched = edge;
        }
        if (matched == null) {
            throw new BadRequestException("Legacy workflow contains a missing branch");
        }
        consumedEdgeIds.add(matched.id());
        return matched.targetNodeId();
    }

    private static void requireExecutionIdentity(String executionMode, Integer runAsUserId) {
        String normalizedMode = normalize(executionMode);
        if (!"user".equals(normalizedMode) && !"system".equals(normalizedMode)) {
            throw new BadRequestException("Legacy workflow execution mode is invalid");
        }
        if ("system".equals(normalizedMode) && runAsUserId != null) {
            throw new BadRequestException("Legacy system workflow must not have a run-as user");
        }
    }

    private static String normalize(String value) {
        return value == null ? null : value.trim().toLowerCase(Locale.ROOT);
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

    private record NodeInventory(String triggerId, String endId) { }

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
