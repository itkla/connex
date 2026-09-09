package ooo.klae.connex.backend.services;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

import ooo.klae.connex.backend.beans.WorkflowManualOptionView;
import ooo.klae.connex.backend.beans.Workflow;
import ooo.klae.connex.backend.dto.RuleTrigger;
import ooo.klae.connex.backend.dto.WorkflowDefinition;
import ooo.klae.connex.backend.dto.WorkflowNode;
import ooo.klae.connex.backend.tenant.Permission;
import ooo.klae.connex.backend.exceptions.ConflictException;

/** Shared read-only eligibility evaluation for manual discovery, preparation, and claim rechecks. */
@Service
@RequiredArgsConstructor
public class WorkflowManualEligibilityService {

    private final WorkflowDefinitionValidator definitionValidator;
    private final WorkflowRuntimeProperties runtimeProperties;
    private final WorkflowRecordGuard recordGuard;
    private final WorkspaceService workspaceService;
    private final SystemActor systemActor;

    public Evaluation evaluate(
            int workspaceId,
            int requesterId,
            WorkflowManualOptionView candidate,
            WorkflowDefinition definition,
            Integer recordId) {
        List<String> reasons = new ArrayList<>();
        RuleTrigger trigger = trigger(definition);
        String mode = manualEntryMode(definition.schemaVersion(), trigger);
        if (candidate.getArchivedAt() != null) reasons.add("workflow_archived");
        if (!candidate.isEnabled()) reasons.add("workflow_disabled");
        if (candidate.getIntakePausedAt() != null) reasons.add("workflow_paused");
        if (!"canonical".equals(candidate.getRuntimeOwner())) reasons.add("workflow_not_canonical");
        if (!runtimeProperties.enabled()) reasons.add("workflow_runtime_unavailable");
        if ("denied".equals(mode)) reasons.add("manual_entry_disabled");
        int actorUserId = actorUserId(candidate);
        if (!"system".equals(candidate.getExecutionMode())
                && workspaceService.getRole(workspaceId, actorUserId) == null) {
            reasons.add("actor_unavailable");
        }
        if ("system".equals(candidate.getExecutionMode())
                && (candidate.getCreatedById() == null
                    || workspaceService.getRole(workspaceId, candidate.getCreatedById()) == null)) {
            reasons.add("actor_unavailable");
        }
        try {
            Set<Permission> required = definitionValidator.validateForMutation(
                candidate.getRecordType(), candidate.getExecutionMode(), definition);
            Set<Permission> requesterPermissions = workspaceService.permissionsFor(workspaceId, requesterId);
            if (!requesterPermissions.containsAll(required)) reasons.add("caller_permission_missing");
            Set<Permission> actorPermissions = "system".equals(candidate.getExecutionMode())
                ? systemActor.permissions()
                : workspaceService.permissionsFor(workspaceId, actorUserId);
            if (!actorPermissions.containsAll(required)) reasons.add("actor_permission_missing");
        } catch (RuntimeException exception) {
            reasons.add("configuration_unavailable");
        }
        if (recordId != null) {
            try {
                recordGuard.requireAccessible(workspaceId, candidate.getRecordType(), recordId);
            } catch (WorkflowExecutionException exception) {
                reasons.add("record_unavailable");
            }
        }
        return new Evaluation(mode, actorUserId, List.copyOf(reasons));
    }

    static String manualEntryMode(int schemaVersion, RuleTrigger trigger) {
        if (schemaVersion == 1) return "legacy_compatible";
        String type = trigger == null || trigger.getType() == null
            ? ""
            : trigger.getType().trim().toLowerCase(java.util.Locale.ROOT);
        if ("manual".equals(type)) return "only";
        return Boolean.TRUE.equals(trigger == null ? null : trigger.getAllowManualRuns())
            ? "allowed" : "denied";
    }

    static RuleTrigger trigger(WorkflowDefinition definition) {
        return definition.nodes().stream()
            .filter(node -> node.id().equals(definition.entryNodeId()))
            .filter(WorkflowNode.Trigger.class::isInstance)
            .map(WorkflowNode.Trigger.class::cast)
            .map(WorkflowNode.Trigger::config)
            .findFirst()
            .orElse(null);
    }

    /** Rechecks the mutable manual-entry state while the caller holds the workflow root lock. */
    public void requireLockedState(
            Workflow workflow,
            long expectedVersionId,
            WorkflowDefinition definition) {
        if (workflow == null || workflow.getActiveVersionId() == null
                || workflow.getActiveVersionId() != expectedVersionId) {
            throw new ConflictException("Workflow version changed; prepare the scope again");
        }
        if (workflow.getArchivedAt() != null) {
            throw new ConflictException("Archived workflows cannot accept manual runs");
        }
        if (!workflow.isEnabled()) {
            throw new ConflictException("Disabled workflows cannot accept manual runs");
        }
        if (workflow.getIntakePausedAt() != null) {
            throw new ConflictException("Paused workflows cannot accept manual runs");
        }
        if (!"canonical".equals(workflow.getRuntimeOwner())) {
            throw new ConflictException("Workflow is not owned by the canonical runtime");
        }
        if (!runtimeProperties.enabled()) {
            throw new ConflictException("Workflow runtime is unavailable");
        }
        if ("denied".equals(manualEntryMode(definition.schemaVersion(), trigger(definition)))) {
            throw new ConflictException("Workflow does not allow manual runs");
        }
    }

    private int actorUserId(WorkflowManualOptionView candidate) {
        Integer actor = "system".equals(candidate.getExecutionMode())
            ? systemActor.user().getId()
            : candidate.getRunAsUserId();
        return actor == null ? 0 : actor;
    }

    /** Manual-entry classification, configured actor, and stable unavailability reasons. */
    public record Evaluation(String manualEntryMode, int actorUserId, List<String> reasons) { }
}
