package ooo.klae.connex.backend.services;

import java.security.MessageDigest;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import ooo.klae.connex.backend.beans.Rule;
import ooo.klae.connex.backend.beans.Workflow;
import ooo.klae.connex.backend.beans.WorkflowVersion;
import ooo.klae.connex.backend.dto.WorkflowDto;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.RuleMapper;
import ooo.klae.connex.backend.mappers.WorkflowMapper;
import ooo.klae.connex.backend.mappers.WorkflowVersionMapper;
import ooo.klae.connex.backend.services.LegacyWorkflowGraphConverter.ConvertedWorkflow;
import ooo.klae.connex.backend.services.WorkflowDraftCanonicalizer.CanonicalDraft;
import ooo.klae.connex.backend.services.WorkflowPrincipalLockService.LockedPrincipals;
import ooo.klae.connex.backend.tenant.Permission;
import ooo.klae.connex.backend.tenant.RequirePermission;

/** Compare-and-swap transitions for the database-authoritative workflow runtime owner. */
@Service
@RequiredArgsConstructor
public class WorkflowRuntimeOwnershipService {

    private final WorkflowMapper workflowMapper;
    private final WorkflowVersionMapper workflowVersionMapper;
    private final RuleMapper ruleMapper;
    private final WorkflowPrincipalLockService principalLockService;
    private final WorkflowDefinitionValidator definitionValidator;
    private final WorkflowDraftCanonicalizer canonicalizer;
    private final LegacyWorkflowGraphConverter graphConverter;
    private final WorkflowRuntimeProperties runtimeProperties;
    private final WorkspaceService workspaceService;
    private final WorkflowService workflowService;
    private final AuditService auditService;

    @Transactional
    @RequirePermission(Permission.RULE_MANAGE)
    public WorkflowDto cutOverToCanonical(
            int workflowId, long expectedActiveVersionId) {
        if (!runtimeProperties.enabled()) {
            throw new ConflictException(
                "Canonical workflow runtime is not enabled on this deployment");
        }
        LockedOwnership ownership = lock(workflowId, expectedActiveVersionId);
        Workflow workflow = ownership.workflow();
        if ("canonical".equals(workflow.getRuntimeOwner())) {
            return workflowService.toDto(workflow);
        }
        if (!"legacy".equals(workflow.getRuntimeOwner())) {
            throw new ConflictException("Workflow runtime owner is invalid");
        }
        requireCompiled(ownership.version());
        Rule rule = ownership.rule();
        if (rule == null) {
            throw new ConflictException("Legacy workflow projection is unavailable");
        }
        if (rule.isEnabled()
                && ruleMapper.updateEnabled(workflow.getWorkspaceId(), rule.getId(), false) != 1) {
            throw new ConflictException("Workflow legacy runtime changed during cutover");
        }
        int actorId = workspaceService.getCurrentUserId();
        if (workflowMapper.compareAndSwapRuntimeOwner(
                workflow.getWorkspaceId(),
                workflow.getId(),
                expectedActiveVersionId,
                "legacy",
                "canonical",
                actorId) != 1) {
            throw new ConflictException("Workflow runtime owner changed during cutover");
        }
        workflow.setRuntimeOwner("canonical");
        workflow.setUpdatedById(actorId);
        auditService.record(
            "workflow.runtime.cutover",
            "workflow",
            workflow.getId(),
            "Workflow " + workflow.getId(),
            "Workflow runtime changed to canonical",
            java.util.Map.of("activeVersionId", expectedActiveVersionId));
        return workflowService.toDto(workflow);
    }

    @Transactional
    @RequirePermission(Permission.RULE_MANAGE)
    public WorkflowDto rollBackToLegacy(
            int workflowId, long expectedActiveVersionId) {
        LockedOwnership ownership = lock(workflowId, expectedActiveVersionId);
        Workflow workflow = ownership.workflow();
        if ("legacy".equals(workflow.getRuntimeOwner())) {
            return workflowService.toDto(workflow);
        }
        if (!"canonical".equals(workflow.getRuntimeOwner())) {
            throw new ConflictException("Workflow runtime owner is invalid");
        }
        CanonicalDraft canonical = requireCompiled(ownership.version());
        Rule rule = ownership.rule();
        Rule projection;
        try {
            projection = graphConverter.project(new ConvertedWorkflow(
                rule == null ? 0 : rule.getId(),
                workflow.getWorkspaceId(),
                ownership.version().getName(),
                ownership.version().getDescription(),
                workflow.isEnabled(),
                ownership.version().getRecordType(),
                ownership.version().getExecutionMode(),
                ownership.version().getRunAsUserId(),
                ownership.version().getCreatedById(),
                canonicalizer.parseDefinition(canonical.definitionJson()),
                canonicalizer.parseCanvas(canonical.canvasJson())));
        } catch (RuntimeException exception) {
            throw new ConflictException(
                "The active workflow version cannot be projected to the legacy runtime");
        }
        int actorId = workspaceService.getCurrentUserId();
        int ownerUpdated;
        if (rule == null) {
            ruleMapper.insert(projection);
            if (projection.getId() <= 0) {
                throw new IllegalStateException("Workflow rule insert did not return an id");
            }
            ownerUpdated = workflowMapper.attachLegacyRuleAndCompareAndSwapRuntimeOwner(
                workflow.getWorkspaceId(),
                workflow.getId(),
                expectedActiveVersionId,
                projection.getId(),
                "canonical",
                "legacy",
                actorId);
            workflow.setLegacyRuleId(projection.getId());
        } else {
            if (ruleMapper.update(projection) != 1) {
                throw new ConflictException("Workflow legacy projection changed during rollback");
            }
            ownerUpdated = workflowMapper.compareAndSwapRuntimeOwner(
                workflow.getWorkspaceId(),
                workflow.getId(),
                expectedActiveVersionId,
                "canonical",
                "legacy",
                actorId);
        }
        if (ownerUpdated != 1) {
            throw new ConflictException("Workflow runtime owner changed during rollback");
        }
        workflow.setRuntimeOwner("legacy");
        workflow.setUpdatedById(actorId);
        auditService.record(
            "workflow.runtime.rollback",
            "workflow",
            workflow.getId(),
            "Workflow " + workflow.getId(),
            "Workflow runtime changed to legacy",
            java.util.Map.of("activeVersionId", expectedActiveVersionId));
        return workflowService.toDto(workflow);
    }

    private LockedOwnership lock(int workflowId, long expectedActiveVersionId) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        int actorId = workspaceService.getCurrentUserId();
        Workflow discovered = workflowMapper.getById(workspaceId, workflowId);
        if (discovered == null) {
            throw new ResourceNotFoundException("Workflow not found");
        }
        if (discovered.getArchivedAt() != null) {
            throw new ConflictException("Archived workflow cannot change runtime owner");
        }
        if (discovered.getActiveVersionId() == null
                || discovered.getActiveVersionId() != expectedActiveVersionId) {
            throw new ConflictException("Workflow active version does not match");
        }
        WorkflowVersion discoveredVersion = workflowVersionMapper.getById(
            workspaceId, workflowId, expectedActiveVersionId);
        if (discoveredVersion == null) {
            throw new ConflictException("Workflow active version changed during authorization");
        }
        Rule discoveredRule = discovered.getLegacyRuleId() == null
            ? null : ruleMapper.getById(workspaceId, discovered.getLegacyRuleId());
        TreeSet<Integer> principalIds = principalIds(
            discovered, discoveredVersion, discoveredRule);
        LockedPrincipals principals = lockOwnershipPrincipals(
            workspaceId,
            actorId,
            discoveredVersion.getExecutionMode(),
            principalIds);
        Workflow workflow = workflowMapper.getByIdForUpdate(workspaceId, workflowId);
        if (!sameOwnership(discovered, workflow)
                || workflow.getArchivedAt() != null
                || workflow.getActiveVersionId() == null
                || workflow.getActiveVersionId() != expectedActiveVersionId) {
            throw new ConflictException("Workflow state changed during authorization");
        }
        principals.requireDiscoveredReferences(principalIds(workflow, null, null));
        WorkflowVersion version = workflowVersionMapper.getByIdForUpdate(
            workspaceId, workflowId, expectedActiveVersionId);
        if (version == null) {
            throw new ConflictException("Workflow active version changed during authorization");
        }
        Rule rule = workflow.getLegacyRuleId() == null
            ? null : ruleMapper.getByIdForUpdate(workspaceId, workflow.getLegacyRuleId());
        principals.requireDiscoveredReferences(principalIds(workflow, version, rule));
        return new LockedOwnership(workflow, version, rule);
    }

    private LockedPrincipals lockOwnershipPrincipals(
            int workspaceId,
            int actorId,
            String executionMode,
            Set<Integer> principalIds) {
        if ("system".equals(executionMode)) {
            return principalLockService.lockSystemMutation(
                workspaceId, actorId, principalIds);
        }
        if ("user".equals(executionMode)) {
            return principalLockService.lockUserMutation(
                workspaceId, actorId, principalIds, Set.of());
        }
        throw new ConflictException("Workflow active version has an invalid execution mode");
    }

    private CanonicalDraft requireCompiled(WorkflowVersion version) {
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
            throw new ConflictException("Workflow active version failed its integrity check");
        }
        definitionValidator.validate(
            version.getRecordType(),
            version.getExecutionMode(),
            canonicalizer.parseDefinition(canonical.definitionJson()));
        return canonical;
    }

    private static TreeSet<Integer> principalIds(
            Workflow workflow, WorkflowVersion version, Rule rule) {
        TreeSet<Integer> ids = new TreeSet<>();
        add(ids, workflow.getCreatedById());
        add(ids, workflow.getUpdatedById());
        add(ids, workflow.getDraftRunAsUserId());
        if (version != null) {
            add(ids, version.getCreatedById());
            add(ids, version.getPublishedById());
            add(ids, version.getRunAsUserId());
        }
        if (rule != null) {
            add(ids, rule.getCreatedById());
            add(ids, rule.getRunAsUserId());
        }
        return ids;
    }

    private static void add(Set<Integer> ids, Integer id) {
        if (id != null) {
            ids.add(id);
        }
    }

    private static boolean sameOwnership(Workflow expected, Workflow actual) {
        return actual != null
            && expected.getId() == actual.getId()
            && expected.getWorkspaceId() == actual.getWorkspaceId()
            && Objects.equals(expected.getLegacyRuleId(), actual.getLegacyRuleId())
            && Objects.equals(expected.getActiveVersionId(), actual.getActiveVersionId())
            && Objects.equals(expected.getRuntimeOwner(), actual.getRuntimeOwner())
            && Objects.equals(expected.getArchivedAt(), actual.getArchivedAt());
    }

    private record LockedOwnership(
        Workflow workflow,
        WorkflowVersion version,
        Rule rule
    ) { }
}
