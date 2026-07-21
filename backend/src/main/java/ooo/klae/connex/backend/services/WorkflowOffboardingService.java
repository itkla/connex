package ooo.klae.connex.backend.services;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.beans.Rule;
import ooo.klae.connex.backend.beans.Workflow;
import ooo.klae.connex.backend.beans.WorkflowVersion;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.mappers.RuleMapper;
import ooo.klae.connex.backend.mappers.WorkflowMapper;
import ooo.klae.connex.backend.mappers.WorkflowVersionMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;

/** Locks, disables, and redacts workflow identities during permanent account deletion. */
@Service
@RequiredArgsConstructor
public class WorkflowOffboardingService {

    private static final Comparator<Workflow> WORKFLOW_ORDER = Comparator
        .comparingInt(Workflow::getWorkspaceId)
        .thenComparingInt(Workflow::getId);
    private static final Comparator<WorkflowVersion> VERSION_ORDER = Comparator
        .comparingInt(WorkflowVersion::getWorkspaceId)
        .thenComparingInt(WorkflowVersion::getWorkflowId)
        .thenComparingLong(WorkflowVersion::getId);
    private static final Comparator<Rule> RULE_ORDER = Comparator
        .comparingInt(Rule::getWorkspaceId)
        .thenComparingInt(Rule::getId);

    private final WorkflowMapper workflowMapper;
    private final WorkflowVersionMapper workflowVersionMapper;
    private final RuleMapper ruleMapper;
    private final WorkspaceMapper workspaceMapper;

    /** Discovers identity-bound workflow roots without taking row locks. */
    public OffboardingPlan discover(int userId) {
        return new OffboardingPlan(
            workflowMapper.findAffectedByUserAnywhere(userId),
            workflowVersionMapper.findLockCandidatesByUserAnywhere(userId),
            ruleMapper.findLockCandidatesByUserAnywhere(userId));
    }

    /** Locks every candidate workspace root in ascending id order. */
    public void lockWorkspaceRoots(OffboardingPlan plan) {
        for (int workspaceId : plan.workspaceIds()) {
            if (workspaceMapper.lockWorkspaceForShare(workspaceId) == null) {
                throw new ConflictException("Workflow workspace changed during account offboarding");
            }
        }
    }

    /** Locks exact workflow, version, and rule roots before disabling and redacting references. */
    public void offboard(int userId, OffboardingPlan plan) {
        Map<WorkflowKey, Workflow> workflows = lockWorkflows(plan.workflows());
        Map<VersionKey, WorkflowVersion> versions = lockVersions(plan.versions());
        Map<RuleKey, Rule> rules = lockRules(plan.rules());
        revalidatePlan(userId, plan, workflows, versions, rules);

        TreeSet<RuleKey> rulesToDisable = new TreeSet<>();
        for (Map.Entry<WorkflowKey, Workflow> entry : workflows.entrySet()) {
            Workflow workflow = entry.getValue();
            Rule linkedRule = linkedRule(workflow, rules);
            WorkflowVersion activeVersion = activeVersion(workflow, versions);
            if (creatorOrRunAsAffected(userId, workflow, activeVersion, linkedRule)) {
                if (workflow.isEnabled()
                        && workflowMapper.disableForOffboarding(
                            workflow.getWorkspaceId(), workflow.getId()) != 1) {
                    throw new ConflictException("Workflow changed during account offboarding");
                }
                if (linkedRule != null) {
                    rulesToDisable.add(new RuleKey(linkedRule.getWorkspaceId(), linkedRule.getId()));
                }
            }
        }
        for (Map.Entry<RuleKey, Rule> entry : rules.entrySet()) {
            if (ruleDirectlyAffected(userId, entry.getValue())) {
                rulesToDisable.add(entry.getKey());
            }
        }
        for (RuleKey key : rulesToDisable) {
            Rule rule = rules.get(key);
            if (rule != null && rule.isEnabled()
                    && ruleMapper.updateEnabled(key.workspaceId(), key.ruleId(), false) != 1) {
                throw new ConflictException("Workflow rule changed during account offboarding");
            }
        }

        workflows.values().forEach(workflow -> workflowMapper.redactUserReferences(
            workflow.getWorkspaceId(), workflow.getId(), userId));
        versions.values().forEach(version -> workflowVersionMapper.redactUserReferences(
            version.getWorkspaceId(), version.getWorkflowId(), version.getId(), userId));
        rules.values().forEach(rule -> ruleMapper.redactUserReferences(
            rule.getWorkspaceId(), rule.getId(), userId));
    }

    private Map<WorkflowKey, Workflow> lockWorkflows(List<Workflow> discovered) {
        Map<WorkflowKey, Workflow> locked = new LinkedHashMap<>();
        for (Workflow candidate : discovered) {
            Workflow current = workflowMapper.getByIdForUpdate(
                candidate.getWorkspaceId(), candidate.getId());
            if (current == null) {
                throw new ConflictException("Workflow changed during account offboarding");
            }
            WorkflowKey key = new WorkflowKey(current.getWorkspaceId(), current.getId());
            if (!key.equals(new WorkflowKey(candidate.getWorkspaceId(), candidate.getId()))) {
                throw new ConflictException("Workflow changed during account offboarding");
            }
            locked.put(key, current);
        }
        return locked;
    }

    private Map<VersionKey, WorkflowVersion> lockVersions(List<WorkflowVersion> discovered) {
        Map<VersionKey, WorkflowVersion> locked = new LinkedHashMap<>();
        for (WorkflowVersion candidate : discovered) {
            WorkflowVersion current = workflowVersionMapper.getByIdForUpdate(
                candidate.getWorkspaceId(), candidate.getWorkflowId(), candidate.getId());
            if (current == null) {
                throw new ConflictException("Workflow version changed during account offboarding");
            }
            VersionKey key = new VersionKey(
                current.getWorkspaceId(), current.getWorkflowId(), current.getId());
            VersionKey expected = new VersionKey(
                candidate.getWorkspaceId(), candidate.getWorkflowId(), candidate.getId());
            if (!key.equals(expected)) {
                throw new ConflictException("Workflow version changed during account offboarding");
            }
            locked.put(key, current);
        }
        return locked;
    }

    private Map<RuleKey, Rule> lockRules(List<Rule> discovered) {
        Map<RuleKey, Rule> locked = new LinkedHashMap<>();
        for (Rule candidate : discovered) {
            Rule current = ruleMapper.getByIdForUpdate(candidate.getWorkspaceId(), candidate.getId());
            if (current == null) {
                throw new ConflictException("Workflow rule changed during account offboarding");
            }
            RuleKey key = new RuleKey(current.getWorkspaceId(), current.getId());
            if (!key.equals(new RuleKey(candidate.getWorkspaceId(), candidate.getId()))) {
                throw new ConflictException("Workflow rule changed during account offboarding");
            }
            locked.put(key, current);
        }
        return locked;
    }

    private void revalidatePlan(
            int userId,
            OffboardingPlan plan,
            Map<WorkflowKey, Workflow> workflows,
            Map<VersionKey, WorkflowVersion> versions,
            Map<RuleKey, Rule> rules) {
        for (Workflow candidate : plan.workflows()) {
            Workflow current = workflows.get(
                new WorkflowKey(candidate.getWorkspaceId(), candidate.getId()));
            if (current == null || !workflowAffected(userId, current, versions, rules)) {
                throw new ConflictException("Workflow principal references changed during account offboarding");
            }
        }
        for (WorkflowVersion candidate : plan.versions()) {
            VersionKey key = new VersionKey(
                candidate.getWorkspaceId(), candidate.getWorkflowId(), candidate.getId());
            WorkflowVersion current = versions.get(key);
            Workflow workflow = workflows.get(
                new WorkflowKey(candidate.getWorkspaceId(), candidate.getWorkflowId()));
            boolean activeLock = workflow != null
                && Objects.equals(workflow.getActiveVersionId(), candidate.getId())
                && workflowAffected(userId, workflow, versions, rules);
            if (current == null || !(versionDirectlyAffected(userId, current) || activeLock)) {
                throw new ConflictException(
                    "Workflow version principal references changed during account offboarding");
            }
        }
        for (Rule candidate : plan.rules()) {
            RuleKey key = new RuleKey(candidate.getWorkspaceId(), candidate.getId());
            Rule current = rules.get(key);
            Workflow linked = linkedWorkflow(candidate, workflows);
            if (current == null
                    || !(ruleDirectlyAffected(userId, current)
                        || linked != null && workflowAffected(userId, linked, versions, rules))) {
                throw new ConflictException(
                    "Workflow rule principal references changed during account offboarding");
            }
        }
    }

    private static boolean workflowAffected(
            int userId,
            Workflow workflow,
            Map<VersionKey, WorkflowVersion> versions,
            Map<RuleKey, Rule> rules) {
        if (workflowDirectlyAffected(userId, workflow)) {
            return true;
        }
        boolean versionAffected = versions.values().stream()
            .filter(version -> version.getWorkspaceId() == workflow.getWorkspaceId())
            .filter(version -> version.getWorkflowId() == workflow.getId())
            .anyMatch(version -> versionDirectlyAffected(userId, version));
        Rule linkedRule = linkedRule(workflow, rules);
        return versionAffected || linkedRule != null && ruleDirectlyAffected(userId, linkedRule);
    }

    private static boolean creatorOrRunAsAffected(
            int userId,
            Workflow workflow,
            WorkflowVersion activeVersion,
            Rule linkedRule) {
        return Objects.equals(workflow.getCreatedById(), userId)
            || Objects.equals(workflow.getDraftRunAsUserId(), userId)
            || activeVersion != null && (
                Objects.equals(activeVersion.getCreatedById(), userId)
                    || Objects.equals(activeVersion.getRunAsUserId(), userId))
            || linkedRule != null && ruleDirectlyAffected(userId, linkedRule);
    }

    private static boolean workflowDirectlyAffected(int userId, Workflow workflow) {
        return Objects.equals(workflow.getCreatedById(), userId)
            || Objects.equals(workflow.getUpdatedById(), userId)
            || Objects.equals(workflow.getDraftRunAsUserId(), userId);
    }

    private static boolean versionDirectlyAffected(int userId, WorkflowVersion version) {
        return Objects.equals(version.getCreatedById(), userId)
            || Objects.equals(version.getPublishedById(), userId)
            || Objects.equals(version.getRunAsUserId(), userId);
    }

    private static boolean ruleDirectlyAffected(int userId, Rule rule) {
        return Objects.equals(rule.getCreatedById(), userId)
            || Objects.equals(rule.getRunAsUserId(), userId);
    }

    private static WorkflowVersion activeVersion(
            Workflow workflow, Map<VersionKey, WorkflowVersion> versions) {
        Long activeVersionId = workflow.getActiveVersionId();
        return activeVersionId == null
            ? null
            : versions.get(new VersionKey(
                workflow.getWorkspaceId(), workflow.getId(), activeVersionId));
    }

    private static Rule linkedRule(Workflow workflow, Map<RuleKey, Rule> rules) {
        Integer ruleId = workflow.getLegacyRuleId();
        return ruleId == null
            ? null
            : rules.get(new RuleKey(workflow.getWorkspaceId(), ruleId));
    }

    private static Workflow linkedWorkflow(
            Rule rule, Map<WorkflowKey, Workflow> workflows) {
        return workflows.values().stream()
            .filter(workflow -> workflow.getWorkspaceId() == rule.getWorkspaceId())
            .filter(workflow -> Objects.equals(workflow.getLegacyRuleId(), rule.getId()))
            .findFirst()
            .orElse(null);
    }

    /** Immutable, deterministically sorted non-locking discovery for one departing identity. */
    public record OffboardingPlan(
        List<Workflow> workflows,
        List<WorkflowVersion> versions,
        List<Rule> rules) {

        public OffboardingPlan {
            workflows = sorted(workflows, WORKFLOW_ORDER);
            versions = sorted(versions, VERSION_ORDER);
            rules = sorted(rules, RULE_ORDER);
        }

        /** Candidate workspace roots in ascending id order. */
        public List<Integer> workspaceIds() {
            TreeSet<Integer> workspaceIds = new TreeSet<>();
            workflows.forEach(workflow -> workspaceIds.add(workflow.getWorkspaceId()));
            versions.forEach(version -> workspaceIds.add(version.getWorkspaceId()));
            rules.forEach(rule -> workspaceIds.add(rule.getWorkspaceId()));
            return List.copyOf(workspaceIds);
        }

        private static <T> List<T> sorted(List<T> values, Comparator<T> comparator) {
            return values == null ? List.of() : values.stream().sorted(comparator).toList();
        }
    }

    private record WorkflowKey(int workspaceId, int workflowId)
            implements Comparable<WorkflowKey> {
        @Override
        public int compareTo(WorkflowKey other) {
            int workspace = Integer.compare(workspaceId, other.workspaceId);
            return workspace != 0 ? workspace : Integer.compare(workflowId, other.workflowId);
        }
    }

    private record VersionKey(int workspaceId, int workflowId, long versionId) { }

    private record RuleKey(int workspaceId, int ruleId)
            implements Comparable<RuleKey> {
        @Override
        public int compareTo(RuleKey other) {
            int workspace = Integer.compare(workspaceId, other.workspaceId);
            return workspace != 0 ? workspace : Integer.compare(ruleId, other.ruleId);
        }
    }
}
