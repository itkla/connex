package ooo.klae.connex.backend.services;

/** One database-leased unit selected under the workspace fairness gate. */
public record WorkflowWorkClaim(
    Kind kind,
    int workspaceId,
    long id,
    String leaseOwner,
    String resumedWaitKind
) {

    /** Durable queue that owns the claimed row. */
    public enum Kind {
        TRIGGER,
        RUN
    }
}
