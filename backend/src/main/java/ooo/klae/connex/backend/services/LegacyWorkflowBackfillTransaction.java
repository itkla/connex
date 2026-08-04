package ooo.klae.connex.backend.services;

import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import ooo.klae.connex.backend.beans.Rule;
import ooo.klae.connex.backend.beans.Workflow;
import ooo.klae.connex.backend.beans.WorkflowVersion;
import ooo.klae.connex.backend.dto.RuleAction;
import ooo.klae.connex.backend.dto.RuleTrigger;
import ooo.klae.connex.backend.dto.SegmentDefinition;
import ooo.klae.connex.backend.mappers.RuleMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WorkflowMapper;
import ooo.klae.connex.backend.mappers.WorkflowVersionMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.services.LegacyWorkflowGraphConverter.ConvertedWorkflow;
import ooo.klae.connex.backend.services.WorkflowDraftCanonicalizer.CanonicalDraft;

/** Atomically creates or verifies legacy-rule workflow pairs for one pinned workspace catalog. */
@Service
@RequiredArgsConstructor
public class LegacyWorkflowBackfillTransaction {

    private final RuleMapper ruleMapper;
    private final WorkflowMapper workflowMapper;
    private final WorkflowVersionMapper workflowVersionMapper;
    private final UserMapper userMapper;
    private final WorkspaceMapper workspaceMapper;
    private final LegacyWorkflowGraphConverter graphConverter;
    private final WorkflowDraftCanonicalizer canonicalizer;
    private final RuleDefinitionCodec definitionCodec;

    /** Backfills one workspace inside the catalog scope installed by the startup runner. */
    @Transactional
    public void backfillWorkspace(String catalog, int workspaceId) {
        List<BackfillCandidate> candidates = discover(catalog, workspaceId);
        int firstRuleId = candidates.isEmpty() ? 0 : candidates.getFirst().rule().getId();
        TreeSet<Integer> discoveredPrincipalIds = principalIds(candidates);
        TreeSet<Integer> requiredPrincipalIds = requiredPrincipalIds(candidates);
        for (int userId : discoveredPrincipalIds) {
            if (userMapper.lockById(userId) == null && requiredPrincipalIds.contains(userId)) {
                throw failure(catalog, workspaceId, firstRuleId);
            }
        }
        if (workspaceMapper.lockWorkspace(workspaceId) == null) {
            throw failure(catalog, workspaceId, firstRuleId);
        }
        refreshAndLockWorkflows(catalog, workspaceId, candidates);
        lockVersions(catalog, workspaceId, candidates);
        Map<Integer, Rule> lockedRules = lockRules(catalog, workspaceId, candidates);
        requireLockedPrincipals(
            catalog, workspaceId, candidates, lockedRules, discoveredPrincipalIds);
        for (BackfillCandidate candidate : candidates) {
            Rule rule = lockedRules.get(candidate.rule().getId());
            try {
                Workflow workflow = candidate.lockedWorkflow();
                disableIfOperationalIdentityWasRedacted(rule, workflow);
                backfillRule(rule, workflow, candidate.lockedVersion());
            } catch (RuntimeException exception) {
                throw failure(catalog, workspaceId, candidate.rule().getId(), exception);
            }
        }
        requireComplete(catalog, workspaceId, List.copyOf(lockedRules.values()));
    }

    private List<BackfillCandidate> discover(String catalog, int workspaceId) {
        List<Rule> rules = ruleMapper.getByWorkspace(workspaceId).stream()
            .sorted(Comparator.nullsFirst(Comparator.comparingInt(Rule::getId)))
            .toList();
        Map<Integer, BackfillCandidate> candidates = new LinkedHashMap<>();
        for (Rule rule : rules) {
            int ruleId = rule == null ? 0 : rule.getId();
            if (rule == null || ruleId <= 0 || rule.getWorkspaceId() != workspaceId) {
                throw failure(catalog, workspaceId, ruleId);
            }
            Workflow workflow = workflowMapper.getByLegacyRuleId(workspaceId, ruleId);
            WorkflowVersion active = null;
            if (workflow != null) {
                Long activeVersionId = workflow.getActiveVersionId();
                if (workflow.getId() <= 0
                        || workflow.getWorkspaceId() != workspaceId
                        || !Objects.equals(workflow.getLegacyRuleId(), ruleId)
                        || activeVersionId == null) {
                    throw failure(catalog, workspaceId, ruleId);
                }
                active = workflowVersionMapper.getById(
                    workspaceId, workflow.getId(), activeVersionId);
                if (active == null
                        || active.getId() != activeVersionId
                        || active.getWorkspaceId() != workspaceId
                        || active.getWorkflowId() != workflow.getId()
                        || active.getVersionNumber() <= 0) {
                    throw failure(catalog, workspaceId, ruleId);
                }
            }
            if (candidates.put(
                    ruleId, new BackfillCandidate(rule, workflow, active)) != null) {
                throw failure(catalog, workspaceId, ruleId);
            }
        }
        return List.copyOf(candidates.values());
    }

    private void refreshAndLockWorkflows(
            String catalog, int workspaceId, List<BackfillCandidate> candidates) {
        for (BackfillCandidate candidate : candidates) {
            Workflow current = workflowMapper.getByLegacyRuleIdForUpdate(
                workspaceId, candidate.rule().getId());
            if (current == null) {
                if (candidate.workflow() != null) {
                    throw failure(catalog, workspaceId, candidate.rule().getId());
                }
                continue;
            }
            if (current.getId() <= 0
                    || current.getWorkspaceId() != workspaceId
                    || !Objects.equals(current.getLegacyRuleId(), candidate.rule().getId())
                    || current.getActiveVersionId() == null) {
                throw failure(catalog, workspaceId, candidate.rule().getId());
            }
            candidate.lockedWorkflow(current);
        }
    }

    private void lockVersions(
            String catalog, int workspaceId, List<BackfillCandidate> candidates) {
        candidates.stream()
            .filter(candidate -> candidate.lockedWorkflow() != null)
            .sorted(Comparator.comparingLong(
                candidate -> candidate.lockedWorkflow().getActiveVersionId()))
            .forEach(candidate -> {
                Workflow workflow = candidate.lockedWorkflow();
                long activeVersionId = workflow.getActiveVersionId();
                WorkflowVersion current = workflowVersionMapper.getByIdForUpdate(
                    workspaceId,
                    workflow.getId(),
                    activeVersionId);
                if (current == null
                        || current.getId() != activeVersionId
                        || current.getWorkspaceId() != workspaceId
                        || current.getWorkflowId() != workflow.getId()
                        || current.getVersionNumber() <= 0) {
                    throw failure(catalog, workspaceId, candidate.rule().getId());
                }
                candidate.lockedVersion(current);
            });
    }

    private void requireLockedPrincipals(
            String catalog,
            int workspaceId,
            List<BackfillCandidate> candidates,
            Map<Integer, Rule> lockedRules,
            TreeSet<Integer> lockedPrincipalIds) {
        TreeSet<Integer> currentPrincipalIds = new TreeSet<>();
        for (BackfillCandidate candidate : candidates) {
            addWorkflowPrincipals(currentPrincipalIds, candidate.lockedWorkflow());
            addVersionPrincipals(currentPrincipalIds, candidate.lockedVersion());
        }
        for (Rule rule : lockedRules.values()) {
            addPrincipal(currentPrincipalIds, rule.getCreatedById());
            addPrincipal(currentPrincipalIds, rule.getRunAsUserId());
        }
        if (!lockedPrincipalIds.containsAll(currentPrincipalIds)) {
            throw failure(catalog, workspaceId, candidates.getFirst().rule().getId());
        }
    }

    private Map<Integer, Rule> lockRules(
            String catalog, int workspaceId, List<BackfillCandidate> candidates) {
        Map<Integer, Rule> locked = new LinkedHashMap<>();
        for (BackfillCandidate candidate : candidates) {
            Rule current = ruleMapper.getByIdForUpdate(workspaceId, candidate.rule().getId());
            if (current == null
                    || current.getId() != candidate.rule().getId()
                    || current.getWorkspaceId() != workspaceId) {
                throw failure(catalog, workspaceId, candidate.rule().getId());
            }
            locked.put(current.getId(), current);
        }
        List<Rule> completeLock = ruleMapper.getByWorkspaceForUpdate(workspaceId);
        if (completeLock.size() != locked.size()) {
            int ruleId = completeLock.isEmpty() ? 0 : completeLock.getFirst().getId();
            throw failure(catalog, workspaceId, ruleId);
        }
        for (Rule rule : completeLock) {
            if (!sameRule(locked.get(rule.getId()), rule)) {
                throw failure(catalog, workspaceId, rule.getId());
            }
        }
        return locked;
    }

    private void disableIfOperationalIdentityWasRedacted(Rule rule, Workflow workflow) {
        boolean missingUserPrincipal = "user".equals(rule.getExecutionMode())
            && rule.getRunAsUserId() == null;
        boolean missingSystemPrincipal = "system".equals(rule.getExecutionMode())
            && rule.getCreatedById() == null;
        if (!rule.isEnabled() || !missingUserPrincipal && !missingSystemPrincipal) {
            return;
        }
        if (ruleMapper.updateEnabled(rule.getWorkspaceId(), rule.getId(), false) != 1) {
            throw new IllegalStateException();
        }
        rule.setEnabled(false);
        if (workflow != null && workflow.isEnabled()) {
            if (workflowMapper.disableForOffboarding(
                    workflow.getWorkspaceId(), workflow.getId()) != 1) {
                throw new IllegalStateException();
            }
            workflow.setEnabled(false);
        }
    }

    private void backfillRule(
            Rule rule, Workflow existing, WorkflowVersion active) {
        Snapshot expected = snapshot(rule);
        if (existing == null) {
            insert(expected);
            return;
        }
        requireEquivalent(existing, active, rule);
    }

    private Snapshot snapshot(Rule rule) {
        ConvertedWorkflow converted = graphConverter.convert(rule);
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
            converted.description(),
            converted.enabled(),
            draft.recordType(),
            draft.executionMode(),
            converted.runAsUserId(),
            converted.createdById(),
            converted.definition(),
            converted.canvas());
        Rule projection = graphConverter.project(normalized);
        requireSourceEquivalent(rule, projection);
        return new Snapshot(projection, draft);
    }

    private void insert(Snapshot expected) {
        Rule projection = expected.projection();
        CanonicalDraft draft = expected.draft();

        Workflow workflow = new Workflow();
        workflow.setWorkspaceId(projection.getWorkspaceId());
        workflow.setLegacyRuleId(projection.getId());
        workflow.setName(projection.getName());
        workflow.setDescription(projection.getDescription());
        workflow.setEnabled(false);
        workflow.setDraftRevision(1);
        workflow.setDraftRecordType(projection.getRecordType());
        workflow.setDraftExecutionMode(projection.getExecutionMode());
        workflow.setDraftRunAsUserId(projection.getRunAsUserId());
        workflow.setDraftDefinitionJson(draft.definitionJson());
        workflow.setDraftCanvasJson(draft.canvasJson());
        workflow.setActiveVersionId(null);
        workflow.setCreatedById(projection.getCreatedById());
        workflow.setUpdatedById(null);
        workflowMapper.insert(workflow);
        if (workflow.getId() <= 0) {
            throw new IllegalStateException();
        }

        WorkflowVersion version = new WorkflowVersion();
        version.setWorkspaceId(projection.getWorkspaceId());
        version.setWorkflowId(workflow.getId());
        version.setVersionNumber(1);
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
        version.setPublishedById(null);
        version.setDefinitionJson(draft.definitionJson());
        version.setCanvasJson(draft.canvasJson());
        version.setDefinitionHash(draft.definitionHash());
        workflowVersionMapper.insert(version);
        if (version.getId() <= 0) {
            throw new IllegalStateException();
        }

        int activated = workflowMapper.updateActiveVersion(
            projection.getWorkspaceId(), workflow.getId(), version.getId(), null);
        if (activated != 1) {
            throw new IllegalStateException();
        }
        if (projection.isEnabled()) {
            int enabled = workflowMapper.updateLifecycle(
                projection.getWorkspaceId(), workflow.getId(), true, null);
            if (enabled != 1) {
                throw new IllegalStateException();
            }
        }
    }

    private void requireEquivalent(
            Workflow workflow, WorkflowVersion active, Rule source) {
        Long activeVersionId = workflow.getActiveVersionId();
        if (workflow.getId() <= 0
                || workflow.getWorkspaceId() != source.getWorkspaceId()
                || !Objects.equals(workflow.getLegacyRuleId(), source.getId())
                || workflow.isEnabled() != source.isEnabled()
                || activeVersionId == null) {
            throw new IllegalStateException();
        }
        if (active == null
                || active.getId() <= 0
                || active.getId() != activeVersionId
                || active.getWorkspaceId() != source.getWorkspaceId()
                || active.getWorkflowId() != workflow.getId()
                || active.getVersionNumber() <= 0
                || !redactedPrincipalMatches(
                    workflow.getCreatedById(), active.getCreatedById(), !workflow.isEnabled())) {
            throw new IllegalStateException();
        }
        CanonicalDraft canonical = canonicalizer.canonicalizeDraftJson(
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
            throw new IllegalStateException();
        }
        ConvertedWorkflow stored = new ConvertedWorkflow(
            source.getId(),
            source.getWorkspaceId(),
            active.getName(),
            active.getDescription(),
            workflow.isEnabled(),
            active.getRecordType(),
            active.getExecutionMode(),
            active.getRunAsUserId(),
            active.getCreatedById(),
            canonicalizer.parseDefinition(canonical.definitionJson()),
            canonicalizer.parseCanvas(canonical.canvasJson()));
        Rule projection = graphConverter.project(stored);
        if (!runtimeEquivalent(active, projection)) {
            throw new IllegalStateException();
        }
        requireSourceEquivalent(source, projection, !workflow.isEnabled());
    }

    private boolean runtimeEquivalent(WorkflowVersion active, Rule expected) {
        if (!Objects.equals(active.getName(), expected.getName())
                || !Objects.equals(active.getDescription(), expected.getDescription())
                || !Objects.equals(active.getRecordType(), expected.getRecordType())
                || !Objects.equals(active.getTriggerType(), expected.getTriggerType())
                || !Objects.equals(active.getExecutionMode(), expected.getExecutionMode())
                || !Objects.equals(active.getRunAsUserId(), expected.getRunAsUserId())
                || !Objects.equals(active.getCreatedById(), expected.getCreatedById())) {
            return false;
        }
        return definitionsEquivalent(
            active.getTriggerConfig(),
            active.getConditionJson(),
            active.getActionsJson(),
            expected.getTriggerConfig(),
            expected.getConditionJson(),
            expected.getActionsJson());
    }

    private void requireSourceEquivalent(Rule source, Rule projection) {
        requireSourceEquivalent(source, projection, false);
    }

    private void requireSourceEquivalent(
            Rule source, Rule projection, boolean allowRedactedPrincipals) {
        if (source.getId() != projection.getId()
                || source.getWorkspaceId() != projection.getWorkspaceId()
                || !Objects.equals(source.getName(), projection.getName())
                || !Objects.equals(source.getDescription(), projection.getDescription())
                || source.isEnabled() != projection.isEnabled()
                || !Objects.equals(source.getRecordType(), projection.getRecordType())
                || !Objects.equals(source.getTriggerType(), projection.getTriggerType())
                || !Objects.equals(source.getExecutionMode(), projection.getExecutionMode())
                || !redactedPrincipalMatches(
                    source.getRunAsUserId(),
                    projection.getRunAsUserId(),
                    allowRedactedPrincipals)
                || !redactedPrincipalMatches(
                    source.getCreatedById(),
                    projection.getCreatedById(),
                    allowRedactedPrincipals)
                || !definitionsEquivalent(
                    source.getTriggerConfig(),
                    source.getConditionJson(),
                    source.getActionsJson(),
                    projection.getTriggerConfig(),
                    projection.getConditionJson(),
                    projection.getActionsJson())) {
            throw new IllegalStateException();
        }
    }

    private static boolean redactedPrincipalMatches(
            Integer mutableId, Integer immutableId, boolean allowRedactedPrincipal) {
        return Objects.equals(mutableId, immutableId)
            || allowRedactedPrincipal && mutableId == null;
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
        if (!Objects.equals(firstTrigger, secondTrigger)) {
            return false;
        }
        if (!conditionsEquivalent(firstConditionJson, secondConditionJson)) {
            return false;
        }
        RuleAction[] firstActions = definitionCodec.parse(firstActionsJson, RuleAction[].class);
        RuleAction[] secondActions = definitionCodec.parse(secondActionsJson, RuleAction[].class);
        return Arrays.equals(firstActions, secondActions);
    }

    private boolean conditionsEquivalent(String activeJson, String expectedJson) {
        if (activeJson == null || expectedJson == null) {
            return activeJson == null && expectedJson == null;
        }
        SegmentDefinition active = definitionCodec.parse(activeJson, SegmentDefinition.class);
        SegmentDefinition expected = definitionCodec.parse(expectedJson, SegmentDefinition.class);
        return Objects.equals(active, expected);
    }

    private void requireComplete(String catalog, int workspaceId, List<Rule> lockedRules) {
        int ruleCount = ruleMapper.countByWorkspace(workspaceId);
        int linkedCount = workflowMapper.countLegacyRuleLinks(workspaceId);
        int unpairedCount = workflowMapper.countUnpairedLegacyRules(workspaceId);
        if (ruleCount == lockedRules.size() && linkedCount == ruleCount && unpairedCount == 0) {
            return;
        }
        Integer unpairedRuleId = workflowMapper.firstUnpairedLegacyRuleId(workspaceId);
        int ruleId = unpairedRuleId != null
            ? unpairedRuleId
            : lockedRules.isEmpty() ? 0 : lockedRules.getFirst().getId();
        throw failure(catalog, workspaceId, ruleId);
    }

    private static boolean hashesEqual(byte[] first, byte[] second) {
        return first != null && second != null && MessageDigest.isEqual(first, second);
    }

    private static IllegalStateException failure(String catalog, int workspaceId, int ruleId) {
        return failure(catalog, workspaceId, ruleId, null);
    }

    private static IllegalStateException failure(
            String catalog, int workspaceId, int ruleId, RuntimeException cause) {
        String catalogId = catalog == null ? "(default)" : catalog;
        return new IllegalStateException(
            "Legacy workflow backfill failed for catalog=" + catalogId
                + " workspace=" + workspaceId + " rule=" + ruleId,
            cause);
    }

    private static TreeSet<Integer> principalIds(List<BackfillCandidate> candidates) {
        TreeSet<Integer> ids = new TreeSet<>();
        for (BackfillCandidate candidate : candidates) {
            addPrincipal(ids, candidate.rule().getCreatedById());
            addPrincipal(ids, candidate.rule().getRunAsUserId());
            addWorkflowPrincipals(ids, candidate.workflow());
            addVersionPrincipals(ids, candidate.version());
        }
        return ids;
    }

    private static TreeSet<Integer> requiredPrincipalIds(List<BackfillCandidate> candidates) {
        TreeSet<Integer> ids = new TreeSet<>();
        for (BackfillCandidate candidate : candidates) {
            addPrincipal(ids, candidate.rule().getCreatedById());
            addPrincipal(ids, candidate.rule().getRunAsUserId());
            addWorkflowPrincipals(ids, candidate.workflow());
        }
        return ids;
    }

    private static void addWorkflowPrincipals(TreeSet<Integer> ids, Workflow workflow) {
        if (workflow != null) {
            addPrincipal(ids, workflow.getCreatedById());
            addPrincipal(ids, workflow.getUpdatedById());
            addPrincipal(ids, workflow.getDraftRunAsUserId());
        }
    }

    private static void addVersionPrincipals(
            TreeSet<Integer> ids, WorkflowVersion version) {
        if (version != null) {
            addPrincipal(ids, version.getCreatedById());
            addPrincipal(ids, version.getPublishedById());
            addPrincipal(ids, version.getRunAsUserId());
        }
    }

    private static void addPrincipal(TreeSet<Integer> ids, Integer userId) {
        if (userId != null) {
            ids.add(userId);
        }
    }

    private static boolean sameRule(Rule expected, Rule current) {
        return expected != null
            && current != null
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

    private record Snapshot(Rule projection, CanonicalDraft draft) { }

    private static final class BackfillCandidate {
        private final Rule rule;
        private final Workflow workflow;
        private final WorkflowVersion version;
        private Workflow lockedWorkflow;
        private WorkflowVersion lockedVersion;

        private BackfillCandidate(
                Rule rule, Workflow workflow, WorkflowVersion version) {
            this.rule = rule;
            this.workflow = workflow;
            this.version = version;
        }

        private Rule rule() {
            return rule;
        }

        private Workflow workflow() {
            return workflow;
        }

        private WorkflowVersion version() {
            return version;
        }

        private Workflow lockedWorkflow() {
            return lockedWorkflow;
        }

        private void lockedWorkflow(Workflow value) {
            lockedWorkflow = value;
        }

        private WorkflowVersion lockedVersion() {
            return lockedVersion;
        }

        private void lockedVersion(WorkflowVersion value) {
            lockedVersion = value;
        }
    }
}
