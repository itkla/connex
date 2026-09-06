package ooo.klae.connex.backend.services;

/** Durable action-specific outcome projected into canonical workflow run history. */
public record WorkflowActionResult(String outcome, Long referenceId) {

    private static final WorkflowActionResult NONE = new WorkflowActionResult(null, null);

    /** Returns the empty marker used by actions without a more specific durable outcome. */
    public static WorkflowActionResult none() {
        return NONE;
    }
}
