package ooo.klae.connex.backend.services;

import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;

import ooo.klae.connex.backend.dto.RuleAction;
import ooo.klae.connex.backend.dto.RulePreviewRequest;
import ooo.klae.connex.backend.dto.RuleRequest;
import ooo.klae.connex.backend.dto.RuleTrigger;
import ooo.klae.connex.backend.dto.SegmentDefinition;
import ooo.klae.connex.backend.dto.WorkflowDiagnosticCode;
import ooo.klae.connex.backend.dto.WorkflowDiagnosticDto;
import ooo.klae.connex.backend.dto.WorkflowNode;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.WorkflowDefinitionValidationException;
import ooo.klae.connex.backend.services.WorkspaceService.Role;
import ooo.klae.connex.backend.tenant.Permission;

/**
 * Validates and normalizes the shared semantic definition used by automation rules. Every path that
 * authors, publishes, manually runs, simulates, or revalidates a definition passes through here, so
 * {@link WorkflowDocumentAutomationGate} refusing {@code document} here closes the rolling-deployment
 * fence for every one of them.
 */
@Component
@RequiredArgsConstructor
public class RuleDefinitionValidator {

    private static final Set<String> RECORD_TYPES = Set.of(
        "company", "person", "deal", "task", "document");
    private static final Set<String> TRIGGER_TYPES = Set.of("entity_change", "schedule");
    private static final Set<String> EXECUTION_MODES = Set.of("user", "system");
    private static final Set<String> ACTION_TYPES = Set.of(
        "create_task", "log_activity", "add_tag", "remove_tag", "create_note",
        "assign_owner", "set_response_due", "change_stage", "notify");
    private static final Set<String> CADENCES = Set.of("hourly", "daily", "weekly");
    private static final int MAX_RESPONSE_DUE_IN_HOURS = 24 * 365;
    private static final Set<String> ENTITY_CHANGE_RECORD_TYPES = Set.of(
        "deal", "company", "person", "task", "document");
    private static final Set<String> SEGMENT_RECORD_TYPES = Set.of("company", "person", "deal");
    private static final Set<String> DEAL_EVENTS = Set.of(
        "deal.created", "deal.stage_changed", "deal.updated", "deal.won", "deal.lost",
        "deal.owner_changed", "deal.value_changed");
    private static final Set<String> COMPANY_EVENTS = Set.of(
        "company.created", "company.updated", "company.owner_changed");
    private static final Set<String> PERSON_EVENTS = Set.of(
        "person.created", "person.updated", "person.job_changed", "person.owner_changed",
        "person.lifecycle_changed", "person.first_response_overdue");
    private static final Set<String> TASK_EVENTS = Set.of("task.created", "task.completed");
    private static final Set<String> DOCUMENT_EVENTS = Set.of(
        "document.approval_requested", "document.approved", "document.rejected",
        "document.finalized", "document.superseded");
    private static final Map<String, Set<String>> ACTION_RECORD_TYPES = Map.of(
        "create_task", Set.of("person", "deal", "document"),
        "log_activity", Set.of("person", "deal", "document"),
        "add_tag", Set.of("company", "person", "deal"),
        "remove_tag", Set.of("company", "person", "deal"),
        "create_note", Set.of("person", "deal", "document"),
        "assign_owner", Set.of("person", "deal"),
        "set_response_due", Set.of("person"),
        "change_stage", Set.of("deal"),
        "notify", Set.of("company", "person", "deal", "task", "document"));

    private final SegmentService segmentService;
    private final WorkspaceService workspaceService;
    private final Validator beanValidator;
    private final WorkflowDocumentAutomationGate documentAutomationGate;

    String validatePreview(RulePreviewRequest request) {
        String recordType = normalize(request.getRecordType());
        if (!SEGMENT_RECORD_TYPES.contains(recordType)) {
            throw new BadRequestException("Preview is not supported for record type: " + request.getRecordType());
        }
        if (!hasWhen(request.getCondition())) {
            throw new BadRequestException("A preview requires at least one condition");
        }
        return recordType;
    }

    void validate(RuleRequest request) {
        Set<Permission> required = validateForMutation(request);
        requireCurrentSystemRole(request.getExecutionMode());
        requireCurrentPermissions(required);
    }

    Set<Permission> validateForMutation(RuleRequest request) {
        if (request == null) {
            throw new BadRequestException("Rule definition is required");
        }
        return validateDefinitionForMutation(
            request.getRecordType(),
            request.getTrigger(),
            request.getCondition(),
            request.getActions(),
            request.getExecutionMode());
    }

    void validateDefinition(
            String recordTypeValue,
            RuleTrigger trigger,
            SegmentDefinition condition,
            List<RuleAction> actions,
            String executionMode) {
        Set<Permission> required = validateDefinitionForMutation(
            recordTypeValue, trigger, condition, actions, executionMode);
        requireCurrentSystemRole(executionMode);
        requireCurrentPermissions(required);
    }

    void validateWorkflowDefinition(
            String recordTypeValue,
            RuleTrigger trigger,
            List<SegmentDefinition> conditions,
            List<RuleAction> actions,
            String executionMode) {
        Set<Permission> required = validateWorkflowDefinitionForMutation(
            recordTypeValue, trigger, conditions, actions, executionMode);
        requireCurrentSystemRole(executionMode);
        requireCurrentPermissions(required);
    }

    void validateWorkflowNodes(
            String recordTypeValue,
            WorkflowNode.Trigger trigger,
            List<WorkflowNode.Condition> conditions,
            List<WorkflowNode.Action> actions,
            String executionMode) {
        Set<Permission> required = validateWorkflowNodesForMutation(
            recordTypeValue, trigger, conditions, actions, executionMode);
        requireCurrentSystemRole(executionMode);
        requireCurrentPermissions(required);
    }

    Set<Permission> validateWorkflowNodesForMutation(
            String recordTypeValue,
            WorkflowNode.Trigger trigger,
            List<WorkflowNode.Condition> conditions,
            List<WorkflowNode.Action> actions,
            String executionMode) {
        Configured<RuleTrigger> configuredTrigger = new Configured<>(
            trigger == null ? null : trigger.id(),
            trigger == null ? null : trigger.config());
        List<Configured<SegmentDefinition>> configuredConditions = conditions == null
            ? null
            : conditions.stream()
                .map(condition -> new Configured<>(condition.id(), condition.config()))
                .toList();
        List<Configured<RuleAction>> configuredActions = actions == null
            ? null
            : actions.stream()
                .map(action -> new Configured<>(action.id(), action.config()))
                .toList();
        return validateConfiguredWorkflowDefinitionForMutation(
            recordTypeValue,
            configuredTrigger,
            configuredConditions,
            configuredActions,
            executionMode);
    }

    Set<Permission> validateDefinitionForMutation(
            String recordTypeValue,
            RuleTrigger trigger,
            SegmentDefinition condition,
            List<RuleAction> actions,
            String executionMode) {
        List<SegmentDefinition> conditions = condition == null
            ? List.of()
            : List.of(condition);
        return validateWorkflowDefinitionForMutation(
            recordTypeValue, trigger, conditions, actions, executionMode);
    }

    Set<Permission> validateWorkflowDefinitionForMutation(
            String recordTypeValue,
            RuleTrigger trigger,
            List<SegmentDefinition> conditions,
            List<RuleAction> actions,
            String executionMode) {
        return validateConfiguredWorkflowDefinitionForMutation(
            recordTypeValue,
            new Configured<>(null, trigger),
            conditions == null
                ? null
                : conditions.stream().map(value -> new Configured<>(null, value)).toList(),
            actions == null
                ? null
                : actions.stream().map(value -> new Configured<>(null, value)).toList(),
            executionMode);
    }

    private Set<Permission> validateConfiguredWorkflowDefinitionForMutation(
            String recordTypeValue,
            Configured<RuleTrigger> trigger,
            List<Configured<SegmentDefinition>> conditions,
            List<Configured<RuleAction>> actions,
            String executionMode) {
        if (trigger == null || trigger.value() == null) {
            throw invalid(
                WorkflowDiagnosticCode.TRIGGER_CONFIG_REQUIRED,
                "Rule trigger is required",
                trigger == null ? null : trigger.nodeId(), "config", Map.of());
        }
        if (conditions == null) {
            throw invalid(
                WorkflowDiagnosticCode.CONDITION_CONFIG_REQUIRED,
                "Workflow condition config is required",
                null, "config", Map.of());
        }
        Configured<SegmentDefinition> missingCondition = conditions.stream()
            .filter(condition -> condition.value() == null)
            .findFirst()
            .orElse(null);
        if (missingCondition != null) {
            throw invalid(
                WorkflowDiagnosticCode.CONDITION_CONFIG_REQUIRED,
                "Workflow condition config is required",
                missingCondition.nodeId(), "config", Map.of());
        }
        if (actions == null || actions.isEmpty() || actions.size() > 16) {
            throw invalid(
                WorkflowDiagnosticCode.ACTION_REQUIRED,
                "A rule requires between 1 and 16 actions",
                null, "nodes", Map.of("minimum", "1", "maximum", "16"));
        }
        Configured<RuleAction> missingAction = actions.stream()
            .filter(action -> action.value() == null)
            .findFirst()
            .orElse(null);
        if (missingAction != null) {
            throw invalid(
                WorkflowDiagnosticCode.ACTION_CONFIG_REQUIRED,
                "Rule action config is required",
                missingAction.nodeId(), "config", Map.of());
        }
        requireStructurallyValid(trigger);
        conditions.forEach(this::requireStructurallyValid);
        actions.forEach(this::requireStructurallyValid);

        String recordType = normalize(recordTypeValue);
        if (!RECORD_TYPES.contains(recordType)
                || !documentAutomationGate.permits(recordType)) {
            throw invalid(
                WorkflowDiagnosticCode.RECORD_TYPE_INVALID,
                "Invalid record type: " + recordTypeValue,
                null, "recordType", Map.of());
        }
        String mode = normalize(executionMode);
        if (!EXECUTION_MODES.contains(mode)) {
            throw invalid(
                WorkflowDiagnosticCode.EXECUTION_MODE_INVALID,
                "Invalid execution mode: " + executionMode,
                null, "executionMode", Map.of());
        }
        if (!conditions.isEmpty() && !SEGMENT_RECORD_TYPES.contains(recordType)) {
            throw invalid(
                WorkflowDiagnosticCode.CONDITION_RECORD_TYPE_UNSUPPORTED,
                "WHEN conditions are not supported for record type: " + recordTypeValue,
                conditions.getFirst().nodeId(), "config", Map.of("recordType", recordType));
        }
        for (Configured<SegmentDefinition> condition : conditions) {
            if (!hasWhen(condition.value())) {
                throw invalid(
                    WorkflowDiagnosticCode.CONDITION_EMPTY,
                    "A WHEN condition must contain at least one condition",
                    condition.nodeId(), "config", Map.of());
            }
            try {
                segmentService.validate(recordType, condition.value());
            } catch (WorkflowDefinitionValidationException exception) {
                throw new WorkflowDefinitionValidationException(
                    exception.getMessage(),
                    exception.diagnostic().atNode(condition.nodeId(), "config"));
            }
        }
        validateTrigger(trigger, recordType, !conditions.isEmpty());
        validateActions(actions, recordType);
        return actionPermissions(actions, recordType);
    }

    private <T> void requireStructurallyValid(Configured<T> configured) {
        ConstraintViolation<T> violation = beanValidator.validate(configured.value()).stream()
            .sorted(Comparator.comparing(value -> value.getPropertyPath().toString()))
            .findFirst()
            .orElse(null);
        if (violation != null) {
            String property = violation.getPropertyPath().toString();
            throw invalid(
                WorkflowDiagnosticCode.CONFIG_FIELD_INVALID,
                "Rule definition is invalid",
                configured.nodeId(),
                property.isBlank() ? "config" : "config." + property,
                Map.of());
        }
    }

    String normalize(String value) {
        return value == null ? null : value.trim().toLowerCase(Locale.ROOT);
    }

    boolean hasWhen(SegmentDefinition condition) {
        if (condition == null) {
            return false;
        }
        boolean hasConditions = condition.getConditions() != null && !condition.getConditions().isEmpty();
        boolean hasGroups = condition.getGroups() != null && !condition.getGroups().isEmpty();
        return hasConditions || hasGroups;
    }

    private Set<Permission> actionPermissions(
            List<Configured<RuleAction>> actions, String recordType) {
        EnumSet<Permission> required = EnumSet.noneOf(Permission.class);
        for (Configured<RuleAction> action : actions) {
            Permission permission = actionPermission(
                normalize(action.value().getType()), recordType);
            if (permission != null) {
                required.add(permission);
            }
        }
        return Set.copyOf(required);
    }

    private void requireCurrentPermissions(Set<Permission> required) {
        required.forEach(workspaceService::requirePermission);
    }

    private void requireCurrentSystemRole(String executionMode) {
        if ("system".equals(normalize(executionMode))) {
            workspaceService.requireRole(Role.ADMIN);
        }
    }

    Permission actionPermission(RuleAction action, String recordType) {
        return actionPermission(normalize(action.getType()), recordType);
    }

    private Permission actionPermission(String type, String recordType) {
        return switch (type) {
            case "create_task" -> Permission.TASK_CREATE;
            case "log_activity" -> Permission.ACTIVITY_CREATE;
            case "create_note" -> Permission.NOTE_CREATE;
            case "change_stage" -> Permission.DEAL_UPDATE;
            case "set_response_due" -> Permission.PERSON_UPDATE;
            case "assign_owner" -> switch (recordType) {
                case "person" -> Permission.PERSON_UPDATE;
                case "deal" -> Permission.DEAL_UPDATE;
                default -> null;
            };
            case "add_tag", "remove_tag" -> switch (recordType) {
                case "company" -> Permission.COMPANY_UPDATE;
                case "person" -> Permission.PERSON_UPDATE;
                case "deal" -> Permission.DEAL_UPDATE;
                default -> null;
            };
            default -> null;
        };
    }

    private void validateTrigger(
            Configured<RuleTrigger> trigger, String recordType, boolean hasCondition) {
        RuleTrigger value = trigger.value();
        String type = normalize(value.getType());
        if (!TRIGGER_TYPES.contains(type)) {
            throw invalid(
                WorkflowDiagnosticCode.TRIGGER_TYPE_INVALID,
                "Invalid trigger type: " + value.getType(),
                trigger.nodeId(), "config.type", Map.of());
        }
        if ("entity_change".equals(type)) {
            if (!ENTITY_CHANGE_RECORD_TYPES.contains(recordType)) {
                throw invalid(
                    WorkflowDiagnosticCode.ENTITY_CHANGE_RECORD_TYPE_UNSUPPORTED,
                    "Entity-change rules are not supported for record type: " + recordType,
                    trigger.nodeId(), "config.type", Map.of("recordType", recordType));
            }
            if (value.getEvents() == null || value.getEvents().isEmpty()) {
                throw invalid(
                    WorkflowDiagnosticCode.TRIGGER_EVENTS_REQUIRED,
                    "An entity-change trigger requires at least one event",
                    trigger.nodeId(), "config.events", Map.of());
            }
            Set<String> supported = switch (recordType) {
                case "deal" -> DEAL_EVENTS;
                case "company" -> COMPANY_EVENTS;
                case "person" -> PERSON_EVENTS;
                case "task" -> TASK_EVENTS;
                case "document" -> DOCUMENT_EVENTS;
                default -> Set.of();
            };
            for (String event : value.getEvents()) {
                if (!supported.contains(event)) {
                    throw invalid(
                        WorkflowDiagnosticCode.TRIGGER_EVENT_UNSUPPORTED,
                        "Unsupported event for " + recordType + ": " + event,
                        trigger.nodeId(), "config.events",
                        Map.of("recordType", recordType));
                }
            }
        } else {
            if (!SEGMENT_RECORD_TYPES.contains(recordType)) {
                throw invalid(
                    WorkflowDiagnosticCode.SCHEDULE_RECORD_TYPE_UNSUPPORTED,
                    "Schedule rules are not supported for record type: " + recordType,
                    trigger.nodeId(), "config.type", Map.of("recordType", recordType));
            }
            if (value.getCadence() == null
                    || !CADENCES.contains(normalize(value.getCadence()))) {
                throw invalid(
                    WorkflowDiagnosticCode.SCHEDULE_CADENCE_INVALID,
                    "A schedule rule requires a valid cadence",
                    trigger.nodeId(), "config.cadence", Map.of());
            }
            if (!hasCondition) {
                throw invalid(
                    WorkflowDiagnosticCode.SCHEDULE_CONDITION_REQUIRED,
                    "A schedule rule requires a WHEN condition",
                    trigger.nodeId(), "config.type", Map.of());
            }
        }
    }

    private void validateActions(
            List<Configured<RuleAction>> actions, String recordType) {
        for (Configured<RuleAction> configured : actions) {
            RuleAction action = configured.value();
            String type = normalize(action.getType());
            if (!ACTION_TYPES.contains(type)) {
                throw invalid(
                    WorkflowDiagnosticCode.ACTION_TYPE_INVALID,
                    "Invalid action type: " + action.getType(),
                    configured.nodeId(), "config.type", Map.of());
            }
            Set<String> supportedRecordTypes = ACTION_RECORD_TYPES.get(type);
            if (supportedRecordTypes == null || !supportedRecordTypes.contains(recordType)) {
                throw invalid(
                    WorkflowDiagnosticCode.ACTION_RECORD_TYPE_UNSUPPORTED,
                    "'" + type + "' actions are not supported for " + recordType + " rules",
                    configured.nodeId(), "config.type",
                    Map.of("actionType", type, "recordType", recordType));
            }
            switch (type) {
                case "create_task", "notify" -> requireText(
                    action.getTitle(), configured.nodeId(), "title");
                case "log_activity" -> requireText(
                    action.getActivityType(), configured.nodeId(), "activityType");
                case "create_note" -> requireText(
                    action.getBody(), configured.nodeId(), "body");
                case "add_tag", "remove_tag" -> {
                    if (action.getTagId() == null) {
                        throw requiredActionField(
                            "A " + type + " action requires a tagId",
                            configured.nodeId(), "tagId");
                    }
                }
                case "assign_owner" -> {
                    if (action.getTargetUserId() == null) {
                        throw requiredActionField(
                            "An assign_owner action requires a targetUserId",
                            configured.nodeId(), "targetUserId");
                    }
                }
                case "set_response_due" -> {
                    if (action.getDueInHours() == null || action.getDueInHours() < 1
                            || action.getDueInHours() > MAX_RESPONSE_DUE_IN_HOURS) {
                        throw requiredActionField(
                            "A set_response_due action requires a dueInHours between 1 and "
                                + MAX_RESPONSE_DUE_IN_HOURS,
                            configured.nodeId(), "dueInHours");
                    }
                }
                case "change_stage" -> {
                    if (action.getTargetStageId() == null) {
                        throw requiredActionField(
                            "A change_stage action requires a targetStageId",
                            configured.nodeId(), "targetStageId");
                    }
                }
                default -> throw invalid(
                    WorkflowDiagnosticCode.ACTION_TYPE_INVALID,
                    "Invalid action type: " + action.getType(),
                    configured.nodeId(), "config.type", Map.of());
            }
        }
    }

    private static void requireText(String value, String nodeId, String field) {
        if (value == null || value.isBlank()) {
            throw requiredActionField("Action requires '" + field + "'", nodeId, field);
        }
    }

    private static WorkflowDefinitionValidationException requiredActionField(
            String message, String nodeId, String field) {
        return invalid(
            WorkflowDiagnosticCode.ACTION_FIELD_REQUIRED,
            message, nodeId, "config." + field, Map.of("field", field));
    }

    private static WorkflowDefinitionValidationException invalid(
            WorkflowDiagnosticCode code,
            String message,
            String nodeId,
            String fieldPath,
            Map<String, String> params) {
        return new WorkflowDefinitionValidationException(
            message,
            new WorkflowDiagnosticDto(code, nodeId, null, fieldPath, params));
    }

    private record Configured<T>(String nodeId, T value) { }
}
