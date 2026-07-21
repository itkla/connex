package ooo.klae.connex.backend.services;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.TreeMap;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

import ooo.klae.connex.backend.dto.RuleAction;
import ooo.klae.connex.backend.dto.SegmentDefinition;
import ooo.klae.connex.backend.dto.WorkflowDefinition;
import ooo.klae.connex.backend.dto.WorkflowEdge;
import ooo.klae.connex.backend.dto.WorkflowNode;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.tenant.Permission;

/** Compiles and authoritatively validates executable schema-v1 workflow DAGs. */
@Component
@RequiredArgsConstructor
public class WorkflowDefinitionValidator {

    private final RuleDefinitionValidator ruleDefinitionValidator;

    CompiledWorkflow validate(
            String recordType,
            String executionMode,
            WorkflowDefinition definition) {
        CompiledWorkflow compiled = compile(definition);
        ruleDefinitionValidator.validateWorkflowDefinition(
            recordType,
            compiled.trigger().config(),
            conditions(compiled),
            actions(compiled),
            executionMode);
        return compiled;
    }

    Set<Permission> validateForMutation(
            String recordType,
            String executionMode,
            WorkflowDefinition definition) {
        CompiledWorkflow compiled = compile(definition);
        return ruleDefinitionValidator.validateWorkflowDefinitionForMutation(
            recordType,
            compiled.trigger().config(),
            conditions(compiled),
            actions(compiled),
            executionMode);
    }

    private static CompiledWorkflow compile(WorkflowDefinition definition) {
        WorkflowDraftCanonicalizer.validateDefinitionStructure(definition);

        Map<String, WorkflowNode> nodes = new TreeMap<>();
        for (WorkflowNode node : definition.nodes()) {
            nodes.put(node.id(), node);
        }
        WorkflowNode.Trigger trigger = requireEntryTrigger(definition, nodes);

        Map<String, List<WorkflowEdge>> incoming = edgeLists(nodes.keySet());
        Map<String, Map<WorkflowEdge.Outcome, WorkflowEdge>> outgoing = outcomeMaps(nodes.keySet());
        List<WorkflowEdge> orderedEdges = definition.edges().stream()
            .sorted(Comparator.comparing(WorkflowEdge::id))
            .toList();
        for (WorkflowEdge edge : orderedEdges) {
            incoming.get(edge.targetNodeId()).add(edge);
            WorkflowEdge duplicate = outgoing.get(edge.sourceNodeId()).put(edge.outcome(), edge);
            if (duplicate != null) {
                throw new BadRequestException(
                    "Workflow node contains a duplicate branch outcome: " + edge.sourceNodeId());
            }
        }

        validateNodeConnections(nodes, incoming, outgoing);
        requireReachable(trigger.id(), nodes, outgoing);
        List<String> topologicalOrder = topologicalOrder(nodes, incoming, outgoing);
        return new CompiledWorkflow(trigger, nodes, outgoing, topologicalOrder);
    }

    private static WorkflowNode.Trigger requireEntryTrigger(
            WorkflowDefinition definition, Map<String, WorkflowNode> nodes) {
        List<WorkflowNode.Trigger> triggers = nodes.values().stream()
            .filter(WorkflowNode.Trigger.class::isInstance)
            .map(WorkflowNode.Trigger.class::cast)
            .toList();
        if (triggers.size() != 1) {
            throw new BadRequestException("Workflow must contain exactly one trigger node");
        }
        if (definition.entryNodeId() == null) {
            throw new BadRequestException("Workflow entryNodeId is required");
        }
        WorkflowNode entry = nodes.get(definition.entryNodeId());
        WorkflowNode.Trigger trigger = triggers.getFirst();
        if (!(entry instanceof WorkflowNode.Trigger) || !trigger.id().equals(entry.id())) {
            throw new BadRequestException("Workflow entryNodeId must reference the trigger node");
        }
        return trigger;
    }

    private static Map<String, List<WorkflowEdge>> edgeLists(Set<String> nodeIds) {
        Map<String, List<WorkflowEdge>> edges = new LinkedHashMap<>();
        for (String nodeId : nodeIds) {
            edges.put(nodeId, new ArrayList<>());
        }
        return edges;
    }

    private static Map<String, Map<WorkflowEdge.Outcome, WorkflowEdge>> outcomeMaps(
            Set<String> nodeIds) {
        Map<String, Map<WorkflowEdge.Outcome, WorkflowEdge>> edges = new LinkedHashMap<>();
        for (String nodeId : nodeIds) {
            edges.put(nodeId, new EnumMap<>(WorkflowEdge.Outcome.class));
        }
        return edges;
    }

    private static void validateNodeConnections(
            Map<String, WorkflowNode> nodes,
            Map<String, List<WorkflowEdge>> incoming,
            Map<String, Map<WorkflowEdge.Outcome, WorkflowEdge>> outgoing) {
        for (WorkflowNode node : nodes.values()) {
            int incomingCount = incoming.get(node.id()).size();
            Map<WorkflowEdge.Outcome, WorkflowEdge> transitions = outgoing.get(node.id());
            if (node instanceof WorkflowNode.Trigger) {
                requireTriggerConnections(incomingCount, transitions);
            } else if (node instanceof WorkflowNode.Condition) {
                requireConditionConnections(incomingCount, transitions);
            } else if (node instanceof WorkflowNode.Action) {
                requireActionConnections(incomingCount, transitions);
            } else if (node instanceof WorkflowNode.End) {
                requireEndConnections(incomingCount, transitions);
            }
        }
    }

    private static void requireTriggerConnections(
            int incomingCount,
            Map<WorkflowEdge.Outcome, WorkflowEdge> outgoing) {
        if (incomingCount != 0) {
            throw new BadRequestException("Workflow trigger node must not have an incoming edge");
        }
        if (outgoing.size() != 1 || !outgoing.containsKey(WorkflowEdge.Outcome.NEXT)) {
            throw new BadRequestException("Workflow trigger node must have exactly one next edge");
        }
    }

    private static void requireConditionConnections(
            int incomingCount,
            Map<WorkflowEdge.Outcome, WorkflowEdge> outgoing) {
        if (incomingCount != 1) {
            throw new BadRequestException("Workflow condition node must have exactly one incoming edge");
        }
        if (outgoing.size() != 2
                || !outgoing.containsKey(WorkflowEdge.Outcome.YES)
                || !outgoing.containsKey(WorkflowEdge.Outcome.NO)) {
            throw new BadRequestException("Workflow condition node must have exactly one yes and one no edge");
        }
    }

    private static void requireActionConnections(
            int incomingCount,
            Map<WorkflowEdge.Outcome, WorkflowEdge> outgoing) {
        if (incomingCount == 0) {
            throw new BadRequestException("Workflow action node must have at least one incoming edge");
        }
        if (outgoing.size() > 1
                || outgoing.size() == 1 && !outgoing.containsKey(WorkflowEdge.Outcome.NEXT)) {
            throw new BadRequestException("Workflow action node may have at most one next edge");
        }
    }

    private static void requireEndConnections(
            int incomingCount,
            Map<WorkflowEdge.Outcome, WorkflowEdge> outgoing) {
        if (incomingCount == 0) {
            throw new BadRequestException("Workflow end node must have at least one incoming edge");
        }
        if (!outgoing.isEmpty()) {
            throw new BadRequestException("Workflow end node must not have an outgoing edge");
        }
    }

    private static void requireReachable(
            String entryNodeId,
            Map<String, WorkflowNode> nodes,
            Map<String, Map<WorkflowEdge.Outcome, WorkflowEdge>> outgoing) {
        Set<String> reachable = new HashSet<>();
        ArrayDeque<String> pending = new ArrayDeque<>();
        pending.add(entryNodeId);
        while (!pending.isEmpty()) {
            String nodeId = pending.removeFirst();
            if (!reachable.add(nodeId)) {
                continue;
            }
            outgoing.get(nodeId).values().stream()
                .map(WorkflowEdge::targetNodeId)
                .forEach(pending::addLast);
        }
        for (String nodeId : nodes.keySet()) {
            if (!reachable.contains(nodeId)) {
                throw new BadRequestException("Workflow contains an unreachable node: " + nodeId);
            }
        }
    }

    private static List<String> topologicalOrder(
            Map<String, WorkflowNode> nodes,
            Map<String, List<WorkflowEdge>> incoming,
            Map<String, Map<WorkflowEdge.Outcome, WorkflowEdge>> outgoing) {
        Map<String, Integer> remainingIncoming = new HashMap<>();
        PriorityQueue<String> ready = new PriorityQueue<>();
        for (String nodeId : nodes.keySet()) {
            int count = incoming.get(nodeId).size();
            remainingIncoming.put(nodeId, count);
            if (count == 0) {
                ready.add(nodeId);
            }
        }

        List<String> ordered = new ArrayList<>(nodes.size());
        while (!ready.isEmpty()) {
            String nodeId = ready.remove();
            ordered.add(nodeId);
            for (WorkflowEdge edge : outgoing.get(nodeId).values()) {
                int remaining = remainingIncoming.compute(
                    edge.targetNodeId(), (ignored, count) -> count == null ? -1 : count - 1);
                if (remaining == 0) {
                    ready.add(edge.targetNodeId());
                }
            }
        }
        if (ordered.size() != nodes.size()) {
            throw new BadRequestException("Workflow graph must not contain a cycle");
        }
        return List.copyOf(ordered);
    }

    private static List<SegmentDefinition> conditions(CompiledWorkflow compiled) {
        List<SegmentDefinition> conditions = new ArrayList<>();
        for (String nodeId : compiled.topologicalOrder()) {
            if (compiled.nodes().get(nodeId) instanceof WorkflowNode.Condition condition) {
                conditions.add(condition.config());
            }
        }
        return conditions;
    }

    private static List<RuleAction> actions(CompiledWorkflow compiled) {
        List<RuleAction> actions = new ArrayList<>();
        for (String nodeId : compiled.topologicalOrder()) {
            if (compiled.nodes().get(nodeId) instanceof WorkflowNode.Action action) {
                actions.add(action.config());
            }
        }
        return actions;
    }

    record CompiledWorkflow(
        WorkflowNode.Trigger trigger,
        Map<String, WorkflowNode> nodes,
        Map<String, Map<WorkflowEdge.Outcome, WorkflowEdge>> outgoing,
        List<String> topologicalOrder
    ) {

        CompiledWorkflow {
            nodes = Collections.unmodifiableMap(new LinkedHashMap<>(nodes));
            Map<String, Map<WorkflowEdge.Outcome, WorkflowEdge>> transitions = new LinkedHashMap<>();
            outgoing.forEach((nodeId, edges) -> transitions.put(
                nodeId,
                Collections.unmodifiableMap(new EnumMap<>(edges))));
            outgoing = Collections.unmodifiableMap(transitions);
            topologicalOrder = List.copyOf(topologicalOrder);
        }

        WorkflowNode node(String nodeId) {
            return nodes.get(nodeId);
        }

        WorkflowEdge transition(String nodeId, WorkflowEdge.Outcome outcome) {
            Map<WorkflowEdge.Outcome, WorkflowEdge> transitions = outgoing.get(nodeId);
            return transitions == null ? null : transitions.get(outcome);
        }
    }
}
