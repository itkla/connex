package ooo.klae.connex.backend.services;

import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.Stage;
import ooo.klae.connex.backend.dto.RuleAction;
import ooo.klae.connex.backend.dto.WorkflowDiagnosticCode;
import ooo.klae.connex.backend.dto.WorkflowDiagnosticDto;
import ooo.klae.connex.backend.mappers.DealMapper;
import ooo.klae.connex.backend.mappers.PipelineMapper;
import ooo.klae.connex.backend.mappers.TagMapper;
import ooo.klae.connex.backend.tenant.Permission;

/** Read-only action permission and mutable-reference preflight shared with simulation. */
@Service
@RequiredArgsConstructor
public class WorkflowActionGuard {

    private final WorkspaceService workspaceService;
    private final RuleDefinitionValidator definitionValidator;
    private final TagMapper tagMapper;
    private final PipelineMapper pipelineMapper;
    private final DealMapper dealMapper;

    public WorkflowDiagnosticDto blocker(
            int workspaceId,
            int actorUserId,
            String recordType,
            int recordId,
            String nodeId,
            RuleAction action) {
        Permission required = definitionValidator.actionPermission(action, recordType);
        Set<Permission> permissions = workspaceService.permissionsFor(workspaceId, actorUserId);
        if (required != null && !permissions.contains(required)) {
            return diagnostic(
                WorkflowDiagnosticCode.ACTION_PERMISSION_MISSING,
                nodeId,
                null,
                Map.of("permission", required.name()));
        }
        String type = normalize(action.getType());
        if ("add_tag".equals(type)
                && tagMapper.getTagById(workspaceId, action.getTagId()) == null) {
            return diagnostic(
                WorkflowDiagnosticCode.ACTION_TAG_UNAVAILABLE,
                nodeId,
                "config.tagId",
                Map.of());
        }
        if ("assign_owner".equals(type)
                && workspaceService.getRole(workspaceId, action.getTargetUserId()) == null) {
            return diagnostic(
                WorkflowDiagnosticCode.ACTION_TARGET_MEMBER_UNAVAILABLE,
                nodeId,
                "config.targetUserId",
                Map.of());
        }
        if ("change_stage".equals(type)) {
            Stage stage = pipelineMapper.getStageById(workspaceId, action.getTargetStageId());
            if (stage == null || stage.getPipeline() == null) {
                return diagnostic(
                    WorkflowDiagnosticCode.ACTION_STAGE_UNAVAILABLE,
                    nodeId,
                    "config.targetStageId",
                    Map.of());
            }
            Deal deal = dealMapper.getDealById(workspaceId, recordId);
            if (deal == null || deal.getPipelineId() == null
                    || deal.getPipelineId() != stage.getPipeline().getId()) {
                return diagnostic(
                    WorkflowDiagnosticCode.ACTION_STAGE_PIPELINE_MISMATCH,
                    nodeId,
                    "config.targetStageId",
                    Map.of());
            }
        }
        return null;
    }

    private static WorkflowDiagnosticDto diagnostic(
            WorkflowDiagnosticCode code,
            String nodeId,
            String fieldPath,
            Map<String, String> params) {
        return new WorkflowDiagnosticDto(code, nodeId, null, fieldPath, params);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT);
    }
}
