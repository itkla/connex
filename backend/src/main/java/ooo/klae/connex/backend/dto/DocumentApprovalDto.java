package ooo.klae.connex.backend.dto;

import ooo.klae.connex.backend.beans.DocumentApproval;

/**
 * Client-facing approval request on a generated document, with its decision once made.
 *
 * @param id              approval id
 * @param documentId      the document version this approval covers
 * @param policyId        policy that triggered the request (nullable)
 * @param status          pending | approved | rejected | cancelled
 * @param requestedBy     requesting user id
 * @param requestComment  requester's note to the approver
 * @param decidedBy       deciding user id (null while pending)
 * @param decisionComment approver's note on the decision
 * @param decidedAt       when decided
 * @param createdAt       when requested
 */
public record DocumentApprovalDto(
    int id,
    int documentId,
    Integer policyId,
    String status,
    Integer requestedBy,
    String requestComment,
    Integer decidedBy,
    String decisionComment,
    String decidedAt,
    String createdAt
) {
    public static DocumentApprovalDto from(DocumentApproval a) {
        if (a == null) return null;
        return new DocumentApprovalDto(a.getId(), a.getDocumentId(), a.getPolicyId(), a.getStatus(),
            a.getRequestedBy(), a.getRequestComment(), a.getDecidedBy(), a.getDecisionComment(),
            a.getDecidedAt(), a.getCreatedAt());
    }
}
