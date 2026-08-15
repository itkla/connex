package ooo.klae.connex.backend.exceptions;

/** Raised when a tightening policy edit would invalidate pending approval requests. */
public class ApprovalImpactConfirmationRequiredException extends RuntimeException {
    private ApprovalImpactConfirmationRequiredException(String message) {
        super(message);
    }

    public ApprovalImpactConfirmationRequiredException(int pendingApprovalCount) {
        super("This policy change would invalidate " + pendingApprovalCount
            + " pending approval request" + (pendingApprovalCount == 1 ? "" : "s")
            + "; confirm invalidation to continue");
    }

    /** Creates the conflict raised when the confirmed preview no longer matches current impact. */
    public static ApprovalImpactConfirmationRequiredException changed() {
        return new ApprovalImpactConfirmationRequiredException(
            "The approval situation changed; review the impact again before saving");
    }
}
