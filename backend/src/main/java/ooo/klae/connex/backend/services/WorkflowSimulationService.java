package ooo.klae.connex.backend.services;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.Workflow;
import ooo.klae.connex.backend.dto.RuleTrigger;
import ooo.klae.connex.backend.dto.WorkflowDiagnosticCode;
import ooo.klae.connex.backend.dto.WorkflowDiagnosticDto;
import ooo.klae.connex.backend.dto.WorkflowDefinition;
import ooo.klae.connex.backend.dto.WorkflowEdge;
import ooo.klae.connex.backend.dto.WorkflowNode;
import ooo.klae.connex.backend.dto.WorkflowSimulateRequest;
import ooo.klae.connex.backend.dto.WorkflowSimulationDto;
import ooo.klae.connex.backend.dto.WorkflowSimulationDto.Blocker;
import ooo.klae.connex.backend.dto.WorkflowSimulationDto.PathStep;
import ooo.klae.connex.backend.dto.WorkflowSimulationDto.Result;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.exceptions.WorkflowDefinitionValidationException;
import ooo.klae.connex.backend.mappers.DealMapper;
import ooo.klae.connex.backend.mappers.WorkflowMapper;
import ooo.klae.connex.backend.services.WorkflowDefinitionValidator.CompiledWorkflow;
import ooo.klae.connex.backend.services.WorkflowDefinitionValidator.NodeType;
import ooo.klae.connex.backend.services.WorkflowDefinitionValidator.ValidatedWorkflow;
import ooo.klae.connex.backend.services.WorkflowDraftCanonicalizer.CanonicalDraft;
import ooo.klae.connex.backend.tenant.Permission;
import ooo.klae.connex.backend.tenant.RequirePermission;

/** Predicts one selected-record traversal without creating ledgers or invoking action services. */
@Service
@RequiredArgsConstructor
public class WorkflowSimulationService {

    private static final int MAX_STEPS = 50;

    private final WorkflowMapper workflowMapper;
    private final DealMapper dealMapper;
    private final WorkspaceService workspaceService;
    private final WorkflowDraftCanonicalizer canonicalizer;
    private final WorkflowDefinitionValidator definitionValidator;
    private final WorkflowExecutionPrincipalService principalService;
    private final WorkflowRecordGuard recordGuard;
    private final WorkflowNodeDecisionService decisionService;
    private final WorkflowActionGuard actionGuard;

    @Transactional(readOnly = true)
    @RequirePermission(Permission.RULE_MANAGE)
    public WorkflowSimulationDto simulate(int workflowId, WorkflowSimulateRequest request) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        Workflow workflow = workflowMapper.getById(workspaceId, workflowId);
        if (workflow == null) {
            throw new ResourceNotFoundException("Workflow not found");
        }
        if (workflow.getArchivedAt() != null) {
            throw new ConflictException("Archived workflow cannot be simulated");
        }
        if (workflow.getDraftRevision() != request.expectedRevision()) {
            throw new ConflictException("Workflow draft revision does not match");
        }
        CanonicalDraft draft = persistedDraft(workflow);
        WorkflowDefinition definition = canonicalizer.parseDefinition(draft.definitionJson());
        return simulateDraft(
            draft,
            definition,
            workflow.getDraftRunAsUserId(),
            workflow.getCreatedById(),
            request.recordId());
    }

    WorkflowSimulationDto simulateDraft(
            CanonicalDraft draft,
            WorkflowDefinition definition,
            Integer runAsUserId,
            Integer createdById,
            int recordId) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        if ("system".equals(draft.executionMode())
                && !workspaceService.isBuiltInAdmin(
                    workspaceId, workspaceService.getCurrentUserId())) {
            throw new ForbiddenException("Requires a built-in admin role in this workspace");
        }
        ValidatedWorkflow validated;
        try {
            canonicalizer.requirePublishableCanvas(draft);
            validated = definitionValidator.validateForMutationAndCompile(
                draft.recordType(),
                draft.executionMode(),
                definition);
        } catch (WorkflowDefinitionValidationException exception) {
            return blocked(List.of(), exception.diagnostic());
        }
        WorkflowExecutionPrincipal principal;
        try {
            principal = principalService.resolveDraft(
                workspaceId,
                draft.executionMode(),
                runAsUserId,
                createdById);
        } catch (WorkflowExecutionException exception) {
            return blocked(
                List.of(),
                diagnostic(WorkflowDiagnosticCode.ACTOR_UNAVAILABLE, null, null));
        }
        try {
            recordGuard.requireAccessible(
                workspaceId, draft.recordType(), recordId);
        } catch (WorkflowExecutionException exception) {
            return blocked(
                List.of(),
                diagnostic(WorkflowDiagnosticCode.RECORD_UNAVAILABLE, null, null));
        }
        return traverse(
            workspaceId,
            draft,
            validated.compiled(),
            principal,
            recordId);
    }

    private WorkflowSimulationDto traverse(
            int workspaceId,
            CanonicalDraft draft,
            CompiledWorkflow compiled,
            WorkflowExecutionPrincipal principal,
            int recordId) {
        List<PathStep> path = new ArrayList<>();
        WorkflowNode entry = compiled.node(compiled.entryNodeId());
        if (!(entry instanceof WorkflowNode.Trigger trigger)) {
            return blocked(
                path,
                diagnostic(
                    WorkflowDiagnosticCode.DEFINITION_CORRUPT,
                    compiled.entryNodeId(),
                    null));
        }
        String triggerType = normalize(trigger.config().getType());
        if (!triggerFilterMatches(draft.recordType(), recordId, trigger.config())) {
            path.add(new PathStep(
                trigger.id(),
                "trigger",
                "not_enrolled",
                null,
                null,
                WorkflowDiagnosticCode.TRIGGER_FILTER_NOT_MATCHED));
            return new WorkflowSimulationDto(Result.NOT_ENROLLED, path, List.of());
        }
        String nodeId = compiled.entryNodeId();
        boolean scheduleEnrollmentConfirmed = false;
        for (int sequence = 0; sequence < MAX_STEPS && nodeId != null; sequence++) {
            WorkflowNode node = compiled.node(nodeId);
            NodeType nodeType = compiled.nodeType(nodeId);
            if (node == null || nodeType == null) {
                return blocked(
                    path,
                    diagnostic(WorkflowDiagnosticCode.DEFINITION_CORRUPT, nodeId, null));
            }
            if (node instanceof WorkflowNode.Action action) {
                WorkflowDiagnosticDto blocker = actionGuard.blocker(
                    workspaceId,
                    principal.actorUserId(),
                    draft.recordType(),
                    recordId,
                    nodeId,
                    action.config());
                if (blocker != null) {
                    path.add(new PathStep(
                        nodeId,
                        "action",
                        "blocked",
                        null,
                        normalize(action.config().getType()),
                        blocker.code()));
                    return blocked(path, blocker);
                }
            }
            WorkflowNodeDecisionContext context = new WorkflowNodeDecisionContext(
                workspaceId,
                principal.attributionUserId(),
                triggerType,
                draft.recordType(),
                recordId,
                scheduleEnrollmentConfirmed,
                compiled);
            WorkflowStepTransition transition = decisionService.decide(context, node);
            if (node instanceof WorkflowNode.End) {
                path.add(step(nodeId, nodeType, "would_complete", null, null,
                    WorkflowDiagnosticCode.END_REACHED));
                return new WorkflowSimulationDto(Result.WOULD_COMPLETE, path, List.of());
            }
            if (node instanceof WorkflowNode.Delay) {
                path.add(step(nodeId, nodeType, "would_wait", null, null,
                    WorkflowDiagnosticCode.DELAY_WAIT));
                return new WorkflowSimulationDto(Result.WOULD_WAIT, path, List.of());
            }
            boolean enrollment = "schedule".equals(triggerType)
                && nodeId.equals(compiled.enrollmentConditionNodeId());
            if (enrollment && transition.outcome() == WorkflowEdge.Outcome.NO) {
                path.add(step(
                    nodeId,
                    nodeType,
                    "not_enrolled",
                    null,
                    null,
                    WorkflowDiagnosticCode.ENROLLMENT_NOT_MATCHED));
                return new WorkflowSimulationDto(Result.NOT_ENROLLED, path, List.of());
            }
            WorkflowEdge.Outcome outcome = transition.outcome();
            WorkflowDiagnosticCode code = pathCode(node, outcome);
            path.add(step(
                nodeId,
                nodeType,
                pathStatus(node, outcome),
                outcome == null ? null : outcome.value(),
                node instanceof WorkflowNode.Action action
                    ? normalize(action.config().getType()) : null,
                code));
            WorkflowEdge edge = outcome == null ? null : compiled.transition(nodeId, outcome);
            if (edge == null) {
                return blocked(
                    path,
                    diagnostic(WorkflowDiagnosticCode.DEFINITION_CORRUPT, nodeId, null));
            }
            if (enrollment) {
                scheduleEnrollmentConfirmed = true;
            }
            nodeId = edge.targetNodeId();
        }
        return blocked(
            path,
            diagnostic(WorkflowDiagnosticCode.TRAVERSAL_LIMIT, nodeId, null));
    }

    private boolean triggerFilterMatches(
            String recordType, int recordId, RuleTrigger trigger) {
        if (!"entity_change".equals(normalize(trigger.getType()))
                || !"deal".equals(recordType)
                || trigger.getTargetStageId() == null) {
            return true;
        }
        Deal deal = dealMapper.getDealById(
            workspaceService.getCurrentWorkspaceId(), recordId);
        return deal != null && Objects.equals(deal.getStageId(), trigger.getTargetStageId());
    }

    private CanonicalDraft persistedDraft(Workflow workflow) {
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
            throw new ConflictException("Workflow state is inconsistent");
        }
        if (!Objects.equals(workflow.getName(), canonical.name())
                || !Objects.equals(workflow.getDescription(), canonical.description())
                || !Objects.equals(workflow.getDraftRecordType(), canonical.recordType())
                || !Objects.equals(workflow.getDraftExecutionMode(), canonical.executionMode())
                || !Objects.equals(
                    workflow.getDraftDefinitionJson(), canonical.definitionJson())
                || !Objects.equals(workflow.getDraftCanvasJson(), canonical.canvasJson())) {
            throw new ConflictException("Workflow state is inconsistent");
        }
        return canonical;
    }

    private static PathStep step(
            String nodeId,
            NodeType nodeType,
            String status,
            String outcome,
            String actionType,
            WorkflowDiagnosticCode code) {
        return new PathStep(
            nodeId,
            nodeType.name().toLowerCase(java.util.Locale.ROOT),
            status,
            outcome,
            actionType,
            code);
    }

    private static WorkflowDiagnosticCode pathCode(
            WorkflowNode node, WorkflowEdge.Outcome outcome) {
        if (node instanceof WorkflowNode.Trigger) {
            return WorkflowDiagnosticCode.TRIGGER_READY;
        }
        if (node instanceof WorkflowNode.Condition) {
            return outcome == WorkflowEdge.Outcome.YES
                ? WorkflowDiagnosticCode.CONDITION_MATCHED
                : WorkflowDiagnosticCode.CONDITION_NOT_MATCHED;
        }
        return WorkflowDiagnosticCode.ACTION_READY;
    }

    private static String pathStatus(
            WorkflowNode node, WorkflowEdge.Outcome outcome) {
        if (node instanceof WorkflowNode.Trigger) {
            return "evaluated";
        }
        if (node instanceof WorkflowNode.Condition) {
            return outcome == WorkflowEdge.Outcome.YES ? "matched" : "not_matched";
        }
        return "would_execute";
    }

    private static WorkflowSimulationDto blocked(
            List<PathStep> path, WorkflowDiagnosticDto diagnostic) {
        return new WorkflowSimulationDto(
            Result.BLOCKED,
            path,
            List.of(Blocker.from(diagnostic)));
    }

    private static WorkflowDiagnosticDto diagnostic(
            WorkflowDiagnosticCode code, String nodeId, String fieldPath) {
        return new WorkflowDiagnosticDto(code, nodeId, null, fieldPath, Map.of());
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT);
    }
}
