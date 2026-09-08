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
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;

import lombok.RequiredArgsConstructor;

import ooo.klae.connex.backend.dto.WorkflowDelayConfig;
import ooo.klae.connex.backend.dto.WorkflowDefinition;
import ooo.klae.connex.backend.dto.WorkflowDiagnosticCode;
import ooo.klae.connex.backend.dto.WorkflowDiagnosticDto;
import ooo.klae.connex.backend.dto.WorkflowEdge;
import ooo.klae.connex.backend.dto.WorkflowInputDefinition;
import ooo.klae.connex.backend.dto.WorkflowInputType;
import ooo.klae.connex.backend.dto.WorkflowNode;
import ooo.klae.connex.backend.dto.WorkflowTextPart;
import ooo.klae.connex.backend.dto.WorkflowTextTemplate;
import ooo.klae.connex.backend.dto.WorkflowValueRef;
import ooo.klae.connex.backend.dto.WorkflowWaitConfig;
import ooo.klae.connex.backend.dto.WorkflowEnrollment;
import ooo.klae.connex.backend.dto.SegmentDefinition;
import ooo.klae.connex.backend.exceptions.WorkflowDefinitionValidationException;
import ooo.klae.connex.backend.tenant.Permission;

/** Compiles and authoritatively validates executable schema-v1 workflow DAGs. */
@Component
@RequiredArgsConstructor
public class WorkflowDefinitionValidator {

    private static final int MIN_DELAY_SECONDS = 60;
    private static final int MAX_DELAY_SECONDS = 2_592_000;
    private static final int MAX_PATH_DELAY_SECONDS = 7_776_000;
    private static final int MAX_INPUTS = 16;
    private static final Pattern INPUT_KEY = Pattern.compile("[a-z][a-zA-Z0-9_]{0,47}");

    private final RuleDefinitionValidator ruleDefinitionValidator;
    private final WorkflowCapabilityCatalog capabilityCatalog;

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
            executionMode,
            definition.schemaVersion());
        validateV2Definition(recordType, definition, compilation);
        return compilation.compiled();
    }

    public Set<Permission> validateForMutation(
            String recordType,
            String executionMode,
            WorkflowDefinition definition) {
        return validateForMutationAndCompile(
            recordType, executionMode, definition).requiredPermissions();
    }

    /**
     * Validates only egress-sensitive actions that an incomplete draft is not allowed to persist.
     * Graph completeness remains a publication concern.
     */
    public Set<Permission> validateDraftActionsForMutation(
            String recordType,
            String executionMode,
            WorkflowDefinition definition) {
        List<WorkflowNode> nodes = definition == null || definition.nodes() == null
            ? List.of()
            : definition.nodes();
        return ruleDefinitionValidator.validateDraftActionsForMutation(
            recordType, nodes, executionMode);
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
            executionMode,
            definition.schemaVersion());
        Set<Permission> completePermissions = new HashSet<>(permissions);
        ruleDefinitionValidator.requireSystemPermissions(executionMode, completePermissions);
        validateV2Definition(recordType, definition, compilation);
        return new ValidatedWorkflow(compilation.compiled(), completePermissions);
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
            } else if (node instanceof WorkflowNode.Wait) {
                nodeTypes.put(nodeId, NodeType.WAIT);
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
        validateWaits(snapshot, nodes, incoming, outgoing, topologicalOrder);
        String enrollmentConditionNodeId = scheduleEnrollmentCondition(
            snapshot, trigger, nodes, outgoing);
        CompiledWorkflow compiled = new CompiledWorkflow(
            snapshot.schemaVersion(), trigger.id(), nodes, nodeTypes, outgoing, topologicalOrder,
            snapshot.inputs() == null ? List.of() : snapshot.inputs(),
            enrollmentConditionNodeId, snapshot.enrollment(), snapshot.stopConditions());
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
            } else if (node instanceof WorkflowNode.Wait) {
                requireWaitConnections(node.id(), incomingCount, transitions);
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

    private static void requireWaitConnections(
            String nodeId,
            int incomingCount,
            Map<WorkflowEdge.Outcome, WorkflowEdge> outgoing) {
        if (incomingCount == 0) {
            throw invalid(
                WorkflowDiagnosticCode.INCOMING_EDGE_REQUIRED,
                "Workflow wait node must have at least one incoming edge",
                nodeId, null, null, Map.of());
        }
        requireOutcomes(
            nodeId,
            outgoing,
            Set.of(WorkflowEdge.Outcome.COMPLETED, WorkflowEdge.Outcome.TIMEOUT),
            "Workflow wait node must have exactly one completed and one timeout edge");
    }

    private static String scheduleEnrollmentCondition(
            WorkflowDefinition definition,
            WorkflowNode.Trigger trigger,
            Map<String, WorkflowNode> nodes,
            Map<String, Map<WorkflowEdge.Outcome, WorkflowEdge>> outgoing) {
        if (trigger.config() == null
                || !"schedule".equals(normalize(trigger.config().getType()))) {
            return null;
        }
        WorkflowEdge edge = outgoing.get(trigger.id()).get(WorkflowEdge.Outcome.NEXT);
        WorkflowNode target = nodes.get(edge.targetNodeId());
        if (definition.schemaVersion() == 2
                && definition.enrollment() != null
                && definition.enrollment().condition() != null) {
            if (target instanceof WorkflowNode.Condition) {
                throw invalid(
                    WorkflowDiagnosticCode.SCHEDULE_ENROLLMENT_CONDITION_REQUIRED,
                    "Schedule enrollment cannot use both top-level and immediate conditions",
                    trigger.id(), edge.id(), "enrollment.condition", Map.of());
            }
            return null;
        }
        if (definition.schemaVersion() == 2 && !(target instanceof WorkflowNode.Condition)) {
            return null;
        }
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

    private void validateV2Definition(
            String recordType,
            WorkflowDefinition definition,
            Compilation compilation) {
        if (definition.schemaVersion() == 1) {
            if (definition.inputs() != null
                    || definition.enrollment() != null
                    || definition.stopConditions() != null) {
                throw invalid(
                    WorkflowDiagnosticCode.BINDING_INVALID,
                    "Schema-v2 configuration requires schemaVersion 2",
                    null, null, null, Map.of());
            }
            return;
        }
        if (definition.schemaVersion() != 2) {
            throw invalid(
                WorkflowDiagnosticCode.CONFIG_FIELD_INVALID,
                "Workflow definition schemaVersion is unsupported",
                null, null, "schemaVersion", Map.of());
        }
        Map<String, WorkflowInputDefinition> inputs = validateInputs(definition, compilation.trigger());
        validatePolicies(recordType, definition);
        for (WorkflowNode.Action action : compilation.actions()) {
            validateActionBindings(recordType, action, inputs, compilation.compiled());
        }
        for (WorkflowNode node : definition.nodes()) {
            if (node instanceof WorkflowNode.Wait wait) {
                validateRef(
                    recordType,
                    wait.id(),
                    "config.source",
                    new WorkflowValueRef(
                        "step_output",
                        null,
                        null,
                        wait.config().source().nodeId(),
                        wait.config().source().output()),
                    "integer",
                    inputs,
                    compilation.compiled(),
                    false);
            }
        }
    }

    private void validatePolicies(String recordType, WorkflowDefinition definition) {
        WorkflowEnrollment enrollment = definition.enrollment();
        if (enrollment != null) {
            if (enrollment.oneActiveRun() == null
                    || enrollment.cooldownMinutes() == null
                    || enrollment.cooldownMinutes() < 0
                    || enrollment.cooldownMinutes() > 525_600) {
                throw invalid(
                    WorkflowDiagnosticCode.CONFIG_FIELD_INVALID,
                    "Workflow enrollment policy is invalid",
                    null, null, "enrollment", Map.of());
            }
            if (enrollment.condition() != null) {
                ruleDefinitionValidator.validateWorkflowPolicyCondition(
                    recordType, enrollment.condition(), "enrollment.condition");
            }
        }
        if (definition.stopConditions() != null) {
            ruleDefinitionValidator.validateWorkflowPolicyCondition(
                recordType, definition.stopConditions(), "stopConditions");
        }
        for (WorkflowNode node : definition.nodes()) {
            if (!(node instanceof WorkflowNode.End end) || end.config() == null) {
                continue;
            }
            String outcome = end.config().outcome();
            String reason = end.config().reason();
            if (!Set.of("completed", "stopped").contains(outcome)
                    || "completed".equals(outcome) && reason != null
                    || "stopped".equals(outcome) && reason != null
                        && !reason.matches("[a-z][a-z0-9_]{0,63}")) {
                throw invalid(
                    WorkflowDiagnosticCode.CONFIG_FIELD_INVALID,
                    "Workflow end configuration is invalid",
                    end.id(), null, "config", Map.of());
            }
        }
    }

    private static Map<String, WorkflowInputDefinition> validateInputs(
            WorkflowDefinition definition,
            WorkflowNode.Trigger trigger) {
        List<WorkflowInputDefinition> declared = definition.inputs() == null
            ? List.of()
            : definition.inputs();
        if (declared.size() > MAX_INPUTS) {
            throw invalid(
                WorkflowDiagnosticCode.INPUT_DEFINITION_INVALID,
                "Workflow input count exceeds the limit",
                null, null, "inputs", Map.of("maximum", Integer.toString(MAX_INPUTS)));
        }
        Map<String, WorkflowInputDefinition> inputs = new LinkedHashMap<>();
        boolean automatic = trigger.config() != null
            && !"manual".equals(normalize(trigger.config().getType()));
        for (int index = 0; index < declared.size(); index++) {
            WorkflowInputDefinition input = declared.get(index);
            String path = "inputs." + index;
            if (input == null
                    || input.key() == null
                    || !INPUT_KEY.matcher(input.key()).matches()
                    || input.label() == null
                    || input.label().isBlank()
                    || input.label().length() > 80
                    || input.type() == null
                    || inputs.putIfAbsent(input.key(), input) != null) {
                throw invalid(
                    WorkflowDiagnosticCode.INPUT_DEFINITION_INVALID,
                    "Workflow input definition is invalid",
                    null, null, path, Map.of());
            }
            if (input.defaultValue() != null
                    && !validInputValue(input.type(), input.defaultValue())) {
                throw invalid(
                    WorkflowDiagnosticCode.INPUT_TYPE_INVALID,
                    "Workflow input default has the wrong type",
                    null, null, path + ".defaultValue", Map.of("key", input.key()));
            }
            if (input.required()
                    && input.type() == WorkflowInputType.TEXT
                    && input.defaultValue() != null
                    && input.defaultValue().isTextual()
                    && input.defaultValue().textValue().isBlank()) {
                throw invalid(
                    WorkflowDiagnosticCode.INPUT_REQUIRED,
                    "Required workflow input default cannot be blank",
                    null, null, path + ".defaultValue", Map.of("key", input.key()));
            }
            if (automatic && input.required() && input.defaultValue() == null) {
                throw invalid(
                    WorkflowDiagnosticCode.INPUT_REQUIRED,
                    "Automatic workflow inputs require defaults",
                    null, null, path + ".defaultValue", Map.of("key", input.key()));
            }
        }
        return Map.copyOf(inputs);
    }

    private static boolean validInputValue(WorkflowInputType type, JsonNode value) {
        return switch (type) {
            case TEXT -> value.isTextual() && value.textValue().length() <= 2000;
            case USER -> value.isIntegralNumber() && value.canConvertToInt() && value.intValue() > 0;
            case DATE -> value.isTextual() && validDate(value.textValue());
        };
    }

    private void validateActionBindings(
            String recordType,
            WorkflowNode.Action node,
            Map<String, WorkflowInputDefinition> inputs,
            CompiledWorkflow compiled) {
        var action = node.config();
        validateRef(recordType, node.id(), "config.targetUserRef", action.getTargetUserRef(),
            "user", inputs, compiled, false);
        validateRef(recordType, node.id(), "config.dueDateRef", action.getDueDateRef(),
            "date", inputs, compiled, false);
        validateRef(recordType, node.id(), "config.valueRef", action.getValueRef(),
            "date", inputs, compiled, false);
        validateTemplate(recordType, node.id(), "config.titleTemplate", action.getTitleTemplate(),
            inputs, compiled);
        validateTemplate(recordType, node.id(), "config.bodyTemplate", action.getBodyTemplate(),
            inputs, compiled);
    }

    private void validateTemplate(
            String recordType,
            String nodeId,
            String path,
            WorkflowTextTemplate template,
            Map<String, WorkflowInputDefinition> inputs,
            CompiledWorkflow compiled) {
        if (template == null) {
            return;
        }
        if (template.parts() == null
                || template.parts().isEmpty()
                || template.parts().size() > 64
                || !Set.of("fail", "empty").contains(template.missingValue())) {
            throw invalid(
                WorkflowDiagnosticCode.BINDING_INVALID,
                "Workflow text template is invalid",
                nodeId, null, path, Map.of());
        }
        for (int index = 0; index < template.parts().size(); index++) {
            WorkflowTextPart part = template.parts().get(index);
            if (part == null || (part.text() == null) == (part.ref() == null)) {
                throw invalid(
                    WorkflowDiagnosticCode.BINDING_INVALID,
                    "Workflow text template part is invalid",
                    nodeId, null, path + ".parts." + index, Map.of());
            }
            if (part.text() != null && part.text().length() > 2000) {
                throw invalid(
                    WorkflowDiagnosticCode.BINDING_INVALID,
                    "Workflow text template literal is too long",
                    nodeId, null, path + ".parts." + index + ".text", Map.of());
            }
            if (part.ref() != null) {
                validateRef(recordType, nodeId, path + ".parts." + index + ".ref",
                    part.ref(), null, inputs, compiled, true);
            }
        }
    }

    private void validateRef(
            String recordType,
            String consumerNodeId,
            String path,
            WorkflowValueRef ref,
            String expectedType,
            Map<String, WorkflowInputDefinition> inputs,
            CompiledWorkflow compiled,
            boolean textContext) {
        if (ref == null) {
            return;
        }
        String actualType;
        switch (ref.source() == null ? "" : ref.source()) {
            case "launch_input" -> {
                WorkflowInputDefinition input = inputs.get(ref.key());
                if (input == null || ref.field() != null || ref.nodeId() != null || ref.output() != null) {
                    throw bindingInvalid(consumerNodeId, path);
                }
                actualType = input.type().value();
            }
            case "record_field" -> {
                if (ref.field() == null || ref.key() != null || ref.nodeId() != null || ref.output() != null) {
                    throw bindingInvalid(consumerNodeId, path);
                }
                actualType = capabilityCatalog.recordFieldType(recordType, ref.field());
                if (actualType == null) {
                    throw bindingInvalid(consumerNodeId, path);
                }
            }
            case "step_output" -> {
                if (ref.nodeId() == null
                        || !"taskId".equals(ref.output())
                        || ref.key() != null
                        || ref.field() != null) {
                    throw bindingInvalid(consumerNodeId, path);
                }
                WorkflowNode source = compiled.node(ref.nodeId());
                if (!(source instanceof WorkflowNode.Action sourceAction)
                        || sourceAction.config() == null
                        || !"create_task".equals(normalize(sourceAction.config().getType()))) {
                    throw bindingInvalid(consumerNodeId, path);
                }
                if (!dominates(compiled, ref.nodeId(), consumerNodeId)) {
                    throw invalid(
                        WorkflowDiagnosticCode.STEP_OUTPUT_NOT_DOMINATING,
                        "Workflow step output does not dominate its consumer",
                        consumerNodeId, null, path, Map.of("sourceNodeId", ref.nodeId()));
                }
                actualType = "integer";
            }
            default -> throw bindingInvalid(consumerNodeId, path);
        }
        if (!textContext && !actualType.equals(expectedType)) {
            throw bindingInvalid(consumerNodeId, path);
        }
    }

    private static boolean dominates(
            CompiledWorkflow compiled, String sourceNodeId, String consumerNodeId) {
        if (sourceNodeId.equals(consumerNodeId)) {
            return false;
        }
        Set<String> blocked = new HashSet<>();
        blocked.add(sourceNodeId);
        ArrayDeque<String> pending = new ArrayDeque<>();
        pending.add(compiled.entryNodeId());
        Set<String> visited = new HashSet<>();
        while (!pending.isEmpty()) {
            String current = pending.removeFirst();
            if (!visited.add(current) || blocked.contains(current)) {
                continue;
            }
            if (consumerNodeId.equals(current)) {
                return false;
            }
            compiled.outgoing().getOrDefault(current, Map.of()).values().stream()
                .map(WorkflowEdge::targetNodeId)
                .forEach(pending::addLast);
        }
        return true;
    }

    private static WorkflowDefinitionValidationException bindingInvalid(
            String nodeId, String path) {
        return invalid(
            WorkflowDiagnosticCode.BINDING_INVALID,
            "Workflow value binding is invalid",
            nodeId, null, path, Map.of());
    }

    private static boolean validDate(String value) {
        if (!value.matches("\\d{4}-\\d{2}-\\d{2}")) {
            return false;
        }
        try {
            return java.time.LocalDate.parse(value).toString().equals(value);
        } catch (java.time.DateTimeException exception) {
            return false;
        }
    }

    private static void validateWaits(
            WorkflowDefinition definition,
            Map<String, WorkflowNode> nodes,
            Map<String, List<WorkflowEdge>> incoming,
            Map<String, Map<WorkflowEdge.Outcome, WorkflowEdge>> outgoing,
            List<String> topologicalOrder) {
        if (definition.schemaVersion() == 1
                && nodes.values().stream().anyMatch(WorkflowNode.Wait.class::isInstance)) {
            throw invalid(
                WorkflowDiagnosticCode.NODE_TYPE_UNSUPPORTED,
                "Workflow wait nodes require schemaVersion 2",
                null, null, "nodes", Map.of());
        }
        for (String nodeId : topologicalOrder) {
            if (!(nodes.get(nodeId) instanceof WorkflowNode.Wait wait)) {
                continue;
            }
            WorkflowWaitConfig config = wait.config();
            if (config == null
                    || !"event".equals(config.kind())
                    || !"task.completed".equals(config.event())
                    || config.source() == null
                    || config.timeoutSeconds() == null
                    || config.timeoutSeconds() < 60
                    || config.timeoutSeconds() > 2_592_000) {
                throw invalid(
                    WorkflowDiagnosticCode.CONFIG_FIELD_INVALID,
                    "Workflow wait configuration is invalid",
                    nodeId, null, "config", Map.of());
            }
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
        int schemaVersion,
        String entryNodeId,
        Map<String, WorkflowNode> nodes,
        Map<String, NodeType> nodeTypes,
        Map<String, Map<WorkflowEdge.Outcome, WorkflowEdge>> outgoing,
        List<String> topologicalOrder,
        List<WorkflowInputDefinition> inputs,
        String enrollmentConditionNodeId,
        WorkflowEnrollment enrollment,
        SegmentDefinition stopConditions
    ) {

        public CompiledWorkflow {
            nodes = Map.copyOf(nodes);
            nodeTypes = Map.copyOf(nodeTypes);
            Map<String, Map<WorkflowEdge.Outcome, WorkflowEdge>> transitions = new LinkedHashMap<>();
            outgoing.forEach((nodeId, edges) -> {
                Map<WorkflowEdge.Outcome, WorkflowEdge> copiedEdges =
                    new EnumMap<>(WorkflowEdge.Outcome.class);
                copiedEdges.putAll(edges);
                transitions.put(nodeId, Collections.unmodifiableMap(copiedEdges));
            });
            outgoing = Collections.unmodifiableMap(transitions);
            topologicalOrder = List.copyOf(topologicalOrder);
            inputs = List.copyOf(inputs);
        }

        public CompiledWorkflow(
                String entryNodeId,
                Map<String, WorkflowNode> nodes,
                Map<String, NodeType> nodeTypes,
                Map<String, Map<WorkflowEdge.Outcome, WorkflowEdge>> outgoing,
                List<String> topologicalOrder,
                String enrollmentConditionNodeId) {
            this(
                1,
                entryNodeId,
                nodes,
                nodeTypes,
                outgoing,
                topologicalOrder,
                List.of(),
                enrollmentConditionNodeId,
                null,
                null);
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
        WAIT,
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
