package ooo.klae.connex.backend.services;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.ObjectMapper;

import ooo.klae.connex.backend.beans.Rule;
import ooo.klae.connex.backend.beans.RuleExecution;
import ooo.klae.connex.backend.dto.RuleAction;
import ooo.klae.connex.backend.dto.RuleDto;
import ooo.klae.connex.backend.dto.RuleRequest;
import ooo.klae.connex.backend.dto.RuleTrigger;
import ooo.klae.connex.backend.dto.SegmentDefinition;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.RuleMapper;
import ooo.klae.connex.backend.services.WorkspaceService.Role;
import ooo.klae.connex.backend.tenant.Permission;
import ooo.klae.connex.backend.tenant.RequirePermission;

/**
 * Business logic for automation rules: CRUD scoped to the active workspace and gated by
 * {@link Permission#RULE_MANAGE}, with {@code system}-mode rules additionally requiring the admin
 * tier. The typed trigger / optional WHEN condition / THEN actions are validated here and stored as
 * JSON. v1: a USER-mode rule runs as its creator; WHEN conditions apply to {@code company} rules.
 */
@Service
@RequiredArgsConstructor
public class RuleService {

    private final RuleMapper ruleMapper;
    private final WorkspaceService workspaceService;
    private final AuthService authService;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    private static final int MAX_JSON_BYTES = 16384;
    private static final Set<String> RECORD_TYPES = Set.of("company", "person", "deal", "task");
    private static final Set<String> TRIGGER_TYPES = Set.of("entity_change", "schedule");
    private static final Set<String> EXECUTION_MODES = Set.of("user", "system");
    private static final Set<String> ACTION_TYPES = Set.of("create_task", "log_activity", "add_tag", "notify");
    private static final Set<String> CADENCES = Set.of("hourly", "daily", "weekly");
    private static final Set<String> ENTITY_CHANGE_RECORD_TYPES = Set.of("deal", "company", "person", "task");
    private static final Set<String> SEGMENT_RECORD_TYPES = Set.of("company", "person", "deal");
    private static final Set<String> DEAL_EVENTS = Set.of(
        "deal.created", "deal.stage_changed", "deal.updated", "deal.won", "deal.lost",
        "deal.owner_changed", "deal.value_changed");
    private static final Set<String> COMPANY_EVENTS = Set.of("company.created", "company.updated");
    private static final Set<String> PERSON_EVENTS = Set.of("person.created", "person.updated", "person.job_changed");
    private static final Set<String> TASK_EVENTS = Set.of("task.created", "task.completed");
    private static final Map<String, Set<String>> ACTION_RECORD_TYPES = Map.of(
        "create_task", Set.of("person", "deal"),
        "log_activity", Set.of("person", "deal"),
        "add_tag", Set.of("company", "person", "deal"),
        "notify", Set.of("company", "person", "deal", "task"));

    @RequirePermission(Permission.RULE_MANAGE)
    public List<RuleDto> list() {
        return ruleMapper.getByWorkspace(workspaceService.getCurrentWorkspaceId())
            .stream().map(this::toDto).toList();
    }

    @RequirePermission(Permission.RULE_MANAGE)
    public RuleDto getById(int id) {
        return toDto(requireRule(id));
    }

    @RequirePermission(Permission.RULE_MANAGE)
    public List<RuleExecution> executions(int id) {
        requireRule(id);
        return ruleMapper.getExecutionsByRule(workspaceService.getCurrentWorkspaceId(), id, 50);
    }

    @Transactional
    @RequirePermission(Permission.RULE_MANAGE)
    public RuleDto create(RuleRequest request) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        int userId = authService.getCurrentUser().getId();
        validate(request);

        Rule rule = new Rule();
        rule.setWorkspaceId(workspaceId);
        rule.setCreatedById(userId);
        applyRequest(rule, request);
        ruleMapper.insert(rule);
        auditService.record("rule.create", "rule", rule.getId(), rule.getName(),
            "Created rule " + rule.getName(), auditService.singleChange("enabled", null, rule.isEnabled()));
        return toDto(rule);
    }

    @Transactional
    @RequirePermission(Permission.RULE_MANAGE)
    public RuleDto update(int id, RuleRequest request) {
        Rule rule = requireRule(id);
        validate(request);
        applyRequest(rule, request);
        ruleMapper.update(rule);
        auditService.record("rule.update", "rule", id, rule.getName(),
            "Updated rule " + rule.getName(), null);
        return toDto(rule);
    }

    @Transactional
    @RequirePermission(Permission.RULE_MANAGE)
    public void delete(int id) {
        Rule rule = requireRule(id);
        ruleMapper.delete(workspaceService.getCurrentWorkspaceId(), id);
        auditService.record("rule.delete", "rule", id, rule.getName(),
            "Deleted rule " + rule.getName(), null);
    }

    private Rule requireRule(int id) {
        Rule rule = ruleMapper.getById(workspaceService.getCurrentWorkspaceId(), id);
        if (rule == null) {
            throw new ResourceNotFoundException("Rule not found with id: " + id);
        }
        return rule;
    }

    private void validate(RuleRequest request) {
        String recordType = normalize(request.getRecordType());
        if (!RECORD_TYPES.contains(recordType)) {
            throw new BadRequestException("Invalid record type: " + request.getRecordType());
        }
        String mode = normalize(request.getExecutionMode());
        if (!EXECUTION_MODES.contains(mode)) {
            throw new BadRequestException("Invalid execution mode: " + request.getExecutionMode());
        }
        if ("system".equals(mode)) {
            workspaceService.requireRole(Role.ADMIN);
        }
        if (request.getCondition() != null && !SEGMENT_RECORD_TYPES.contains(recordType)) {
            throw new BadRequestException("WHEN conditions are not supported for record type: " + request.getRecordType());
        }
        if (request.getCondition() != null && !hasWhen(request.getCondition())) {
            throw new BadRequestException("A WHEN condition must contain at least one condition");
        }
        validateTrigger(request.getTrigger(), recordType, request.getCondition());
        validateActions(request.getActions(), recordType);
        requireActionPermissions(request.getActions(), recordType);
    }

    private void requireActionPermissions(List<RuleAction> actions, String recordType) {
        for (RuleAction action : actions) {
            Permission required = actionPermission(normalize(action.getType()), recordType);
            if (required != null) {
                workspaceService.requirePermission(required);
            }
        }
    }

    private Permission actionPermission(String type, String recordType) {
        return switch (type) {
            case "create_task" -> Permission.TASK_CREATE;
            case "log_activity" -> Permission.ACTIVITY_CREATE;
            case "add_tag" -> switch (recordType) {
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
                case "add_tag" -> {
                    if (action.getTagId() == null) {
                        throw new BadRequestException("An add_tag action requires a tagId");
                    }
                }
                default -> throw new BadRequestException("Invalid action type: " + action.getType());
            }
        }
    }

    private void applyRequest(Rule rule, RuleRequest request) {
        String mode = normalize(request.getExecutionMode());
        rule.setName(request.getName().trim());
        rule.setDescription(request.getDescription());
        rule.setEnabled(request.getEnabled() == null || request.getEnabled());
        rule.setRecordType(normalize(request.getRecordType()));
        rule.setTriggerType(normalize(request.getTrigger().getType()));
        rule.setTriggerConfig(serialize(request.getTrigger()));
        rule.setConditionJson(request.getCondition() == null ? null : serialize(request.getCondition()));
        rule.setActionsJson(serialize(request.getActions()));
        rule.setExecutionMode(mode);
        rule.setRunAsUserId("system".equals(mode) ? null : rule.getCreatedById());
    }

    private RuleDto toDto(Rule rule) {
        RuleDto dto = new RuleDto();
        dto.setId(rule.getId());
        dto.setName(rule.getName());
        dto.setDescription(rule.getDescription());
        dto.setEnabled(rule.isEnabled());
        dto.setRecordType(rule.getRecordType());
        dto.setTrigger(parse(rule.getTriggerConfig(), RuleTrigger.class));
        dto.setCondition(rule.getConditionJson() == null ? null : parse(rule.getConditionJson(), SegmentDefinition.class));
        dto.setActions(List.of(parse(rule.getActionsJson(), RuleAction[].class)));
        dto.setExecutionMode(rule.getExecutionMode());
        dto.setRunAsUserId(rule.getRunAsUserId());
        dto.setCreatedById(rule.getCreatedById());
        dto.setCreatedAt(rule.getCreatedAt());
        dto.setUpdatedAt(rule.getUpdatedAt());
        return dto;
    }

    private String serialize(Object value) {
        String json;
        try {
            json = objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new BadRequestException("Invalid rule configuration");
        }
        if (json.getBytes(StandardCharsets.UTF_8).length > MAX_JSON_BYTES) {
            throw new BadRequestException("Rule configuration is too large");
        }
        return json;
    }

    private <T> T parse(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception e) {
            throw new BadRequestException("Corrupt rule configuration");
        }
    }

    private static boolean hasWhen(SegmentDefinition condition) {
        if (condition == null) {
            return false;
        }
        boolean hasConditions = condition.getConditions() != null && !condition.getConditions().isEmpty();
        boolean hasGroups = condition.getGroups() != null && !condition.getGroups().isEmpty();
        return hasConditions || hasGroups;
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new BadRequestException("Action requires '" + field + "'");
        }
    }

    private static String normalize(String value) {
        return value == null ? null : value.trim().toLowerCase();
    }
}
