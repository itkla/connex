package ooo.klae.connex.backend.services;

import java.util.Map;

/** Durable action-specific outcome projected into canonical workflow run history. */
public record WorkflowActionResult(
    String outcome,
    Long referenceId,
    Map<String, Object> outputs
) {

    private static final WorkflowActionResult NONE = new WorkflowActionResult(null, null, Map.of());

    public WorkflowActionResult {
        outputs = outputs == null ? Map.of() : Map.copyOf(outputs);
    }

    /** Preserves actions that only expose the established delivery outcome/reference pair. */
    public WorkflowActionResult(String outcome, Long referenceId) {
        this(outcome, referenceId, Map.of());
    }

    /** Returns the empty marker used by actions without a more specific durable outcome. */
    public static WorkflowActionResult none() {
        return NONE;
    }
}
