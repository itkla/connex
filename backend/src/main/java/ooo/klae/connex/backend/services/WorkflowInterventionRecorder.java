package ooo.klae.connex.backend.services;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

import ooo.klae.connex.backend.beans.WorkflowIntervention;
import ooo.klae.connex.backend.beans.WorkflowRun;
import ooo.klae.connex.backend.beans.WorkflowStepRun;
import ooo.klae.connex.backend.mappers.WorkflowOperationsMapper;
import ooo.klae.connex.backend.mappers.WorkflowRunMapper;

/** Records one stable, assignable intervention for a terminal canonical run failure. */
@Service
@RequiredArgsConstructor
public class WorkflowInterventionRecorder {

    private final WorkflowRunMapper workflowRunMapper;
    private final WorkflowOperationsMapper workflowOperationsMapper;

    public void record(WorkflowRun run, String reasonCode) {
        WorkflowStepRun step = workflowRunMapper.getStepByNodeForUpdate(
            run.getWorkspaceId(), run.getId(), run.getCurrentNodeId());
        WorkflowIntervention intervention = new WorkflowIntervention();
        intervention.setWorkspaceId(run.getWorkspaceId());
        intervention.setWorkflowRunId(run.getId());
        intervention.setWorkflowStepRunId(step == null ? null : step.getId());
        intervention.setInterventionKey(interventionKey(run));
        intervention.setCategory(failureCategory(reasonCode));
        intervention.setReasonCode(reasonCode);
        workflowOperationsMapper.upsertIntervention(intervention);
    }

    static String failureCategory(String reasonCode) {
        if (reasonCode == null) {
            return "execution";
        }
        return switch (reasonCode) {
            case "actor_unavailable", "actor_inactive" -> "actor";
            case "permission_denied", "action_permission_missing" -> "permission";
            case "reference_unavailable", "record_unavailable" -> "reference";
            case "retry_exhausted", "retry_not_safe", "transient_database_failure" -> "retry";
            case "configuration_invalid", "invalid_action_config", "definition_corrupt",
                    "definition_invalid" -> "configuration";
            default -> "execution";
        };
    }

    private static byte[] interventionKey(WorkflowRun run) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return digest.digest((run.getWorkspaceId() + ":" + run.getId() + ":"
                + run.getCurrentNodeId()).getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
