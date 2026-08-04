package ooo.klae.connex.backend.beans;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/** Operations run row enriched with workflow and open-intervention metadata. */
@Data
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class WorkflowOperationsRunView extends WorkflowRunView {
    private String workflowName;
    private String recipeKey;
    private Long interventionId;
    private Long interventionStepRunId;
    private String interventionStepNodeId;
    private String interventionCategory;
    private String interventionReasonCode;
    private Integer interventionOwnerUserId;
    private String interventionStatus;
    private Integer interventionSourceVersion;
    private java.time.LocalDateTime interventionCreatedAt;
    private java.time.LocalDateTime interventionUpdatedAt;
}
