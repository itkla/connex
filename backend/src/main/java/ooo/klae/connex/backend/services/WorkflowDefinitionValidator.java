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

import ooo.klae.connex.backend.dto.WorkflowDelayConfig;
import ooo.klae.connex.backend.dto.WorkflowDefinition;
import ooo.klae.connex.backend.dto.WorkflowDiagnosticCode;
import ooo.klae.connex.backend.dto.WorkflowDiagnosticDto;
import ooo.klae.connex.backend.dto.WorkflowEdge;
import ooo.klae.connex.backend.dto.WorkflowNode;
import ooo.klae.connex.backend.exceptions.WorkflowDefinitionValidationException;
import ooo.klae.connex.backend.tenant.Permission;

/** Compiles and authoritatively validates executable schema-v1 workflow DAGs. */
@Component
@RequiredArgsConstructor
public class WorkflowDefinitionValidator {

    private static final int MIN_DELAY_SECONDS = 60;
    private static final int MAX_DELAY_SECONDS = 2_592_000;
    private static final int MAX_PATH_DELAY_SECONDS = 7_776_000;

    private final RuleDefinitionValidator ruleDefinitionValidator;

    public CompiledWorkflow validate(
            String recordType,
            String executionMode,
            WorkflowDefinition definition) {
        Compilation compilation = compile(definition);
        ruleDefinitionValidator.validateWorkflowNodes(
            recordType,
            compilation.trigger(),
            compilation.conditions(),
            compilation.actions(),
            executionMode);
        return compilation.compiled();
    }

    public Set<Permission> validateForMutation(
            String recordType,
            String executionMode,
            WorkflowDefinition definition) {
        return validateForMutationAndCompile(
            recordType, executionMode, definition).requiredPermissions();
    }

    public ValidatedWorkflow validateForMutationAndCompile(
            String recordType,
            String executionMode,
            WorkflowDefinition definition) {
        Compilation compilation = compile(definition);
        Set<Permission> permissions = ruleDefinitionValidator.validateWorkflowNodesForMutation(
            recordType,
            compilation.trigger(),
            compilation.conditions(),
            compilation.actions(),
            executionMode);
        return new ValidatedWorkflow(compilation.compiled(), permissions);
    }

    private static Compilation compile(WorkflowDefinition definition) {
        WorkflowDefinition snapshot = WorkflowDraftCanonicalizer.snapshotDefinition(definition);

        Map<String, WorkflowNode> nodes = new TreeMap<>();
        for (WorkflowNode node : snapshot.nodes()) {
            nodes.put(node.id(), node);
        }
        WorkflowNode.Trigger trigger = requireEntryTrigger(snapshot, nodes);

        Map<String, List<WorkflowEdge>> incoming = edgeLists(nodes.keySet());
        Map<String, Map<WorkflowEdge.Outcome, WorkflowEdge>> outgoing = outcomeMaps(nodes.keySet());
        List<WorkflowEdge> orderedEdges = snapshot.edges().stream()
            .sorted(Comparator.comparing(WorkflowEdge::id))
            .toList();
        for (WorkflowEdge edge : orderedEdges) {
            incoming.get(edge.targetNodeId()).add(edge);
            WorkflowEdge duplicate = outgoing.get(edge.sourceNodeId()).put(edge.outcome(), edge);
            if (duplicate != null) {
                throw invalid(
                    WorkflowDiagnosticCode.BRANCH_OUTCOME_DUPLICATE,
                    "Workflow node contains a duplicate branch outcome: " + edge.sourceNodeId(),
                    edge.sourceNodeId(), edge.id(), null,
                    Map.of("outcome", edge.outcome().value()));
            }
        }

        validateNodeConnections(nodes, incoming, outgoing);
        requireReachable(trigger.id(), nodes, outgoing);
        List<String> topologicalOrder = topologicalOrder(nodes, incoming, outgoing);
        Map<String, NodeType> nodeTypes = new LinkedHashMap<>();
        List<WorkflowNode.Condition> conditions = new ArrayList<>();
        List<WorkflowNode.Action> actions = new ArrayList<>();
        for (String nodeId : topologicalOrder) {
            WorkflowNode node = nodes.get(nodeId);
            if (node instanceof WorkflowNode.Trigger) {
                nodeTypes.put(nodeId, NodeType.TRIGGER);
            } else if (node instanceof WorkflowNode.Condition condition) {
                nodeTypes.put(nodeId, NodeType.CONDITION);
                conditions.add(condition);
            } else if (node instanceof WorkflowNode.Action action) {
                nodeTypes.put(nodeId, NodeType.ACTION);
                actions.add(action);
            } else if (node instanceof WorkflowNode.Delay) {
                nodeTypes.put(nodeId, NodeType.DELAY);
            } else if (node instanceof WorkflowNode.End) {
                nodeTypes.put(nodeId, NodeType.END);
            } else {
                throw invalid(
                    WorkflowDiagnosticCode.NODE_TYPE_UNSUPPORTED,
                    "Workflow contains an unsupported node type",
                    nodeId, null, null, Map.of());
            }
        }
        validateDelayBounds(nodes, incoming, topologicalOrder);
        String enrollmentConditionNodeId = scheduleEnrollmentCondition(
            trigger, nodes, outgoing);
        CompiledWorkflow compiled = new CompiledWorkflow(
            trigger.id(), nodes, nodeTypes, outgoing, topologicalOrder,
            enrollmentConditionNodeId);
        return new Compilation(
            compiled,
            trigger,
            Collections.unmodifiableList(new ArrayList<>(conditions)),
            Collections.unmodifiableList(new ArrayList<>(actions)));
    }

    private static WorkflowNode.Trigger requireEntryTrigger(
            WorkflowDefinition definition, Map<String, WorkflowNode> nodes) {
        List<WorkflowNode.Trigger> triggers = nodes.values().stream()
            .filter(WorkflowNode.Trigger.class::isInstance)
            .map(WorkflowNode.Trigger.class::cast)
            .toList();
        if (triggers.size() != 1) {
            throw invalid(
                WorkflowDiagnosticCode.TRIGGER_COUNT_INVALID,
                "Workflow must contain exactly one trigger node",
                null, null, "nodes", Map.of("count", Integer.toString(triggers.size())));
        }
        if (definition.entryNodeId() == null) {
            throw invalid(
                WorkflowDiagnosticCode.ENTRY_NODE_REQUIRED,
                "Workflow entryNodeId is required",
                null, null, "entryNodeId", Map.of());
        }
        WorkflowNode entry = nodes.get(definition.entryNodeId());
        WorkflowNode.Trigger trigger = triggers.getFirst();
        if (!(entry instanceof WorkflowNode.Trigger) || !trigger.id().equals(entry.id())) {
            throw invalid(
                WorkflowDiagnosticCode.ENTRY_TRIGGER_INVALID,
                "Workflow entryNodeId must reference the trigger node",
                definition.entryNodeId(), null, "entryNodeId", Map.of());
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
                requireTriggerConnections(node.id(), incomingCount, transitions);
            } else if (node instanceof WorkflowNode.Condition) {
                requireConditionConnections(node.id(), incomingCount, transitions);
            } else if (node instanceof WorkflowNode.Action) {
                requireActionConnections(node.id(), incomingCount, transitions);
            } else if (node instanceof WorkflowNode.Delay) {
                requireDelayConnections(node.id(), incomingCount, transitions);
            } else if (node instanceof WorkflowNode.End) {
                requireEndConnections(node.id(), incomingCount, transitions);
            }
        }
    }

    private static void requireTriggerConnections(
            String nodeId,
            int incomingCount,
            Map<WorkflowEdge.Outcome, WorkflowEdge> outgoing) {
        if (incomingCount != 0) {
            throw invalid(
                WorkflowDiagnosticCode.INCOMING_EDGE_FORBIDDEN,
                "Workflow trigger node must not have an incoming edge",
                nodeId, null, null, Map.of());
        }
        requireOnlyOutcome(
            nodeId,
            outgoing,
            WorkflowEdge.Outcome.NEXT,
            "Workflow trigger node must have exactly one next edge");
    }

    private static void requireConditionConnections(
            String nodeId,
            int incomingCount,
            Map<WorkflowEdge.Outcome, WorkflowEdge> outgoing) {
        if (incomingCount != 1) {
            throw invalid(
                WorkflowDiagnosticCode.INCOMING_EDGE_COUNT_INVALID,
                "Workflow condition node must have exactly one incoming edge",
                nodeId, null, null,
                Map.of("expected", "1", "actual", Integer.toString(incomingCount)));
        }
        requireOutcomes(
            nodeId,
            outgoing,
            Set.of(WorkflowEdge.Outcome.YES, WorkflowEdge.Outcome.NO),
            "Workflow condition node must have exactly one yes and one no edge");
    }

    private static void requireActionConnections(
            String nodeId,
            int incomingCount,
            Map<WorkflowEdge.Outcome, WorkflowEdge> outgoing) {
        if (incomingCount == 0) {
            throw invalid(
                WorkflowDiagnosticCode.INCOMING_EDGE_REQUIRED,
                "Workflow action node must have at least one incoming edge",
                nodeId, null, null, Map.of());
        }
        requireOnlyOutcome(
            nodeId,
            outgoing,
            WorkflowEdge.Outcome.NEXT,
            "Workflow action node must have exactly one next edge");
    }

    private static void requireDelayConnections(
            String nodeId,
            int incomingCount,
            Map<WorkflowEdge.Outcome, WorkflowEdge> outgoing) {
        if (incomingCount == 0) {
            throw invalid(
                WorkflowDiagnosticCode.INCOMING_EDGE_REQUIRED,
                "Workflow delay node must have at least one incoming edge",
                nodeId, null, null, Map.of());
        }
        requireOnlyOutcome(
            nodeId,
            outgoing,
            WorkflowEdge.Outcome.NEXT,
            "Workflow delay node must have exactly one next edge");
    }

    private static String scheduleEnrollmentCondition(
            WorkflowNode.Trigger trigger,
            Map<String, WorkflowNode> nodes,
            Map<String, Map<WorkflowEdge.Outcome, WorkflowEdge>> outgoing) {
        if (trigger.config() == null
                || !"schedule".equals(normalize(trigger.config().getType()))) {
            return null;
        }
        WorkflowEdge edge = outgoing.get(trigger.id()).get(WorkflowEdge.Outcome.NEXT);
        WorkflowNode target = nodes.get(edge.targetNodeId());
        if (!(target instanceof WorkflowNode.Condition)) {
            throw invalid(
                WorkflowDiagnosticCode.SCHEDULE_ENROLLMENT_CONDITION_REQUIRED,
                "Workflow schedule trigger must immediately target its enrollment condition",
                trigger.id(), edge.id(), "config.type", Map.of());
        }
        return target.id();
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private static void requireOnlyOutcome(
            String nodeId,
            Map<WorkflowEdge.Outcome, WorkflowEdge> outgoing,
            WorkflowEdge.Outcome required,
            String message) {
        requireOutcomes(nodeId, outgoing, Set.of(required), message);
    }

    private static void requireOutcomes(
            String nodeId,
            Map<WorkflowEdge.Outcome, WorkflowEdge> outgoing,
            Set<WorkflowEdge.Outcome> required,
            String message) {
        for (WorkflowEdge.Outcome outcome : required) {
            if (!outgoing.containsKey(outcome)) {
                throw invalid(
                    WorkflowDiagnosticCode.BRANCH_OUTCOME_REQUIRED,
                    message,
                    nodeId, null, null, Map.of("outcome", outcome.value()));
            }
        }
        for (Map.Entry<WorkflowEdge.Outcome, WorkflowEdge> entry : outgoing.entrySet()) {
            if (!required.contains(entry.getKey())) {
                throw invalid(
                    WorkflowDiagnosticCode.BRANCH_OUTCOME_NOT_ALLOWED,
                    message,
                    nodeId, entry.getValue().id(), null,
                    Map.of("outcome", entry.getKey().value()));
            }
        }
    }

    private static void validateDelayBounds(
            Map<String, WorkflowNode> nodes,
            Map<String, List<WorkflowEdge>> incoming,
            List<String> topologicalOrder) {
        Map<String, Long> cumulative = new HashMap<>();
        for (String nodeId : topologicalOrder) {
            long preceding = 0;
            for (WorkflowEdge edge : incoming.get(nodeId)) {
                preceding = Math.max(
                    preceding,
                    cumulative.getOrDefault(edge.sourceNodeId(), 0L));
            }
            WorkflowNode node = nodes.get(nodeId);
            long current = preceding;
            if (node instanceof WorkflowNode.Delay delay) {
                WorkflowDelayConfig config = delay.config();
                Integer duration = config == null ? null : config.durationSeconds();
                if (duration == null) {
                    throw invalid(
                        WorkflowDiagnosticCode.DELAY_DURATION_REQUIRED,
                        "Workflow delay durationSeconds is required",
                        nodeId, null, "config.durationSeconds", Map.of());
                }
                if (duration < MIN_DELAY_SECONDS) {
                    throw invalid(
                        WorkflowDiagnosticCode.DELAY_DURATION_BELOW_MINIMUM,
                        "Workflow delay durationSeconds is below the minimum",
                        nodeId, null, "config.durationSeconds",
                        Map.of("minimumSeconds", Integer.toString(MIN_DELAY_SECONDS)));
                }
                if (duration > MAX_DELAY_SECONDS) {
                    throw invalid(
                        WorkflowDiagnosticCode.DELAY_DURATION_ABOVE_MAXIMUM,
                        "Workflow delay durationSeconds exceeds the maximum",
                        nodeId, null, "config.durationSeconds",
                        Map.of("maximumSeconds", Integer.toString(MAX_DELAY_SECONDS)));
                }
                current += duration;
                if (current > MAX_PATH_DELAY_SECONDS) {
                    throw invalid(
                        WorkflowDiagnosticCode.CUMULATIVE_DELAY_ABOVE_MAXIMUM,
                        "Workflow cumulative delay exceeds the maximum",
                        nodeId, null, "config.durationSeconds",
                        Map.of("maximumSeconds", Integer.toString(MAX_PATH_DELAY_SECONDS)));
                }
            }
            cumulative.put(nodeId, current);
        }
    }

    private static void requireEndConnections(
            String nodeId,
            int incomingCount,
            Map<WorkflowEdge.Outcome, WorkflowEdge> outgoing) {
        if (incomingCount == 0) {
            throw invalid(
                WorkflowDiagnosticCode.INCOMING_EDGE_REQUIRED,
                "Workflow end node must have at least one incoming edge",
                nodeId, null, null, Map.of());
        }
        if (!outgoing.isEmpty()) {
            WorkflowEdge edge = outgoing.values().iterator().next();
            throw invalid(
                WorkflowDiagnosticCode.OUTGOING_EDGE_FORBIDDEN,
                "Workflow end node must not have an outgoing edge",
                edge.sourceNodeId(), edge.id(), null,
                Map.of("outcome", edge.outcome().value()));
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
                throw invalid(
                    WorkflowDiagnosticCode.NODE_UNREACHABLE,
                    "Workflow contains an unreachable node: " + nodeId,
                    nodeId, null, null, Map.of());
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
            String nodeId = nodes.keySet().stream()
                .filter(id -> !ordered.contains(id))
                .findFirst()
                .orElse(null);
            throw invalid(
                WorkflowDiagnosticCode.GRAPH_CYCLE,
                "Workflow graph must not contain a cycle",
                nodeId, null, null, Map.of());
        }
        return List.copyOf(ordered);
    }

    public record CompiledWorkflow(
        String entryNodeId,
        Map<String, WorkflowNode> nodes,
        Map<String, NodeType> nodeTypes,
        Map<String, Map<WorkflowEdge.Outcome, WorkflowEdge>> outgoing,
        List<String> topologicalOrder,
        String enrollmentConditionNodeId
    ) {

        public CompiledWorkflow {
            nodes = Collections.unmodifiableMap(new LinkedHashMap<>(nodes));
            nodeTypes = Collections.unmodifiableMap(new LinkedHashMap<>(nodeTypes));
            Map<String, Map<WorkflowEdge.Outcome, WorkflowEdge>> transitions = new LinkedHashMap<>();
            outgoing.forEach((nodeId, edges) -> {
                Map<WorkflowEdge.Outcome, WorkflowEdge> copiedEdges =
                    new EnumMap<>(WorkflowEdge.Outcome.class);
                copiedEdges.putAll(edges);
                transitions.put(nodeId, Collections.unmodifiableMap(copiedEdges));
            });
            outgoing = Collections.unmodifiableMap(transitions);
            topologicalOrder = List.copyOf(topologicalOrder);
        }

        /** Returns the immutable node for a stable node id, or {@code null}. */
        public WorkflowNode node(String nodeId) {
            return nodes.get(nodeId);
        }

        /** Returns the compiled node type for a stable node id, or {@code null}. */
        public NodeType nodeType(String nodeId) {
            return nodeTypes.get(nodeId);
        }

        /** Returns the deterministic transition for one outcome, or {@code null}. */
        public WorkflowEdge transition(String nodeId, WorkflowEdge.Outcome outcome) {
            Map<WorkflowEdge.Outcome, WorkflowEdge> transitions = outgoing.get(nodeId);
            return transitions == null ? null : transitions.get(outcome);
        }
    }

    /** Closed schema-v1 executable node categories. */
    public enum NodeType {
        TRIGGER,
        CONDITION,
        ACTION,
        DELAY,
        END
    }

    /** Compiled topology and aggregate action permissions from the same authoritative pass. */
    public record ValidatedWorkflow(
        CompiledWorkflow compiled,
        Set<Permission> requiredPermissions
    ) {

        public ValidatedWorkflow {
            requiredPermissions = Set.copyOf(requiredPermissions);
        }
    }

    private record Compilation(
        CompiledWorkflow compiled,
        WorkflowNode.Trigger trigger,
        List<WorkflowNode.Condition> conditions,
        List<WorkflowNode.Action> actions
    ) { }

    private static WorkflowDefinitionValidationException invalid(
            WorkflowDiagnosticCode code,
            String message,
            String nodeId,
            String edgeId,
            String fieldPath,
            Map<String, String> params) {
        return new WorkflowDefinitionValidationException(
            message,
            new WorkflowDiagnosticDto(code, nodeId, edgeId, fieldPath, params));
    }
}
