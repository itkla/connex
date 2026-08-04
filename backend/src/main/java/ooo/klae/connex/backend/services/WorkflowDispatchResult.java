package ooo.klae.connex.backend.services;

/** Bounded dispatch accounting without record content. */
public record WorkflowDispatchResult(
    int candidates,
    int started,
    int replayed,
    int rejected
) {

    /** Empty result for an unsupported dispatch. */
    public static WorkflowDispatchResult empty() {
        return new WorkflowDispatchResult(0, 0, 0, 0);
    }
}
