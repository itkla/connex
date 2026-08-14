package ooo.klae.connex.backend.dto;

import ooo.klae.connex.backend.beans.DocumentApprovalDecision;

/**
 * One approver's decision within an approval chain.
 *
 * @param id        decision id
 * @param stepId    the chain step this decision belongs to
 * @param decision  approved | rejected
 * @param decidedBy deciding user id
 * @param comment   the approver's note
 * @param decidedAt when the decision was recorded
 */
public record DocumentApprovalDecisionDto(
    int id,
    int stepId,
    String decision,
    int decidedBy,
    String comment,
    String decidedAt
) {
    public static DocumentApprovalDecisionDto from(DocumentApprovalDecision d) {
        if (d == null) return null;
        return new DocumentApprovalDecisionDto(d.getId(), d.getStepId(), d.getDecision(),
            d.getDecidedBy(), d.getComment(), d.getDecidedAt());
    }
}
