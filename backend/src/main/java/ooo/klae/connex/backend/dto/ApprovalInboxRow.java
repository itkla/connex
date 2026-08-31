package ooo.klae.connex.backend.dto;

/**
 * Bounded tenant-plane projection of one active approval step that may be actionable by a caller.
 * The statement deliberately over-selects — a frozen name superseded by a reassignment, a
 * separation-of-duties exclusion, or an already-recorded decision are all filtered in Java.
 *
 * @param approvalId    the pending approval request
 * @param dealId        deal owning the document
 * @param dealName      deal name
 * @param documentId    the document version awaiting approval
 * @param documentTitle document title
 * @param documentType  document type
 * @param version       document version
 * @param stepId        the active frozen step
 * @param stepOrder     position of that step in the chain
 * @param stepName      operator label for the step
 * @param requiredCount distinct approvals the step still declares
 * @param dueAt         absolute deadline, null when the step has none
 * @param escalatedAt   when the step widened after its deadline, null when it never did
 * @param requestedBy   requesting user id
 * @param requestedAt   when approval was requested
 * @param urgencyRank   exact My Work urgency band rank at the requested snapshot
 * @param dueDateKey    nullable-last UTC due-date ordering key
 * @param freshnessAt   latest authoritative chain state change ordering key
 */
public record ApprovalInboxRow(
    int approvalId,
    int dealId,
    String dealName,
    int documentId,
    String documentTitle,
    String documentType,
    int version,
    int stepId,
    int stepOrder,
    String stepName,
    int requiredCount,
    String dueAt,
    String escalatedAt,
    Integer requestedBy,
    String requestedAt,
    int urgencyRank,
    String dueDateKey,
    String freshnessAt
) {
    /** Returns this raw row's complete approval-provider keyset cursor. */
    public ApprovalInboxCursor cursor() {
        return new ApprovalInboxCursor(
            urgencyRank, dueDateKey, freshnessAt, approvalId, stepId);
    }
}
