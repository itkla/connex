package ooo.klae.connex.backend.services;

import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;

import ooo.klae.connex.backend.dto.RuleAction;
import ooo.klae.connex.backend.dto.RulePreviewRequest;
import ooo.klae.connex.backend.dto.RuleRequest;
import ooo.klae.connex.backend.dto.RuleTrigger;
import ooo.klae.connex.backend.dto.SegmentDefinition;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.services.WorkspaceService.Role;
import ooo.klae.connex.backend.tenant.Permission;

/** Validates and normalizes the shared semantic definition used by automation rules. */
@Component
@RequiredArgsConstructor
public class RuleDefinitionValidator {

    private static final Set<String> RECORD_TYPES = Set.of("company", "person", "deal", "task");
    private static final Set<String> TRIGGER_TYPES = Set.of("entity_change", "schedule");
    private static final Set<String> EXECUTION_MODES = Set.of("user", "system");
    private static final Set<String> ACTION_TYPES = Set.of(
        "create_task", "log_activity", "add_tag", "remove_tag", "create_note",
        "assign_owner", "change_stage", "notify");
    private static final Set<String> CADENCES = Set.of("hourly", "daily", "weekly");
    private static final Set<String> ENTITY_CHANGE_RECORD_TYPES = Set.of("deal", "company", "person", "task");
    private static final Set<String> SEGMENT_RECORD_TYPES = Set.of("company", "person", "deal");
    private static final Set<String> DEAL_EVENTS = Set.of(
        "deal.created", "deal.stage_changed", "deal.updated", "deal.won", "deal.lost",
        "deal.owner_changed", "deal.value_changed");
    private static final Set<String> COMPANY_EVENTS = Set.of(
        "company.created", "company.updated", "company.owner_changed");
    private static final Set<String> PERSON_EVENTS = Set.of(
        "person.created", "person.updated", "person.job_changed", "person.owner_changed");
    private static final Set<String> TASK_EVENTS = Set.of("task.created", "task.completed");
    private static final Map<String, Set<String>> ACTION_RECORD_TYPES = Map.of(
        "create_task", Set.of("person", "deal"),
        "log_activity", Set.of("person", "deal"),
        "add_tag", Set.of("company", "person", "deal"),
        "remove_tag", Set.of("company", "person", "deal"),
        "create_note", Set.of("person", "deal"),
        "assign_owner", Set.of("deal"),
        "change_stage", Set.of("deal"),
        "notify", Set.of("company", "person", "deal", "task"));

    private final SegmentService segmentService;
    private final WorkspaceService workspaceService;
    private final Validator beanValidator;

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

    Set<Permission> validateDefinitionForMutation(
            String recordTypeValue,
            RuleTrigger trigger,
            SegmentDefinition condition,
            List<RuleAction> actions,
            String executionMode) {
        if (trigger == null) {
            throw new BadRequestException("Rule trigger is required");
        }
        if (actions == null || actions.isEmpty() || actions.size() > 16) {
            throw new BadRequestException("A rule requires between 1 and 16 actions");
        }
        if (actions.stream().anyMatch(action -> action == null)) {
            throw new BadRequestException("Rule action config is required");
        }
        requireStructurallyValid(trigger);
        if (condition != null) {
            requireStructurallyValid(condition);
        }
        actions.forEach(this::requireStructurallyValid);

        String recordType = normalize(recordTypeValue);
        if (!RECORD_TYPES.contains(recordType)) {
            throw new BadRequestException("Invalid record type: " + recordTypeValue);
        }
        String mode = normalize(executionMode);
        if (!EXECUTION_MODES.contains(mode)) {
            throw new BadRequestException("Invalid execution mode: " + executionMode);
        }
        if (condition != null && !SEGMENT_RECORD_TYPES.contains(recordType)) {
            throw new BadRequestException("WHEN conditions are not supported for record type: " + recordTypeValue);
        }
        if (condition != null && !hasWhen(condition)) {
            throw new BadRequestException("A WHEN condition must contain at least one condition");
        }
        if (condition != null) {
            segmentService.validate(recordType, condition);
        }
        validateTrigger(trigger, recordType, condition);
        validateActions(actions, recordType);
        return actionPermissions(actions, recordType);
    }

    private <T> void requireStructurallyValid(T value) {
        if (!beanValidator.validate(value).isEmpty()) {
            throw new BadRequestException("Rule definition is invalid");
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

    private Set<Permission> actionPermissions(List<RuleAction> actions, String recordType) {
        EnumSet<Permission> required = EnumSet.noneOf(Permission.class);
        for (RuleAction action : actions) {
            Permission permission = actionPermission(normalize(action.getType()), recordType);
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

    private Permission actionPermission(String type, String recordType) {
        return switch (type) {
            case "create_task" -> Permission.TASK_CREATE;
            case "log_activity" -> Permission.ACTIVITY_CREATE;
            case "create_note" -> Permission.NOTE_CREATE;
            case "assign_owner", "change_stage" -> Permission.DEAL_UPDATE;
            case "add_tag", "remove_tag" -> switch (recordType) {
                case "company" -> Permission.COMPANY_UPDATE;
                case "person" -> Permission.PERSON_UPDATE;
                case "deal" -> Permission.DEAL_UPDATE;
                default -> null;
            };
            default -> null;
        };
    }

    private void validateTrigger(RuleTrigger trigger, String recordType, SegmentDefinition condition) {
        String type = normalize(trigger.getType());
        if (!TRIGGER_TYPES.contains(type)) {
            throw new BadRequestException("Invalid trigger type: " + trigger.getType());
        }
        if ("entity_change".equals(type)) {
            if (!ENTITY_CHANGE_RECORD_TYPES.contains(recordType)) {
                throw new BadRequestException("Entity-change rules are only supported for deal and company records");
            }
            if (trigger.getEvents() == null || trigger.getEvents().isEmpty()) {
                throw new BadRequestException("An entity-change trigger requires at least one event");
            }
            Set<String> supported = switch (recordType) {
                case "deal" -> DEAL_EVENTS;
                case "company" -> COMPANY_EVENTS;
                case "person" -> PERSON_EVENTS;
                case "task" -> TASK_EVENTS;
                default -> Set.of();
            };
            for (String event : trigger.getEvents()) {
                if (!supported.contains(event)) {
                    throw new BadRequestException("Unsupported event for " + recordType + ": " + event);
                }
            }
        } else {
            if (!SEGMENT_RECORD_TYPES.contains(recordType)) {
                throw new BadRequestException("Schedule rules are not supported for record type: " + recordType);
            }
            if (trigger.getCadence() == null || !CADENCES.contains(normalize(trigger.getCadence()))) {
                throw new BadRequestException("A schedule rule requires a valid cadence");
            }
            if (!hasWhen(condition)) {
                throw new BadRequestException("A schedule rule requires a WHEN condition");
            }
        }
    }

    private void validateActions(List<RuleAction> actions, String recordType) {
        for (RuleAction action : actions) {
            String type = normalize(action.getType());
            if (!ACTION_TYPES.contains(type)) {
                throw new BadRequestException("Invalid action type: " + action.getType());
            }
            Set<String> supportedRecordTypes = ACTION_RECORD_TYPES.get(type);
            if (supportedRecordTypes == null || !supportedRecordTypes.contains(recordType)) {
                throw new BadRequestException("'" + type + "' actions are not supported for " + recordType + " rules");
            }
            switch (type) {
                case "create_task", "notify" -> requireText(action.getTitle(), "title");
                case "log_activity" -> requireText(action.getActivityType(), "activityType");
                case "create_note" -> requireText(action.getBody(), "body");
                case "add_tag", "remove_tag" -> {
                    if (action.getTagId() == null) {
                        throw new BadRequestException("A " + type + " action requires a tagId");
                    }
                }
                case "assign_owner" -> {
                    if (action.getTargetUserId() == null) {
                        throw new BadRequestException("An assign_owner action requires a targetUserId");
                    }
                }
                case "change_stage" -> {
                    if (action.getTargetStageId() == null) {
                        throw new BadRequestException("A change_stage action requires a targetStageId");
                    }
                }
                default -> throw new BadRequestException("Invalid action type: " + action.getType());
            }
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new BadRequestException("Action requires '" + field + "'");
        }
    }
}
