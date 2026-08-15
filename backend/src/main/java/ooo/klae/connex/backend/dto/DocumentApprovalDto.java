package ooo.klae.connex.backend.dto;

import java.util.List;

import ooo.klae.connex.backend.beans.DocumentApproval;

/**
 * Client-facing approval request on a generated document, with its chain and terminal decision.
 *
 * @param id                 approval id
 * @param documentId         the document version this approval covers
 * @param policyId           policy that triggered the request (nullable)
 * @param status             pending | approved | rejected | cancelled | invalidated | unsatisfiable
 * @param outcomeReason      stable reason for a terminal outcome
 * @param outcomeDetail      bounded detail for a terminal outcome
 * @param mode               sequential | parallel, frozen from the policy at request time
 * @param separationOfDuties strict | requester | off, frozen from the policy at request time
 * @param requestedBy        requesting user id
 * @param requestComment     requester's note to the approvers
 * @param decidedBy          user whose decision terminated the request (null while pending)
 * @param decisionComment    that approver's note
 * @param decidedAt          when the request terminated
 * @param createdAt          when requested
 * @param satisfiable        whether every open frozen step can still reach its quorum
 * @param blockedReason      reason the first blocking step cannot reach its quorum
 * @param steps              the frozen approver chain, in order
 */
public record DocumentApprovalDto(
    int id,
    int documentId,
    Integer policyId,
    String status,
    String outcomeReason,
    String outcomeDetail,
    String mode,
    String separationOfDuties,
    Integer requestedBy,
    String requestComment,
    Integer decidedBy,
    String decisionComment,
    String decidedAt,
    String createdAt,
    boolean satisfiable,
    String blockedReason,
    List<DocumentApprovalStepDto> steps
) {
    public static DocumentApprovalDto from(DocumentApproval a) {
        if (a == null) return null;
        return new DocumentApprovalDto(a.getId(), a.getDocumentId(), a.getPolicyId(), a.getStatus(),
            a.getOutcomeReason(), a.getOutcomeDetail(), a.getMode(), a.getSeparationOfDuties(),
            a.getRequestedBy(), a.getRequestComment(),
            a.getDecidedBy(), a.getDecisionComment(), a.getDecidedAt(), a.getCreatedAt(),
            true, null,
            a.getSteps().stream().map(DocumentApprovalStepDto::from).toList());
    }

    public static DocumentApprovalDto from(DocumentApproval a, boolean satisfiable,
            String blockedReason, List<DocumentApprovalStepDto> steps) {
        if (a == null) return null;
        return new DocumentApprovalDto(a.getId(), a.getDocumentId(), a.getPolicyId(), a.getStatus(),
            a.getOutcomeReason(), a.getOutcomeDetail(), a.getMode(), a.getSeparationOfDuties(),
            a.getRequestedBy(), a.getRequestComment(), a.getDecidedBy(), a.getDecisionComment(),
            a.getDecidedAt(), a.getCreatedAt(), satisfiable, blockedReason, steps);
    }
}
