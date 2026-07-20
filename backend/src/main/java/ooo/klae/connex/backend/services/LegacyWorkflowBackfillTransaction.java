package ooo.klae.connex.backend.services;

import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

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
import ooo.klae.connex.backend.mappers.WorkflowMapper;
import ooo.klae.connex.backend.mappers.WorkflowVersionMapper;
import ooo.klae.connex.backend.services.LegacyWorkflowGraphConverter.ConvertedWorkflow;
import ooo.klae.connex.backend.services.WorkflowDraftCanonicalizer.CanonicalDraft;

/** Atomically creates or verifies legacy-rule workflow pairs for one pinned workspace catalog. */
@Service
@RequiredArgsConstructor
public class LegacyWorkflowBackfillTransaction {

    private final RuleMapper ruleMapper;
    private final WorkflowMapper workflowMapper;
    private final WorkflowVersionMapper workflowVersionMapper;
    private final LegacyWorkflowGraphConverter graphConverter;
    private final WorkflowDraftCanonicalizer canonicalizer;
    private final RuleDefinitionCodec definitionCodec;

    /** Backfills one workspace inside the catalog scope installed by the startup runner. */
    @Transactional
    public void backfillWorkspace(String catalog, int workspaceId) {
        List<Rule> rules = ruleMapper.getByWorkspaceForUpdate(workspaceId);
        for (Rule rule : rules) {
            int ruleId = rule == null ? 0 : rule.getId();
            try {
                backfillRule(workspaceId, rule);
            } catch (RuntimeException exception) {
                throw failure(catalog, workspaceId, ruleId);
            }
        }
        requireComplete(catalog, workspaceId, rules);
    }

    private void backfillRule(int workspaceId, Rule rule) {
        if (rule == null || rule.getId() <= 0 || rule.getWorkspaceId() != workspaceId) {
            throw new IllegalStateException();
        }
        Snapshot expected = snapshot(rule);
        Workflow existing = workflowMapper.getByLegacyRuleId(workspaceId, rule.getId());
        if (existing == null) {
            insert(expected);
            return;
        }
        requireEquivalent(existing, expected);
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

    private void requireEquivalent(Workflow workflow, Snapshot expected) {
        Rule projection = expected.projection();
        Long activeVersionId = workflow.getActiveVersionId();
        if (workflow.getId() <= 0
                || workflow.getWorkspaceId() != projection.getWorkspaceId()
                || !Objects.equals(workflow.getLegacyRuleId(), projection.getId())
                || workflow.isEnabled() != projection.isEnabled()
                || activeVersionId == null) {
            throw new IllegalStateException();
        }
        WorkflowVersion active = workflowVersionMapper.getById(
            projection.getWorkspaceId(), workflow.getId(), activeVersionId);
        if (active == null
                || active.getId() <= 0
                || active.getId() != activeVersionId
                || active.getWorkspaceId() != projection.getWorkspaceId()
                || active.getWorkflowId() != workflow.getId()
                || active.getVersionNumber() <= 0
                || !runtimeEquivalent(active, projection)
                || !Objects.equals(active.getDefinitionJson(), expected.draft().definitionJson())
                || !Objects.equals(active.getCanvasJson(), expected.draft().canvasJson())
                || !hashesEqual(active.getDefinitionHash(), expected.draft().definitionHash())) {
            throw new IllegalStateException();
        }
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
        if (source.getId() != projection.getId()
                || source.getWorkspaceId() != projection.getWorkspaceId()
                || !Objects.equals(source.getName(), projection.getName())
                || !Objects.equals(source.getDescription(), projection.getDescription())
                || source.isEnabled() != projection.isEnabled()
                || !Objects.equals(source.getRecordType(), projection.getRecordType())
                || !Objects.equals(source.getTriggerType(), projection.getTriggerType())
                || !Objects.equals(source.getExecutionMode(), projection.getExecutionMode())
                || !Objects.equals(source.getRunAsUserId(), projection.getRunAsUserId())
                || !Objects.equals(source.getCreatedById(), projection.getCreatedById())
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
        String catalogId = catalog == null ? "(default)" : catalog;
        return new IllegalStateException(
            "Legacy workflow backfill failed for catalog=" + catalogId
                + " workspace=" + workspaceId + " rule=" + ruleId);
    }

    private record Snapshot(Rule projection, CanonicalDraft draft) { }
}
