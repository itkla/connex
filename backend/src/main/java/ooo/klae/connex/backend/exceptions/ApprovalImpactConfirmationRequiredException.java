package ooo.klae.connex.backend.exceptions;

/** Raised when a tightening policy edit would invalidate pending approval requests. */
public class ApprovalImpactConfirmationRequiredException extends RuntimeException {
    public ApprovalImpactConfirmationRequiredException(int pendingApprovalCount) {
        super("This policy change would invalidate " + pendingApprovalCount
            + " pending approval request" + (pendingApprovalCount == 1 ? "" : "s")
            + "; confirm invalidation to continue");
    }
}
