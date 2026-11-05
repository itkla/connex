package ooo.klae.connex.backend.beans;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/** Rule execution enriched with its tenant-scoped workflow identity for Operations. */
@Data
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class WorkflowOperationsLegacyRunView extends RuleExecution {
    private int workflowId;
    private String workflowName;
    private String recipeKey;
}
