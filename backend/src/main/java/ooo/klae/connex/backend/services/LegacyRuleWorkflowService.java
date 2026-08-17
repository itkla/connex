package ooo.klae.connex.backend.services;

import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.beans.Rule;
import ooo.klae.connex.backend.beans.Workflow;
import ooo.klae.connex.backend.beans.WorkflowVersion;
import ooo.klae.connex.backend.dto.RuleAction;
import ooo.klae.connex.backend.dto.RuleRequest;
import ooo.klae.connex.backend.dto.RuleTrigger;
import ooo.klae.connex.backend.dto.SegmentCondition;
import ooo.klae.connex.backend.dto.SegmentDefinition;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.RuleMapper;
import ooo.klae.connex.backend.mappers.WorkflowMapper;
import ooo.klae.connex.backend.mappers.WorkflowVersionMapper;
import ooo.klae.connex.backend.services.LegacyWorkflowGraphConverter.ConvertedWorkflow;
import ooo.klae.connex.backend.services.WorkflowDraftCanonicalizer.CanonicalDraft;
import ooo.klae.connex.backend.services.WorkflowPrincipalLockService.LockedPrincipals;
import ooo.klae.connex.backend.tenant.Permission;

/**
 * Maintains legacy rule mutations as a transactional projection of versioned workflows. This is the
 * only write path behind {@code /api/rules}; the workflow aggregate and immutable versions remain
 * authoritative, and canonical-owned workflows reject legacy mutation. Remove this projection after
 * the supported rule-client inventory is empty and no workflow requires legacy rollback.
 */
@Service
@RequiredArgsConstructor
public class LegacyRuleWorkflowService {

    private final RuleMapper ruleMapper;
    private final WorkflowMapper workflowMapper;
    private final WorkflowVersionMapper workflowVersionMapper;
    private final WorkflowPrincipalLockService principalLockService;
    private final RuleDefinitionValidator definitionValidator;
    private final RuleDefinitionCodec definitionCodec;
    private final LegacyWorkflowGraphConverter graphConverter;
    private final WorkflowDraftCanonicalizer canonicalizer;

    Rule create(int workspaceId, int actorId, RuleRequest request) {
        Set<Permission> requiredPermissions = definitionValidator.validateForMutation(request);
        String executionMode = definitionValidator.normalize(request.getExecutionMode());
        LockedPrincipals principals = lockRequestedMode(
            workspaceId,
            actorId,
            executionMode,
            Set.of(actorId),
            "user".equals(executionMode) ? Set.of(actorId) : Set.of());
        principals.requirePermissions(requiredPermissions);

        Rule requested = requestedRule(
            workspaceId,
            0,
            actorId,
            "user".equals(executionMode) ? actorId : null,
            request);
        LegacySnapshot snapshot = snapshot(requested);
        Rule projection = snapshot.projection();
        ruleMapper.insert(projection);
        if (projection.getId() <= 0) {
            throw new IllegalStateException("Rule insert did not return an id");
        }

        Workflow workflow = workflow(projection, snapshot.draft(), actorId);
        workflowMapper.insert(workflow);
        if (workflow.getId() <= 0) {
            throw new IllegalStateException("Workflow insert did not return an id");
        }
        WorkflowVersion version = version(
            workflow, projection, snapshot.draft(), 1, actorId);
        workflowVersionMapper.insert(version);
        if (version.getId() <= 0) {
            throw new IllegalStateException("Workflow version insert did not return an id");
        }
        if (workflowMapper.updateActiveVersion(
                workspaceId, workflow.getId(), version.getId(), actorId) != 1) {
            throw new IllegalStateException("Workflow active version was not assigned");
        }
        if (projection.isEnabled()
                && workflowMapper.updateLifecycle(
                    workspaceId, workflow.getId(), true, actorId) != 1) {
            throw new IllegalStateException("Workflow lifecycle was not synchronized");
        }
        return requirePersistedRule(workspaceId, projection.getId());
    }

    Rule update(int workspaceId, int actorId, int ruleId, RuleRequest request) {
        AggregateDiscovery discovery = discover(workspaceId, ruleId);
        requireLegacyMutable(discovery.workflow());
        Set<Permission> requiredPermissions = definitionValidator.validateForMutation(request);
        String executionMode = definitionValidator.normalize(request.getExecutionMode());
        Integer discoveredRunAs = requestedRunAs(discovery.activeVersion(), executionMode);
        TreeSet<Integer> principalIds = new TreeSet<>(discovery.principalIds());
        addPrincipal(principalIds, discoveredRunAs);
        LockedPrincipals principals = lockRequestedMode(
            workspaceId,
            actorId,
            executionMode,
            principalIds,
            "user".equals(executionMode) ? Set.of(discoveredRunAs) : Set.of());
        if ("system".equals(executionMode)) {
            principals.requireExisting(
                discovery.rule().getCreatedById(),
                "System rule creator account no longer exists");
        }
        principals.requirePermissions(requiredPermissions);

        boolean allowBrokenPrincipals = !discovery.workflow().isEnabled()
            && !discovery.rule().isEnabled();
        LockedAggregate aggregate = lockAggregate(discovery, principals, false);
        Rule currentProjection = requireConsistentPublished(
            aggregate, allowBrokenPrincipals);
        Integer runAsUserId = requestedRunAs(aggregate.activeVersion(), executionMode);
        if (!Objects.equals(discoveredRunAs, runAsUserId)) {
            throw new ConflictException("Rule execution identity changed during authorization");
        }
        Rule requested = requestedRule(
            workspaceId,
            ruleId,
            aggregate.rule().getCreatedById(),
            runAsUserId,
            request);
        LegacySnapshot snapshot = snapshot(requested);
        Rule replacement = snapshot.projection();
        replacement.setId(ruleId);

        if (semanticallyEquivalent(currentProjection, replacement)) {
            if (aggregate.rule().isEnabled() != replacement.isEnabled()) {
                updateEnabledOnly(aggregate, replacement.isEnabled(), actorId);
            }
            return requirePersistedRule(workspaceId, ruleId);
        }
        replacePublication(aggregate, replacement, snapshot.draft(), actorId);
        return requirePersistedRule(workspaceId, ruleId);
    }

    Rule delete(int workspaceId, int actorId, int ruleId) {
        AggregateDiscovery discovery = discover(workspaceId, ruleId);
        LockedPrincipals principals = principalLockService.lockUserMutation(
            workspaceId, actorId, discovery.principalIds(), Set.of());
        LockedAggregate aggregate = lockAggregate(discovery, principals, true);
        requireLegacyOwner(aggregate.workflow());
        requireDeletablePair(aggregate);
        Workflow workflow = aggregate.workflow();
        if (workflow.getArchivedAt() != null) {
            return aggregate.rule();
        }
        if (aggregate.rule().isEnabled()
                && ruleMapper.updateEnabled(workspaceId, ruleId, false) != 1) {
            throw new ConflictException("Rule lifecycle changed during archive");
        }
        if (workflowMapper.archive(workspaceId, workflow.getId(), actorId) != 1) {
            throw new ConflictException("Rule workflow state changed during archive");
        }
        aggregate.rule().setEnabled(false);
        return aggregate.rule();
    }

    private LockedPrincipals lockRequestedMode(
            int workspaceId,
            int actorId,
            String executionMode,
            Collection<Integer> principalIds,
            Collection<Integer> requiredActiveIds) {
        if ("system".equals(executionMode)) {
            return principalLockService.lockSystemMutation(
                workspaceId, actorId, principalIds);
        }
        if (!"user".equals(executionMode)) {
            throw new BadRequestException("Invalid execution mode: " + executionMode);
        }
        return principalLockService.lockUserMutation(
            workspaceId, actorId, principalIds, requiredActiveIds);
    }

    private AggregateDiscovery discover(int workspaceId, int ruleId) {
        Rule rule = ruleMapper.getById(workspaceId, ruleId);
        if (rule == null) {
            throw ruleNotFound(ruleId);
        }
        if (rule.getId() != ruleId || rule.getWorkspaceId() != workspaceId) {
            throw inconsistentAggregate();
        }
        Workflow workflow = workflowMapper.getByLegacyRuleId(workspaceId, ruleId);
        if (workflow == null
                || workflow.getId() <= 0
                || workflow.getWorkspaceId() != workspaceId
                || !Objects.equals(workflow.getLegacyRuleId(), ruleId)) {
            throw inconsistentAggregate();
        }
        TreeMap<Long, WorkflowVersion> versions = new TreeMap<>();
        for (WorkflowVersion version : workflowVersionMapper.listByWorkflow(
                workspaceId, workflow.getId())) {
            if (version == null
                    || version.getId() <= 0
                    || version.getWorkspaceId() != workspaceId
                    || version.getWorkflowId() != workflow.getId()
                    || version.getVersionNumber() <= 0
                    || versions.put(version.getId(), version) != null) {
                throw inconsistentAggregate();
            }
        }
        Long activeVersionId = workflow.getActiveVersionId();
        WorkflowVersion active = activeVersionId == null ? null : versions.get(activeVersionId);
        if (active == null) {
            throw inconsistentAggregate();
        }
        TreeSet<Integer> principalIds = principalIds(rule, workflow, versions.values());
        return new AggregateDiscovery(
            rule,
            workflow,
            Collections.unmodifiableMap(new LinkedHashMap<>(versions)),
            active,
            Set.copyOf(principalIds));
    }

    private LockedAggregate lockAggregate(
            AggregateDiscovery discovery,
            LockedPrincipals principals,
            boolean allowMissingPrincipals) {
        Workflow expectedWorkflow = discovery.workflow();
        Workflow workflow = workflowMapper.getByIdForUpdate(
            expectedWorkflow.getWorkspaceId(), expectedWorkflow.getId());
        if (!sameWorkflow(expectedWorkflow, workflow)) {
            throw new ConflictException("Rule workflow changed during authorization");
        }

        Map<Long, WorkflowVersion> versions = new LinkedHashMap<>();
        for (Map.Entry<Long, WorkflowVersion> entry : discovery.versions().entrySet()) {
            WorkflowVersion expected = entry.getValue();
            WorkflowVersion current = workflowVersionMapper.getByIdForUpdate(
                expected.getWorkspaceId(), expected.getWorkflowId(), expected.getId());
            if (!sameVersion(expected, current)) {
                throw new ConflictException("Rule workflow version changed during authorization");
            }
            versions.put(entry.getKey(), current);
        }

        Rule expectedRule = discovery.rule();
        Rule rule = ruleMapper.getByIdForUpdate(expectedRule.getWorkspaceId(), expectedRule.getId());
        if (!sameRule(expectedRule, rule)) {
            throw new ConflictException("Rule changed during authorization");
        }
        TreeSet<Integer> mutablePrincipalIds = mutablePrincipalIds(rule, workflow);
        TreeSet<Integer> immutablePrincipalIds = versionPrincipalIds(versions.values());
        principals.requireDiscoveredReferences(immutablePrincipalIds);
        if (allowMissingPrincipals) {
            principals.requireDiscoveredReferences(mutablePrincipalIds);
        } else {
            principals.requireCurrentReferences(mutablePrincipalIds);
        }
        WorkflowVersion active = versions.get(workflow.getActiveVersionId());
        return new LockedAggregate(
            rule, workflow, Collections.unmodifiableMap(new LinkedHashMap<>(versions)), active);
    }

    private Rule requireConsistentPublished(
            LockedAggregate aggregate, boolean allowBrokenPrincipals) {
        Workflow workflow = aggregate.workflow();
        WorkflowVersion active = aggregate.activeVersion();
        Rule rule = aggregate.rule();
        if (active == null
                || workflow.getActiveVersionId() == null
                || active.getId() != workflow.getActiveVersionId()
                || active.getWorkspaceId() != workflow.getWorkspaceId()
                || active.getWorkflowId() != workflow.getId()
                || active.getVersionNumber() <= 0
                || !Objects.equals(workflow.getLegacyRuleId(), rule.getId())
                || !redactedPrincipalMatches(
                    workflow.getCreatedById(),
                    active.getCreatedById(),
                    allowBrokenPrincipals)) {
            throw inconsistentAggregate();
        }
        CanonicalDraft canonical;
        Rule projection;
        try {
            canonical = canonicalizer.canonicalizeDraftJson(
                active.getName(),
                active.getDescription(),
                active.getRecordType(),
                active.getExecutionMode(),
                active.getDefinitionJson(),
                active.getCanvasJson());
            if (!Objects.equals(active.getName(), canonical.name())
                    || !Objects.equals(active.getDescription(), canonical.description())
                    || !Objects.equals(active.getRecordType(), canonical.recordType())
                    || !Objects.equals(active.getExecutionMode(), canonical.executionMode())
                    || !Objects.equals(active.getDefinitionJson(), canonical.definitionJson())
                    || !Objects.equals(active.getCanvasJson(), canonical.canvasJson())
                    || !hashesEqual(active.getDefinitionHash(), canonical.definitionHash())) {
                throw inconsistentAggregate();
            }
            ConvertedWorkflow converted = new ConvertedWorkflow(
                rule.getId(),
                rule.getWorkspaceId(),
                active.getName(),
                active.getDescription(),
                workflow.isEnabled(),
                active.getRecordType(),
                active.getExecutionMode(),
                active.getRunAsUserId(),
                active.getCreatedById(),
                canonicalizer.parseDefinition(canonical.definitionJson()),
                canonicalizer.parseCanvas(canonical.canvasJson()));
            projection = graphConverter.project(converted);
        } catch (BadRequestException exception) {
            throw inconsistentAggregate();
        }
        try {
            if (!versionMatches(active, projection)
                    || !(allowBrokenPrincipals
                        ? ruleMatchesRedactedPrincipals(rule, projection)
                        : ruleMatches(rule, projection))) {
                throw inconsistentAggregate();
            }
        } catch (BadRequestException exception) {
            throw inconsistentAggregate();
        }
        return projection;
    }

    private void updateEnabledOnly(
            LockedAggregate aggregate, boolean enabled, int actorId) {
        Rule rule = aggregate.rule();
        Workflow workflow = aggregate.workflow();
        if (ruleMapper.updateEnabled(rule.getWorkspaceId(), rule.getId(), enabled) != 1) {
            throw new ConflictException("Rule lifecycle changed during update");
        }
        if (workflowMapper.updateLifecycle(
                workflow.getWorkspaceId(), workflow.getId(), enabled, actorId) != 1) {
            throw new ConflictException("Rule workflow lifecycle changed during update");
        }
    }

    private void replacePublication(
            LockedAggregate aggregate,
            Rule replacement,
            CanonicalDraft draft,
            int actorId) {
        Workflow workflow = aggregate.workflow();
        if (workflow.getDraftRevision() == Integer.MAX_VALUE) {
            throw new ConflictException("Workflow draft revision cannot be advanced");
        }
        WorkflowVersion latest = latestVersion(aggregate.versions().values());
        if (latest == null || latest.getVersionNumber() == Integer.MAX_VALUE) {
            throw inconsistentAggregate();
        }
        WorkflowVersion version = version(
            workflow,
            replacement,
            draft,
            latest.getVersionNumber() + 1,
            actorId);
        workflowVersionMapper.insert(version);
        if (version.getId() <= 0) {
            throw new IllegalStateException("Workflow version insert did not return an id");
        }
        if (ruleMapper.update(replacement) != 1) {
            throw new ConflictException("Rule changed during update");
        }
        Workflow replacementWorkflow = workflow(replacement, draft, actorId);
        replacementWorkflow.setId(workflow.getId());
        replacementWorkflow.setEnabled(replacement.isEnabled());
        if (workflowMapper.replaceLegacyPublication(
                replacementWorkflow,
                version.getId(),
                replacement.getId(),
                workflow.getActiveVersionId(),
                workflow.getDraftRevision()) != 1) {
            throw new ConflictException("Rule workflow changed during update");
        }
    }

    private LegacySnapshot snapshot(Rule source) {
        ConvertedWorkflow converted = graphConverter.convert(source);
        CanonicalDraft draft = canonicalizer.canonicalizeDraft(
            converted.name(),
            converted.description(),
            converted.recordType(),
            converted.executionMode(),
            converted.definition(),
            converted.canvas());
        ConvertedWorkflow normalized = new ConvertedWorkflow(
            converted.legacyRuleId(),
            converted.workspaceId(),
            draft.name(),
            draft.description(),
            converted.enabled(),
            draft.recordType(),
            draft.executionMode(),
            converted.runAsUserId(),
            converted.createdById(),
            converted.definition(),
            converted.canvas());
        return new LegacySnapshot(graphConverter.project(normalized), draft);
    }

    private Rule requestedRule(
            int workspaceId,
            int ruleId,
            Integer createdById,
            Integer runAsUserId,
            RuleRequest request) {
        Rule rule = new Rule();
        rule.setId(ruleId);
        rule.setWorkspaceId(workspaceId);
        rule.setName(request.getName().trim());
        rule.setDescription(request.getDescription());
        rule.setEnabled(request.getEnabled() == null || request.getEnabled());
        rule.setRecordType(definitionValidator.normalize(request.getRecordType()));
        rule.setTriggerType(definitionValidator.normalize(request.getTrigger().getType()));
        rule.setTriggerConfig(definitionCodec.serialize(request.getTrigger()));
        rule.setConditionJson(request.getCondition() == null
            ? null
            : definitionCodec.serialize(request.getCondition()));
        rule.setActionsJson(definitionCodec.serialize(request.getActions()));
        rule.setExecutionMode(definitionValidator.normalize(request.getExecutionMode()));
        rule.setRunAsUserId(runAsUserId);
        rule.setCreatedById(createdById);
        return rule;
    }

    private static Workflow workflow(
            Rule projection, CanonicalDraft draft, int updatedById) {
        Workflow workflow = new Workflow();
        workflow.setWorkspaceId(projection.getWorkspaceId());
        workflow.setLegacyRuleId(projection.getId());
        workflow.setName(projection.getName());
        workflow.setDescription(projection.getDescription());
        workflow.setEnabled(false);
        workflow.setRuntimeOwner("legacy");
        workflow.setArchivedAt(null);
        workflow.setDraftRevision(1);
        workflow.setDraftRecordType(projection.getRecordType());
        workflow.setDraftExecutionMode(projection.getExecutionMode());
        workflow.setDraftRunAsUserId(projection.getRunAsUserId());
        workflow.setDraftDefinitionJson(draft.definitionJson());
        workflow.setDraftCanvasJson(draft.canvasJson());
        workflow.setActiveVersionId(null);
        workflow.setCreatedById(projection.getCreatedById());
        workflow.setUpdatedById(updatedById);
        return workflow;
    }

    private static WorkflowVersion version(
            Workflow workflow,
            Rule projection,
            CanonicalDraft draft,
            int versionNumber,
            int publishedById) {
        WorkflowVersion version = new WorkflowVersion();
        version.setWorkspaceId(projection.getWorkspaceId());
        version.setWorkflowId(workflow.getId());
        version.setVersionNumber(versionNumber);
        version.setName(projection.getName());
        version.setDescription(projection.getDescription());
        version.setRecordType(projection.getRecordType());
        version.setTriggerType(projection.getTriggerType());
        version.setTriggerConfig(projection.getTriggerConfig());
        version.setConditionJson(projection.getConditionJson());
        version.setActionsJson(projection.getActionsJson());
        version.setExecutionMode(projection.getExecutionMode());
        version.setRunAsUserId(projection.getRunAsUserId());
        version.setCreatedById(projection.getCreatedById());
        version.setPublishedById(publishedById);
        version.setDefinitionJson(draft.definitionJson());
        version.setCanvasJson(draft.canvasJson());
        version.setDefinitionHash(draft.definitionHash());
        return version;
    }

    private Integer requestedRunAs(WorkflowVersion active, String requestedMode) {
        if (!"user".equals(requestedMode)) {
            return null;
        }
        Integer runAsUserId = "user".equals(active.getExecutionMode())
            ? active.getRunAsUserId()
            : active.getCreatedById();
        if (runAsUserId == null || runAsUserId <= 0) {
            throw new ConflictException("Rule run-as user is not an active workspace member");
        }
        return runAsUserId;
    }

    private void requireDeletablePair(LockedAggregate aggregate) {
        Workflow workflow = aggregate.workflow();
        Long activeVersionId = workflow.getActiveVersionId();
        if (activeVersionId == null
                || !Objects.equals(workflow.getLegacyRuleId(), aggregate.rule().getId())
                || !aggregate.versions().containsKey(activeVersionId)) {
            throw inconsistentAggregate();
        }
    }

    private boolean semanticallyEquivalent(Rule current, Rule requested) {
        return current.getWorkspaceId() == requested.getWorkspaceId()
            && current.getId() == requested.getId()
            && Objects.equals(current.getName(), requested.getName())
            && Objects.equals(current.getDescription(), requested.getDescription())
            && Objects.equals(current.getRecordType(), requested.getRecordType())
            && Objects.equals(current.getTriggerType(), requested.getTriggerType())
            && Objects.equals(current.getExecutionMode(), requested.getExecutionMode())
            && Objects.equals(current.getRunAsUserId(), requested.getRunAsUserId())
            && Objects.equals(current.getCreatedById(), requested.getCreatedById())
            && definitionsEquivalent(current, requested);
    }

    private boolean versionMatches(WorkflowVersion version, Rule projection) {
        return version.getWorkspaceId() == projection.getWorkspaceId()
            && Objects.equals(version.getName(), projection.getName())
            && Objects.equals(version.getDescription(), projection.getDescription())
            && Objects.equals(version.getRecordType(), projection.getRecordType())
            && Objects.equals(version.getTriggerType(), projection.getTriggerType())
            && Objects.equals(version.getExecutionMode(), projection.getExecutionMode())
            && Objects.equals(version.getRunAsUserId(), projection.getRunAsUserId())
            && Objects.equals(version.getCreatedById(), projection.getCreatedById())
            && definitionsEquivalent(version, projection);
    }

    private boolean ruleMatches(Rule rule, Rule projection) {
        return ruleMatchesIgnoringPrincipals(rule, projection)
            && Objects.equals(rule.getRunAsUserId(), projection.getRunAsUserId())
            && Objects.equals(rule.getCreatedById(), projection.getCreatedById());
    }

    private boolean ruleMatchesIgnoringPrincipals(Rule rule, Rule projection) {
        return rule.getId() == projection.getId()
            && rule.getWorkspaceId() == projection.getWorkspaceId()
            && rule.isEnabled() == projection.isEnabled()
            && Objects.equals(rule.getName(), projection.getName())
            && Objects.equals(rule.getDescription(), projection.getDescription())
            && Objects.equals(rule.getRecordType(), projection.getRecordType())
            && Objects.equals(rule.getTriggerType(), projection.getTriggerType())
            && Objects.equals(rule.getExecutionMode(), projection.getExecutionMode())
            && definitionsEquivalent(rule, projection);
    }

    private boolean ruleMatchesRedactedPrincipals(Rule rule, Rule projection) {
        return ruleMatchesIgnoringPrincipals(rule, projection)
            && redactedPrincipalMatches(
                rule.getRunAsUserId(), projection.getRunAsUserId(), true)
            && redactedPrincipalMatches(
                rule.getCreatedById(), projection.getCreatedById(), true);
    }

    private static boolean redactedPrincipalMatches(
            Integer mutableId, Integer immutableId, boolean allowRedactedPrincipal) {
        return Objects.equals(mutableId, immutableId)
            || allowRedactedPrincipal && mutableId == null;
    }

    private boolean definitionsEquivalent(WorkflowVersion version, Rule rule) {
        return definitionsEquivalent(
            version.getTriggerConfig(),
            version.getConditionJson(),
            version.getActionsJson(),
            rule.getTriggerConfig(),
            rule.getConditionJson(),
            rule.getActionsJson());
    }

    private boolean definitionsEquivalent(Rule first, Rule second) {
        return definitionsEquivalent(
            first.getTriggerConfig(),
            first.getConditionJson(),
            first.getActionsJson(),
            second.getTriggerConfig(),
            second.getConditionJson(),
            second.getActionsJson());
    }

    private boolean definitionsEquivalent(
            String firstTriggerJson,
            String firstConditionJson,
            String firstActionsJson,
            String secondTriggerJson,
            String secondConditionJson,
            String secondActionsJson) {
        RuleTrigger firstTrigger = definitionCodec.parse(firstTriggerJson, RuleTrigger.class);
        RuleTrigger secondTrigger = definitionCodec.parse(secondTriggerJson, RuleTrigger.class);
        if (!triggersEquivalent(firstTrigger, secondTrigger)) {
            return false;
        }
        if (!conditionsEquivalent(firstConditionJson, secondConditionJson)) {
            return false;
        }
        RuleAction[] firstActions = definitionCodec.parse(firstActionsJson, RuleAction[].class);
        RuleAction[] secondActions = definitionCodec.parse(secondActionsJson, RuleAction[].class);
        if (firstActions == null
                || secondActions == null
                || firstActions.length != secondActions.length) {
            return false;
        }
        for (int index = 0; index < firstActions.length; index++) {
            if (!actionsEquivalent(firstActions[index], secondActions[index])) {
                return false;
            }
        }
        return true;
    }

    private boolean conditionsEquivalent(String firstJson, String secondJson) {
        if (firstJson == null || secondJson == null) {
            return firstJson == null && secondJson == null;
        }
        SegmentDefinition first = definitionCodec.parse(firstJson, SegmentDefinition.class);
        SegmentDefinition second = definitionCodec.parse(secondJson, SegmentDefinition.class);
        return conditionsEquivalent(first, second);
    }

    private boolean triggersEquivalent(RuleTrigger first, RuleTrigger second) {
        return first != null
            && second != null
            && Objects.equals(
                definitionValidator.normalize(first.getType()),
                definitionValidator.normalize(second.getType()))
            && Objects.equals(first.getEvents(), second.getEvents())
            && Objects.equals(first.getTargetStageId(), second.getTargetStageId())
            && Objects.equals(first.getThrottleMinutes(), second.getThrottleMinutes())
            && Objects.equals(
                definitionValidator.normalize(first.getCadence()),
                definitionValidator.normalize(second.getCadence()));
    }

    private boolean actionsEquivalent(RuleAction first, RuleAction second) {
        return first != null
            && second != null
            && Objects.equals(
                definitionValidator.normalize(first.getType()),
                definitionValidator.normalize(second.getType()))
            && Objects.equals(first.getTitle(), second.getTitle())
            && Objects.equals(first.getBody(), second.getBody())
            && Objects.equals(first.getActivityType(), second.getActivityType())
            && Objects.equals(first.getTagId(), second.getTagId())
            && Objects.equals(first.getDueInDays(), second.getDueInDays())
            && Objects.equals(first.getDueInHours(), second.getDueInHours())
            && Objects.equals(first.getSeverity(), second.getSeverity())
            && Objects.equals(first.getTargetUserId(), second.getTargetUserId())
            && Objects.equals(first.getTargetStageId(), second.getTargetStageId());
    }

    private boolean conditionsEquivalent(SegmentDefinition first, SegmentDefinition second) {
        if (first == null || second == null) {
            return first == null && second == null;
        }
        if (!Objects.equals(
                definitionValidator.normalize(first.getMatch()),
                definitionValidator.normalize(second.getMatch()))
                || first.isNegate() != second.isNegate()) {
            return false;
        }
        List<SegmentCondition> firstConditions =
            first.getConditions() == null ? List.of() : first.getConditions();
        List<SegmentCondition> secondConditions =
            second.getConditions() == null ? List.of() : second.getConditions();
        if (firstConditions.size() != secondConditions.size()) {
            return false;
        }
        for (int index = 0; index < firstConditions.size(); index++) {
            if (!conditionsEquivalent(firstConditions.get(index), secondConditions.get(index))) {
                return false;
            }
        }
        List<SegmentDefinition> firstGroups = first.getGroups() == null ? List.of() : first.getGroups();
        List<SegmentDefinition> secondGroups = second.getGroups() == null ? List.of() : second.getGroups();
        if (firstGroups.size() != secondGroups.size()) {
            return false;
        }
        for (int index = 0; index < firstGroups.size(); index++) {
            if (!conditionsEquivalent(firstGroups.get(index), secondGroups.get(index))) {
                return false;
            }
        }
        return true;
    }

    private boolean conditionsEquivalent(
            SegmentCondition first,
            SegmentCondition second) {
        return first != null
            && second != null
            && Objects.equals(
                definitionValidator.normalize(first.getType()),
                definitionValidator.normalize(second.getType()))
            && Objects.equals(
                definitionValidator.normalize(first.getKey()),
                definitionValidator.normalize(second.getKey()))
            && Objects.equals(first.getDays(), second.getDays())
            && Objects.equals(
                definitionValidator.normalize(first.getField()),
                definitionValidator.normalize(second.getField()))
            && Objects.equals(
                definitionValidator.normalize(first.getOp()),
                definitionValidator.normalize(second.getOp()))
            && Objects.equals(first.getValue(), second.getValue())
            && Objects.equals(first.getValues(), second.getValues())
            && first.isNegate() == second.isNegate();
    }

    private static TreeSet<Integer> principalIds(
            Rule rule, Workflow workflow, Collection<WorkflowVersion> versions) {
        TreeSet<Integer> ids = new TreeSet<>();
        addPrincipal(ids, rule.getCreatedById());
        addPrincipal(ids, rule.getRunAsUserId());
        addPrincipal(ids, workflow.getCreatedById());
        addPrincipal(ids, workflow.getUpdatedById());
        addPrincipal(ids, workflow.getDraftRunAsUserId());
        for (WorkflowVersion version : versions) {
            addPrincipal(ids, version.getCreatedById());
            addPrincipal(ids, version.getPublishedById());
            addPrincipal(ids, version.getRunAsUserId());
        }
        return ids;
    }

    private static TreeSet<Integer> mutablePrincipalIds(Rule rule, Workflow workflow) {
        TreeSet<Integer> ids = new TreeSet<>();
        addPrincipal(ids, rule.getCreatedById());
        addPrincipal(ids, rule.getRunAsUserId());
        addPrincipal(ids, workflow.getCreatedById());
        addPrincipal(ids, workflow.getUpdatedById());
        addPrincipal(ids, workflow.getDraftRunAsUserId());
        return ids;
    }

    private static TreeSet<Integer> versionPrincipalIds(
            Collection<WorkflowVersion> versions) {
        TreeSet<Integer> ids = new TreeSet<>();
        for (WorkflowVersion version : versions) {
            addPrincipal(ids, version.getCreatedById());
            addPrincipal(ids, version.getPublishedById());
            addPrincipal(ids, version.getRunAsUserId());
        }
        return ids;
    }

    private static void addPrincipal(Set<Integer> ids, Integer userId) {
        if (userId != null) {
            ids.add(userId);
        }
    }

    private static WorkflowVersion latestVersion(Collection<WorkflowVersion> versions) {
        WorkflowVersion latest = null;
        for (WorkflowVersion version : versions) {
            if (latest == null
                    || version.getVersionNumber() > latest.getVersionNumber()
                    || version.getVersionNumber() == latest.getVersionNumber()
                        && version.getId() > latest.getId()) {
                latest = version;
            }
        }
        return latest;
    }

    private Rule requirePersistedRule(int workspaceId, int ruleId) {
        Rule rule = ruleMapper.getById(workspaceId, ruleId);
        if (rule == null || rule.getId() != ruleId || rule.getWorkspaceId() != workspaceId) {
            throw new IllegalStateException("Rule persistence was not synchronized");
        }
        return rule;
    }

    private static boolean sameRule(Rule expected, Rule current) {
        return current != null
            && expected.getId() == current.getId()
            && expected.getWorkspaceId() == current.getWorkspaceId()
            && expected.isEnabled() == current.isEnabled()
            && Objects.equals(expected.getName(), current.getName())
            && Objects.equals(expected.getDescription(), current.getDescription())
            && Objects.equals(expected.getRecordType(), current.getRecordType())
            && Objects.equals(expected.getTriggerType(), current.getTriggerType())
            && Objects.equals(expected.getTriggerConfig(), current.getTriggerConfig())
            && Objects.equals(expected.getConditionJson(), current.getConditionJson())
            && Objects.equals(expected.getActionsJson(), current.getActionsJson())
            && Objects.equals(expected.getExecutionMode(), current.getExecutionMode())
            && Objects.equals(expected.getRunAsUserId(), current.getRunAsUserId())
            && Objects.equals(expected.getCreatedById(), current.getCreatedById());
    }

    private static boolean sameWorkflow(Workflow expected, Workflow current) {
        return current != null
            && expected.getId() == current.getId()
            && expected.getWorkspaceId() == current.getWorkspaceId()
            && Objects.equals(expected.getLegacyRuleId(), current.getLegacyRuleId())
            && Objects.equals(expected.getName(), current.getName())
            && Objects.equals(expected.getDescription(), current.getDescription())
            && expected.isEnabled() == current.isEnabled()
            && Objects.equals(expected.getRuntimeOwner(), current.getRuntimeOwner())
            && Objects.equals(expected.getArchivedAt(), current.getArchivedAt())
            && expected.getDraftRevision() == current.getDraftRevision()
            && Objects.equals(expected.getDraftRecordType(), current.getDraftRecordType())
            && Objects.equals(expected.getDraftExecutionMode(), current.getDraftExecutionMode())
            && Objects.equals(expected.getDraftRunAsUserId(), current.getDraftRunAsUserId())
            && Objects.equals(expected.getDraftDefinitionJson(), current.getDraftDefinitionJson())
            && Objects.equals(expected.getDraftCanvasJson(), current.getDraftCanvasJson())
            && Objects.equals(expected.getActiveVersionId(), current.getActiveVersionId())
            && Objects.equals(expected.getCreatedById(), current.getCreatedById())
            && Objects.equals(expected.getUpdatedById(), current.getUpdatedById());
    }

    private static boolean sameVersion(WorkflowVersion expected, WorkflowVersion current) {
        return current != null
            && expected.getId() == current.getId()
            && expected.getWorkspaceId() == current.getWorkspaceId()
            && expected.getWorkflowId() == current.getWorkflowId()
            && expected.getVersionNumber() == current.getVersionNumber()
            && Objects.equals(expected.getName(), current.getName())
            && Objects.equals(expected.getDescription(), current.getDescription())
            && Objects.equals(expected.getRecordType(), current.getRecordType())
            && Objects.equals(expected.getTriggerType(), current.getTriggerType())
            && Objects.equals(expected.getTriggerConfig(), current.getTriggerConfig())
            && Objects.equals(expected.getConditionJson(), current.getConditionJson())
            && Objects.equals(expected.getActionsJson(), current.getActionsJson())
            && Objects.equals(expected.getExecutionMode(), current.getExecutionMode())
            && Objects.equals(expected.getRunAsUserId(), current.getRunAsUserId())
            && Objects.equals(expected.getCreatedById(), current.getCreatedById())
            && Objects.equals(expected.getPublishedById(), current.getPublishedById())
            && Objects.equals(expected.getDefinitionJson(), current.getDefinitionJson())
            && Objects.equals(expected.getCanvasJson(), current.getCanvasJson())
            && Arrays.equals(expected.getDefinitionHash(), current.getDefinitionHash());
    }

    private static boolean hashesEqual(byte[] first, byte[] second) {
        return first != null && second != null && MessageDigest.isEqual(first, second);
    }

    private static ResourceNotFoundException ruleNotFound(int ruleId) {
        return new ResourceNotFoundException("Rule not found with id: " + ruleId);
    }

    private static ConflictException inconsistentAggregate() {
        return new ConflictException("Rule workflow state is inconsistent");
    }

    private static void requireLegacyMutable(Workflow workflow) {
        if (workflow.getArchivedAt() != null) {
            throw new ConflictException("Archived workflow cannot be changed through the rule API");
        }
        requireLegacyOwner(workflow);
    }

    private static void requireLegacyOwner(Workflow workflow) {
        if (!"legacy".equals(workflow.getRuntimeOwner())) {
            throw new ConflictException(
                "Canonical-owned workflow cannot be changed through the rule API");
        }
    }

    private record LegacySnapshot(Rule projection, CanonicalDraft draft) { }

    private record AggregateDiscovery(
        Rule rule,
        Workflow workflow,
        Map<Long, WorkflowVersion> versions,
        WorkflowVersion activeVersion,
        Set<Integer> principalIds) { }

    private record LockedAggregate(
        Rule rule,
        Workflow workflow,
        Map<Long, WorkflowVersion> versions,
        WorkflowVersion activeVersion) { }
}
