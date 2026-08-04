package ooo.klae.connex.backend.beans;

import lombok.Data;
import lombok.NoArgsConstructor;

/** One confirmed frozen invocation record awaiting a durable canonical run link. */
@Data
@NoArgsConstructor
public class WorkflowInvocationDispatch {
    private int workspaceId;
    private int workflowId;
    private long workflowVersionId;
    private long invocationId;
    private int recordId;
}
