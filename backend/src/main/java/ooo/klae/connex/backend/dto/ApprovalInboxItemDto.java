package ooo.klae.connex.backend.dto;

/**
 * One approval step the caller can still decide, for the workspace-scoped My Work projection.
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
 * @param requiredCount distinct approvals the step declares
 * @param dueAt         absolute deadline, null when the step has none
 * @param escalated     whether the step widened to every approver after passing its deadline
 * @param requestedBy   requesting user id
 * @param requestedAt   when approval was requested
 */
public record ApprovalInboxItemDto(
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
    boolean escalated,
    Integer requestedBy,
    String requestedAt
) {
}
