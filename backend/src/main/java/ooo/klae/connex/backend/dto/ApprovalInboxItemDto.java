package ooo.klae.connex.backend.dto;

import java.time.Instant;

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
 * @param requestedByLabel current display label of the requester
 * @param requestedAt   when approval was requested
 * @param freshnessAt   latest authoritative chain state change
 * @param currentVersion canonical approval-and-step state hash
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
    String requestedByLabel,
    String requestedAt,
    Instant freshnessAt,
    String currentVersion
) {
}
