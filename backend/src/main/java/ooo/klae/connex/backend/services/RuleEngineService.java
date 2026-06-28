package ooo.klae.connex.backend.services;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.ObjectMapper;

import ooo.klae.connex.backend.beans.Rule;
import ooo.klae.connex.backend.beans.RuleExecution;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.dto.RuleAction;
import ooo.klae.connex.backend.dto.RuleTrigger;
import ooo.klae.connex.backend.dto.SegmentDefinition;
import ooo.klae.connex.backend.mappers.RuleMapper;
import ooo.klae.connex.backend.mappers.UserMapper;

/**
 * The rule engine: given a committed entity change or a scheduled tick, finds the matching enabled
 * rules in a workspace, evaluates their optional WHEN condition (reusing the segment condition model
 * off-thread), and runs their actions as the rule's actor — the run-as member or the system actor —
 * recording each fire in {@code rule_execution} with an idempotency dedupe key. Runs off the request
 * thread (callers supply the workspace explicitly) and never throws to its caller.
 */
@Service
@RequiredArgsConstructor
public class RuleEngineService {

    private final RuleMapper ruleMapper;
    private final SegmentService segmentService;
    private final RuleActionExecutor actionExecutor;
    private final AutomationExecutor automationExecutor;
    private final UserMapper userMapper;
    private final WorkspaceService workspaceService;
    private final SystemActor systemActor;
    private final ObjectMapper objectMapper;

    private static final Logger log = LoggerFactory.getLogger(RuleEngineService.class);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter HOUR = DateTimeFormatter.ofPattern("yyyyMMddHH");
    private static final DateTimeFormatter WEEK = DateTimeFormatter.ofPattern("YYYY'W'ww");
    private static final int MAX_SCHEDULE_MATCHES = 500;

    /** Runs every enabled entity-change rule whose trigger matches this committed change. */
    public void onEntityChange(int workspaceId, String recordType, int entityId, String event) {
        for (Rule rule : ruleMapper.getEnabledByTrigger(workspaceId, "entity_change")) {
            try {
                if (!recordType.equals(rule.getRecordType())) {
                    continue;
                }
                RuleTrigger trigger = read(rule.getTriggerConfig(), RuleTrigger.class);
                if (trigger.getEvents() == null || !trigger.getEvents().contains(event)) {
                    continue;
                }
                if (!conditionMatches(rule, workspaceId, entityId)) {
                    continue;
                }
                fire(rule, workspaceId, recordType, entityId, event);
            } catch (Exception e) {
                log.warn("Rule {} dispatch failed on {} {}: {}", rule.getId(), recordType, entityId, e.getMessage());
            }
        }
    }

    /** Evaluates every enabled schedule rule of this cadence over the workspace's records. */
    public void runSchedule(int workspaceId, String cadence) {
        String bucket = scheduleBucket(cadence);
        for (Rule rule : ruleMapper.getEnabledByTrigger(workspaceId, "schedule")) {
            try {
                RuleTrigger trigger = read(rule.getTriggerConfig(), RuleTrigger.class);
                if (trigger.getCadence() == null || !cadence.equalsIgnoreCase(trigger.getCadence().trim())) {
                    continue;
                }
                for (int entityId : scheduleMatches(rule, workspaceId)) {
                    fire(rule, workspaceId, rule.getRecordType(), entityId, bucket);
                }
            } catch (Exception e) {
                log.warn("Schedule rule {} failed: {}", rule.getId(), e.getMessage());
            }
        }
    }

    private boolean conditionMatches(Rule rule, int workspaceId, int entityId) {
        if (rule.getConditionJson() == null || !"company".equals(rule.getRecordType())) {
            return true;
        }
        SegmentDefinition definition = read(rule.getConditionJson(), SegmentDefinition.class);
        return segmentService.evaluate(workspaceId, conditionActorId(rule), "company", definition).contains(entityId);
    }

    private List<Integer> scheduleMatches(Rule rule, int workspaceId) {
        if (!"company".equals(rule.getRecordType()) || rule.getConditionJson() == null) {
            return List.of();
        }
        SegmentDefinition definition = read(rule.getConditionJson(), SegmentDefinition.class);
        List<Integer> ids = segmentService.evaluate(workspaceId, conditionActorId(rule), "company", definition);
        if (ids.size() > MAX_SCHEDULE_MATCHES) {
            log.warn("Schedule rule {} matched {} records; capping at {}", rule.getId(), ids.size(), MAX_SCHEDULE_MATCHES);
            return ids.subList(0, MAX_SCHEDULE_MATCHES);
        }
        return ids;
    }

    private int conditionActorId(Rule rule) {
        return rule.getRunAsUserId() != null ? rule.getRunAsUserId() : systemActor.user().getId();
    }

    private String scheduleBucket(String cadence) {
        LocalDateTime now = LocalDateTime.now();
        return switch (cadence == null ? "daily" : cadence.trim().toLowerCase()) {
            case "hourly" -> now.format(HOUR);
            case "weekly" -> now.format(WEEK);
            default -> now.format(DAY);
        };
    }

    private void fire(Rule rule, int workspaceId, String recordType, int entityId, String dedupeSuffix) {
        String dedupeKey = entityId + ":" + dedupeSuffix;
        if (ruleMapper.executionExists(rule.getId(), dedupeKey)) {
            return;
        }
        Principal principal = resolvePrincipal(rule, workspaceId);
        if (principal == null) {
            logExecution(rule, workspaceId, recordType, entityId, "skipped", dedupeKey, "actor is not an active member");
            return;
        }
        List<RuleAction> actions = List.of(read(rule.getActionsJson(), RuleAction[].class));
        RuleFireContext ctx = new RuleFireContext(workspaceId, rule.getId(), recordType, entityId, principal.targetUserId(), dedupeSuffix);
        try {
            automationExecutor.runAs(workspaceId, principal.user(), principal.role(), () -> {
                actions.forEach(action -> actionExecutor.execute(action, ctx));
                return null;
            });
            logExecution(rule, workspaceId, recordType, entityId, "matched", dedupeKey, null);
        } catch (Exception e) {
            log.warn("Rule {} failed for {} {}: {}", rule.getId(), recordType, entityId, e.getMessage());
            logExecution(rule, workspaceId, recordType, entityId, "failed", dedupeKey, e.getMessage());
        }
    }

    private Principal resolvePrincipal(Rule rule, int workspaceId) {
        if ("system".equals(rule.getExecutionMode())) {
            Integer target = rule.getCreatedById();
            if (target == null || workspaceService.getRole(workspaceId, target) == null) {
                return null;
            }
            return new Principal(systemActor.user(), "system", target);
        }
        Integer runAs = rule.getRunAsUserId();
        if (runAs == null) {
            return null;
        }
        String role = workspaceService.getRole(workspaceId, runAs);
        if (role == null) {
            return null;
        }
        User user = userMapper.getUserById(runAs);
        return user == null ? null : new Principal(user, role, runAs);
    }

    private void logExecution(Rule rule, int workspaceId, String recordType, int entityId, String status, String dedupeKey, String detail) {
        RuleExecution execution = new RuleExecution();
        execution.setWorkspaceId(workspaceId);
        execution.setRuleId(rule.getId());
        execution.setTriggerEntityType(recordType);
        execution.setTriggerEntityId(entityId);
        execution.setStatus(status);
        execution.setDedupeKey(dedupeKey);
        execution.setDetail(writeDetail(detail));
        try {
            ruleMapper.insertExecution(execution);
        } catch (Exception e) {
            log.warn("Failed to record execution for rule {}: {}", rule.getId(), e.getMessage());
        }
    }

    private <T> T read(String json, Class<T> type) {
        return objectMapper.readValue(json, type);
    }

    private String writeDetail(String message) {
        if (message == null) {
            return null;
        }
        try {
            String trimmed = message.length() > 480 ? message.substring(0, 480) : message;
            return objectMapper.writeValueAsString(Map.of("message", trimmed));
        } catch (Exception e) {
            return null;
        }
    }

    private record Principal(User user, String role, int targetUserId) {}
}
