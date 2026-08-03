package ooo.klae.connex.backend.services;

import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import ooo.klae.connex.backend.beans.WorkflowIntervention;
import ooo.klae.connex.backend.beans.WorkflowRun;
import ooo.klae.connex.backend.dto.WorkflowInterventionDto;
import ooo.klae.connex.backend.dto.WorkflowInterventionOwnerRequest;
import ooo.klae.connex.backend.dto.WorkflowInterventionResolveRequest;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.WorkflowOperationsMapper;
import ooo.klae.connex.backend.mappers.WorkflowRunMapper;
import ooo.klae.connex.backend.tenant.Permission;
import ooo.klae.connex.backend.tenant.RequirePermission;

/** Optimistic ownership and resolution controls for workflow interventions. */
@Service
@RequiredArgsConstructor
public class WorkflowInterventionService {

    private final WorkflowOperationsMapper operationsMapper;
    private final WorkflowRunMapper workflowRunMapper;
    private final WorkspaceService workspaceService;
    private final AuditService auditService;

    @Transactional
    @RequirePermission(Permission.RULE_MANAGE)
    public WorkflowInterventionDto updateOwner(
            long interventionId,
            WorkflowInterventionOwnerRequest request) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        if (request.ownerUserId() != null) {
            workspaceService.lockAndRequireMember(workspaceId, request.ownerUserId());
        }
        WorkflowIntervention intervention = requireForUpdate(workspaceId, interventionId);
        WorkflowRun run = requireRun(workspaceId, intervention.getWorkflowRunId());
        if (operationsMapper.updateInterventionOwner(
                workspaceId,
                interventionId,
                request.ownerUserId(),
                request.expectedSourceVersion()) != 1) {
            throw new ConflictException("Workflow intervention changed; refresh and retry");
        }
        auditService.recordStrict(
            "workflow.intervention.assign",
            "workflow",
            run.getWorkflowId(),
            "Workflow " + run.getWorkflowId(),
            "Workflow intervention ownership changed",
            Map.of(
                "interventionId", interventionId,
                "runKey", "canonical-" + run.getId()));
        return require(workspaceId, interventionId);
    }

    @Transactional
    @RequirePermission(Permission.RULE_MANAGE)
    public WorkflowInterventionDto resolve(
            long interventionId,
            WorkflowInterventionResolveRequest request) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        WorkflowIntervention intervention = requireForUpdate(workspaceId, interventionId);
        WorkflowRun run = requireRun(workspaceId, intervention.getWorkflowRunId());
        if (operationsMapper.resolveIntervention(
                workspaceId,
                interventionId,
                "resolved",
                request.expectedSourceVersion()) != 1) {
            throw new ConflictException("Workflow intervention changed; refresh and retry");
        }
        auditService.recordStrict(
            "workflow.intervention.resolve",
            "workflow",
            run.getWorkflowId(),
            "Workflow " + run.getWorkflowId(),
            "Workflow intervention resolved",
            Map.of(
                "interventionId", interventionId,
                "runKey", "canonical-" + run.getId()));
        return require(workspaceId, interventionId);
    }

    private WorkflowIntervention requireForUpdate(int workspaceId, long interventionId) {
        WorkflowIntervention intervention = operationsMapper.getInterventionForUpdate(
            workspaceId, interventionId);
        if (intervention == null) {
            throw new ResourceNotFoundException("Workflow intervention not found");
        }
        if (!"open".equals(intervention.getStatus())) {
            throw new ConflictException("Workflow intervention is already closed");
        }
        return intervention;
    }

    private WorkflowInterventionDto require(int workspaceId, long interventionId) {
        WorkflowIntervention intervention = operationsMapper.getIntervention(
            workspaceId, interventionId);
        if (intervention == null) {
            throw new ResourceNotFoundException("Workflow intervention not found");
        }
        return WorkflowInterventionDto.from(intervention);
    }

    private WorkflowRun requireRun(int workspaceId, long runId) {
        WorkflowRun run = workflowRunMapper.getByIdInWorkspace(workspaceId, runId);
        if (run == null) {
            throw new ResourceNotFoundException("Workflow run not found");
        }
        return run;
    }
}
