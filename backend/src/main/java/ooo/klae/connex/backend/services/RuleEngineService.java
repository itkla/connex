package ooo.klae.connex.backend.services;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.IsoFields;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.ObjectMapper;

import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.Rule;
import ooo.klae.connex.backend.beans.RuleExecution;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.dto.RuleAction;
import ooo.klae.connex.backend.dto.RuleTrigger;
import ooo.klae.connex.backend.dto.SegmentDefinition;
import ooo.klae.connex.backend.mappers.DealMapper;
import ooo.klae.connex.backend.mappers.PersonMapper;
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
    private final DealMapper dealMapper;
    private final PersonMapper personMapper;
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

    /** Runs every enabled entity-change rule whose trigger matches this committed change. */
    public void onEntityChange(int workspaceId, String recordType, int entityId, String event) {
        if ("person".equals(recordType)
                && personMapper.getProcessablePersonIds(workspaceId, List.of(entityId)).isEmpty()) {
            return;
        }
        for (Rule rule : ruleMapper.getEnabledByTrigger(workspaceId, "entity_change")) {
            try {
                if (!recordType.equals(rule.getRecordType())) {
                    continue;
                }
                RuleTrigger trigger = read(rule.getTriggerConfig(), RuleTrigger.class);
                if (trigger.getEvents() == null || !trigger.getEvents().contains(event)) {
                    continue;
                }
                if (!stageMatches(trigger, workspaceId, recordType, entityId)) {
                    continue;
                }
                if (!conditionMatches(rule, workspaceId, entityId)) {
                    continue;
                }
                fire(rule, workspaceId, recordType, entityId, dedupeSuffix(trigger, event));
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

    private boolean stageMatches(RuleTrigger trigger, int workspaceId, String recordType, int entityId) {
        if (trigger.getTargetStageId() == null || !"deal".equals(recordType)) {
            return true;
        }
        Deal deal = dealMapper.getDealById(workspaceId, entityId);
        return deal != null && trigger.getTargetStageId().equals(deal.getStageId());
    }

    private boolean conditionMatches(Rule rule, int workspaceId, int entityId) {
        if (rule.getConditionJson() == null) {
            return true;
        }
        SegmentDefinition definition = read(rule.getConditionJson(), SegmentDefinition.class);
        return segmentService.evaluate(workspaceId, conditionActorId(rule), rule.getRecordType(), definition).contains(entityId);
    }

    private List<Integer> scheduleMatches(Rule rule, int workspaceId) {
        if (rule.getConditionJson() == null) {
            return List.of();
        }
        SegmentDefinition definition = read(rule.getConditionJson(), SegmentDefinition.class);
        return segmentService.evaluate(workspaceId, conditionActorId(rule), rule.getRecordType(), definition);
    }

    private int conditionActorId(Rule rule) {
        if (rule.getRunAsUserId() != null) {
            return rule.getRunAsUserId();
        }
        return rule.getCreatedById() != null ? rule.getCreatedById() : systemActor.user().getId();
    }

    private String scheduleBucket(String cadence) {
        LocalDateTime now = LocalDateTime.now();
        return switch (cadence == null ? "daily" : cadence.trim().toLowerCase()) {
            case "hourly" -> now.format(HOUR);
            case "weekly" -> now.get(IsoFields.WEEK_BASED_YEAR) + "W" + now.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
            default -> now.format(DAY);
        };
    }

    private String dedupeSuffix(RuleTrigger trigger, String event) {
        Integer throttle = trigger.getThrottleMinutes();
        if (throttle != null && throttle > 0) {
            long window = (System.currentTimeMillis() / 60000L) / throttle;
            return event + ":t" + throttle + ":" + window;
        }
        return event + ":" + System.nanoTime();
    }

    private void fire(Rule rule, int workspaceId, String recordType, int entityId, String dedupeSuffix) {
        RuleExecution execution = new RuleExecution();
        execution.setWorkspaceId(workspaceId);
        execution.setRuleId(rule.getId());
        execution.setTriggerEntityType(recordType);
        execution.setTriggerEntityId(entityId);
        execution.setDedupeKey(entityId + ":" + dedupeSuffix);
        execution.setStatus("running");
        try {
            ruleMapper.insertExecution(execution);
        } catch (DuplicateKeyException alreadyClaimed) {
            return;
        } catch (Exception e) {
            log.warn("Failed to claim execution for rule {}: {}", rule.getId(), e.getMessage());
            return;
        }
        Principal principal = resolvePrincipal(rule, workspaceId);
        if (principal == null) {
            finishExecution(execution, "skipped", "actor is not an active member");
            return;
        }
        List<RuleAction> actions = List.of(read(rule.getActionsJson(), RuleAction[].class));
        RuleFireContext ctx = new RuleFireContext(workspaceId, rule.getId(), recordType, entityId, principal.targetUserId(), dedupeSuffix);
        List<String> failures = new ArrayList<>();
        try {
            automationExecutor.runAs(workspaceId, principal.user(), principal.role(), () -> {
                for (RuleAction action : actions) {
                    try {
                        actionExecutor.execute(action, ctx);
                    } catch (Exception actionError) {
                        log.warn("Action {} failed for rule {}: {}", action.getType(), rule.getId(), actionError.getMessage());
                        failures.add(action.getType() + ": " + actionError.getMessage());
                    }
                }
                return null;
            });
            finishExecution(execution, failures.isEmpty() ? "matched" : "partial",
                failures.isEmpty() ? null : String.join("; ", failures));
        } catch (Exception e) {
            log.warn("Rule {} failed for {} {}: {}", rule.getId(), recordType, entityId, e.getMessage());
            finishExecution(execution, "failed", e.getMessage());
        }
    }

    private void finishExecution(RuleExecution execution, String status, String detail) {
        try {
            ruleMapper.updateExecution(execution.getWorkspaceId(), execution.getId(), status, writeDetail(detail));
        } catch (Exception e) {
            log.warn("Failed to finalize execution {}: {}", execution.getId(), e.getMessage());
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
