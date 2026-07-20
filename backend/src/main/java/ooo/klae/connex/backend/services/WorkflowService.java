package ooo.klae.connex.backend.services;

import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
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
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.services.LegacyWorkflowGraphConverter.ConvertedWorkflow;
import ooo.klae.connex.backend.services.WorkflowDraftCanonicalizer.CanonicalDraft;
import ooo.klae.connex.backend.services.WorkspaceService.Role;
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
    private final WorkspaceMapper workspaceMapper;
    private final WorkspaceService workspaceService;
    private final AuditService auditService;
    private final WorkflowDraftCanonicalizer canonicalizer;
    private final LegacyWorkflowGraphConverter graphConverter;
    private final RuleDefinitionValidator definitionValidator;
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
        requireSystemAuthor(draft.executionMode());

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
        Workflow existing = requireWorkflow(workspaceId, id);
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
        requireSystemAuthor(draft.executionMode());

        Integer runAsUserId = resolveDraftRunAs(existing, draft.executionMode());
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
            if (workflowMapper.getById(workspaceId, id) == null) {
                throw workflowNotFound();
            }
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
        Rule projection = project(workflow, draft);
        validateProjection(projection);
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
        Workflow workflow = requireWorkflowForUpdate(workspaceId, id);
        if (workflow.getDraftRevision() != request.getExpectedRevision()) {
            throw new ConflictException("Workflow draft revision does not match");
        }

        CanonicalDraft draft = canonicalPersistedDraft(workflow);
        Rule projection = project(workflow, draft);
        validateProjection(projection);
        PublishedState current = workflow.getActiveVersionId() == null
            ? requireUnpublishedState(workflow)
            : requirePublishedState(workflow);
        if (current != null && materiallyEquivalent(current.version(), projection, draft)) {
            return toDto(workflow, draft);
        }

        int versionNumber = nextVersionNumber(workspaceId, workflow.getId(), current);
        if (current == null) {
            ruleMapper.insert(projection);
            if (projection.getId() <= 0) {
                throw new IllegalStateException("Workflow rule insert did not return an id");
            }
            int linked = workflowMapper.updateLegacyRuleLink(
                workspaceId, workflow.getId(), projection.getId(), actorId);
            if (linked != 1) {
                throw new IllegalStateException("Workflow rule link was not created");
            }
            workflow.setLegacyRuleId(projection.getId());
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
        if (workflowMapper.updateActiveVersion(
                workspaceId, workflow.getId(), version.getId(), actorId) != 1) {
            throw new IllegalStateException("Workflow active version was not advanced");
        }
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
        Workflow workflow = requireWorkflowForUpdate(workspaceId, id);
        if (workflow.isEnabled() == enabled) {
            if (workflow.getActiveVersionId() != null || workflow.getLegacyRuleId() != null || enabled) {
                requirePublishedState(workflow);
            }
            return toDto(workflow);
        }
        PublishedState state = requirePublishedState(workflow);
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

    private PublishedState requireUnpublishedState(Workflow workflow) {
        if (workflow.getLegacyRuleId() != null || workflow.isEnabled()) {
            throw inconsistentWorkflow();
        }
        WorkflowVersion latest = workflowVersionMapper.getLatest(
            workflow.getWorkspaceId(), workflow.getId());
        if (latest != null) {
            throw inconsistentWorkflow();
        }
        return null;
    }

    private PublishedState requirePublishedState(Workflow workflow) {
        Long activeVersionId = workflow.getActiveVersionId();
        Integer legacyRuleId = workflow.getLegacyRuleId();
        if (activeVersionId == null || legacyRuleId == null) {
            throw inconsistentWorkflow();
        }
        WorkflowVersion active = workflowVersionMapper.getById(
            workflow.getWorkspaceId(), workflow.getId(), activeVersionId);
        Rule linked = ruleMapper.getByIdForUpdate(workflow.getWorkspaceId(), legacyRuleId);
        if (active == null
                || active.getId() != activeVersionId
                || active.getWorkspaceId() != workflow.getWorkspaceId()
                || active.getWorkflowId() != workflow.getId()
                || active.getVersionNumber() <= 0
                || linked == null) {
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
        if (!versionMatchesProjection(active, projection)
                || !ruleMatchesProjection(linked, projection)) {
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

    private void validateProjection(Rule projection) {
        RuleTrigger trigger = definitionCodec.parse(projection.getTriggerConfig(), RuleTrigger.class);
        SegmentDefinition condition = projection.getConditionJson() == null
            ? null
            : definitionCodec.parse(projection.getConditionJson(), SegmentDefinition.class);
        RuleAction[] actions = definitionCodec.parse(projection.getActionsJson(), RuleAction[].class);
        definitionValidator.validateDefinition(
            projection.getRecordType(),
            trigger,
            condition,
            Arrays.asList(actions),
            projection.getExecutionMode());
    }

    private Integer resolveDraftRunAs(Workflow existing, String executionMode) {
        if ("system".equals(executionMode)) {
            return null;
        }
        if ("user".equals(existing.getDraftExecutionMode())) {
            return existing.getDraftRunAsUserId();
        }
        Integer creatorId = existing.getCreatedById();
        if (creatorId == null
                || workspaceMapper.lockActiveMembership(existing.getWorkspaceId(), creatorId) == null) {
            throw new ConflictException("Workflow creator is not an active workspace member");
        }
        return creatorId;
    }

    private void requireSystemAuthor(String executionMode) {
        if ("system".equals(executionMode)) {
            workspaceService.requireRole(Role.ADMIN);
        }
    }

    private int nextVersionNumber(
            int workspaceId, int workflowId, PublishedState current) {
        WorkflowVersion latest = workflowVersionMapper.getLatest(workspaceId, workflowId);
        if (current == null) {
            if (latest != null) {
                throw inconsistentWorkflow();
            }
            return 1;
        }
        if (latest == null
                || latest.getVersionNumber() < current.version().getVersionNumber()
                || latest.getVersionNumber() == Integer.MAX_VALUE) {
            throw inconsistentWorkflow();
        }
        return latest.getVersionNumber() + 1;
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
        return Objects.equals(active.getName(), projection.getName())
            && Objects.equals(active.getDescription(), projection.getDescription())
            && Objects.equals(active.getRecordType(), projection.getRecordType())
            && Objects.equals(active.getExecutionMode(), projection.getExecutionMode())
            && Objects.equals(active.getRunAsUserId(), projection.getRunAsUserId())
            && Objects.equals(active.getCreatedById(), projection.getCreatedById())
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
        return rule.getId() == projection.getId()
            && rule.getWorkspaceId() == projection.getWorkspaceId()
            && Objects.equals(rule.getName(), projection.getName())
            && Objects.equals(rule.getDescription(), projection.getDescription())
            && rule.isEnabled() == projection.isEnabled()
            && Objects.equals(rule.getRecordType(), projection.getRecordType())
            && Objects.equals(rule.getTriggerType(), projection.getTriggerType())
            && Objects.equals(rule.getExecutionMode(), projection.getExecutionMode())
            && Objects.equals(rule.getRunAsUserId(), projection.getRunAsUserId())
            && Objects.equals(rule.getCreatedById(), projection.getCreatedById())
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

    private record PublishedState(WorkflowVersion version, Rule rule) { }
}
