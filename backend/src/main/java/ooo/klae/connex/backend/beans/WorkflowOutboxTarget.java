package ooo.klae.connex.backend.beans;

import lombok.Data;
import lombok.NoArgsConstructor;

/** Bounded workflow/version/generation target resolved during transactional intake. */
@Data
@NoArgsConstructor
public class WorkflowOutboxTarget {
    private int workflowId;
    private long workflowVersionId;
    private long runtimeGeneration;
    private String recordType;
}
