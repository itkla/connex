package ooo.klae.connex.backend.services;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.IsoFields;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
import ooo.klae.connex.backend.beans.Workflow;
import ooo.klae.connex.backend.dto.RuleAction;
import ooo.klae.connex.backend.dto.RuleTrigger;
import ooo.klae.connex.backend.dto.SegmentDefinition;
import ooo.klae.connex.backend.mappers.DealMapper;
import ooo.klae.connex.backend.mappers.PersonMapper;
import ooo.klae.connex.backend.mappers.RuleMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WorkflowMapper;

/**
 * Legacy compatibility executor for workflows whose persisted {@code runtime_owner} is
 * {@code legacy}, plus genuinely unpaired rules awaiting startup backfill. Every effect first claims
 * through {@link WorkflowRuntimeClaimService}, which locks the paired workflow, checks both ledgers,
 * and records the winner in {@code rule_execution}. It evaluates the optional WHEN condition and runs
 * actions as the rule's actor off the request thread. Remove this executor after no workflow is
 * legacy-owned, no unpaired rule remains, rollback support is retired, and the compatibility window
 * has closed.
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
    private final WorkflowRuntimeClaimService workflowRuntimeClaimService;
    private final WorkflowMapper workflowMapper;
    private final WorkflowTriggeredSendGate triggeredSendGate;
    private final AuditService auditService;

    private static final Logger log = LoggerFactory.getLogger(RuleEngineService.class);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter HOUR = DateTimeFormatter.ofPattern("yyyyMMddHH");

    /** Runs every enabled entity-change rule whose trigger matches this committed change. */
    public void onEntityChange(int workspaceId, String recordType, int entityId, String event) {
        onEntityChange(new WorkflowTriggerDispatch.EntityChange(
            workspaceId,
            recordType,
            entityId,
            event,
            java.util.UUID.randomUUID().toString(),
            java.time.Instant.now()));
    }

    /** Runs legacy-owned entity-change rules for one replay-stable dispatch. */
    public void onEntityChange(WorkflowTriggerDispatch.EntityChange dispatch) {
        int workspaceId = dispatch.workspaceId();
        String recordType = dispatch.recordType();
        int entityId = dispatch.recordId();
        for (Rule rule : ruleMapper.getEnabledByTrigger(workspaceId, "entity_change")) {
            try {
                processEntityRule(rule, dispatch);
            } catch (Exception e) {
                log.warn(
                    "Rule dispatch failed ruleId={} recordType={} recordId={} exceptionClass={}",
                    rule.getId(), recordType, entityId, e.getClass().getSimpleName());
            }
        }
    }

    /** Runs the legacy side of one generation-pinned durable entity target. */
    public void onEntityChangeForWorkflow(
            int workflowId, WorkflowTriggerDispatch.EntityChange dispatch) {
        onEntityChangeForWorkflow(workflowId, dispatch, null);
    }

    /** Runs the legacy side with authorization roots already locked by durable outbox delivery. */
    public void onEntityChangeForWorkflow(
            int workflowId,
            WorkflowTriggerDispatch.EntityChange dispatch,
            WorkflowExecutionPrincipal lockedPrincipal) {
        Rule rule = linkedRule(dispatch.workspaceId(), workflowId);
        if (rule == null) {
            return;
        }
        processEntityRule(rule, dispatch, lockedPrincipal);
    }

    /** Evaluates every enabled schedule rule of this cadence over the workspace's records. */
    public void runSchedule(int workspaceId, String cadence) {
        runSchedule(new WorkflowTriggerDispatch.ScheduleTick(
            workspaceId, cadence, scheduleBucket(cadence)));
    }

    /** Runs legacy-owned schedule rules for one deterministic cadence bucket. */
    public void runSchedule(WorkflowTriggerDispatch.ScheduleTick dispatch) {
        int workspaceId = dispatch.workspaceId();
        String cadence = dispatch.cadence();
        for (Rule rule : ruleMapper.getEnabledByTrigger(workspaceId, "schedule")) {
            try {
                RuleTrigger trigger = read(rule.getTriggerConfig(), RuleTrigger.class);
                if (trigger.getCadence() == null || !cadence.equalsIgnoreCase(trigger.getCadence().trim())) {
                    continue;
                }
                List<Integer> matches = scheduleMatches(rule, workspaceId);
                if (hasTriggeredSend(rule) && matches.size() > triggeredSendGate.recipientLimit()) {
                    recordRecipientLimit(rule, dispatch);
                    matches = matches.subList(0, triggeredSendGate.recipientLimit());
                }
                for (int entityId : matches) {
                    fireSchedule(rule, dispatch, entityId);
                }
            } catch (Exception e) {
                log.warn(
                    "Schedule rule failed ruleId={} exceptionClass={}",
                    rule.getId(), e.getClass().getSimpleName());
            }
        }
    }

    /** Runs the legacy side of one already-matched durable schedule enrollment. */
    public void runScheduleRecordForWorkflow(
            int workflowId,
            WorkflowTriggerDispatch.ScheduleTick dispatch,
            int recordId) {
        runScheduleRecordForWorkflow(workflowId, dispatch, recordId, null);
    }

    /** Runs one legacy schedule record with authorization roots held by durable outbox delivery. */
    public void runScheduleRecordForWorkflow(
            int workflowId,
            WorkflowTriggerDispatch.ScheduleTick dispatch,
            int recordId,
            WorkflowExecutionPrincipal lockedPrincipal) {
        Rule rule = linkedRule(dispatch.workspaceId(), workflowId);
        if (rule == null) {
            return;
        }
        RuleTrigger trigger = read(rule.getTriggerConfig(), RuleTrigger.class);
        if (!"schedule".equalsIgnoreCase(trigger.getType())
                || trigger.getCadence() == null
                || !dispatch.cadence().equalsIgnoreCase(trigger.getCadence().trim())) {
            return;
        }
        fireSchedule(rule, dispatch, recordId, lockedPrincipal);
    }

    private void processEntityRule(
            Rule rule, WorkflowTriggerDispatch.EntityChange dispatch) {
        processEntityRule(rule, dispatch, null);
    }

    private void processEntityRule(
            Rule rule,
            WorkflowTriggerDispatch.EntityChange dispatch,
            WorkflowExecutionPrincipal lockedPrincipal) {
        if (!dispatch.recordType().equals(rule.getRecordType())) {
            return;
        }
        RuleTrigger trigger = read(rule.getTriggerConfig(), RuleTrigger.class);
        if (trigger.getEvents() == null || !trigger.getEvents().contains(dispatch.event())) {
            return;
        }
        if (!stageMatches(
                trigger,
                dispatch.workspaceId(),
                dispatch.recordType(),
                dispatch.recordId())) {
            return;
        }
        WorkflowRuntimeClaimService.LegacyClaim claim =
            workflowRuntimeClaimService.claimLegacyEntity(rule, trigger, dispatch);
        if (!claim.started() || claim.execution() == null) {
            return;
        }
        if ("person".equals(dispatch.recordType())
                && personMapper.getProcessablePersonIds(
                    dispatch.workspaceId(), List.of(dispatch.recordId())).isEmpty()) {
            finishExecution(
                claim.execution(),
                "failed",
                "record_unavailable",
                "The trigger record is unavailable.");
            return;
        }
        if (!conditionMatches(rule, dispatch.workspaceId(), dispatch.recordId())) {
            finishExecution(claim.execution(), "skipped", null, null);
            return;
        }
        fireClaimed(
            rule,
            dispatch.workspaceId(),
            dispatch.recordType(),
            dispatch.recordId(),
            claim,
            lockedPrincipal);
    }

    private Rule linkedRule(int workspaceId, int workflowId) {
        Workflow workflow = workflowMapper.getById(workspaceId, workflowId);
        if (workflow == null || workflow.getLegacyRuleId() == null) {
            return null;
        }
        return ruleMapper.getById(workspaceId, workflow.getLegacyRuleId());
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
        return segmentService.matchesEntity(workspaceId, conditionActorId(rule), rule.getRecordType(), definition, entityId);
    }

    private List<Integer> scheduleMatches(Rule rule, int workspaceId) {
        if (rule.getConditionJson() == null) {
            return List.of();
        }
        SegmentDefinition definition = read(rule.getConditionJson(), SegmentDefinition.class);
        if (hasTriggeredSend(rule)) {
            return segmentService.evaluate(
                workspaceId,
                conditionActorId(rule),
                rule.getRecordType(),
                definition,
                triggeredSendGate.recipientLimit() + 1);
        }
        return segmentService.evaluate(
            workspaceId, conditionActorId(rule), rule.getRecordType(), definition);
    }

    private boolean hasTriggeredSend(Rule rule) {
        for (RuleAction action : read(rule.getActionsJson(), RuleAction[].class)) {
            if (action.getType() != null
                    && "send_message".equalsIgnoreCase(action.getType().trim())) {
                return true;
            }
        }
        return false;
    }

    private void recordRecipientLimit(
            Rule rule, WorkflowTriggerDispatch.ScheduleTick dispatch) {
        String code = "triggered_send_recipient_limit";
        RuleExecution diagnostic = new RuleExecution();
        diagnostic.setWorkspaceId(dispatch.workspaceId());
        diagnostic.setRuleId(rule.getId());
        diagnostic.setStatus("partial");
        diagnostic.setDedupeKey("schedule:" + dispatch.bucketKey() + ":" + code);
        diagnostic.setDetail(writeDetail(
            code,
            "The scheduled send-message recipient limit was reached."));
        try {
            ruleMapper.insertExecution(diagnostic);
        } catch (DuplicateKeyException ignored) {
            log.debug("Rule recipient-limit diagnostic already exists ruleId={}", rule.getId());
        }
        auditService.recordStrict(
            "workflow.triggered_send.recipient_limit",
            "rule",
            rule.getId(),
            "Rule " + rule.getId(),
            "Scheduled send-message recipients were capped",
            Map.of("limit", triggeredSendGate.recipientLimit(), "code", code));
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

    private void fireSchedule(
            Rule rule,
            WorkflowTriggerDispatch.ScheduleTick dispatch,
            int entityId) {
        fireSchedule(rule, dispatch, entityId, null);
    }

    private void fireSchedule(
            Rule rule,
            WorkflowTriggerDispatch.ScheduleTick dispatch,
            int entityId,
            WorkflowExecutionPrincipal lockedPrincipal) {
        WorkflowRuntimeClaimService.LegacyClaim claim =
            workflowRuntimeClaimService.claimLegacySchedule(rule, dispatch, entityId);
        fireClaimed(
            rule,
            dispatch.workspaceId(),
            rule.getRecordType(),
            entityId,
            claim,
            lockedPrincipal);
    }

    private void fireClaimed(
            Rule rule,
            int workspaceId,
            String recordType,
            int entityId,
            WorkflowRuntimeClaimService.LegacyClaim claim,
            WorkflowExecutionPrincipal lockedPrincipal) {
        if (!claim.started() || claim.execution() == null) {
            return;
        }
        RuleExecution execution = claim.execution();
        Principal principal = lockedPrincipal == null
            ? resolvePrincipal(rule, workspaceId)
            : lockedPrincipal(rule, lockedPrincipal);
        if (principal == null) {
            finishExecution(
                execution,
                "failed",
                "actor_unavailable",
                "The configured automation actor is unavailable.");
            return;
        }
        List<RuleAction> actions = List.of(read(rule.getActionsJson(), RuleAction[].class));
        RuleFireContext ctx = new RuleFireContext(
            workspaceId,
            rule.getId(),
            recordType,
            entityId,
            principal.targetUserId(),
            claim.dedupeKey(),
            lockedPrincipal == null ? null : lockedPrincipal.actorUserId(),
            lockedPrincipal == null ? Set.of() : lockedPrincipal.lockedPermissions());
        List<String> failures = new ArrayList<>();
        try {
            automationExecutor.runAs(workspaceId, principal.user(), principal.role(), () -> {
                for (RuleAction action : actions) {
                    try {
                        actionExecutor.execute(action, ctx);
                    } catch (Exception actionError) {
                        log.warn(
                            "Rule action failed ruleId={} actionType={} exceptionClass={}",
                            rule.getId(), action.getType(),
                            actionError.getClass().getSimpleName());
                        failures.add(action.getType() + ": action failed");
                    }
                }
                return null;
            });
            finishExecution(execution, failures.isEmpty() ? "matched" : "partial", null,
                failures.isEmpty() ? null : String.join("; ", failures));
        } catch (Exception e) {
            log.warn(
                "Rule execution failed ruleId={} recordType={} recordId={} exceptionClass={}",
                rule.getId(), recordType, entityId, e.getClass().getSimpleName());
            finishExecution(
                execution, "failed", "execution_failed", "Rule execution failed.");
        }
    }

    private void finishExecution(
            RuleExecution execution,
            String status,
            String code,
            String message) {
        try {
            ruleMapper.updateExecution(
                execution.getWorkspaceId(),
                execution.getId(),
                status,
                writeDetail(code, message));
        } catch (Exception e) {
            log.warn(
                "Rule execution finalization failed executionId={} exceptionClass={}",
                execution.getId(), e.getClass().getSimpleName());
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

    private static Principal lockedPrincipal(
            Rule rule, WorkflowExecutionPrincipal principal) {
        boolean matches = "system".equals(rule.getExecutionMode())
            ? "system".equals(principal.role())
                && rule.getCreatedById() != null
                && rule.getCreatedById() == principal.attributionUserId()
            : !"system".equals(principal.role())
                && rule.getRunAsUserId() != null
                && rule.getRunAsUserId() == principal.actorUserId();
        return matches
            ? new Principal(
                principal.principal(), principal.role(), principal.attributionUserId())
            : null;
    }

    private <T> T read(String json, Class<T> type) {
        return objectMapper.readValue(json, type);
    }

    private String writeDetail(String code, String message) {
        if (code == null && message == null) {
            return null;
        }
        try {
            String trimmed = message == null
                ? null
                : message.substring(0, Math.min(message.length(), 480));
            if (code == null) {
                return objectMapper.writeValueAsString(Map.of("message", trimmed));
            }
            if (trimmed == null) {
                return objectMapper.writeValueAsString(Map.of("code", code));
            }
            return objectMapper.writeValueAsString(
                Map.of("code", code, "message", trimmed));
        } catch (Exception e) {
            return null;
        }
    }

    private record Principal(User user, String role, int targetUserId) {}
}
