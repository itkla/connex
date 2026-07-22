package ooo.klae.connex.backend.services;

import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
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
import ooo.klae.connex.backend.dto.WorkflowCreateRequest;
import ooo.klae.connex.backend.dto.WorkflowDto;
import ooo.klae.connex.backend.dto.WorkflowDraftRequest;
import ooo.klae.connex.backend.dto.WorkflowPublishRequest;
import ooo.klae.connex.backend.dto.WorkflowValidationDto;
import ooo.klae.connex.backend.dto.WorkflowVersionDto;
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
import ooo.klae.connex.backend.tenant.RequirePermission;

/** Versioned workflow draft, publication, and lifecycle operations for the active workspace. */
@Service
@RequiredArgsConstructor
public class WorkflowService {

    private static final String ENTITY_TYPE = "workflow";

    private final WorkflowMapper workflowMapper;
    private final WorkflowVersionMapper workflowVersionMapper;
    private final RuleMapper ruleMapper;
    private final WorkflowPrincipalLockService principalLockService;
    private final WorkspaceService workspaceService;
    private final AuditService auditService;
    private final WorkflowDraftCanonicalizer canonicalizer;
    private final WorkflowDefinitionValidator workflowDefinitionValidator;
    private final LegacyWorkflowGraphConverter graphConverter;
    private final RuleDefinitionCodec definitionCodec;

    /** Lists workflows in the active workspace using the existing deterministic mapper order. */
    @Transactional(readOnly = true)
    @RequirePermission(Permission.RULE_MANAGE)
    public List<WorkflowDto> list() {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        return workflowMapper.listByWorkspace(workspaceId).stream().map(this::toDto).toList();
    }

    /** Returns one workflow without revealing whether the same id exists in another workspace. */
    @Transactional(readOnly = true)
    @RequirePermission(Permission.RULE_MANAGE)
    public WorkflowDto getById(int id) {
        return toDto(requireWorkflow(workspaceService.getCurrentWorkspaceId(), id));
    }

    /** Creates a disabled revision-zero workflow without creating a rule or version. */
    @Transactional
    @RequirePermission(Permission.RULE_MANAGE)
    public WorkflowDto create(WorkflowCreateRequest request) {
        if (request == null) {
            throw new BadRequestException("Workflow request is required");
        }
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        int actorId = workspaceService.getCurrentUserId();
        CanonicalDraft draft = canonicalizer.canonicalizeDraftNodes(
            request.getName(),
            request.getDescription(),
            request.getRecordType(),
            request.getExecutionMode(),
            request.getDefinition(),
            request.getCanvas());
        lockAuthoringPrincipals(
            workspaceId,
            actorId,
            draft.executionMode(),
            Set.of(actorId),
            "user".equals(draft.executionMode()) ? Set.of(actorId) : Set.of());

        Workflow workflow = new Workflow();
        workflow.setWorkspaceId(workspaceId);
        workflow.setLegacyRuleId(null);
        workflow.setName(draft.name());
        workflow.setDescription(draft.description());
        workflow.setEnabled(false);
        workflow.setDraftRevision(0);
        workflow.setDraftRecordType(draft.recordType());
        workflow.setDraftExecutionMode(draft.executionMode());
        workflow.setDraftRunAsUserId("user".equals(draft.executionMode()) ? actorId : null);
        workflow.setDraftDefinitionJson(draft.definitionJson());
        workflow.setDraftCanvasJson(draft.canvasJson());
        workflow.setActiveVersionId(null);
        workflow.setCreatedById(actorId);
        workflow.setUpdatedById(actorId);
        workflowMapper.insert(workflow);
        if (workflow.getId() <= 0) {
            throw new IllegalStateException("Workflow insert did not return an id");
        }
        auditService.record(
            "workflow.create",
            ENTITY_TYPE,
            workflow.getId(),
            workflowLabel(workflow.getId()),
            "Workflow created",
            Map.of("draftRevision", 0, "executionMode", draft.executionMode()));
        return toDto(workflow, draft);
    }

    /** Saves a draft through one workspace-scoped optimistic compare-and-swap update. */
    @Transactional
    @RequirePermission(Permission.RULE_MANAGE)
    public WorkflowDto saveDraft(int id, WorkflowDraftRequest request) {
        if (request == null || request.getExpectedRevision() == null) {
            throw new BadRequestException("Expected revision is required");
        }
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        int actorId = workspaceService.getCurrentUserId();
        int expectedRevision = request.getExpectedRevision();
        Workflow discovered = requireWorkflow(workspaceId, id);
        if (expectedRevision == Integer.MAX_VALUE) {
            throw new ConflictException("Workflow draft revision cannot be advanced");
        }
        CanonicalDraft draft = canonicalizer.canonicalizeDraftNodes(
            request.getName(),
            request.getDescription(),
            request.getRecordType(),
            request.getExecutionMode(),
            request.getDefinition(),
            request.getCanvas());
        Integer discoveredRunAsUserId = resolveDraftRunAs(discovered, draft.executionMode());
        TreeSet<Integer> principalIds = workflowPrincipalIds(discovered);
        addPrincipal(principalIds, discoveredRunAsUserId);
        LockedPrincipals principals = lockAuthoringPrincipals(
            workspaceId,
            actorId,
            draft.executionMode(),
            principalIds,
            "user".equals(draft.executionMode())
                ? Set.of(discoveredRunAsUserId)
                : Set.of());
        Workflow existing = requireWorkflowForUpdate(workspaceId, id);
        requireStableAuthorizationDiscovery(discovered, existing);
        principals.requireCurrentReferences(workflowPrincipalIds(existing));
        if (existing.getDraftRevision() != expectedRevision) {
            throw new ConflictException("Workflow draft revision does not match");
        }
        Integer runAsUserId = resolveDraftRunAs(existing, draft.executionMode());
        if (!Objects.equals(discoveredRunAsUserId, runAsUserId)) {
            throw new ConflictException("Workflow principal state changed during authorization");
        }
        Workflow replacement = new Workflow();
        replacement.setId(id);
        replacement.setWorkspaceId(workspaceId);
        replacement.setName(draft.name());
        replacement.setDescription(draft.description());
        replacement.setDraftRecordType(draft.recordType());
        replacement.setDraftExecutionMode(draft.executionMode());
        replacement.setDraftRunAsUserId(runAsUserId);
        replacement.setDraftDefinitionJson(draft.definitionJson());
        replacement.setDraftCanvasJson(draft.canvasJson());
        replacement.setUpdatedById(actorId);

        int updated = workflowMapper.updateDraft(replacement, expectedRevision);
        if (updated != 1) {
            throw new ConflictException("Workflow draft revision does not match");
        }
        auditService.record(
            "workflow.draft.save",
            ENTITY_TYPE,
            id,
            workflowLabel(id),
            "Workflow draft saved",
            auditService.singleChange("draftRevision", expectedRevision, expectedRevision + 1));
        return toDto(requireWorkflow(workspaceId, id));
    }

    /** Strictly validates the persisted draft without changing workflow, rule, or version state. */
    @Transactional(readOnly = true)
    @RequirePermission(Permission.RULE_MANAGE)
    public WorkflowValidationDto validate(int id) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        Workflow workflow = requireWorkflow(workspaceId, id);
        CanonicalDraft draft = canonicalPersistedDraft(workflow);
        canonicalizer.requirePublishableCanvas(draft);
        workflowDefinitionValidator.validate(
            draft.recordType(),
            draft.executionMode(),
            canonicalizer.parseDefinition(draft.definitionJson()));
        project(workflow, draft);
        return new WorkflowValidationDto(workflow.getDraftRevision(), true);
    }

    /** Publishes a revision preconditioned draft and synchronizes its single legacy rule. */
    @Transactional
    @RequirePermission(Permission.RULE_MANAGE)
    public WorkflowDto publish(int id, WorkflowPublishRequest request) {
        if (request == null || request.getExpectedRevision() == null) {
            throw new BadRequestException("Expected revision is required");
        }
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        int actorId = workspaceService.getCurrentUserId();
        MutationDiscovery discovery = discoverMutation(workspaceId, id, true);
        Workflow discovered = discovery.workflow();
        String discoveredExecutionMode = discovered.getDraftExecutionMode();
        TreeSet<Integer> requiredActiveIds = new TreeSet<>();
        if ("user".equals(discoveredExecutionMode)) {
            addRequiredPrincipal(requiredActiveIds, discovered.getDraftRunAsUserId());
        }
        LockedPrincipals principals = lockAuthoringPrincipals(
            workspaceId,
            actorId,
            discoveredExecutionMode,
            discovery.principalIds(),
            requiredActiveIds);
        if ("system".equals(discoveredExecutionMode)) {
            principals.requireExisting(
                discovered.getCreatedById(),
                "System workflow creator account no longer exists");
        }
        Workflow workflow = requireWorkflowForUpdate(workspaceId, id);
        requireStableAuthorizationDiscovery(discovered, workflow);
        principals.requireCurrentReferences(workflowPrincipalIds(workflow));
        if (workflow.getDraftRevision() != request.getExpectedRevision()) {
            throw new ConflictException("Workflow draft revision does not match");
        }

        CanonicalDraft draft = canonicalPersistedDraft(workflow);
        canonicalizer.requirePublishableCanvas(draft);
        if (!Objects.equals(discoveredExecutionMode, draft.executionMode())) {
            throw new ConflictException("Workflow execution mode changed during authorization");
        }
        Set<Permission> requiredPermissions = workflowDefinitionValidator.validateForMutation(
            draft.recordType(),
            draft.executionMode(),
            canonicalizer.parseDefinition(draft.definitionJson()));
        Rule projection = project(workflow, draft);
        principals.requirePermissions(requiredPermissions);
        boolean allowBrokenPrincipals = !workflow.isEnabled();
        PrincipalMatchPolicy principalMatchPolicy = allowBrokenPrincipals
            ? PrincipalMatchPolicy.REDACTED_ALL
            : PrincipalMatchPolicy.STRICT;
        LockedVersionState lockedVersions = lockDiscoveredVersions(
            workflow, discovery, principals);
        Rule linkedRule = lockDiscoveredRule(
            workflow, discovery, principals, allowBrokenPrincipals);
        PublishedState current = workflow.getActiveVersionId() == null
            ? requireUnpublishedState(workflow, lockedVersions.latest())
            : requirePublishedState(
                workflow, lockedVersions.active(), linkedRule, principalMatchPolicy);
        if (current != null && materiallyEquivalent(current.version(), projection, draft)) {
            return toDto(workflow, draft);
        }

        int versionNumber = nextVersionNumber(current, lockedVersions.latest());
        if (current == null) {
            ruleMapper.insert(projection);
            if (projection.getId() <= 0) {
                throw new IllegalStateException("Workflow rule insert did not return an id");
            }
        }

        WorkflowVersion version = version(workflow, projection, draft, versionNumber, actorId);
        workflowVersionMapper.insert(version);
        if (version.getId() <= 0) {
            throw new IllegalStateException("Workflow version insert did not return an id");
        }
        if (current != null) {
            projection.setId(workflow.getLegacyRuleId());
            if (!ruleMatchesProjection(current.rule(), projection)
                    && ruleMapper.update(projection) != 1) {
                throw new IllegalStateException("Workflow rule was not synchronized");
            }
        }
        int pointerUpdated;
        if (current == null) {
            pointerUpdated = workflowMapper.assignFirstPublication(
                workspaceId,
                workflow.getId(),
                projection.getId(),
                version.getId(),
                actorId,
                workflow.getDraftRevision());
        } else {
            pointerUpdated = workflowMapper.advancePublication(
                workspaceId,
                workflow.getId(),
                current.rule().getId(),
                current.version().getId(),
                version.getId(),
                actorId,
                workflow.getDraftRevision());
        }
        if (pointerUpdated != 1) {
            throw new IllegalStateException("Workflow publication pointer was not advanced");
        }
        workflow.setLegacyRuleId(projection.getId());
        workflow.setActiveVersionId(version.getId());
        workflow.setUpdatedById(actorId);
        auditService.record(
            "workflow.publish",
            ENTITY_TYPE,
            id,
            workflowLabel(id),
            "Workflow published",
            Map.of("versionNumber", versionNumber));
        return toDto(workflow, draft);
    }

    /** Enables a published workflow and its linked rule atomically. */
    @Transactional
    @RequirePermission(Permission.RULE_MANAGE)
    public WorkflowDto enable(int id) {
        return setEnabled(id, true);
    }

    /** Disables a workflow and its linked rule atomically. */
    @Transactional
    @RequirePermission(Permission.RULE_MANAGE)
    public WorkflowDto disable(int id) {
        return setEnabled(id, false);
    }

    /** Lists immutable versions newest first without inventing lifecycle pagination. */
    @Transactional(readOnly = true)
    @RequirePermission(Permission.RULE_MANAGE)
    public List<WorkflowVersionDto> versions(int id) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        requireWorkflow(workspaceId, id);
        return workflowVersionMapper.listByWorkflow(workspaceId, id).stream()
            .map(this::toVersionDto)
            .toList();
    }

    private WorkflowDto setEnabled(int id, boolean enabled) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        int actorId = workspaceService.getCurrentUserId();
        MutationDiscovery discovery = discoverMutation(workspaceId, id, false);
        WorkflowVersion discoveredActive = discovery.activeVersion();
        String executionMode = discoveredActive == null
            ? discovery.workflow().getDraftExecutionMode()
            : discoveredActive.getExecutionMode();
        TreeSet<Integer> requiredActiveIds = new TreeSet<>();
        if (enabled && discoveredActive != null && "user".equals(executionMode)) {
            addRequiredPrincipal(requiredActiveIds, discoveredActive.getRunAsUserId());
        }
        LockedPrincipals principals = enabled && "system".equals(executionMode)
            ? principalLockService.lockSystemMutation(
                workspaceId, actorId, discovery.principalIds())
            : principalLockService.lockUserMutation(
                workspaceId, actorId, discovery.principalIds(), requiredActiveIds);
        if (enabled && "system".equals(executionMode)) {
            principals.requireExisting(
                discovery.workflow().getCreatedById(),
                "System workflow creator account no longer exists");
            principals.requireExisting(
                discoveredActive == null ? null : discoveredActive.getCreatedById(),
                "System workflow creator account no longer exists");
        }
        Workflow workflow = requireWorkflowForUpdate(workspaceId, id);
        requireStableAuthorizationDiscovery(discovery.workflow(), workflow);
        PrincipalMatchPolicy principalMatchPolicy = !enabled
            ? PrincipalMatchPolicy.IGNORE
            : "user".equals(executionMode)
                ? PrincipalMatchPolicy.REDACTED_CREATOR
                : PrincipalMatchPolicy.STRICT;
        boolean allowBrokenPrincipals = principalMatchPolicy != PrincipalMatchPolicy.STRICT;
        if (!allowBrokenPrincipals) {
            principals.requireCurrentReferences(workflowPrincipalIds(workflow));
        } else {
            principals.requireDiscoveredReferences(workflowPrincipalIds(workflow));
        }
        if (!enabled
                && !workflow.isEnabled()
                && workflow.getActiveVersionId() == null
                && workflow.getLegacyRuleId() == null) {
            return toDto(workflow);
        }
        LockedVersionState versions = lockDiscoveredVersions(
            workflow, discovery, principals);
        Rule linkedRule = lockDiscoveredRule(
            workflow, discovery, principals, allowBrokenPrincipals);
        PublishedState state = requirePublishedState(
            workflow, versions.active(), linkedRule, principalMatchPolicy);
        if (workflow.isEnabled() == enabled) {
            return toDto(workflow);
        }
        if (ruleMapper.updateEnabled(workspaceId, state.rule().getId(), enabled) != 1) {
            throw new IllegalStateException("Workflow rule lifecycle was not synchronized");
        }
        if (workflowMapper.updateLifecycle(workspaceId, id, enabled, actorId) != 1) {
            throw new IllegalStateException("Workflow lifecycle was not synchronized");
        }
        workflow.setEnabled(enabled);
        workflow.setUpdatedById(actorId);
        auditService.record(
            enabled ? "workflow.enable" : "workflow.disable",
            ENTITY_TYPE,
            id,
            workflowLabel(id),
            enabled ? "Workflow enabled" : "Workflow disabled",
            auditService.singleChange("enabled", !enabled, enabled));
        return toDto(workflow);
    }

    private PublishedState requireUnpublishedState(
            Workflow workflow, WorkflowVersion latest) {
        if (workflow.getLegacyRuleId() != null || workflow.isEnabled()) {
            throw inconsistentWorkflow();
        }
        if (latest != null) {
            throw inconsistentWorkflow();
        }
        return null;
    }

    private PublishedState requirePublishedState(
            Workflow workflow,
            WorkflowVersion active,
            Rule linked,
            PrincipalMatchPolicy principalMatchPolicy) {
        Long activeVersionId = workflow.getActiveVersionId();
        Integer legacyRuleId = workflow.getLegacyRuleId();
        if (activeVersionId == null || legacyRuleId == null) {
            throw inconsistentWorkflow();
        }
        if (active == null
                || active.getId() != activeVersionId
                || active.getWorkspaceId() != workflow.getWorkspaceId()
                || active.getWorkflowId() != workflow.getId()
                || active.getVersionNumber() <= 0) {
            throw inconsistentWorkflow();
        }
        CanonicalDraft canonical = canonicalPublishedVersion(active);
        ConvertedWorkflow stored = new ConvertedWorkflow(
            legacyRuleId,
            workflow.getWorkspaceId(),
            active.getName(),
            active.getDescription(),
            workflow.isEnabled(),
            active.getRecordType(),
            active.getExecutionMode(),
            active.getRunAsUserId(),
            active.getCreatedById(),
            canonicalizer.parseDefinition(canonical.definitionJson()),
            canonicalizer.parseCanvas(canonical.canvasJson()));
        Rule projection;
        try {
            projection = graphConverter.project(stored);
        } catch (BadRequestException exception) {
            throw inconsistentWorkflow();
        }
        if (!versionMatchesProjection(active, projection)) {
            throw inconsistentWorkflow();
        }
        if (linked == null
                || !ruleMatchesProjection(linked, projection, principalMatchPolicy)) {
            throw inconsistentWorkflow();
        }
        return new PublishedState(active, linked);
    }

    private CanonicalDraft canonicalPublishedVersion(WorkflowVersion version) {
        CanonicalDraft canonical;
        try {
            canonical = canonicalizer.canonicalizeDraftJson(
                version.getName(),
                version.getDescription(),
                version.getRecordType(),
                version.getExecutionMode(),
                version.getDefinitionJson(),
                version.getCanvasJson());
        } catch (BadRequestException exception) {
            throw inconsistentWorkflow();
        }
        if (!Objects.equals(version.getName(), canonical.name())
                || !Objects.equals(version.getDescription(), canonical.description())
                || !Objects.equals(version.getRecordType(), canonical.recordType())
                || !Objects.equals(version.getExecutionMode(), canonical.executionMode())
                || !Objects.equals(version.getDefinitionJson(), canonical.definitionJson())
                || !Objects.equals(version.getCanvasJson(), canonical.canvasJson())
                || !hashesEqual(version.getDefinitionHash(), canonical.definitionHash())) {
            throw inconsistentWorkflow();
        }
        return canonical;
    }

    private CanonicalDraft canonicalPersistedDraft(Workflow workflow) {
        CanonicalDraft canonical;
        try {
            canonical = canonicalizer.canonicalizeDraftJson(
                workflow.getName(),
                workflow.getDescription(),
                workflow.getDraftRecordType(),
                workflow.getDraftExecutionMode(),
                workflow.getDraftDefinitionJson(),
                workflow.getDraftCanvasJson());
        } catch (BadRequestException exception) {
            throw inconsistentWorkflow();
        }
        if (!Objects.equals(workflow.getName(), canonical.name())
                || !Objects.equals(workflow.getDescription(), canonical.description())
                || !Objects.equals(workflow.getDraftRecordType(), canonical.recordType())
                || !Objects.equals(workflow.getDraftExecutionMode(), canonical.executionMode())
                || !Objects.equals(workflow.getDraftDefinitionJson(), canonical.definitionJson())
                || !Objects.equals(workflow.getDraftCanvasJson(), canonical.canvasJson())) {
            throw inconsistentWorkflow();
        }
        return canonical;
    }

    private Rule project(Workflow workflow, CanonicalDraft draft) {
        ConvertedWorkflow converted = new ConvertedWorkflow(
            workflow.getLegacyRuleId() == null ? 0 : workflow.getLegacyRuleId(),
            workflow.getWorkspaceId(),
            draft.name(),
            draft.description(),
            workflow.isEnabled(),
            draft.recordType(),
            draft.executionMode(),
            workflow.getDraftRunAsUserId(),
            workflow.getCreatedById(),
            canonicalizer.parseDefinition(draft.definitionJson()),
            canonicalizer.parseCanvas(draft.canvasJson()));
        return graphConverter.project(converted);
    }

    private Integer resolveDraftRunAs(Workflow existing, String executionMode) {
        if ("system".equals(executionMode)) {
            return null;
        }
        if ("user".equals(existing.getDraftExecutionMode())) {
            Integer runAsUserId = existing.getDraftRunAsUserId();
            if (runAsUserId == null) {
                throw new ConflictException("Workflow run-as user is not an active workspace member");
            }
            return runAsUserId;
        }
        Integer creatorId = existing.getCreatedById();
        if (creatorId == null) {
            throw new ConflictException("Workflow creator is not an active workspace member");
        }
        return creatorId;
    }

    private LockedPrincipals lockAuthoringPrincipals(
            int workspaceId,
            int actorId,
            String executionMode,
            Collection<Integer> principalIds,
            Collection<Integer> requiredActiveIds) {
        if ("system".equals(executionMode)) {
            return principalLockService.lockSystemMutation(workspaceId, actorId, principalIds);
        }
        if (!"user".equals(executionMode)) {
            throw inconsistentWorkflow();
        }
        return principalLockService.lockUserMutation(
            workspaceId, actorId, principalIds, requiredActiveIds);
    }

    private int nextVersionNumber(PublishedState current, WorkflowVersion latest) {
        if (current == null) {
            return 1;
        }
        if (latest == null
                || latest.getVersionNumber() < current.version().getVersionNumber()
                || latest.getVersionNumber() == Integer.MAX_VALUE) {
            throw inconsistentWorkflow();
        }
        return latest.getVersionNumber() + 1;
    }

    private MutationDiscovery discoverMutation(
            int workspaceId, int workflowId, boolean includeLatestVersion) {
        Workflow workflow = requireWorkflow(workspaceId, workflowId);
        TreeMap<Long, WorkflowVersion> versions = new TreeMap<>();
        if (workflow.getActiveVersionId() != null) {
            WorkflowVersion active = workflowVersionMapper.getById(
                workspaceId, workflowId, workflow.getActiveVersionId());
            if (active != null) {
                versions.put(active.getId(), active);
            }
        }
        WorkflowVersion latest = null;
        if (includeLatestVersion) {
            latest = workflowVersionMapper.getLatest(workspaceId, workflowId);
            if (latest != null) {
                versions.put(latest.getId(), latest);
            }
        }
        Rule linkedRule = workflow.getLegacyRuleId() == null
            ? null
            : ruleMapper.getById(workspaceId, workflow.getLegacyRuleId());
        TreeSet<Integer> principalIds = workflowPrincipalIds(workflow);
        versions.values().forEach(version -> principalIds.addAll(versionPrincipalIds(version)));
        if (linkedRule != null) {
            principalIds.addAll(rulePrincipalIds(linkedRule));
        }
        return new MutationDiscovery(
            workflow,
            new LinkedHashMap<>(versions),
            latest == null ? null : latest.getId(),
            linkedRule,
            principalIds);
    }

    private LockedVersionState lockDiscoveredVersions(
            Workflow workflow,
            MutationDiscovery discovery,
            LockedPrincipals principals) {
        if (workflow.getActiveVersionId() != null
                && !discovery.versions().containsKey(workflow.getActiveVersionId())) {
            throw new ConflictException("Workflow version state changed during authorization");
        }
        Map<Long, WorkflowVersion> locked = new LinkedHashMap<>();
        for (long versionId : discovery.versions().keySet()) {
            WorkflowVersion current = workflowVersionMapper.getByIdForUpdate(
                workflow.getWorkspaceId(), workflow.getId(), versionId);
            if (current == null
                    || current.getWorkspaceId() != workflow.getWorkspaceId()
                    || current.getWorkflowId() != workflow.getId()
                    || current.getId() != versionId) {
                throw inconsistentWorkflow();
            }
            principals.requireDiscoveredReferences(versionPrincipalIds(current));
            locked.put(versionId, current);
        }
        WorkflowVersion active = workflow.getActiveVersionId() == null
            ? null
            : locked.get(workflow.getActiveVersionId());
        WorkflowVersion latest = discovery.latestVersionId() == null
            ? null
            : locked.get(discovery.latestVersionId());
        return new LockedVersionState(active, latest);
    }

    private Rule lockDiscoveredRule(
            Workflow workflow,
            MutationDiscovery discovery,
            LockedPrincipals principals,
            boolean allowMissingPrincipals) {
        Integer ruleId = workflow.getLegacyRuleId();
        if (ruleId == null) {
            if (discovery.linkedRule() != null) {
                throw new ConflictException("Workflow rule state changed during authorization");
            }
            return null;
        }
        if (discovery.linkedRule() == null || discovery.linkedRule().getId() != ruleId) {
            throw new ConflictException("Workflow rule state changed during authorization");
        }
        Rule current = ruleMapper.getByIdForUpdate(workflow.getWorkspaceId(), ruleId);
        if (current == null || current.getWorkspaceId() != workflow.getWorkspaceId()) {
            throw inconsistentWorkflow();
        }
        if (allowMissingPrincipals) {
            principals.requireDiscoveredReferences(rulePrincipalIds(current));
        } else {
            principals.requireCurrentReferences(rulePrincipalIds(current));
        }
        return current;
    }

    private static TreeSet<Integer> workflowPrincipalIds(Workflow workflow) {
        TreeSet<Integer> ids = new TreeSet<>();
        addPrincipal(ids, workflow.getCreatedById());
        addPrincipal(ids, workflow.getUpdatedById());
        addPrincipal(ids, workflow.getDraftRunAsUserId());
        return ids;
    }

    private static TreeSet<Integer> versionPrincipalIds(WorkflowVersion version) {
        TreeSet<Integer> ids = new TreeSet<>();
        addPrincipal(ids, version.getCreatedById());
        addPrincipal(ids, version.getPublishedById());
        addPrincipal(ids, version.getRunAsUserId());
        return ids;
    }

    private static TreeSet<Integer> rulePrincipalIds(Rule rule) {
        TreeSet<Integer> ids = new TreeSet<>();
        addPrincipal(ids, rule.getCreatedById());
        addPrincipal(ids, rule.getRunAsUserId());
        return ids;
    }

    private static void addPrincipal(Set<Integer> ids, Integer userId) {
        if (userId != null) {
            ids.add(userId);
        }
    }

    private static void addRequiredPrincipal(Set<Integer> ids, Integer userId) {
        if (userId == null || userId <= 0) {
            throw new ConflictException("Workflow run-as user is not an active workspace member");
        }
        ids.add(userId);
    }

    private static void requireStableAuthorizationDiscovery(
            Workflow discovered, Workflow current) {
        if (discovered.getId() != current.getId()
                || discovered.getWorkspaceId() != current.getWorkspaceId()
                || !Objects.equals(
                    discovered.getDraftExecutionMode(), current.getDraftExecutionMode())
                || !Objects.equals(
                    discovered.getDraftRunAsUserId(), current.getDraftRunAsUserId())
                || discovered.getDraftRevision() != current.getDraftRevision()
                || !Objects.equals(
                    discovered.getActiveVersionId(), current.getActiveVersionId())
                || !Objects.equals(
                    discovered.getLegacyRuleId(), current.getLegacyRuleId())) {
            throw new ConflictException("Workflow state changed during authorization");
        }
    }

    private WorkflowVersion version(
            Workflow workflow,
            Rule projection,
            CanonicalDraft draft,
            int versionNumber,
            int publishedById) {
        WorkflowVersion version = new WorkflowVersion();
        version.setWorkspaceId(workflow.getWorkspaceId());
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
        version.setCreatedById(workflow.getCreatedById());
        version.setPublishedById(publishedById);
        version.setDefinitionJson(draft.definitionJson());
        version.setCanvasJson(draft.canvasJson());
        version.setDefinitionHash(draft.definitionHash());
        return version;
    }

    private boolean materiallyEquivalent(
            WorkflowVersion active, Rule projection, CanonicalDraft draft) {
        return versionMatchesProjection(active, projection)
            && Objects.equals(active.getDefinitionJson(), draft.definitionJson())
            && Objects.equals(active.getCanvasJson(), draft.canvasJson())
            && hashesEqual(active.getDefinitionHash(), draft.definitionHash())
            && definitionsEquivalent(active, projection);
    }

    private boolean versionMatchesProjection(WorkflowVersion version, Rule projection) {
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

    private boolean ruleMatchesProjection(Rule rule, Rule projection) {
        return ruleMatchesProjectionIgnoringPrincipals(rule, projection)
            && Objects.equals(rule.getRunAsUserId(), projection.getRunAsUserId())
            && Objects.equals(rule.getCreatedById(), projection.getCreatedById());
    }

    private boolean ruleMatchesProjection(
            Rule rule, Rule projection, PrincipalMatchPolicy principalMatchPolicy) {
        return switch (principalMatchPolicy) {
            case STRICT -> ruleMatchesProjection(rule, projection);
            case REDACTED_CREATOR -> ruleMatchesProjectionIgnoringPrincipals(rule, projection)
                && Objects.equals(rule.getRunAsUserId(), projection.getRunAsUserId())
                && redactedPrincipalMatches(
                    rule.getCreatedById(), projection.getCreatedById());
            case REDACTED_ALL -> ruleMatchesProjectionIgnoringPrincipals(rule, projection)
                && redactedPrincipalMatches(
                    rule.getRunAsUserId(), projection.getRunAsUserId())
                && redactedPrincipalMatches(
                    rule.getCreatedById(), projection.getCreatedById());
            case IGNORE -> ruleMatchesProjectionIgnoringPrincipals(rule, projection);
        };
    }

    private static boolean redactedPrincipalMatches(Integer mutableId, Integer immutableId) {
        return mutableId == null || Objects.equals(mutableId, immutableId);
    }

    private boolean ruleMatchesProjectionIgnoringPrincipals(Rule rule, Rule projection) {
        return rule.getId() == projection.getId()
            && rule.getWorkspaceId() == projection.getWorkspaceId()
            && Objects.equals(rule.getName(), projection.getName())
            && Objects.equals(rule.getDescription(), projection.getDescription())
            && rule.isEnabled() == projection.isEnabled()
            && Objects.equals(rule.getRecordType(), projection.getRecordType())
            && Objects.equals(rule.getTriggerType(), projection.getTriggerType())
            && Objects.equals(rule.getExecutionMode(), projection.getExecutionMode())
            && definitionsEquivalent(rule, projection);
    }

    private boolean definitionsEquivalent(WorkflowVersion version, Rule projection) {
        return definitionsEquivalent(
            version.getTriggerConfig(),
            version.getConditionJson(),
            version.getActionsJson(),
            projection.getTriggerConfig(),
            projection.getConditionJson(),
            projection.getActionsJson());
    }

    private boolean definitionsEquivalent(Rule rule, Rule projection) {
        return definitionsEquivalent(
            rule.getTriggerConfig(),
            rule.getConditionJson(),
            rule.getActionsJson(),
            projection.getTriggerConfig(),
            projection.getConditionJson(),
            projection.getActionsJson());
    }

    private boolean definitionsEquivalent(
            String firstTriggerJson,
            String firstConditionJson,
            String firstActionsJson,
            String secondTriggerJson,
            String secondConditionJson,
            String secondActionsJson) {
        try {
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
        } catch (BadRequestException exception) {
            throw inconsistentWorkflow();
        }
    }

    private boolean conditionsEquivalent(String firstJson, String secondJson) {
        if (firstJson == null || secondJson == null) {
            return firstJson == null && secondJson == null;
        }
        SegmentDefinition first = definitionCodec.parse(firstJson, SegmentDefinition.class);
        SegmentDefinition second = definitionCodec.parse(secondJson, SegmentDefinition.class);
        return Objects.equals(first, second);
    }

    private WorkflowDto toDto(Workflow workflow) {
        return toDto(workflow, canonicalPersistedDraft(workflow));
    }

    private WorkflowDto toDto(Workflow workflow, CanonicalDraft draft) {
        return new WorkflowDto(
            workflow.getId(),
            draft.name(),
            draft.description(),
            workflow.isEnabled(),
            workflow.getDraftRevision(),
            draft.recordType(),
            draft.executionMode(),
            workflow.getDraftRunAsUserId(),
            canonicalizer.parseDefinition(draft.definitionJson()),
            canonicalizer.parseCanvas(draft.canvasJson()),
            workflow.getActiveVersionId(),
            workflow.getCreatedById(),
            workflow.getUpdatedById(),
            workflow.getCreatedAt(),
            workflow.getUpdatedAt());
    }

    private WorkflowVersionDto toVersionDto(WorkflowVersion version) {
        CanonicalDraft canonical = canonicalPublishedVersion(version);
        return new WorkflowVersionDto(
            version.getId(),
            version.getVersionNumber(),
            canonical.name(),
            canonical.description(),
            canonical.recordType(),
            canonical.executionMode(),
            version.getRunAsUserId(),
            version.getCreatedById(),
            version.getPublishedById(),
            canonicalizer.parseDefinition(canonical.definitionJson()),
            canonicalizer.parseCanvas(canonical.canvasJson()),
            version.getPublishedAt());
    }

    private Workflow requireWorkflow(int workspaceId, int id) {
        Workflow workflow = workflowMapper.getById(workspaceId, id);
        if (workflow == null) {
            throw workflowNotFound();
        }
        return workflow;
    }

    private Workflow requireWorkflowForUpdate(int workspaceId, int id) {
        Workflow workflow = workflowMapper.getByIdForUpdate(workspaceId, id);
        if (workflow == null) {
            throw workflowNotFound();
        }
        return workflow;
    }

    private static boolean hashesEqual(byte[] first, byte[] second) {
        return first != null && second != null && MessageDigest.isEqual(first, second);
    }

    private static String workflowLabel(int id) {
        return "Workflow " + id;
    }

    private static ResourceNotFoundException workflowNotFound() {
        return new ResourceNotFoundException("Workflow not found");
    }

    private static ConflictException inconsistentWorkflow() {
        return new ConflictException("Workflow state is inconsistent");
    }

    private record MutationDiscovery(
        Workflow workflow,
        Map<Long, WorkflowVersion> versions,
        Long latestVersionId,
        Rule linkedRule,
        Set<Integer> principalIds) {

        private WorkflowVersion activeVersion() {
            Long activeVersionId = workflow.getActiveVersionId();
            return activeVersionId == null ? null : versions.get(activeVersionId);
        }
    }

    private record LockedVersionState(
        WorkflowVersion active,
        WorkflowVersion latest) { }

    private record PublishedState(WorkflowVersion version, Rule rule) { }

    private enum PrincipalMatchPolicy {
        STRICT,
        REDACTED_CREATOR,
        REDACTED_ALL,
        IGNORE
    }
}
