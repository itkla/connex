package ooo.klae.connex.backend.dto;

import java.time.LocalDateTime;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;

import ooo.klae.connex.backend.beans.WorkflowIntervention;

/** Permission-safe workflow intervention projection. */
@JsonInclude(Include.ALWAYS)
public record WorkflowInterventionDto(
    long id,
    String runKey,
    String stepNodeId,
    String category,
    String reasonCode,
    Integer ownerUserId,
    String status,
    int sourceVersion,
    LocalDateTime createdAt,
    LocalDateTime updatedAt
) {

    /** Creates the stable wire projection for a canonical intervention. */
    public static WorkflowInterventionDto from(WorkflowIntervention intervention) {
        return new WorkflowInterventionDto(
            intervention.getId(),
            "canonical-" + intervention.getWorkflowRunId(),
            intervention.getStepNodeId(),
            intervention.getCategory(),
            intervention.getReasonCode(),
            intervention.getOwnerUserId(),
            intervention.getStatus(),
            intervention.getSourceVersion(),
            intervention.getCreatedAt(),
            intervention.getUpdatedAt());
    }
}
