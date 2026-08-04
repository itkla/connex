package ooo.klae.connex.backend.services;

import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.Objects;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.Rule;
import ooo.klae.connex.backend.beans.RuleExecution;
import ooo.klae.connex.backend.beans.Workflow;
import ooo.klae.connex.backend.beans.WorkflowRun;
import ooo.klae.connex.backend.beans.WorkflowTriggerOutbox;
import ooo.klae.connex.backend.beans.WorkflowVersion;
import ooo.klae.connex.backend.dto.RuleTrigger;
import ooo.klae.connex.backend.dto.WorkflowDefinition;
import ooo.klae.connex.backend.dto.WorkflowNode;
import ooo.klae.connex.backend.mappers.DealMapper;
import ooo.klae.connex.backend.mappers.RuleMapper;
import ooo.klae.connex.backend.mappers.WorkflowMapper;
import ooo.klae.connex.backend.mappers.WorkflowRunMapper;
import ooo.klae.connex.backend.mappers.WorkflowTriggerOutboxMapper;
import ooo.klae.connex.backend.mappers.WorkflowVersionMapper;
import ooo.klae.connex.backend.services.WorkflowDefinitionValidator.CompiledWorkflow;
import ooo.klae.connex.backend.services.WorkflowDraftCanonicalizer.CanonicalDraft;

/** Serializes runtime ownership and dedupe claims on the exact workflow root. */
@Service
@RequiredArgsConstructor
public class WorkflowRuntimeClaimService {

    private final WorkflowMapper workflowMapper;
    private final WorkflowVersionMapper workflowVersionMapper;
    private final WorkflowRunMapper workflowRunMapper;
    private final WorkflowTriggerOutboxMapper workflowTriggerOutboxMapper;
    private final RuleMapper ruleMapper;
    private final DealMapper dealMapper;
    private final WorkflowDraftCanonicalizer canonicalizer;
    private final WorkflowDefinitionValidator definitionValidator;
    private final WorkflowDedupeKey dedupeKey;
    private final SystemActor systemActor;

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public CanonicalClaim claimEntity(
            int workflowId, WorkflowTriggerDispatch.EntityChange dispatch) {
        workflowTriggerOutboxMapper.ensureWorkspaceGate(dispatch.workspaceId());
        Workflow workflow = workflowMapper.getByIdForUpdate(dispatch.workspaceId(), workflowId);
        if (!canonicalOwnerCanClaim(workflow)) {
            return CanonicalClaim.rejectedClaim();
        }
        WorkflowVersion version = activeVersion(workflow);
        CompiledWorkflow compiled = compiled(workflow, version);
        WorkflowNode.Trigger triggerNode = entryTrigger(compiled);
        RuleTrigger trigger = triggerNode.config();
        if (!entityTriggerMatches(version, trigger, dispatch)) {
            return CanonicalClaim.rejectedClaim();
        }
        String key = dedupeKey.entityChange(
            dedupeIdentity(workflow),
            dispatch.recordType(),
            dispatch.recordId(),
            dispatch.event(),
            dispatch.triggerKey(),
            dispatch.occurredAt(),
            trigger.getThrottleMinutes());
        String legacyKey = dedupeKey.legacyEntityChange(
            dispatch.recordId(),
            dispatch.event(),
            dispatch.occurredAt(),
            trigger.getThrottleMinutes());
        return claimCanonical(
            workflow, version, compiled, "entity_change", dispatch.event(),
            dispatch.triggerKey(), dispatch.recordType(), dispatch.recordId(), key,
            legacyKey, null, "queued");
    }

    @Transactional(readOnly = true)
    public ScheduleEnrollment scheduleEnrollment(
            int workflowId, WorkflowTriggerDispatch.ScheduleTick dispatch) {
        Workflow workflow = workflowMapper.getById(dispatch.workspaceId(), workflowId);
        if (!canonicalOwnerCanClaim(workflow)) {
            return null;
        }
        WorkflowVersion version = activeVersion(workflow);
        CompiledWorkflow compiled = compiled(workflow, version);
        WorkflowNode.Trigger triggerNode = entryTrigger(compiled);
        RuleTrigger trigger = triggerNode.config();
        if (!"schedule".equals(normalize(trigger.getType()))
                || !normalize(dispatch.cadence()).equals(normalize(trigger.getCadence()))) {
            return null;
        }
        String enrollmentNodeId = compiled.enrollmentConditionNodeId();
        WorkflowNode enrollmentNode = compiled.node(enrollmentNodeId);
        if (!(enrollmentNode instanceof WorkflowNode.Condition condition)
                || condition.config() == null) {
            throw new WorkflowExecutionException(
                "definition_invalid",
                "The active workflow definition is invalid.",
                true);
        }
        int conditionActorId = conditionActorId(version);
        return new ScheduleEnrollment(
            workflow.getId(), version, compiled, condition, conditionActorId);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public CanonicalClaim claimScheduleRecord(
            int workflowId,
            long expectedVersionId,
            WorkflowTriggerDispatch.ScheduleTick dispatch,
            int recordId) {
        workflowTriggerOutboxMapper.ensureWorkspaceGate(dispatch.workspaceId());
        Workflow workflow = workflowMapper.getByIdForUpdate(dispatch.workspaceId(), workflowId);
        if (!canonicalOwnerCanClaim(workflow)
                || workflow.getActiveVersionId() == null
                || workflow.getActiveVersionId() != expectedVersionId) {
            return CanonicalClaim.rejectedClaim();
        }
        WorkflowVersion version = activeVersion(workflow);
        CompiledWorkflow compiled = compiled(workflow, version);
        WorkflowNode.Trigger triggerNode = entryTrigger(compiled);
        RuleTrigger trigger = triggerNode.config();
        if (!"schedule".equals(normalize(trigger.getType()))
                || !normalize(dispatch.cadence()).equals(normalize(trigger.getCadence()))) {
            return CanonicalClaim.rejectedClaim();
        }
        String key = dedupeKey.schedule(
            dedupeIdentity(workflow), version.getRecordType(), recordId,
            normalize(dispatch.cadence()), dispatch.bucketKey());
        String legacyKey = dedupeKey.legacySchedule(recordId, dispatch.bucketKey());
        return claimCanonical(
            workflow, version, compiled, "schedule", normalize(dispatch.cadence()),
            dispatch.bucketKey(), version.getRecordType(), recordId, key,
            legacyKey, null, "queued");
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public CanonicalClaim claimOutbox(
            WorkflowTriggerOutbox outbox, int recordId) {
        Workflow workflow = workflowMapper.getByIdForUpdate(
            outbox.getWorkspaceId(), outbox.getWorkflowId());
        if (!canonicalOwnerCanClaim(workflow)
                || workflow.getRuntimeGeneration() != outbox.getWorkflowRuntimeGeneration()
                || workflow.getActiveVersionId() == null
                || workflow.getActiveVersionId() != outbox.getWorkflowVersionId()) {
            return CanonicalClaim.rejectedClaim();
        }
        WorkflowVersion version = activeVersion(workflow);
        CompiledWorkflow compiled = compiled(workflow, version);
        WorkflowNode.Trigger triggerNode = entryTrigger(compiled);
        RuleTrigger trigger = triggerNode.config();
        String key;
        String legacyKey;
        if ("entity_change".equals(outbox.getTriggerType())) {
            if (outbox.getOccurredAt() == null
                    || !entityOutboxMatches(version, trigger, outbox, recordId)) {
                return CanonicalClaim.rejectedClaim();
            }
            key = dedupeKey.entityChange(
                dedupeIdentity(workflow),
                outbox.getRecordType(),
                recordId,
                outbox.getTriggerEvent(),
                outbox.getTriggerKey(),
                outbox.getOccurredAt().toInstant(java.time.ZoneOffset.UTC),
                trigger.getThrottleMinutes());
            legacyKey = dedupeKey.legacyEntityChange(
                recordId,
                outbox.getTriggerEvent(),
                outbox.getOccurredAt().toInstant(java.time.ZoneOffset.UTC),
                trigger.getThrottleMinutes());
        } else if ("schedule".equals(outbox.getTriggerType())) {
            if (!scheduleOutboxMatches(version, trigger, outbox)) {
                return CanonicalClaim.rejectedClaim();
            }
            key = dedupeKey.schedule(
                dedupeIdentity(workflow),
                version.getRecordType(),
                recordId,
                normalize(outbox.getTriggerEvent()),
                outbox.getTriggerKey());
            legacyKey = dedupeKey.legacySchedule(recordId, outbox.getTriggerKey());
        } else {
            return CanonicalClaim.rejectedClaim();
        }
        return claimCanonical(
            workflow,
            version,
            compiled,
            outbox.getTriggerType(),
            outbox.getTriggerEvent(),
            outbox.getTriggerKey(),
            outbox.getRecordType(),
            recordId,
            key,
            legacyKey,
            outbox.getId(),
            "queued");
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public CanonicalClaim claimManual(
            int workspaceId,
            int workflowId,
            long expectedVersionId,
            long invocationId,
            int recordId) {
        workflowTriggerOutboxMapper.ensureWorkspaceGate(workspaceId);
        Workflow workflow = workflowMapper.getByIdForUpdate(workspaceId, workflowId);
        if (!canonicalOwnerCanClaim(workflow)
                || workflow.getActiveVersionId() == null
                || workflow.getActiveVersionId() != expectedVersionId) {
            return CanonicalClaim.rejectedClaim();
        }
        WorkflowVersion version = activeVersion(workflow);
        CompiledWorkflow compiled = compiled(workflow, version);
        String triggerKey = Long.toString(invocationId);
        String key = "manual:" + invocationId + ":" + recordId;
        return claimCanonical(
            workflow,
            version,
            compiled,
            "manual",
            "manual",
            triggerKey,
            version.getRecordType(),
            recordId,
            key,
            null,
            null,
            "queued");
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public ScheduleEnrollment outboxScheduleEnrollment(
            WorkflowTriggerOutbox outbox) {
        Workflow workflow = workflowMapper.getByIdForUpdate(
            outbox.getWorkspaceId(), outbox.getWorkflowId());
        if (!outboxStateMatches(workflow, outbox)) {
            return null;
        }
        WorkflowVersion version = activeVersion(workflow);
        CompiledWorkflow compiled = compiled(workflow, version);
        WorkflowNode.Trigger triggerNode = entryTrigger(compiled);
        if (!scheduleOutboxMatches(version, triggerNode.config(), outbox)) {
            return null;
        }
        String enrollmentNodeId = compiled.enrollmentConditionNodeId();
        WorkflowNode enrollmentNode = compiled.node(enrollmentNodeId);
        if (!(enrollmentNode instanceof WorkflowNode.Condition condition)
                || condition.config() == null) {
            throw new WorkflowExecutionException(
                "definition_invalid",
                "The active workflow definition is invalid.",
                true);
        }
        return new ScheduleEnrollment(
            workflow.getId(), version, compiled, condition, conditionActorId(version));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public LegacyClaim claimLegacyEntity(
            Rule rule,
            RuleTrigger trigger,
            WorkflowTriggerDispatch.EntityChange dispatch) {
        Workflow workflow = workflowMapper.getByLegacyRuleIdForUpdate(
            dispatch.workspaceId(), rule.getId());
        if (!legacyOwnerCanClaim(workflow, rule, dispatch.workspaceId())) {
            return LegacyClaim.rejectedClaim();
        }
        String key = dedupeKey.entityChange(
            rule.getId(), dispatch.recordType(), dispatch.recordId(), dispatch.event(),
            dispatch.triggerKey(), dispatch.occurredAt(), trigger.getThrottleMinutes());
        String legacyKey = dedupeKey.legacyEntityChange(
            dispatch.recordId(),
            dispatch.event(),
            dispatch.occurredAt(),
            trigger.getThrottleMinutes());
        return claimLegacy(
            workflow, rule, dispatch.recordType(), dispatch.recordId(), key, legacyKey);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public LegacyClaim claimLegacySchedule(
            Rule rule,
            WorkflowTriggerDispatch.ScheduleTick dispatch,
            int recordId) {
        Workflow workflow = workflowMapper.getByLegacyRuleIdForUpdate(
            dispatch.workspaceId(), rule.getId());
        if (!legacyOwnerCanClaim(workflow, rule, dispatch.workspaceId())) {
            return LegacyClaim.rejectedClaim();
        }
        String key = dedupeKey.schedule(
            rule.getId(), rule.getRecordType(), recordId,
            normalize(dispatch.cadence()), dispatch.bucketKey());
        String legacyKey = dedupeKey.legacySchedule(recordId, dispatch.bucketKey());
        return claimLegacy(
            workflow, rule, rule.getRecordType(), recordId, key, legacyKey);
    }

    private CanonicalClaim claimCanonical(
            Workflow workflow,
            WorkflowVersion version,
            CompiledWorkflow compiled,
            String triggerType,
            String triggerEvent,
            String triggerKey,
            String recordType,
            int recordId,
            String key,
            String legacyKey,
            Long triggerOutboxId,
            String initialStatus) {
        if (workflow.getLegacyRuleId() != null) {
            RuleExecution current = ruleMapper.getExecutionByDedupe(
                workflow.getWorkspaceId(), workflow.getLegacyRuleId(), key);
            RuleExecution legacy = legacyKey == null || current != null
                ? null
                : ruleMapper.getExecutionByDedupe(
                    workflow.getWorkspaceId(), workflow.getLegacyRuleId(), legacyKey);
            if (current != null || legacy != null) {
                return CanonicalClaim.replayedWithoutRun();
            }
        }
        WorkflowRun existing = workflowRunMapper.getByDedupe(
            workflow.getWorkspaceId(), workflow.getId(), key);
        if (existing != null) {
            return new CanonicalClaim(existing, false, true, false);
        }
        WorkflowRun run = new WorkflowRun();
        run.setWorkspaceId(workflow.getWorkspaceId());
        run.setWorkflowId(workflow.getId());
        run.setWorkflowVersionId(version.getId());
        run.setStatus(initialStatus);
        run.setTriggerType(triggerType);
        run.setTriggerEvent(triggerEvent);
        run.setTriggerKey(triggerKey);
        run.setRecordType(recordType);
        run.setRecordId(recordId);
        run.setDedupeKey(key);
        run.setTriggerOutboxId(triggerOutboxId);
        run.setExecutionMode(version.getExecutionMode());
        run.setActorUserId(actorUserId(version));
        run.setAttributionUserId(conditionActorId(version));
        run.setCurrentNodeId(compiled.entryNodeId());
        run.setStartedAt(LocalDateTime.now());
        try {
            workflowRunMapper.insertRun(run);
            return new CanonicalClaim(run, true, false, false);
        } catch (DuplicateKeyException exception) {
            WorkflowRun replay = workflowRunMapper.getByDedupe(
                workflow.getWorkspaceId(), workflow.getId(), key);
            return replay == null
                ? CanonicalClaim.rejectedClaim()
                : new CanonicalClaim(replay, false, true, false);
        }
    }

    private boolean entityOutboxMatches(
            WorkflowVersion version,
            RuleTrigger trigger,
            WorkflowTriggerOutbox outbox,
            int recordId) {
        if (!"entity_change".equals(normalize(trigger.getType()))
                || !version.getRecordType().equals(outbox.getRecordType())
                || trigger.getEvents() == null
                || !trigger.getEvents().contains(outbox.getTriggerEvent())) {
            return false;
        }
        if (trigger.getTargetStageId() == null || !"deal".equals(outbox.getRecordType())) {
            return true;
        }
        Deal deal = dealMapper.getDealById(outbox.getWorkspaceId(), recordId);
        return deal != null && trigger.getTargetStageId().equals(deal.getStageId());
    }

    private static boolean scheduleOutboxMatches(
            WorkflowVersion version,
            RuleTrigger trigger,
            WorkflowTriggerOutbox outbox) {
        return "schedule".equals(normalize(trigger.getType()))
            && version.getRecordType().equals(outbox.getRecordType())
            && normalize(outbox.getTriggerEvent()).equals(normalize(trigger.getCadence()));
    }

    private static boolean outboxStateMatches(
            Workflow workflow, WorkflowTriggerOutbox outbox) {
        return workflow != null
            && workflow.isEnabled()
            && workflow.getArchivedAt() == null
            && workflow.getIntakePausedAt() == null
            && workflow.getActiveVersionId() != null
            && workflow.getActiveVersionId() == outbox.getWorkflowVersionId()
            && workflow.getRuntimeGeneration() == outbox.getWorkflowRuntimeGeneration();
    }

    private LegacyClaim claimLegacy(
            Workflow workflow,
            Rule rule,
            String recordType,
            int recordId,
            String key,
            String legacyKey) {
        int workspaceId = rule.getWorkspaceId();
        if (workflow != null && workflowRunMapper.getByDedupe(
                workspaceId, workflow.getId(), key) != null) {
            return LegacyClaim.replayedClaim();
        }
        RuleExecution existing = ruleMapper.getExecutionByDedupe(
            workspaceId, rule.getId(), key);
        if (existing == null && legacyKey != null) {
            existing = ruleMapper.getExecutionByDedupe(
                workspaceId, rule.getId(), legacyKey);
        }
        if (existing != null) {
            return LegacyClaim.replayedClaim();
        }
        RuleExecution execution = new RuleExecution();
        execution.setWorkspaceId(workspaceId);
        execution.setRuleId(rule.getId());
        execution.setTriggerEntityType(recordType);
        execution.setTriggerEntityId(recordId);
        execution.setStatus("running");
        execution.setDedupeKey(key);
        try {
            ruleMapper.insertExecution(execution);
            return new LegacyClaim(execution, key, true, false, false);
        } catch (DuplicateKeyException exception) {
            return LegacyClaim.replayedClaim();
        }
    }

    private WorkflowVersion activeVersion(Workflow workflow) {
        Long activeVersionId = workflow.getActiveVersionId();
        if (activeVersionId == null) {
            throw new WorkflowExecutionException(
                "definition_unavailable",
                "The active workflow version is unavailable.",
                true);
        }
        WorkflowVersion version = workflowVersionMapper.getById(
            workflow.getWorkspaceId(), workflow.getId(), activeVersionId);
        if (version == null) {
            throw new WorkflowExecutionException(
                "definition_unavailable",
                "The active workflow version is unavailable.",
                true);
        }
        return version;
    }

    private CompiledWorkflow compiled(Workflow workflow, WorkflowVersion version) {
        CanonicalDraft canonical = canonicalizer.canonicalizeDraftJson(
            version.getName(),
            version.getDescription(),
            version.getRecordType(),
            version.getExecutionMode(),
            version.getDefinitionJson(),
            version.getCanvasJson());
        if (version.getDefinitionHash() == null
                || !MessageDigest.isEqual(
                    version.getDefinitionHash(), canonical.definitionHash())) {
            throw new WorkflowExecutionException(
                "definition_corrupt",
                "The active workflow definition failed its integrity check.",
                true);
        }
        WorkflowDefinition definition = canonicalizer.parseDefinition(canonical.definitionJson());
        return definitionValidator.validate(
            version.getRecordType(), version.getExecutionMode(), definition);
    }

    private boolean entityTriggerMatches(
            WorkflowVersion version,
            RuleTrigger trigger,
            WorkflowTriggerDispatch.EntityChange dispatch) {
        if (!"entity_change".equals(normalize(trigger.getType()))
                || !version.getRecordType().equals(dispatch.recordType())
                || trigger.getEvents() == null
                || !trigger.getEvents().contains(dispatch.event())) {
            return false;
        }
        if (trigger.getTargetStageId() == null || !"deal".equals(dispatch.recordType())) {
            return true;
        }
        Deal deal = dealMapper.getDealById(dispatch.workspaceId(), dispatch.recordId());
        return deal != null && trigger.getTargetStageId().equals(deal.getStageId());
    }

    private static boolean canonicalOwnerCanClaim(Workflow workflow) {
        return workflow != null
            && workflow.isEnabled()
            && "canonical".equals(workflow.getRuntimeOwner())
            && workflow.getArchivedAt() == null
            && workflow.getIntakePausedAt() == null
            && workflow.getActiveVersionId() != null;
    }

    private static WorkflowNode.Trigger entryTrigger(CompiledWorkflow compiled) {
        WorkflowNode entry = compiled.node(compiled.entryNodeId());
        if (!(entry instanceof WorkflowNode.Trigger trigger) || trigger.config() == null) {
            throw new WorkflowExecutionException(
                "definition_invalid",
                "The active workflow entry node is invalid.",
                true);
        }
        return trigger;
    }

    private static boolean legacyOwnerCanClaim(
            Workflow workflow, Rule rule, int workspaceId) {
        if (!rule.isEnabled() || rule.getWorkspaceId() != workspaceId) {
            return false;
        }
        return workflow == null
            || (workflow.getWorkspaceId() == workspaceId
                && "legacy".equals(workflow.getRuntimeOwner())
                && workflow.getArchivedAt() == null
                && workflow.getIntakePausedAt() == null
                && Objects.equals(workflow.getLegacyRuleId(), rule.getId()));
    }

    private static int dedupeIdentity(Workflow workflow) {
        Integer legacyRuleId = workflow.getLegacyRuleId();
        return legacyRuleId == null ? workflow.getId() : legacyRuleId;
    }

    private int actorUserId(WorkflowVersion version) {
        return "system".equals(version.getExecutionMode())
            ? systemActor.user().getId()
            : conditionActorId(version);
    }

    private static int conditionActorId(WorkflowVersion version) {
        Integer actorId = "system".equals(version.getExecutionMode())
            ? version.getCreatedById()
            : version.getRunAsUserId();
        if (actorId == null || actorId <= 0) {
            throw new WorkflowExecutionException(
                "actor_unavailable",
                "The configured workflow actor is unavailable.",
                true);
        }
        return actorId;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT);
    }

    /** Result of one serialized canonical claim. */
    public record CanonicalClaim(
        WorkflowRun run,
        boolean started,
        boolean replayed,
        boolean rejected
    ) {

        static CanonicalClaim rejectedClaim() {
            return new CanonicalClaim(null, false, false, true);
        }

        static CanonicalClaim replayedWithoutRun() {
            return new CanonicalClaim(null, false, true, false);
        }
    }

    /** Immutable schedule-enrollment snapshot used before per-record claims. */
    public record ScheduleEnrollment(
        int workflowId,
        WorkflowVersion version,
        CompiledWorkflow compiled,
        WorkflowNode.Condition condition,
        int conditionActorId
    ) { }

    /** Result of one serialized legacy claim. */
    public record LegacyClaim(
        RuleExecution execution,
        String dedupeKey,
        boolean started,
        boolean replayed,
        boolean rejected
    ) {

        static LegacyClaim replayedClaim() {
            return new LegacyClaim(null, null, false, true, false);
        }

        static LegacyClaim rejectedClaim() {
            return new LegacyClaim(null, null, false, false, true);
        }
    }
}
